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
 * EncryptionService, on a TM1 of its own, since encryption changes the server. Whether
 * local key file settings check as valid depends on the install, not the version (#439):
 * TM1 makes the key in its install's {@code bin64/ssl/ibmtm1.kdb}, so on a writable
 * install (a bundle's, a volume's) they check as valid, and configuring would really
 * convert the server and stop it, which the test doesn't try. On a read-only one (an
 * installed folder, {@code TM1_HOME_DIR}) TM1 can't make the key, the settings check as
 * invalid and every change is refused: the test pins how TM1 says so. So on a writable
 * source the run does change the install: a volume of your own
 * ({@code TM1_INSTALL_VOLUME}), or the bundle cache, keeps the key TM1 made in its
 * {@code ibmtm1.kdb}.
 */
@SuppressWarnings("deprecation")
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
	void validatesLocalKeyFileSettings() {
		ServerAssumptions.assumeSupports(tm1, Tm1Operations.VALIDATE_ENCRYPTION_AT_REST_SETTINGS);
		EncryptionAtRestValidationReport report = tm1.encryption().validate(LOCAL).block(WAIT);
		if (ServerAssumptions.install(server.getSource()) == ServerAssumptions.Install.WRITABLE) {
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
		// only where that server says they're invalid, whatever its install
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

}
