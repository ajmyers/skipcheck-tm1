package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PawSourceTest {

	@TempDir
	Path dir;

	@Test
	void findsNothingWhenNothingIsSet() {
		assertThat(PawSource.discover(Map.<String, String>of()::get)).isEmpty();
		assertThat(PawSource.discover(Map.of(PawSource.ZIP, "  ", PawSource.BUNDLE_DIR, "")::get)).isEmpty();
	}

	@Test
	void usesTheZipSetDirectly() throws Exception {
		Path zip = Files.createFile(dir.resolve("ipa_workspace_local_2.1.19.1260.zip"));

		assertThat(PawSource.discover(Map.of(PawSource.ZIP, zip.toString())::get)).contains(new PawSource(zip));
	}

	@Test
	void picksTheNewestZipInTheBundleFolder() throws Exception {
		Path older = Files.createFile(dir.resolve("ipa_workspace_local_2.1.18.1000.zip"));
		Path newer = Files.createFile(dir.resolve("ipa_workspace_local_2.1.19.1260.zip"));
		Files.createFile(dir.resolve("something-else.zip"));
		Files.setLastModifiedTime(older, FileTime.fromMillis(1_000));
		Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000));

		assertThat(PawSource.discover(Map.of(PawSource.BUNDLE_DIR, dir.toString())::get))
			.contains(new PawSource(newer));
	}

	@Test
	void prefersTheZipOverTheBundleFolder() throws Exception {
		Path zip = Files.createFile(dir.resolve("ipa_workspace_local_2.1.19.1260.zip"));

		assertThat(PawSource.discover(Map.of(PawSource.ZIP, zip.toString(), PawSource.BUNDLE_DIR, "/nowhere")::get))
			.contains(new PawSource(zip));
	}

	@Test
	void failsOnAWrongSettingRatherThanSkipping() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PawSource.discover(Map.of(PawSource.ZIP, dir.resolve("missing.zip").toString())::get))
			.withMessageContaining("Not a file");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PawSource.discover(Map.of(PawSource.BUNDLE_DIR, dir.toString())::get))
			.withMessageContaining("has no ipa_workspace_local_*.zip");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> PawSource.discover(Map.of(PawSource.BUNDLE_DIR, dir.resolve("nope").toString())::get))
			.withMessageContaining("is not a folder");
	}

	@Test
	void refusesToStartWithoutTheLicense() throws Exception {
		Path zip = Files.createFile(dir.resolve("ipa_workspace_local_2.1.19.1260.zip"));
		var paw = new PawContainer(new PawSource(zip));

		if (!PawContainer.licenseAcceptedBySetting()) {
			assertThatIllegalStateException().isThrownBy(paw::start).withMessageContaining(PawContainer.ACCEPT_LICENSE);
		}
		assertThatIllegalArgumentException().isThrownBy(() -> paw.withSetting("TM1_HOME_DIR", "x"));
	}

}
