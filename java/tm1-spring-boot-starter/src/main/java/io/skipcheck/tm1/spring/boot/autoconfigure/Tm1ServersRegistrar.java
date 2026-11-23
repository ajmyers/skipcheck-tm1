// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.session.Tm1Session;
import io.skipcheck.tm1.client.session.Tm1SessionPool;

/**
 * Registers each TM1 server's beans, the same way for every server: connection details,
 * session, client and, with the server's {@code pool.enabled}, pool, named
 * {@code <name>Tm1ConnectionDetails}, {@code <name>Tm1Session}, {@code <name>Tm1Client}
 * and {@code <name>Tm1SessionPool}, and qualified by the name. The server named
 * {@value #DEFAULT} is {@code tm1.*} or {@code tm1.servers.default.*}; its beans are the
 * default candidates, and every other server's aren't.
 *
 * <p>
 * Bean definitions, not {@code @Bean} methods, because the names are only known from the
 * properties. They're read from the property sources' names alone: a value can be a
 * supplier (a {@code @DynamicPropertySource}'s, say) that only works once a container has
 * started, so values are bound when the beans are created.
 */
final class Tm1ServersRegistrar implements ImportBeanDefinitionRegistrar {

	/** The name of the server {@code tm1.*} configures. */
	static final String DEFAULT = "default";

	private static final ConfigurationPropertyName TM1 = ConfigurationPropertyName.of("tm1");

	private static final ConfigurationPropertyName SERVERS = ConfigurationPropertyName.of("tm1.servers");

	private static final ConfigurationPropertyName DEFAULT_SERVER = ConfigurationPropertyName.of("tm1.servers.default");

	/**
	 * A server's properties, as their names' uniform form: {@code base-url} is
	 * {@code baseurl}.
	 */
	private static final Set<String> PROPERTIES = Arrays.stream(Tm1Properties.class.getRecordComponents())
		.map(component -> component.getName().toLowerCase(Locale.ROOT))
		.collect(Collectors.toUnmodifiableSet());

	/** The properties that say where a server is, so that {@code tm1.*} makes one. */
	private static final Set<String> ADDRESSES = Set.of("address", "baseurl");

	private static final List<Class<?>> TYPES = List.of(Tm1ConnectionDetails.class, Tm1Session.class, Tm1Client.class,
			Tm1SessionPool.class);

	private final Environment environment;

	private final BeanFactory beanFactory;

	Tm1ServersRegistrar(Environment environment, BeanFactory beanFactory) {
		this.environment = environment;
		this.beanFactory = beanFactory;
	}

	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		Set<String> servers = servers(environment);
		// Beans of these types under other names, as default candidates, are the default
		// server's: Spring Boot's connection details (@ServiceConnection) work that way
		@Nullable String[] others = new String[TYPES.size()];
		for (int i = 0; i < others.length; i++) {
			others[i] = other(registry, servers, TYPES.get(i));
		}
		if (others[0] != null || registry.containsBeanDefinition(beanName(DEFAULT, Tm1ConnectionDetails.class))) {
			servers.add(DEFAULT);
		}
		for (String server : servers) {
			register(registry, server, server.equals(DEFAULT) ? others : new String[TYPES.size()]);
		}
	}

	/**
	 * The servers the properties configure: each {@code tm1.servers.<name>}, and
	 * {@value #DEFAULT} when {@code tm1.address} or {@code tm1.base-url} is set.
	 * @throws IllegalStateException if both {@code tm1.*} and
	 * {@code tm1.servers.default.*} are set
	 */
	static Set<String> servers(Environment environment) {
		Set<String> servers = new LinkedHashSet<>();
		boolean tm1 = false;
		boolean address = false;
		for (ConfigurationPropertyName name : names(environment)) {
			if (SERVERS.isAncestorOf(name) && isServerProperty(name, 3)) {
				servers.add(name.getElement(2, Form.ORIGINAL));
			}
			else if (isServerProperty(name, 1)) {
				tm1 = true;
				address |= name.getNumberOfElements() == 2 && ADDRESSES.contains(name.getElement(1, Form.UNIFORM));
			}
		}
		if (tm1 && servers.contains(DEFAULT)) {
			throw new IllegalStateException(
					"Both tm1.* and tm1.servers.default.* are set, and they're the same server, "
							+ "named default: set one of them");
		}
		if (address) {
			servers.add(DEFAULT);
		}
		return servers;
	}

	/**
	 * Whether {@code name} is one of a server's properties, a {@link Tm1Properties}
	 * component (or under one) at element {@code index}. Other {@code tm1.*} names don't
	 * make a server: the environment variables tm1-testcontainers reads, such as
	 * {@code TM1_HOME_DIR}, reach Spring as {@code tm1.home-dir} and the like.
	 */
	private static boolean isServerProperty(ConfigurationPropertyName name, int index) {
		return name.getNumberOfElements() > index && PROPERTIES.contains(name.getElement(index, Form.UNIFORM));
	}

	/**
	 * The prefix of a server's properties: {@code tm1.servers.<name>}, or {@code tm1} for
	 * the default server unless it's set as {@code tm1.servers.default}.
	 */
	static String prefix(Environment environment, String server) {
		if (server.equals(DEFAULT) && names(environment).stream()
			.noneMatch(name -> DEFAULT_SERVER.isAncestorOf(name) && isServerProperty(name, 3))) {
			return "tm1";
		}
		return "tm1.servers." + server;
	}

	/** The name of a server's bean, such as {@code planningTm1Client}. */
	static String beanName(String server, Class<?> type) {
		return server + type.getSimpleName();
	}

	/** Every property name under {@code tm1}, without reading a value. */
	private static List<ConfigurationPropertyName> names(Environment environment) {
		List<ConfigurationPropertyName> names = new ArrayList<>();
		for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
			if (source instanceof IterableConfigurationPropertySource iterable) {
				iterable.filter(TM1::isAncestorOf).stream().forEach(names::add);
			}
		}
		return names;
	}

	/**
	 * The one default-candidate bean of {@code type} not named for a server, if there is
	 * one.
	 */
	private @Nullable String other(BeanDefinitionRegistry registry, Set<String> servers, Class<?> type) {
		List<String> others = new ArrayList<>();
		for (String name : ((ListableBeanFactory) beanFactory).getBeanNamesForType(type, true, false)) {
			boolean named = servers.stream().anyMatch(server -> name.equals(beanName(server, type)))
					|| name.equals(beanName(DEFAULT, type));
			if (!named && registry.containsBeanDefinition(name)
					&& registry.getBeanDefinition(name).isAutowireCandidate()
					&& isDefaultCandidate(registry.getBeanDefinition(name))) {
				others.add(name);
			}
		}
		if (others.size() > 1) {
			throw new IllegalStateException(
					"Several " + type.getSimpleName() + " beans could be the default TM1 server's: " + others
							+ ". Name the one you mean " + beanName(DEFAULT, type));
		}
		return others.isEmpty() ? null : others.get(0);
	}

	private static boolean isDefaultCandidate(BeanDefinition definition) {
		return !(definition instanceof AbstractBeanDefinition abstractDefinition)
				|| abstractDefinition.isDefaultCandidate();
	}

	/**
	 * Registers a server's beans. {@code others} has, for each of {@link #TYPES}, the
	 * name of a bean of yours to use instead, or {@code null}.
	 */
	private void register(BeanDefinitionRegistry registry, String server, @Nullable String[] others) {
		String connection = bean(registry, server, Tm1ConnectionDetails.class, others[0],
				() -> new PropertiesTm1ConnectionDetails(properties(server),
						beanFactory.getBeanProvider(SslBundles.class).getIfAvailable(), prefix(environment, server)));
		String session = bean(registry, server, Tm1Session.class, others[1],
				() -> settings(server, connection).build());
		bean(registry, server, Tm1Client.class, others[2],
				() -> Tm1Client.of(beanFactory.getBean(session, Tm1Session.class)), session);
		register(registry, server, beanName(server, Tm1Session.class) + "Shutdown", DisposableBean.class, () -> {
			Tm1Session bean = beanFactory.getBean(session, Tm1Session.class);
			return () -> bean.close().block(Tm1AutoConfiguration.SHUTDOWN_WAIT);
		}, false, session);
		boolean pool = Binder.get(environment)
			.bind(prefix(environment, server) + ".pool.enabled", Bindable.of(Boolean.class))
			.orElse(false);
		if (pool || others[3] != null) {
			String poolName = bean(registry, server, Tm1SessionPool.class, others[3],
					() -> Tm1AutoConfiguration.pool(settings(server, connection), properties(server).pool()));
			register(registry, server, beanName(server, Tm1SessionPool.class) + "Shutdown", DisposableBean.class,
					() -> {
						Tm1SessionPool bean = beanFactory.getBean(poolName, Tm1SessionPool.class);
						return () -> bean.close().block(Tm1AutoConfiguration.SHUTDOWN_WAIT);
					}, false, poolName);
		}
	}

	/**
	 * A server's bean of {@code type}: {@code other} or a bean already registered under
	 * the server's name for it, given the server's qualifier, or else a new one.
	 * @return the bean's name
	 */
	private <T> String bean(BeanDefinitionRegistry registry, String server, Class<T> type, @Nullable String other,
			Supplier<T> supplier, String... dependsOn) {
		String name = other != null ? other : beanName(server, type);
		if (registry.containsBeanDefinition(name)) {
			adopt(registry.getBeanDefinition(name), server);
		}
		else {
			register(registry, server, name, type, supplier, server.equals(DEFAULT), dependsOn);
		}
		return name;
	}

	/**
	 * Gives a bean of yours its server's qualifier and candidacy, as the starter's have.
	 */
	private static void adopt(BeanDefinition definition, String server) {
		if (definition instanceof AbstractBeanDefinition abstractDefinition) {
			abstractDefinition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, server));
			if (!server.equals(DEFAULT)) {
				abstractDefinition.setDefaultCandidate(false);
			}
		}
	}

	private Tm1Properties properties(String server) {
		return Binder.get(environment).bindOrCreate(prefix(environment, server), Tm1Properties.class);
	}

	private Tm1Session.Builder settings(String server, String connection) {
		return Tm1AutoConfiguration.sessionSettings(beanFactory.getBean(connection, Tm1ConnectionDetails.class),
				properties(server), environment, beanFactory.getBeanProvider(WebClient.Builder.class),
				beanFactory.getBeanProvider(ObservationRegistry.class));
	}

	/**
	 * Registers a bean of {@code server}. Its destroy method is none: the shutdown beans
	 * close sessions and pools, which close with a {@code Mono} that an inferred
	 * {@code close()} would never subscribe to.
	 */
	private static <T> void register(BeanDefinitionRegistry registry, String server, String beanName, Class<T> type,
			Supplier<T> supplier, boolean defaultCandidate, String... dependsOn) {
		RootBeanDefinition definition = new RootBeanDefinition(type, supplier);
		definition.setDefaultCandidate(defaultCandidate);
		definition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, server));
		definition.setDestroyMethodName("");
		definition.setDependsOn(dependsOn);
		registry.registerBeanDefinition(beanName, definition);
	}

}
