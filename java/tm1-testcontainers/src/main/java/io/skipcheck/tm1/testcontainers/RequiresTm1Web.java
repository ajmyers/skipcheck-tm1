// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs a test class or method against real TM1 Web and TM1 servers, and skips it when
 * either isn't available.
 *
 * <p>
 * It includes {@link RequiresTm1}, so tests also skip without Docker or a
 * {@link Tm1Source}, and can still take a {@link Tm1ServerContainer} parameter. Declare a
 * {@link Tm1WebContainer} parameter to get the {@linkplain Tm1WebContainer#shared()
 * shared TM1 Web}, which lists and logs on to the shared TM1 server:
 *
 * <pre class="code">
 * &#64;RequiresTm1Web
 * class WebsheetTest {
 *
 *     &#64;Test
 *     void opensAWebsheet(Tm1WebContainer web, Tm1ServerContainer tm1) {
 *         String url = web.getUrl();   // http://localhost:54321/tm1web/
 *         ...
 *     }
 * }
 * </pre>
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RequiresTm1
@ExtendWith(Tm1WebExtension.class)
public @interface RequiresTm1Web {

	/** Why tests skip when no TM1 Web source is configured, and what to set. */
	String NO_SOURCE_MESSAGE = "No TM1 Web source configured. Set one of " + Tm1WebSource.PRIVATE_IMAGE + ", "
			+ Tm1WebSource.HOME_DIR + ", " + Tm1WebSource.INSTALL_VOLUME + ", " + Tm1WebSource.BUNDLE_DIR + " or "
			+ Tm1WebSource.BUNDLE_URL + ", or put a tm1web bundle in " + Tm1Source.BUNDLE_DIR
			+ " (as an environment variable or system property) to run tests against TM1 Web.";

}
