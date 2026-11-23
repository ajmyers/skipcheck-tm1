package io.skipcheck.tm1.client.service.encryption;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.EncryptionAtRestProviderSettingsPatch;
import io.skipcheck.tm1.client.model.EncryptionAtRestValidationReport;
import io.skipcheck.tm1.client.model.EncryptionFileType;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encryption at rest (schema 11.8.35, except where a method says): the model's files
 * encrypted with a data encryption key, itself protected by a key from a key management
 * service. The provider settings are a {@code KeyProtectSettingsPatch} (IBM Key Protect),
 * {@code VaultSettingsPatch} (HashiCorp Vault) or
 * {@code LocalKeyFileProviderSettingsPatch} (a key file made with the local GSKit).
 *
 * <p>
 * What TM1 (11.8) does here that you might not expect:
 * <ul>
 * <li>The local key file provider needs GSKit's {@code gsk9capicmd_64}, which a TM1
 * install may lack (the Docker image used in this project's tests does): validation then
 * reports "Could not generate a new key using the local GSKit installation".</li>
 * <li>On a server that isn't encrypted, the changing actions fail with a bare 400 and an
 * error code's name for a message: {@code DataModelConversion} (configure, disable),
 * {@code DataFileConversion} (encrypt or decrypt a file),
 * {@code DataEncryptionKeyRotationFailed} and {@code RotateDataModelKey}.</li>
 * <li>Each must be run by a full administrator.</li>
 * </ul>
 *
 * <p>
 * Encrypt the model with a key from IBM Key Protect, checking the settings first:
 * {@snippet class = examples.EncryptionServiceExamples region = configure}
 *
 * <p>
 * Rotate the data encryption key:
 * {@snippet class = examples.EncryptionServiceExamples region = rotate}
 */
public final class EncryptionService {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ODataPath.Alias SETTINGS = new ODataPath.Alias("@settings");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public EncryptionService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code ValidateEncryptionAtRestSettings}: checks the provider settings would work
	 * now (the key service is reachable and allows it, and the key exists), changing
	 * nothing.
	 * @param settings the provider settings
	 * @return the report: whether they're valid, and if not, the issues; never secrets
	 */
	@Tm1Endpoint("FunctionImport ValidateEncryptionAtRestSettings")
	public Mono<EncryptionAtRestValidationReport> validate(EncryptionAtRestProviderSettingsPatch settings) {
		Tm1Operation operation = Tm1Operations.VALIDATE_ENCRYPTION_AT_REST_SETTINGS;
		return Mono.fromCallable(() -> json(settings))
			.flatMap(json -> session.execute(Tm1Request
				.get(ODataPath.functionImport(importName(operation), Map.of("Settings", SETTINGS)),
						ODataQuery.none().alias(SETTINGS, json), EncryptionAtRestValidationReport.class)
				.requires(operation.capability())));
	}

	/**
	 * {@code ConfigureEncryptionAtRest}: validates the provider settings, then applies
	 * them; a server not yet encrypted is encrypted.
	 * @param settings the provider settings
	 * @return completes when they're applied
	 */
	@Tm1Endpoint("ActionImport ConfigureEncryptionAtRest")
	public Mono<Void> configure(EncryptionAtRestProviderSettingsPatch settings) {
		return call(Tm1Operations.CONFIGURE_ENCRYPTION_AT_REST,
				Map.of("Settings", Objects.requireNonNull(settings, "settings")));
	}

	/**
	 * {@code DisableEncryptionAtRest}: decrypts the model and stops encrypting it.
	 * @return completes when it's done
	 */
	@Tm1Endpoint("ActionImport DisableEncryptionAtRest")
	public Mono<Void> disable() {
		return call(Tm1Operations.DISABLE_ENCRYPTION_AT_REST, Map.of());
	}

	/**
	 * {@code RotateDataEncryptionKey}: makes a new data encryption key and re-encrypts
	 * the whole model with it.
	 * @return completes when it's done
	 */
	@Tm1Endpoint("ActionImport RotateDataEncryptionKey")
	public Mono<Void> rotateDataEncryptionKey() {
		return call(Tm1Operations.ROTATE_DATA_ENCRYPTION_KEY, Map.of());
	}

	/**
	 * {@code RotateDataModelKey}: rotates the model key.
	 * @return completes when it's done
	 */
	@Tm1Endpoint("ActionImport RotateDataModelKey")
	public Mono<Void> rotateDataModelKey() {
		return call(Tm1Operations.ROTATE_DATA_MODEL_KEY, Map.of());
	}

	/**
	 * {@code EncryptDataFile} (schema 11.3.0): encrypts one file, such as an exported
	 * cube.
	 * @param source the file, relative to the data directory or absolute
	 * @param destination where the encrypted file goes
	 * @param type what the file is: a model object, a transaction log or an audit log
	 * @return completes when it's written
	 */
	@Tm1Endpoint("ActionImport EncryptDataFile")
	public Mono<Void> encryptFile(String source, String destination, EncryptionFileType type) {
		return call(Tm1Operations.ENCRYPT_DATA_FILE, file(source, destination, type));
	}

	/**
	 * {@code DecryptDataFile} (schema 11.3.0): decrypts one file.
	 * @param source the encrypted file, relative to the data directory or absolute
	 * @param destination where the decrypted file goes
	 * @param type what the file is
	 * @return completes when it's written
	 */
	@Tm1Endpoint("ActionImport DecryptDataFile")
	public Mono<Void> decryptFile(String source, String destination, EncryptionFileType type) {
		return call(Tm1Operations.DECRYPT_DATA_FILE, file(source, destination, type));
	}

	/**
	 * {@code EncryptDataModel} (schema 11.3.0): encrypts the model, then shuts the server
	 * down.
	 * @param timeUntilShutdown how long to wait before shutting down, or {@code null} for
	 * TM1's
	 * @return completes when it's started
	 * @deprecated Deprecated in TM1 schema 11.8.35: use {@link #configure}.
	 */
	@Deprecated(since = "11.8.35")
	@Tm1Endpoint("ActionImport EncryptDataModel")
	public Mono<Void> encryptModel(@Nullable Duration timeUntilShutdown) {
		return call(Tm1Operations.ENCRYPT_DATA_MODEL, shutdown(timeUntilShutdown));
	}

	/**
	 * {@code DecryptDataModel} (schema 11.3.0): decrypts the model, then shuts the server
	 * down.
	 * @param timeUntilShutdown how long to wait before shutting down, or {@code null} for
	 * TM1's
	 * @return completes when it's started
	 * @deprecated Deprecated in TM1 schema 11.8.35: use {@link #disable}.
	 */
	@Deprecated(since = "11.8.35")
	@Tm1Endpoint("ActionImport DecryptDataModel")
	public Mono<Void> decryptModel(@Nullable Duration timeUntilShutdown) {
		return call(Tm1Operations.DECRYPT_DATA_MODEL, shutdown(timeUntilShutdown));
	}

	private static Map<String, Object> file(String source, String destination, EncryptionFileType type) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("SourcePath", Objects.requireNonNull(source, "source"));
		parameters.put("DestinationPath", Objects.requireNonNull(destination, "destination"));
		parameters.put("FileType", Objects.requireNonNull(type.json(), "type"));
		return parameters;
	}

	private static Map<String, Object> shutdown(@Nullable Duration timeUntilShutdown) {
		return timeUntilShutdown == null ? Map.of() : Map.of("TimeUntilShutdown", timeUntilShutdown.toString());
	}

	private Mono<Void> call(Tm1Operation operation, Map<String, ?> parameters) {
		return session.execute(Tm1Request.post(ODataPath.of(importName(operation)), parameters, Void.class)
			.requires(operation.capability()));
	}

	private static String importName(Tm1Operation operation) {
		return Objects.requireNonNull(operation.importName(), operation.name());
	}

	private static String json(EncryptionAtRestProviderSettingsPatch settings) {
		try {
			return JSON.writeValueAsString(Objects.requireNonNull(settings, "settings"));
		}
		catch (JacksonException e) {
			throw new IllegalArgumentException("The settings can't be written as JSON", e);
		}
	}

}
