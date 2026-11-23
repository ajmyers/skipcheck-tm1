// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs a test class or method that needs IBM Cognos Analytics (CAM security) as well as
 * TM1, and skips it when either isn't available.
 *
 * <p>
 * It includes {@link RequiresTm1}, so tests also skip without Docker or a
 * {@link Tm1Source}. Declare a {@link CognosSource} parameter to get the source
 * {@link CognosSource#discover()} found. Cognos takes minutes to start and its test users
 * vary by test, so there's no shared Cognos: build a {@link CognosContainer} with the
 * users the test needs.
 *
 * <pre class="code">
 * &#64;RequiresCognos
 * class CamSecurityTest {
 *
 *     &#64;Test
 *     void readersCanOnlyRead(CognosSource cognosSource) {
 *         try (var cognos = new CognosContainer(cognosSource).withUser("viewer", "pw", "Readers") ...
 *     }
 * }
 * </pre>
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RequiresTm1
@ExtendWith(CognosExtension.class)
public @interface RequiresCognos {

	/** Why tests skip when no Cognos source is configured, and what to set. */
	String NO_SOURCE_MESSAGE = "No Cognos source configured. Set one of " + CognosSource.PRIVATE_IMAGE + ", "
			+ CognosSource.INSTALL_VOLUME + ", " + CognosSource.ZIP_DIR + " or " + CognosSource.ZIP_URL
			+ ", or put a casrv-*-linuxi38664h.zip in " + Tm1Source.BUNDLE_DIR
			+ " (as an environment variable or system property) to run tests against Cognos.";

}
