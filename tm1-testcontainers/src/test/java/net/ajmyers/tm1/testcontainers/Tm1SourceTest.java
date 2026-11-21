package net.ajmyers.tm1.testcontainers;

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

class Tm1SourceTest {

	@TempDir
	Path temp;

	@Test
	void findsNothingWhenNothingIsSet() {
		assertThat(discover(Map.of())).isEmpty();
		assertThat(discover(Map.of(Tm1Source.HOME_DIR, "  "))).isEmpty();
	}

	@Test
	void triesSourcesInTheImagesOrder() throws IOException {
		Path install = fakeInstall();
		Map<String, String> all = Map.of(Tm1Source.PRIVATE_IMAGE, "registry.example.com/private/tm1-server:2.1.19",
				Tm1Source.HOME_DIR, install.toString(), Tm1Source.INSTALL_VOLUME, "tm1_tm1-install",
				Tm1Source.BUNDLE_DIR, temp.toString(), Tm1Source.BUNDLE_URL, "https://example.com/b.tar.gz");

		assertThat(discover(all)).containsInstanceOf(Tm1Source.PrivateImage.class);
		assertThat(discover(without(all, Tm1Source.PRIVATE_IMAGE))).contains(new Tm1Source.InstallDir(install));
		assertThat(discover(without(all, Tm1Source.PRIVATE_IMAGE, Tm1Source.HOME_DIR)))
			.contains(new Tm1Source.InstallVolume("tm1_tm1-install"));
		assertThat(discover(without(all, Tm1Source.PRIVATE_IMAGE, Tm1Source.HOME_DIR, Tm1Source.INSTALL_VOLUME)))
			.contains(new Tm1Source.Bundle(temp, null));
		assertThat(
				discover(Map.of(Tm1Source.BUNDLE_URL, "https://example.com/b.tar.gz", Tm1Source.BUNDLE_SHA256, "abc")))
			.contains(new Tm1Source.BundleUrl(URI.create("https://example.com/b.tar.gz"), "abc"));
	}

	@Test
	void failsOnAWrongSettingRatherThanSkipping() {
		assertThatIllegalArgumentException().isThrownBy(() -> discover(Map.of(Tm1Source.HOME_DIR, temp.toString())))
			.withMessageContaining("no bin64/tm1s.exe");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> discover(Map.of(Tm1Source.BUNDLE_DIR, temp.resolve("missing").toString())))
			.withMessageContaining("is not a folder");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString(), Tm1Source.BUNDLE, "nope.tar.gz")))
			.withMessageContaining("isn't in");
	}

	@Test
	void namesABundleFileWithinItsFolder() throws IOException {
		Files.createFile(temp.resolve("tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz"));
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString(), Tm1Source.BUNDLE,
				"tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz")))
			.contains(new Tm1Source.Bundle(temp, "tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz"));
	}

	@Test
	void neverDescribesAUrlsQueryString() {
		Tm1Source url = new Tm1Source.BundleUrl(
				URI.create("https://bucket.s3.amazonaws.com/b.tar.gz?X-Amz-Signature=secret"), null);
		assertThat(url.describe()).isEqualTo("bundle URL https://bucket.s3.amazonaws.com/b.tar.gz")
			.doesNotContain("secret");
	}

	@Test
	void neverDescribesCredentialsInAUrl() {
		Tm1Source url = new Tm1Source.BundleUrl(
				URI.create("https://ci-user:s3cr3t@artifacts.example.com:8443/tm1/b.tar.gz"), null);
		assertThat(url.describe()).isEqualTo("bundle URL https://artifacts.example.com:8443/tm1/b.tar.gz")
			.doesNotContain("ci-user")
			.doesNotContain("s3cr3t");
	}

	@Test
	void tagsSnapshotsWithEdge() {
		assertThat(Tm1ServerContainer.imageTag("0.1.0-SNAPSHOT")).isEqualTo("edge");
		assertThat(Tm1ServerContainer.imageTag("0.1.0")).isEqualTo("0.1.0");
	}

	private Path fakeInstall() throws IOException {
		Path install = temp.resolve("tm1_64");
		Files.createDirectories(install.resolve("bin64"));
		Files.createFile(install.resolve("bin64/tm1s.exe"));
		return install;
	}

	private static Optional<Tm1Source> discover(Map<String, String> settings) {
		return Tm1Source.discover(settings::get);
	}

	private static Map<String, String> without(Map<String, String> settings, String... names) {
		var copy = new java.util.HashMap<>(settings);
		for (String name : names) {
			copy.remove(name);
		}
		return copy;
	}

}
