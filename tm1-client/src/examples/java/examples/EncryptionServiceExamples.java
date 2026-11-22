package examples;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.KeyProtectSettingsPatch;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link net.ajmyers.tm1.client.service.encryption.EncryptionService},
 * quoted in its Javadoc.
 */
final class EncryptionServiceExamples {

	Mono<Void> configure(Tm1Client tm1) {
		// @start region = configure
		// Check the settings first: the report says what's wrong, without secrets
		KeyProtectSettingsPatch keyProtect = KeyProtectSettingsPatch.builder()
			.serviceEndpoint("https://us-south.kms.cloud.ibm.com")
			.instanceID("8f2c1d6e-0000-4000-8000-000000000000")
			.authRef("/run/secrets/key-protect-api-key")
			.keyRef("planning-root-key")
			.build();
		return tm1.encryption()
			.validate(keyProtect)
			.flatMap(report -> Boolean.TRUE.equals(report.isValid()) ? tm1.encryption().configure(keyProtect)
					: Mono.error(new IllegalStateException("Can't encrypt: " + report.issues())));
		// @end
	}

	Mono<Void> rotate(Tm1Client tm1) {
		// @start region = rotate
		// A new data encryption key; TM1 re-encrypts the whole model with it
		return tm1.encryption().rotateDataEncryptionKey();
		// @end
	}

	private EncryptionServiceExamples() {
	}

}
