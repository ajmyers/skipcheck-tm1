// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs a test class or method against a real TM1 and skips it when there isn't one, as
 * tm1-testcontainers' {@code RequiresTm1} does, but also against one already running,
 * named with {@code TM1_URL} (see {@link LiveServer}), as the Python, .NET and TypeScript
 * live tests do. Declare a {@link LiveServer} parameter on a test, constructor or
 * lifecycle method to get it. A test that needs a container of its own, or the container
 * itself, keeps {@code RequiresTm1}: with only a {@code TM1_URL}, it skips.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(LiveTm1Extension.class)
public @interface LiveTm1 {

}
