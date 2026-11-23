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

class CognosSourceTest {

	@TempDir
	Path temp;

	@Test
	void findsNothingWhenNothingIsSet() {
		assertThat(discover(Map.of())).isEmpty();
		assertThat(discover(Map.of(CognosSource.ZIP_DIR, "  "))).isEmpty();
	}

	@Test
	void triesSourcesInOrder() {
		assertThat(discover(
				Map.of(CognosSource.PRIVATE_IMAGE, "cognos-server:12.1.1-private", CognosSource.INSTALL_VOLUME, "v")))
			.contains(new CognosSource.PrivateImage("cognos-server:12.1.1-private"));
		assertThat(discover(Map.of(CognosSource.INSTALL_VOLUME, "v", CognosSource.ZIP_DIR, temp.toString())))
			.contains(new CognosSource.InstallVolume("v"));
		assertThat(discover(
				Map.of(CognosSource.ZIP_DIR, temp.toString(), CognosSource.ZIP_URL, "https://example.com/c.zip")))
			.contains(new CognosSource.Zip(temp, null));
		assertThat(discover(Map.of(CognosSource.ZIP_URL, "https://example.com/c.zip", CognosSource.ZIP_SHA256, "abc")))
			.contains(new CognosSource.ZipUrl(URI.create("https://example.com/c.zip"), "abc"));
	}

	@Test
	void usesTheTm1BundleFolderWhenItHoldsCognos() throws IOException {
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString()))).isEmpty();

		Files.createFile(temp.resolve("casrv-12.1.1-2512181837-linuxi38664h.zip"));
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString()))).contains(new CognosSource.Zip(temp, null));
		assertThat(discover(Map.of(Tm1Source.BUNDLE_DIR, temp.toString(), CognosSource.ZIP,
				"casrv-12.1.1-2512181837-linuxi38664h.zip")))
			.contains(new CognosSource.Zip(temp, "casrv-12.1.1-2512181837-linuxi38664h.zip"));
	}

	@Test
	void failsOnAWrongSettingRatherThanSkipping() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> discover(Map.of(CognosSource.ZIP_DIR, temp.resolve("missing").toString())))
			.withMessageContaining(CognosSource.ZIP_DIR);
		assertThatIllegalArgumentException()
			.isThrownBy(() -> discover(Map.of(CognosSource.ZIP_DIR, temp.toString(), CognosSource.ZIP, "nope.zip")))
			.withMessageContaining("nope.zip");
	}

	@Test
	void neverDescribesAUrlsCredentials() {
		var source = new CognosSource.ZipUrl(
				URI.create("https://user:secret@example.com:8443/c.zip?X-Amz-Signature=SECRET"), null);
		assertThat(source.describe()).isEqualTo("zip URL https://example.com:8443/c.zip")
			.doesNotContain("secret")
			.doesNotContain("SECRET");
	}

	private static Optional<CognosSource> discover(Map<String, String> settings) {
		return CognosSource.discover(settings::get);
	}

}
