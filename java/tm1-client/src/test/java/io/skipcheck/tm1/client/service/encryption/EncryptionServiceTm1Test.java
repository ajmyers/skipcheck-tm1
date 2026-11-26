// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.encryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.ServerAssumptions;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1UnsupportedOperationException;
import io.skipcheck.tm1.client.model.EncryptionAtRestValidationReport;
import io.skipcheck.tm1.client.model.EncryptionFileType;
import io.skipcheck.tm1.client.model.LocalKeyFileProviderSettingsPatch;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;
import reactor.core.publisher.Mono;

/**
 * EncryptionService, on a TM1 of its own, since encryption changes the server. Before TM1
 * 11.8.040 the image's missing GSKit command line stops the local key file provider from
 * making a key, so the settings check as invalid and every change is refused: the test
 * pins how TM1 says so. From 11.8.040 the server makes the key itself, the settings check
 * as valid, and configuring would really convert the server and stop it, so the test
 * doesn't try that.
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
		ServerAssumptions.assumeSupports(tm1, Tm1Operations.VALIDATE_ENCRYPTION_AT_REST_SETTINGS);
		EncryptionAtRestValidationReport report = tm1.encryption().validate(LOCAL).block(WAIT);
		if (makesItsOwnKey()) {
			assertThat(report.isValid()).isTrue();
			assertThat(report.issues()).isEmpty();
		}
		else {
			assertThat(report.isValid()).isFalse();
			assertThat(report.issues()).singleElement()
				.asString()
				.contains("Could not generate a new key using the local GSKit installation");
		}
	}

	@Test
	void refusesChangesWithBareCodeNames() {
		// Configuring with settings the server takes would convert it and stop it, so
		// only where they check as invalid, whatever the version
		boolean takesThem = ServerAssumptions.capabilities(tm1)
			.supports(Tm1Operations.VALIDATE_ENCRYPTION_AT_REST_SETTINGS.capability())
				&& tm1.encryption().validate(LOCAL).block(WAIT).isValid();
		if (!takesThem) {
			refuses(Tm1Operations.CONFIGURE_ENCRYPTION_AT_REST, () -> tm1.encryption().configure(LOCAL),
					"DataModelConversion");
		}
		refuses(Tm1Operations.DISABLE_ENCRYPTION_AT_REST, () -> tm1.encryption().disable(), "DataModelConversion");
		refuses(Tm1Operations.ROTATE_DATA_ENCRYPTION_KEY, () -> tm1.encryption().rotateDataEncryptionKey(),
				"DataEncryptionKeyRotationFailed");
		refuses(Tm1Operations.ROTATE_DATA_MODEL_KEY, () -> tm1.encryption().rotateDataModelKey(), "RotateDataModelKey");
		refuses(Tm1Operations.ENCRYPT_DATA_FILE,
				() -> tm1.encryption().encryptFile("tm1s.cfg", "/tmp/tm1s.cfg.enc", EncryptionFileType.OBJECT),
				"DataFileConversion");
		refuses(Tm1Operations.DECRYPT_DATA_FILE,
				() -> tm1.encryption()
					.decryptFile("}clientgroups.cub", "/tmp/clientgroups.cub", EncryptionFileType.OBJECT),
				"DataFileConversion");
	}

	/**
	 * TM1's refusal, with its bare code name in the message, where the server defines the
	 * operation; the client's own refusal, sending nothing, where it doesn't.
	 */
	private static void refuses(Tm1Operation operation, Supplier<Mono<?>> call, String code) {
		if (ServerAssumptions.capabilities(tm1).supports(operation.capability())) {
			assertThatThrownBy(() -> call.get().block(WAIT)).as(operation.endpoint())
				.isInstanceOf(Tm1BadRequestException.class)
				.hasMessageContaining(code);
		}
		else {
			assertThatThrownBy(() -> call.get().block(WAIT)).as(operation.endpoint())
				.isInstanceOf(Tm1UnsupportedOperationException.class);
		}
	}

	/** Whether the server's settings are expected to check as valid. */
	private static boolean makesItsOwnKey() {
		return ServerAssumptions.isAtLeast(tm1, ServerAssumptions.MAKES_OWN_KEY_FROM);
	}

}
