package net.ajmyers.tm1.testcontainers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs a test class or method against a real TM1 server, and skips it when there isn't one.
 *
 * <p>Tests are <b>skipped</b>, not failed, when Docker isn't available or no {@link Tm1Source}
 * is configured, so a build without IBM software (such as GitHub CI) stays green. A source that
 * is configured but wrong, such as a folder with no TM1 in it, fails instead.
 *
 * <p>Declare a {@link Tm1ServerContainer} parameter on a test method, constructor or lifecycle
 * method to get the {@linkplain Tm1ServerContainer#shared() shared container}, started on first
 * use:
 *
 * <pre>{@code
 * @RequiresTm1
 * class CubeServiceTest {
 *     @Test
 *     void listsCubes(Tm1ServerContainer tm1) {
 *         var client = Tm1Client.builder().baseUrl(tm1.getRestUrl()) ...
 *     }
 * }
 * }</pre>
 *
 * <p>Tests share one server, so each test should create its own uniquely named objects and
 * delete them afterwards.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(Tm1Extension.class)
public @interface RequiresTm1 {

	/** Why tests skip when no source is configured, and what to set. */
	String NO_SOURCE_MESSAGE = "No TM1 source configured. Set one of " + Tm1Source.PRIVATE_IMAGE + ", " + Tm1Source.HOME_DIR + ", "
			+ Tm1Source.INSTALL_VOLUME + ", " + Tm1Source.BUNDLE_DIR + " or " + Tm1Source.BUNDLE_URL
			+ " (as an environment variable or system property) to run tests against TM1.";
}
