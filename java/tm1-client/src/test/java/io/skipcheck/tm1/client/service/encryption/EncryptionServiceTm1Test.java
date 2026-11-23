package io.skipcheck.tm1.client.service.encryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.model.EncryptionAtRestValidationReport;
import io.skipcheck.tm1.client.model.EncryptionFileType;
import io.skipcheck.tm1.client.model.LocalKeyFileProviderSettingsPatch;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * EncryptionService, on a TM1 of its own, since encryption changes the server. The image
 * has no GSKit command line, so the local key file provider can't make a key, and nothing
 * here gets encrypted: the test pins how TM1 says so.
 */
@RequiresTm1
class EncryptionServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static final LocalKeyFileProviderSettingsPatch LOCAL = LocalKeyFileProviderSettingsPatch.builder()
		.keyRef("/tmp/tm1-client.key")
		.build();

	private static Tm1ServerContainer server;

	private static Tm1Client tm1;

	@BeforeAll
	@SuppressWarnings("resource") // stopped in close()
	static void start() {
		server = new Tm1ServerContainer(Tm1Source.discover().orElseThrow());
		server.start();
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
		server.stop();
	}

	@Test
	void validatesSettingsWithoutChangingAnything() {
		EncryptionAtRestValidationReport report = tm1.encryption().validate(LOCAL).block(WAIT);
		assertThat(report.isValid()).isFalse();
		assertThat(report.issues()).singleElement()
			.asString()
			.contains("Could not generate a new key using the local GSKit installation");
	}

	@Test
	void refusesChangesWithBareCodeNames() {
		assertThatThrownBy(() -> tm1.encryption().configure(LOCAL).block(WAIT))
			.isInstanceOf(Tm1BadRequestException.class)
			.hasMessageContaining("DataModelConversion");
		assertThatThrownBy(() -> tm1.encryption().disable().block(WAIT)).hasMessageContaining("DataModelConversion");
		assertThatThrownBy(() -> tm1.encryption().rotateDataEncryptionKey().block(WAIT))
			.hasMessageContaining("DataEncryptionKeyRotationFailed");
		assertThatThrownBy(() -> tm1.encryption().rotateDataModelKey().block(WAIT))
			.hasMessageContaining("RotateDataModelKey");
		assertThatThrownBy(() -> tm1.encryption()
			.encryptFile("tm1s.cfg", "/tmp/tm1s.cfg.enc", EncryptionFileType.OBJECT)
			.block(WAIT)).hasMessageContaining("DataFileConversion");
		assertThatThrownBy(() -> tm1.encryption()
			.decryptFile("}clientgroups.cub", "/tmp/clientgroups.cub", EncryptionFileType.OBJECT)
			.block(WAIT)).hasMessageContaining("DataFileConversion");
	}

}
