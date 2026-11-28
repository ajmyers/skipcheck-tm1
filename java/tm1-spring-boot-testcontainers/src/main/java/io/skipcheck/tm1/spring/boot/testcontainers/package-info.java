// SPDX-License-Identifier: Apache-2.0

/**
 * Spring Boot {@code @ServiceConnection} support for {@code Tm1ServerContainer} (TM1 11)
 * and {@code Tm1EngineContainer} (TM1 12): a Spring test or a development-time
 * application gets its TM1 connection from the container, with no {@code tm1.*}
 * properties.
 *
 * <p>
 * A test gets the shared server, and is skipped where there's no TM1:
 *
 * {@snippet class = examples.ServiceConnectionExamples region = test}
 *
 * <p>
 * An application run locally gets a server of its own, started and stopped with it:
 *
 * {@snippet class = examples.ServiceConnectionExamples region = dev-app}
 *
 * <p>
 * The TM1 servers of a {@code PlanningAnalyticsEnvironment}, each by its name, come in
 * through {@link io.skipcheck.tm1.spring.boot.testcontainers.Tm1EnvironmentServers} as
 * {@code tm1.servers.<name>} instead.
 *
 * <p>
 * Everything here is non-null unless marked {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.skipcheck.tm1.spring.boot.testcontainers;

import org.jspecify.annotations.NullMarked;
