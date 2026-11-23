// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.testcontainers;

import java.util.Objects;

import org.springframework.test.context.DynamicPropertyRegistry;

import io.skipcheck.tm1.testcontainers.CognosContainer;
import io.skipcheck.tm1.testcontainers.PlanningAnalyticsEnvironment;

/**
 * Wires the TM1 servers of a {@link PlanningAnalyticsEnvironment} into the starter by
 * name: each server becomes {@code tm1.servers.<name>}, so a Spring Boot test gets a
 * {@code @Qualifier("<name>")} client for each. A server named {@code default} is the
 * starter's default server, the one a plain {@code Tm1Client} gets.
 *
 * {@snippet class = examples.Tm1EnvironmentServersExamples region = test}
 *
 * <p>
 * Call it from a {@code @DynamicPropertySource} method. A
 * {@code DynamicPropertyRegistrar} bean, or {@code @ServiceConnection} on an environment
 * bean, comes too late: the starter reads the names under {@code tm1.servers} when it
 * registers its bean definitions, before any bean exists, and so before the environment
 * that knows the names. A {@code @DynamicPropertySource} method runs before the context
 * starts, so the names are there. The values are read only when the beans are created,
 * once the environment has started.
 *
 * <p>
 * For one server, {@code @ServiceConnection} on its {@code Tm1ServerContainer} (see the
 * {@linkplain io.skipcheck.tm1.spring.boot.testcontainers package}) needs no names: it
 * makes the default server.
 */
public final class Tm1EnvironmentServers {

	private Tm1EnvironmentServers() {
	}

	/**
	 * Adds {@code tm1.servers.<name>.*} for each of the environment's TM1 servers: its
	 * {@code base-url}, and the login its own checks use, {@code user} and
	 * {@code password}, with {@code namespace} in Cognos (CAM) security.
	 * @param environment the environment, built; it may start later
	 * @param registry the registry of a {@code @DynamicPropertySource} method
	 */
	public static void register(PlanningAnalyticsEnvironment environment, DynamicPropertyRegistry registry) {
		Objects.requireNonNull(registry, "registry");
		Objects.requireNonNull(environment, "environment").tm1Servers().forEach((name, server) -> {
			String prefix = "tm1.servers." + name + ".";
			registry.add(prefix + "base-url", server::getRestUrl);
			// The container sets its CAM administrator's credentials when it starts
			registry.add(prefix + "user", server::getUsername);
			registry.add(prefix + "password", server::getPassword);
			CognosContainer.Namespace namespace = server.getCamNamespace();
			if (namespace != null) {
				registry.add(prefix + "namespace", namespace::id);
			}
		});
	}

}
