// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Tm1EngineSourceTest {

	private static final String PACKAGE = "tm1-v12.6.7-standalone-linux.tgz";

	@TempDir
	Path temp;

	@Test
	void findsNothingWhenNothingIsSet() {
		assertThat(discover(Map.of())).isEmpty();
		assertThat(discover(Map.of(Tm1EngineSource.HOME_DIR, "  "))).isEmpty();
	}

	@Test
	void triesSourcesInTheImagesOrder() throws IOException {
		Path install = fakeInstall();
		assertThat(discover(Map.of(Tm1EngineSource.PRIVATE_IMAGE, "registry.example.com/private/tm1-engine:12.6.7",
				Tm1EngineSource.HOME_DIR, install.toString())))
			.containsInstanceOf(Tm1EngineSource.PrivateImage.class);
		assertThat(discover(Map.of(Tm1EngineSource.HOME_DIR, install.toString(), Tm1EngineSource.INSTALL_VOLUME, "v")))
			.contains(new Tm1EngineSource.InstallDir(install));
		assertThat(discover(Map.of(Tm1EngineSource.INSTALL_VOLUME, "v", Tm1EngineSource.BUNDLE_DIR, temp.toString())))
			.contains(new Tm1EngineSource.InstallVolume("v"));
		assertThat(discover(Map.of(Tm1EngineSource.BUNDLE_DIR, temp.toString(), Tm1EngineSource.BUNDLE_URL,
				"https://example.com/p.tgz")))
			.contains(new Tm1EngineSource.Bundle(temp, null));
		assertThat(discover(
				Map.of(Tm1EngineSource.BUNDLE_URL, "https://example.com/p.tgz", Tm1EngineSource.BUNDLE_SHA256, "abc")))
			.contains(new Tm1EngineSource.BundleUrl(URI.create("https://example.com/p.tgz"), "abc"));
	}

	@Test
	void usesTheTm1BundleFolderWhenItHoldsTm1V12() throws IOException {
		// TM1 11's folder without a v12 package in it: no source, so the tests are
		// skipped
		Files.createFile(temp.resolve("tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz"));
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString()))).isEmpty();

		Files.createFile(temp.resolve(PACKAGE));
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString())))
			.contains(new Tm1EngineSource.Bundle(temp, null));
		// TM1_ENGINE_BUNDLE names a file within it
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString(), Tm1EngineSource.BUNDLE, PACKAGE)))
			.contains(new Tm1EngineSource.Bundle(temp, PACKAGE));
		// Its own folder setting wins
		Path other = Files.createDirectory(temp.resolve("other"));
		assertThat(
				discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString(), Tm1EngineSource.BUNDLE_DIR, other.toString())))
			.contains(new Tm1EngineSource.Bundle(other, null));
	}

	@Test
	void failsOnAWrongSettingRatherThanSkipping() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> discover(Map.of(Tm1EngineSource.HOME_DIR, temp.toString())))
			.withMessageContaining("no bin/tm1sd.exe");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> discover(Map.of(Tm1EngineSource.BUNDLE_DIR, temp.resolve("missing").toString())))
			.withMessageContaining("is not a folder");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> discover(
					Map.of(Tm1EngineSource.BUNDLE_DIR, temp.toString(), Tm1EngineSource.BUNDLE, "missing.tgz")))
			.withMessageContaining("which isn't in");
	}

	@Test
	void describesAUrlWithoutItsSecrets() {
		var source = new Tm1EngineSource.BundleUrl(
				URI.create("https://user:token@downloads.example.com:8443/tm1/p.tgz?X-Amz-Signature=secret"), null);
		assertThat(source.describe()).isEqualTo("package URL https://downloads.example.com:8443/tm1/p.tgz");
	}

	private Path fakeInstall() throws IOException {
		Path install = Files.createDirectories(temp.resolve("tm1-engine/bin")).getParent();
		Files.createFile(install.resolve("bin/tm1sd.exe"));
		return install;
	}

	private static Optional<Tm1EngineSource> discover(Map<String, String> settings) {
		return Tm1EngineSource.discover(settings::get);
	}

}
