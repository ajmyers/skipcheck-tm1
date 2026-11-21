package net.ajmyers.tm1.testcontainers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs a test class or method against a real Planning Analytics Workspace and TM1 server,
 * and skips it when either isn't available.
 *
 * <p>
 * It includes {@link RequiresTm1}, so tests also skip without Docker or a
 * {@link Tm1Source}, and can still take a {@link Tm1ServerContainer} parameter. They also
 * skip without a {@link PawSource}, or when IBM's PAW license hasn't been accepted with
 * {@code PAW_ACCEPT_LICENSE=true}. Declare a {@link PawContainer} parameter to get the
 * {@linkplain PawContainer#shared() shared PAW}, which logs users on to the shared TM1
 * server:
 *
 * <pre class="code">
 * &#64;RequiresPaw
 * class WorkspaceTest {
 *
 *     &#64;Test
 *     void opensPaw(PawContainer paw, Tm1ServerContainer tm1) {
 *         String url = paw.getUrl();   // http://localhost:54321/
 *         ...
 *     }
 * }
 * </pre>
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RequiresTm1
@ExtendWith(PawExtension.class)
public @interface RequiresPaw {

	/** Why tests skip when no PAW source is configured, and what to set. */
	String NO_SOURCE_MESSAGE = "No Planning Analytics Workspace source configured. Set " + PawSource.ZIP
			+ " to IBM's ipa_workspace_local_*.zip, or " + PawSource.BUNDLE_DIR
			+ " to its folder (as an environment variable or system property), to run tests against PAW.";

	/** Why tests skip when IBM's PAW license hasn't been accepted, and what to set. */
	String NO_LICENSE_MESSAGE = "IBM's Planning Analytics Workspace license hasn't been accepted. Read it (licenses/LA_en"
			+ " in the PAW Local zip), then set " + PawContainer.ACCEPT_LICENSE
			+ "=true (as an environment variable or system property) to run tests against PAW.";

}
