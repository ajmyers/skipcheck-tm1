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

class Tm1WebSourceTest {

	@TempDir
	Path temp;

	@Test
	void findsNothingWhenNothingIsSet() {
		assertThat(discover(Map.of())).isEmpty();
		assertThat(discover(Map.of(Tm1WebSource.HOME_DIR, "  "))).isEmpty();
	}

	@Test
	void triesSourcesInTheImagesOrder() throws IOException {
		Path install = fakeInstall();
		assertThat(discover(Map.of(Tm1WebSource.PRIVATE_IMAGE, "registry.example.com/private/tm1-web:2.1.19",
				Tm1WebSource.HOME_DIR, install.toString())))
			.containsInstanceOf(Tm1WebSource.PrivateImage.class);
		assertThat(discover(Map.of(Tm1WebSource.HOME_DIR, install.toString(), Tm1WebSource.INSTALL_VOLUME, "v")))
			.contains(new Tm1WebSource.InstallDir(install));
		assertThat(discover(Map.of(Tm1WebSource.INSTALL_VOLUME, "v", Tm1WebSource.BUNDLE_DIR, temp.toString())))
			.contains(new Tm1WebSource.InstallVolume("v"));
		assertThat(discover(Map.of(Tm1WebSource.BUNDLE_DIR, temp.toString(), Tm1WebSource.BUNDLE_URL,
				"https://example.com/b.tar.gz")))
			.contains(new Tm1WebSource.Bundle(temp, null));
		assertThat(discover(
				Map.of(Tm1WebSource.BUNDLE_URL, "https://example.com/b.tar.gz", Tm1WebSource.BUNDLE_SHA256, "abc")))
			.contains(new Tm1WebSource.BundleUrl(URI.create("https://example.com/b.tar.gz"), "abc"));
	}

	@Test
	void usesTheTm1BundleFolderWhenItHoldsTm1Web() throws IOException {
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString()))).isEmpty();

		Files.createFile(temp.resolve("tm1web-11.0.112-26031316-2.1.19-linuxi38664h_bundle.tar.gz"));
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString())))
			.contains(new Tm1WebSource.Bundle(temp, null));
		// TM1_WEB_BUNDLE names a file within it, as in the compose setup
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString(), Tm1WebSource.BUNDLE,
				"tm1web-11.0.112-26031316-2.1.19-linuxi38664h_bundle.tar.gz")))
			.contains(new Tm1WebSource.Bundle(temp, "tm1web-11.0.112-26031316-2.1.19-linuxi38664h_bundle.tar.gz"));
		// Its own folder setting wins
		Path other = Files.createDirectory(temp.resolve("other"));
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString(), Tm1WebSource.BUNDLE_DIR, other.toString())))
			.contains(new Tm1WebSource.Bundle(other, null));
	}

	@Test
	void failsOnAWrongSettingRatherThanSkipping() {
		assertThatIllegalArgumentException().isThrownBy(() -> discover(Map.of(Tm1WebSource.HOME_DIR, temp.toString())))
			.withMessageContaining("no wlp/bin/server");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> discover(Map.of(Tm1WebSource.BUNDLE_DIR, temp.resolve("missing").toString())))
			.withMessageContaining("is not a folder");
		assertThatIllegalArgumentException().isThrownBy(
				() -> discover(Map.of(Tm1WebSource.BUNDLE_DIR, temp.toString(), Tm1WebSource.BUNDLE, "nope.tar.gz")))
			.withMessageContaining("isn't in");
	}

	@Test
	void neverDescribesAUrlWithItsCredentials() {
		var source = new Tm1WebSource.BundleUrl(
				URI.create("https://user:token@example.com:8443/b/tm1web.tar.gz?X-Amz-Signature=SECRET"), null);
		assertThat(source.describe()).isEqualTo("bundle URL https://example.com:8443/b/tm1web.tar.gz");
	}

	private Path fakeInstall() throws IOException {
		Path install = temp.resolve("tm1web");
		Files.createDirectories(install.resolve("wlp/bin"));
		Files.createFile(install.resolve("wlp/bin/server"));
		return install;
	}

	private static Optional<Tm1WebSource> discover(Map<String, String> settings) {
		return Tm1WebSource.discover(settings::get);
	}

}
