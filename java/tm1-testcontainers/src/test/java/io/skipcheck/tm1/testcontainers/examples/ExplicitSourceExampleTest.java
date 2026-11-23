// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * <b>Choosing where TM1 comes from in code</b>, instead of with settings.
 *
 * <p>
 * Most tests shouldn't: let {@link Tm1Source#discover()} read the environment, so each
 * developer and CI system can supply TM1 their own way. Choose in code when a test needs
 * a specific source, such as a particular bundle version, or when your test setup reads
 * its own configuration.
 *
 * <p>
 * Each test here runs only where that kind of source is available, read from the same
 * environment variables {@code discover()} uses, so these examples skip where you haven't
 * set them up.
 */
@RequiresTm1
class ExplicitSourceExampleTest {

	@Test
	@EnabledIfEnvironmentVariable(named = Tm1Source.HOME_DIR, matches = ".+")
	void fromAnInstalledFolder() {
		// The tm1_64 folder of a Linux TM1 Server install. Fastest to start: nothing to
		// install.
		var source = new Tm1Source.InstallDir(Path.of(System.getenv(Tm1Source.HOME_DIR)));
		assertStarts(new Tm1ServerContainer(source));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = Tm1Source.BUNDLE_DIR, matches = ".+")
	void fromIbmsBundleFile() {
		// IBM's download, as is. Installed on first use into a shared cache volume.
		// Pass a file name as the second argument to pick one of several bundles.
		var source = new Tm1Source.Bundle(Path.of(System.getenv(Tm1Source.BUNDLE_DIR)), null);
		assertStarts(new Tm1ServerContainer(source));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = Tm1Source.BUNDLE_URL, matches = ".+")
	void fromADownloadLink() {
		// A pre-signed S3/R2/GCS link, or any URL curl can fetch. The SHA-256 is optional
		// but
		// recommended. The URL's query string (where pre-signed credentials live) is
		// never logged.
		var source = new Tm1Source.BundleUrl(URI.create(System.getenv(Tm1Source.BUNDLE_URL)),
				System.getenv(Tm1Source.BUNDLE_SHA256));
		assertStarts(new Tm1ServerContainer(source));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = Tm1Source.INSTALL_VOLUME, matches = ".+")
	void fromADockerVolume() {
		// A volume that already holds an install, such as the one the tm1-server compose
		// setup
		// fills (tm1_tm1-install), so tests reuse the TM1 you already run.
		var source = new Tm1Source.InstallVolume(System.getenv(Tm1Source.INSTALL_VOLUME));
		assertStarts(new Tm1ServerContainer(source));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = Tm1Source.PRIVATE_IMAGE, matches = ".+")
	void fromAPrivateImageWithTm1Inside() {
		// Built from docker/tm1-server/examples/private-image and pushed to a PRIVATE
		// registry
		// (it contains IBM software). Fastest for CI: nothing to mount or install.
		var source = new Tm1Source.PrivateImage(System.getenv(Tm1Source.PRIVATE_IMAGE));
		assertStarts(new Tm1ServerContainer(source));
	}

	private static void assertStarts(Tm1ServerContainer container) {
		try (container) {
			container.start();
			assertThat(container.getTm1Version()).isNotNull();
		}
	}

}
