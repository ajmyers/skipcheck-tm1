// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs a test class or method against a real TM1 v12 engine, and skips it when there
 * isn't one. For TM1 11, use {@link RequiresTm1}.
 *
 * <p>
 * Tests are <b>skipped</b>, not failed, when Docker isn't available or no
 * {@link Tm1EngineSource} is configured, so a build without IBM software stays green. A
 * source that is configured but wrong fails instead.
 *
 * <p>
 * One engine serves the whole test JVM ({@link Tm1EngineContainer#shared()}), and <b>each
 * test class gets a database of its own</b>, created when the class first asks for it and
 * dropped when the class is done. So classes don't see each other's objects. Declare a
 * {@link Tm1Database} parameter on a test method, constructor or lifecycle method to get
 * it; a {@link Tm1EngineContainer} parameter gets the engine.
 *
 * {@snippet class = examples.Tm1EngineExamples region = test-class}
 *
 * <p>
 * A database takes about 13 seconds to create and 5 to drop. {@link #shareDatabase} gives
 * a class the engine's one shared database instead, at no cost: its tests then need
 * uniquely named objects, and to delete them afterwards:
 *
 * {@snippet class = examples.Tm1EngineExamples region = shared-database}
 *
 * <p>
 * "A test class" is the unit each language's test framework shares set-up over: a JUnit
 * class here (with its nested classes), a module in pytest, a class in xUnit, and a file
 * in vitest.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(Tm1EngineExtension.class)
public @interface RequiresTm1Engine {

	/** Why tests skip when no source is configured, and what to set. */
	String NO_SOURCE_MESSAGE = "No TM1 v12 source configured. Set one of " + Tm1EngineSource.PRIVATE_IMAGE + ", "
			+ Tm1EngineSource.HOME_DIR + ", " + Tm1EngineSource.INSTALL_VOLUME + ", " + Tm1EngineSource.BUNDLE_DIR
			+ " or " + Tm1EngineSource.BUNDLE_URL + ", or put a tm1-v*-standalone-linux.tgz in " + Tm1Source.BUNDLE_DIR
			+ " (as an environment variable or system property) to run tests against TM1 v12.";

	/**
	 * Whether the class uses the engine's shared database, {@code tm1}, rather than one
	 * of its own.
	 * @return {@code true} to share
	 */
	boolean shareDatabase() default false;

	/**
	 * The oldest TM1 the tests apply to, such as {@code 12.6}; they're skipped on an
	 * older engine. Checking it starts the engine.
	 * @return the version, or empty for any
	 */
	String minVersion() default "";

}
