import { EncryptionAtRestValidationReport, Tm1Operations, type EncryptionAtRestProviderSettingsPatch, type EncryptionFileType } from "../../generated/index.js";
import { Tm1Duration, writeJson } from "../../json.js";
import { ODataPath, ODataQuery } from "../../odata/index.js";
import type { Tm1Operation } from "../../operation.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const SETTINGS = new ODataPath.Alias("@settings");

/** Encryption at rest (schema 11.8.35, except where a method says): the model's files encrypted
 * with a data encryption key, itself protected by a key from a key management service. The provider
 * settings are a `KeyProtectSettingsPatch` (IBM Key Protect), `VaultSettingsPatch` (HashiCorp
 * Vault) or `LocalKeyFileProviderSettingsPatch` (a key file made with the local GSKit).
 *
 * What TM1 (11.8) does here that you might not expect:
 * - The local key file provider needs GSKit's `gsk9capicmd_64`, which a TM1 install may lack (the
 *   Docker image used in this project's tests does): validation then reports "Could not generate a
 *   new key using the local GSKit installation".
 * - On a server that isn't encrypted, the changing actions fail with a bare 400 and an error code's
 *   name for a message: `DataModelConversion` (configure, disable), `DataFileConversion` (encrypt or
 *   decrypt a file), `DataEncryptionKeyRotationFailed` and `RotateDataModelKey`.
 * - Each must be run by a full administrator.
 *
 * Encrypt the model with a key from IBM Key Protect, checking the settings first, then rotate the
 * data encryption key:
 * ```ts
 * const settings = KeyProtectSettingsPatch.builder().serviceUrl(url).instanceId(instance).apiKey(key).keyId(keyId).build();
 * const report = await tm1.encryption.validate(settings);
 * if (report.IsValid) await tm1.encryption.configure(settings);
 * await tm1.encryption.rotateDataEncryptionKey();
 * ```
 */
export class EncryptionService extends Tm1Service {
  static readonly endpoints = {
    validate: ["FunctionImport ValidateEncryptionAtRestSettings"],
    configure: ["ActionImport ConfigureEncryptionAtRest"],
    disable: ["ActionImport DisableEncryptionAtRest"],
    rotateDataEncryptionKey: ["ActionImport RotateDataEncryptionKey"],
    rotateDataModelKey: ["ActionImport RotateDataModelKey"],
    encryptFile: ["ActionImport EncryptDataFile"],
    decryptFile: ["ActionImport DecryptDataFile"],
    encryptModel: ["ActionImport EncryptDataModel"],
    decryptModel: ["ActionImport DecryptDataModel"],
  } as const satisfies Tm1Endpoints<EncryptionService>;

  /** `ValidateEncryptionAtRestSettings`: checks the provider settings would work now (the key
   * service is reachable and allows it, and the key exists), changing nothing. The settings go as a
   * parameter alias, `ValidateEncryptionAtRestSettings(Settings=@settings)?@settings={…}`. Returns
   * the report: whether they're valid, and if not, the issues; never secrets. */
  async validate(settings: EncryptionAtRestProviderSettingsPatch, options?: Tm1CallOptions): Promise<EncryptionAtRestValidationReport> {
    const operation = Tm1Operations.ValidateEncryptionAtRestSettings;
    const json = writeJson(required(settings, "settings"));
    const request = Tm1Request.get(ODataPath.functionImport(importName(operation), { Settings: SETTINGS }), ODataQuery.none().alias(SETTINGS, json), EncryptionAtRestValidationReport).requires(operation.capability());
    return this.session.execute(request, options);
  }

  /** `ConfigureEncryptionAtRest`: validates the provider settings, then applies them; a server not
   * yet encrypted is encrypted. */
  configure(settings: EncryptionAtRestProviderSettingsPatch, options?: Tm1CallOptions): Promise<void> {
    return this.call(Tm1Operations.ConfigureEncryptionAtRest, { Settings: required(settings, "settings") }, options);
  }

  /** `DisableEncryptionAtRest`: decrypts the model and stops encrypting it. */
  disable(options?: Tm1CallOptions): Promise<void> {
    return this.call(Tm1Operations.DisableEncryptionAtRest, {}, options);
  }

  /** `RotateDataEncryptionKey`: makes a new data encryption key and re-encrypts the whole model
   * with it. */
  rotateDataEncryptionKey(options?: Tm1CallOptions): Promise<void> {
    return this.call(Tm1Operations.RotateDataEncryptionKey, {}, options);
  }

  /** `RotateDataModelKey`: rotates the model key. */
  rotateDataModelKey(options?: Tm1CallOptions): Promise<void> {
    return this.call(Tm1Operations.RotateDataModelKey, {}, options);
  }

  /** `EncryptDataFile` (schema 11.3.0): encrypts one file, such as an exported cube. `source` is
   * relative to the data directory or absolute; `type` says what the file is: a model object, a
   * transaction log or an audit log. */
  encryptFile(source: string, destination: string, type: EncryptionFileType, options?: Tm1CallOptions): Promise<void> {
    return this.call(Tm1Operations.EncryptDataFile, file(source, destination, type), options);
  }

  /** `DecryptDataFile` (schema 11.3.0): decrypts one file. */
  decryptFile(source: string, destination: string, type: EncryptionFileType, options?: Tm1CallOptions): Promise<void> {
    return this.call(Tm1Operations.DecryptDataFile, file(source, destination, type), options);
  }

  /** `EncryptDataModel` (schema 11.3.0): encrypts the model, then shuts the server down after
   * `timeUntilShutdown` milliseconds, or, with none, TM1's wait.
   * @deprecated Deprecated in TM1 schema 11.8.35: use {@link configure}. */
  encryptModel(timeUntilShutdown?: number | null, options?: Tm1CallOptions): Promise<void> {
    return this.call(Tm1Operations.EncryptDataModel, shutdown(timeUntilShutdown), options);
  }

  /** `DecryptDataModel` (schema 11.3.0): decrypts the model, then shuts the server down after
   * `timeUntilShutdown` milliseconds, or, with none, TM1's wait.
   * @deprecated Deprecated in TM1 schema 11.8.35: use {@link disable}. */
  decryptModel(timeUntilShutdown?: number | null, options?: Tm1CallOptions): Promise<void> {
    return this.call(Tm1Operations.DecryptDataModel, shutdown(timeUntilShutdown), options);
  }

  private call(operation: Tm1Operation, parameters: Record<string, unknown>, options: Tm1CallOptions | undefined): Promise<void> {
    return this.session.execute(Tm1Request.post(ODataPath.of(importName(operation)), parameters).requires(operation.capability()), options);
  }
}

function file(source: string, destination: string, type: EncryptionFileType): Record<string, unknown> {
  const sourcePath = required(source, "source");
  const destinationPath = required(destination, "destination");
  // "Unknown" stands for a value this client doesn't know, and isn't sent
  if (required(type, "type") === "Unknown") throw new TypeError("type is required: Unknown isn't sent");
  return { SourcePath: sourcePath, DestinationPath: destinationPath, FileType: type };
}

function shutdown(timeUntilShutdown: number | null | undefined): Record<string, unknown> {
  return timeUntilShutdown == null ? {} : { TimeUntilShutdown: duration(timeUntilShutdown) };
}

/** Milliseconds as Java's `Duration.toString()` writes them, which is what the Java client sends:
 * hours, minutes and seconds, never days (`PT36H`, `PT30S`, `PT0.5S`). */
function duration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) throw new RangeError("timeUntilShutdown is a number of milliseconds, not negative: " + ms);
  if (ms >= 86_400_000) {
    // Tm1Duration writes days; Java doesn't
    const hours = Math.floor(ms / 3_600_000);
    const rest = Tm1Duration.ofMillis(ms - hours * 3_600_000);
    return "PT" + hours + "H" + (rest === "PT0S" ? "" : rest.slice(2));
  }
  return Tm1Duration.ofMillis(ms);
}

function importName(operation: Tm1Operation): string {
  return required(operation.importName, operation.name);
}
