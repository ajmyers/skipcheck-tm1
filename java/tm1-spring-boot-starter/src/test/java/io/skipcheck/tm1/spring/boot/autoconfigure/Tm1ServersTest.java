// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.session.Tm1Session;
import io.skipcheck.tm1.client.session.Tm1SessionClosedException;
import io.skipcheck.tm1.client.session.Tm1SessionPool;
import io.skipcheck.tm1.client.version.Tm1Dialect;

/**
 * TM1 servers by name: {@code tm1.servers.<name>}, and {@code tm1.*} as the one named
 * default.
 */
class Tm1ServersTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(Tm1AutoConfiguration.class));

	private final ApplicationContextRunner twoServers = runner.withPropertyValues(
			"tm1.servers.planning.address=http://planning:8010", "tm1.servers.planning.user=admin",
			"tm1.servers.reporting.base-url=https://proxy/reporting/api/v1/", "tm1.servers.reporting.user=alice",
			"tm1.servers.reporting.namespace=LDAP");

	@Test
	void namesTm1TheDefaultServer() {
		runner.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=admin")
			.withUserConfiguration(DefaultServer.class)
			.run(context -> {
				Tm1Client tm1 = context.getBean("defaultTm1Client", Tm1Client.class);
				assertThat(tm1.session().serviceRoot()).isEqualTo("http://tm1:8010/api/v1/");
				assertThat(context.getBean(DefaultServer.class).plain).isSameAs(tm1);
				assertThat(context.getBean(DefaultServer.class).qualified).isSameAs(tm1);
			});
	}

	@Test
	void takesTm1ServersDefaultForTheSameServer() {
		runner.withPropertyValues("tm1.servers.default.address=http://tm1:8010", "tm1.servers.default.user=admin")
			.withUserConfiguration(DefaultServer.class)
			.run(context -> assertThat(context.getBean(DefaultServer.class).plain.session().serviceRoot())
				.isEqualTo("http://tm1:8010/api/v1/"));
	}

	@Test
	void ignoresTm1PropertiesThatAreNoServers() {
		// As TM1_HOME_DIR and TM1_PRIVATE_IMAGE, for tm1-testcontainers, reach Spring
		runner
			.withPropertyValues("tm1.home-dir=/tmp/x", "tm1.private-image=tm1-server:test",
					"tm1.servers.planning.home-dir=/tmp/y")
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(Tm1Client.class));
	}

	@Test
	void takesTm1ServersDefaultBesideTm1PropertiesThatAreNoServers() {
		runner
			.withPropertyValues("tm1.home-dir=/tmp/x", "tm1.servers.default.address=http://tm1:8010",
					"tm1.servers.default.user=admin")
			.withUserConfiguration(DefaultServer.class)
			.run(context -> assertThat(context.getBean(DefaultServer.class).plain.session().serviceRoot())
				.isEqualTo("http://tm1:8010/api/v1/"));
	}

	@Test
	void findsServersConfiguredByEnvironmentVariables() {
		// Spring's environment-variable form, as a container sets it
		Map<String, Object> variables = Map.of("TM1_BASEURL", "http://tm1:8010/api/v1/", "TM1_USER", "admin",
				"TM1_PASSWORD", "apple", "TM1_SERVERS_PLANNING_ADDRESS", "http://planning:8010",
				"TM1_SERVERS_PLANNING_USER", "planner", "TM1_SERVERS_PLANNING_POOL_ENABLED", "true",
				"TM1_SERVERS_REPORTING_BASE_URL", "http://reporting:8010/api/v1/", "TM1_SERVERS_REPORTING_USER",
				"reporter", "TM1_HOME_DIR", "/tmp/x");
		runner
			.withInitializer(context -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
			.withUserConfiguration(DefaultServer.class)
			.run(context -> {
				assertThat(context.getBean(DefaultServer.class).plain.session().serviceRoot())
					.isEqualTo("http://tm1:8010/api/v1/");
				assertThat(context.getBean("defaultTm1ConnectionDetails", Tm1ConnectionDetails.class)
					.getAuthenticator()
					.describe()).isEqualTo("Basic admin");
				assertThat(context.getBean("planningTm1Client", Tm1Client.class).session().serviceRoot())
					.isEqualTo("http://planning:8010/api/v1/");
				assertThat(context.getBean("planningTm1ConnectionDetails", Tm1ConnectionDetails.class)
					.getAuthenticator()
					.describe()).isEqualTo("Basic planner");
				assertThat(context).hasBean("planningTm1SessionPool").doesNotHaveBean("homeTm1Client");
				// The legacy form, with an underscore for the dash in base-url, as the
				// binder takes it
				assertThat(context.getBean("reportingTm1Client", Tm1Client.class).session().serviceRoot())
					.isEqualTo("http://reporting:8010/api/v1/");
			});
	}

	@Test
	void findsTheDefaultServerByTm1AddressInTheEnvironment() {
		Map<String, Object> variables = Map.of("TM1_ADDRESS", "http://tm1:8010", "TM1_USER", "admin");
		runner
			.withInitializer(context -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
			.withUserConfiguration(DefaultServer.class)
			.run(context -> assertThat(context.getBean(DefaultServer.class).plain.session().serviceRoot())
				.isEqualTo("http://tm1:8010/api/v1/"));
	}

	@Test
	void findsTheDefaultServerByTheLegacyTm1BaseUrlVariable() {
		Map<String, Object> variables = Map.of("TM1_BASE_URL", "http://tm1:8010/api/v1/", "TM1_USER", "admin");
		runner
			.withInitializer(context -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
			.withUserConfiguration(DefaultServer.class)
			.run(context -> assertThat(context.getBean(DefaultServer.class).plain.session().serviceRoot())
				.isEqualTo("http://tm1:8010/api/v1/"));
	}

	@Test
	void configuresV12ServersFromEnvironmentVariables() {
		Map<String, Object> variables = Map.of("TM1_ADDRESS", "http://engine:4444", "TM1_INSTANCE", "tm1",
				"TM1_DATABASE", "planning", "TM1_USER", "admin", "TM1_CLIENT_ID", "app-1", "TM1_CLIENT_SECRET",
				"secret", "TM1_SERVERS_CLOUD_ADDRESS", "https://cloud.example", "TM1_SERVERS_CLOUD_INSTANCE", "prod",
				"TM1_SERVERS_CLOUD_DATABASE", "finance", "TM1_SERVERS_CLOUD_BEARER_TOKEN", "jwt-token");
		runner
			.withInitializer(context -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
			.withUserConfiguration(DefaultServer.class)
			.run(context -> {
				Tm1ConnectionDetails def = context.getBean("defaultTm1ConnectionDetails", Tm1ConnectionDetails.class);
				assertThat(def.getDialect()).isEqualTo(Tm1Dialect.v12("tm1", "planning"));
				assertThat(def.getAuthenticator().describe()).isEqualTo("Application app-1 as admin");
				assertThat(context.getBean(DefaultServer.class).plain.session().serviceRoot())
					.isEqualTo("http://engine:4444/tm1/api/v1/Databases('planning')/");

				Tm1ConnectionDetails cloud = context.getBean("cloudTm1ConnectionDetails", Tm1ConnectionDetails.class);
				assertThat(cloud.getDialect()).isEqualTo(Tm1Dialect.v12("prod", "finance"));
				assertThat(cloud.getAuthenticator().describe()).isEqualTo("Bearer");
				assertThat(context.getBean("cloudTm1Client", Tm1Client.class).session().serviceRoot())
					.isEqualTo("https://cloud.example/prod/api/v1/Databases('finance')/");
			});
	}

	@Test
	void refusesTm1AndTm1ServersDefaultTogether() {
		runner.withPropertyValues("tm1.address=http://tm1:8010", "tm1.servers.default.address=http://other:8010")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.hasMessageContaining("Both tm1.* and tm1.servers.default.* are set"));
	}

	@Test
	void createsAClientForEachServer() {
		twoServers.run(context -> {
			assertThat(context.getBean("planningTm1Client", Tm1Client.class).session().serviceRoot())
				.isEqualTo("http://planning:8010/api/v1/");
			assertThat(context.getBean("reportingTm1Client", Tm1Client.class).session().serviceRoot())
				.isEqualTo("https://proxy/reporting/api/v1/");
			assertThat(context.getBean("planningTm1Client", Tm1Client.class).session())
				.isSameAs(context.getBean("planningTm1Session"));
			assertThat(context.getBean("planningTm1ConnectionDetails", Tm1ConnectionDetails.class)
				.getAuthenticator()
				.describe()).isEqualTo("Basic admin");
			assertThat(context.getBean("reportingTm1ConnectionDetails", Tm1ConnectionDetails.class)
				.getAuthenticator()
				.describe()).isEqualTo("CAMNamespace LDAP/alice");
			assertThat(context).doesNotHaveBean("defaultTm1Client");
		});
	}

	@Test
	void injectsEachServerByItsName() {
		twoServers.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=admin")
			.withUserConfiguration(TwoServers.class)
			.run(context -> {
				TwoServers servers = context.getBean(TwoServers.class);
				assertThat(servers.planning.session().serviceRoot()).isEqualTo("http://planning:8010/api/v1/");
				assertThat(servers.reporting.session().serviceRoot()).isEqualTo("https://proxy/reporting/api/v1/");
				// Three clients, and the plain injection is the default server's
				assertThat(servers.plain).isSameAs(context.getBean("defaultTm1Client"));
			});
	}

	@Test
	void injectsNoPlainClientWithoutADefaultServerEvenForOneServer() {
		runner
			.withPropertyValues("tm1.servers.planning.address=http://planning:8010", "tm1.servers.planning.user=admin")
			.withUserConfiguration(MaybeServer.class)
			.run(context -> {
				assertThat(context).hasBean("planningTm1Client");
				assertThat(context.getBean(MaybeServer.class).tm1.getIfAvailable()).isNull();
			});
	}

	@Test
	void createsAPoolOnlyForTheServersThatEnableOne() {
		twoServers
			.withPropertyValues("tm1.servers.reporting.pool.enabled=true", "tm1.servers.reporting.pool.max-size=5")
			.run(context -> {
				assertThat(context).hasBean("reportingTm1SessionPool").doesNotHaveBean("planningTm1SessionPool");
				assertThat(context.getBean("reportingTm1SessionPool")).isInstanceOf(Tm1SessionPool.class);
			});
	}

	@Test
	void explainsAnSslBundleWithTheServersProperty() {
		twoServers.withPropertyValues("tm1.servers.planning.ssl.bundle=nope")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("tm1.servers.planning.ssl.bundle is set to nope"));
	}

	@Test
	void explainsAMissingAddressWithTheServersProperty() {
		runner.withPropertyValues("tm1.servers.planning.user=admin")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Set tm1.servers.planning.address"));
	}

	@Test
	void takesABeanOfYourOwnUnderAServersBeanName() {
		twoServers.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=admin")
			.withBean("planningTm1ConnectionDetails", Tm1ConnectionDetails.class,
					() -> connection("http://elsewhere:9000/api/v1/"))
			.withUserConfiguration(PlainConnection.class)
			.run(context -> {
				assertThat(context.getBean("planningTm1Session", Tm1Session.class).serviceRoot())
					.isEqualTo("http://elsewhere:9000/api/v1/");
				// It's given planning's qualifier and made no default candidate, as the
				// starter's own are, so the plain injection is still the default server's
				assertThat(context.getBean(PlainConnection.class).connection)
					.isSameAs(context.getBean("defaultTm1ConnectionDetails"));
				assertThat(context.getBean(PlainConnection.class).planning)
					.isSameAs(context.getBean("planningTm1ConnectionDetails"));
			});
	}

	@Test
	void makesADefaultServerOfConnectionDetailsUnderAnyName() {
		runner.withBean("elsewhere", Tm1ConnectionDetails.class, () -> connection("http://elsewhere:9000/api/v1/"))
			.run(context -> {
				assertThat(context.getBean("defaultTm1Session", Tm1Session.class).serviceRoot())
					.isEqualTo("http://elsewhere:9000/api/v1/");
				assertThat(context).doesNotHaveBean("defaultTm1ConnectionDetails");
			});
	}

	@Test
	void closesEachServersSessionAndPoolOnShutdown() {
		Tm1Session[] session = new Tm1Session[1];
		Tm1SessionPool[] pool = new Tm1SessionPool[1];
		twoServers.withPropertyValues("tm1.servers.planning.pool.enabled=true").run(context -> {
			session[0] = context.getBean("planningTm1Session", Tm1Session.class);
			pool[0] = context.getBean("planningTm1SessionPool", Tm1SessionPool.class);
		});
		assertThat(session[0].isClosed()).isTrue();
		assertThatThrownBy(() -> pool[0].session()).isInstanceOf(Tm1SessionClosedException.class);
	}

	private static Tm1ConnectionDetails connection(String serviceRoot) {
		return new Tm1ConnectionDetails() {

			@Override
			public String getServiceRoot() {
				return serviceRoot;
			}

			@Override
			public Tm1Authenticator getAuthenticator() {
				return Tm1Authenticator.basic("svc", "x");
			}
		};
	}

	@Configuration(proxyBeanMethods = false)
	static class DefaultServer {

		private final Tm1Client plain;

		private final Tm1Client qualified;

		DefaultServer(Tm1Client plain, @Qualifier("default") Tm1Client qualified) {
			this.plain = plain;
			this.qualified = qualified;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class TwoServers {

		private final Tm1Client planning;

		private final Tm1Client reporting;

		private final Tm1Client plain;

		TwoServers(@Qualifier("planning") Tm1Client planning, @Qualifier("reporting") Tm1Client reporting,
				Tm1Client plain) {
			this.planning = planning;
			this.reporting = reporting;
			this.plain = plain;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class MaybeServer {

		private final ObjectProvider<Tm1Client> tm1;

		MaybeServer(ObjectProvider<Tm1Client> tm1) {
			this.tm1 = tm1;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class PlainConnection {

		private final Tm1ConnectionDetails connection;

		private final Tm1ConnectionDetails planning;

		PlainConnection(Tm1ConnectionDetails connection, @Qualifier("planning") Tm1ConnectionDetails planning) {
			this.connection = connection;
			this.planning = planning;
		}

	}

}
