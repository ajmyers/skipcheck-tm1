package net.ajmyers.tm1.client.service.encryption;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import net.ajmyers.tm1.client.conformance.Conformance;
import net.ajmyers.tm1.client.conformance.ConformanceTm1;
import net.ajmyers.tm1.client.error.Tm1BadRequestException;
import net.ajmyers.tm1.client.error.Tm1ForbiddenException;
import net.ajmyers.tm1.client.model.EncryptionAtRestValidationReport;
import net.ajmyers.tm1.client.model.EncryptionFileType;
import net.ajmyers.tm1.client.model.KeyProtectSettingsPatch;
import net.ajmyers.tm1.client.model.LocalKeyFileProviderSettingsPatch;
import net.ajmyers.tm1.client.model.VaultSettingsPatch;

/**
 * EncryptionService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/EncryptionServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
@SuppressWarnings("deprecation") // encryptModel and decryptModel are deprecated in
									// 11.8.35
class EncryptionServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void validatesSettingsGivenAsAParameterAlias() {
		tm1().json(
				"{\"IsValid\":false,\"Issues\":[\"Could not generate a new key using the local GSKit installation\"]}");
		EncryptionAtRestValidationReport report = conformance.call("encryption", "validate",
				LocalKeyFileProviderSettingsPatch.builder().keyRef("/tmp/a.key").build());
		assertThat(report.isValid()).isFalse();
		assertThat(report.issues()).hasSize(1);
		ConformanceTm1.Recorded get = tm1().last();
		assertThat(get.method()).isEqualTo("GET");
		assertThat(get.target()).startsWith("ValidateEncryptionAtRestSettings(Settings=@settings)?@settings=");
	}

	@Test
	void encodesTheSettingsInTheQuery() {
		tm1().json("{\"IsValid\":true,\"Issues\":[]}");
		EncryptionAtRestValidationReport report = conformance.call("encryption", "validate",
				KeyProtectSettingsPatch.builder()
					.keyRef("key/1?x=a&b=c#d")
					.serviceEndpoint("https://us-south.kms.cloud.ibm.com")
					.instanceID("a1b2 c3")
					.authRef("apikey's \"value\" 100%")
					.authEndpoint("https://iam.cloud.ibm.com/identity/token")
					.build());
		assertThat(report.isValid()).isTrue();

		tm1().json("{\"IsValid\":true}");
		conformance.call("encryption", "validate",
				VaultSettingsPatch.builder()
					.keyRef("transit/keys/tm1")
					.serviceEndpoint("https://vault.example.com:8200")
					.authRef("s.token+/=")
					.build());
	}

	@Test
	void configures() {
		tm1().status(204);
		Object none = conformance.call("encryption", "configure",
				LocalKeyFileProviderSettingsPatch.builder().keyRef("/tmp/a.key").build());
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("POST ConfigureEncryptionAtRest");

		tm1().status(204);
		conformance.call("encryption", "configure",
				VaultSettingsPatch.builder()
					.keyRef("transit/keys/tm1")
					.serviceEndpoint("https://vault.example.com:8200")
					.authRef("s.token")
					.build());
	}

	@Test
	void changingAServerNotEncryptedIsABadRequest() {
		tm1().error(400, "1", "DataModelConversion");
		assertThat(conformance.fail("encryption", "configure",
				LocalKeyFileProviderSettingsPatch.builder().keyRef("/tmp/a.key").build()))
			.isInstanceOf(Tm1BadRequestException.class);
		tm1().error(400, "1", "DataModelConversion");
		assertThat(conformance.fail("encryption", "disable")).isInstanceOf(Tm1BadRequestException.class);
		tm1().error(400, "1", "DataEncryptionKeyRotationFailed");
		assertThat(conformance.fail("encryption", "rotateDataEncryptionKey"))
			.isInstanceOf(Tm1BadRequestException.class);
		tm1().error(400, "1", "RotateDataModelKey");
		assertThat(conformance.fail("encryption", "rotateDataModelKey")).isInstanceOf(Tm1BadRequestException.class);
		tm1().error(400, "1", "DataFileConversion");
		assertThat(conformance.fail("encryption", "encryptFile", "a.cub", "a.cub.enc", EncryptionFileType.OBJECT))
			.isInstanceOf(Tm1BadRequestException.class);
		tm1().error(400, "1", "DataFileConversion");
		assertThat(conformance.fail("encryption", "decryptFile", "a.cub.enc", "a.cub", EncryptionFileType.OBJECT))
			.isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void onlyAFullAdministratorMayChangeIt() {
		tm1().error(403, "1", "Insufficient privileges");
		assertThat(conformance.fail("encryption", "disable")).isInstanceOf(Tm1ForbiddenException.class);
	}

	@Test
	void disablesAndRotatesKeys() {
		tm1().status(204);
		Object none = conformance.call("encryption", "disable");
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("POST DisableEncryptionAtRest");
		assertThat(tm1().last().text()).isEqualTo("{}");

		tm1().status(204);
		conformance.call("encryption", "rotateDataEncryptionKey");
		assertThat(tm1().last().key()).isEqualTo("POST RotateDataEncryptionKey");

		tm1().status(204);
		conformance.call("encryption", "rotateDataModelKey");
		assertThat(tm1().last().key()).isEqualTo("POST RotateDataModelKey");
	}

	@Test
	void encryptsAndDecryptsFilesOfEachType() {
		tm1().status(204);
		conformance.call("encryption", "encryptFile", "Sales.cub", "/tmp/Sales.cub.enc", EncryptionFileType.OBJECT);
		assertThat(tm1().last().key()).isEqualTo("POST EncryptDataFile");
		assertThat(tm1().last().text()).isEqualTo(
				"{\"SourcePath\":\"Sales.cub\",\"DestinationPath\":\"/tmp/Sales.cub.enc\",\"FileType\":\"Object\"}");

		tm1().status(204);
		conformance.call("encryption", "decryptFile", "tm1s20261122.log", "C:\\logs\\tm1s.log",
				EncryptionFileType.TRANSACTION_LOG);
		assertThat(tm1().last().key()).isEqualTo("POST DecryptDataFile");

		tm1().status(204);
		conformance.call("encryption", "encryptFile", "tm1auditstore/audit.log", "audit.log.enc",
				EncryptionFileType.AUDIT_LOG);
	}

	@Test
	void aFileTypeIsRequired() {
		Throwable error = conformance.fail("encryption", "encryptFile", "a", "b", EncryptionFileType.UNKNOWN);
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void encryptsAndDecryptsTheModel() {
		tm1().status(204);
		conformance.call("encryption", "encryptModel", Duration.ofSeconds(30));
		assertThat(tm1().last().key()).isEqualTo("POST EncryptDataModel");
		assertThat(tm1().last().text()).isEqualTo("{\"TimeUntilShutdown\":\"PT30S\"}");

		tm1().status(204);
		conformance.call("encryption", "encryptModel", Duration.ofHours(36).plusMillis(1500));
		assertThat(tm1().last().text()).isEqualTo("{\"TimeUntilShutdown\":\"PT36H1.5S\"}");

		tm1().status(204);
		conformance.call("encryption", "decryptModel", (Object) null);
		assertThat(tm1().last().key()).isEqualTo("POST DecryptDataModel");
		assertThat(tm1().last().text()).isEqualTo("{}");

		tm1().status(204);
		conformance.call("encryption", "decryptModel", Duration.ofSeconds(90));
		assertThat(tm1().last().text()).isEqualTo("{\"TimeUntilShutdown\":\"PT1M30S\"}");
	}

}
