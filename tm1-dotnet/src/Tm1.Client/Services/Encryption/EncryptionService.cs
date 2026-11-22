using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;
using AjMyers.Tm1.Client.Json;
using AjMyers.Tm1.Client.Model;
using AjMyers.Tm1.Client.OData;
using AjMyers.Tm1.Client.Session;

namespace AjMyers.Tm1.Client.Services;

/// <summary>Encryption at rest (schema 11.8.35, except where a method says): the model's files
/// encrypted with a data encryption key, itself protected by a key from a key management service.
/// The provider settings are a <c>KeyProtectSettingsPatch</c> (IBM Key Protect),
/// <c>VaultSettingsPatch</c> (HashiCorp Vault) or <c>LocalKeyFileProviderSettingsPatch</c> (a key file
/// made with the local GSKit).
/// <para>What TM1 (11.8) does here that you might not expect:</para>
/// <list type="bullet">
/// <item>The local key file provider needs GSKit's <c>gsk9capicmd_64</c>, which a TM1 install may lack
/// (the Docker image used in this project's tests does): validation then reports "Could not generate a
/// new key using the local GSKit installation".</item>
/// <item>On a server that isn't encrypted, the changing actions fail with a bare 400 and an error
/// code's name for a message: <c>DataModelConversion</c> (configure, disable),
/// <c>DataFileConversion</c> (encrypt or decrypt a file), <c>DataEncryptionKeyRotationFailed</c> and
/// <c>RotateDataModelKey</c>.</item>
/// <item>Each must be run by a full administrator.</item>
/// </list>
/// <para>Encrypt the model with a key from IBM Key Protect, checking the settings first:</para>
/// <code>
/// // Check the settings first: the report says what's wrong, without secrets
/// var keyProtect = KeyProtectSettingsPatch.Builder()
///     .ServiceEndpoint("https://us-south.kms.cloud.ibm.com")
///     .InstanceID("8f2c1d6e-0000-4000-8000-000000000000")
///     .AuthRef("/run/secrets/key-protect-api-key")
///     .KeyRef("planning-root-key")
///     .Build();
/// var report = await tm1.Encryption.ValidateAsync(keyProtect);
/// if (report.IsValid != true)
/// {
///     throw new InvalidOperationException("Can't encrypt: " + string.Join("; ", report.Issues ?? []));
/// }
/// await tm1.Encryption.ConfigureAsync(keyProtect);
/// </code>
/// <para>Rotate the data encryption key:</para>
/// <code>
/// // A new data encryption key; TM1 re-encrypts the whole model with it
/// await tm1.Encryption.RotateDataEncryptionKeyAsync();
/// </code>
/// </summary>
public sealed class EncryptionService : Tm1Service
{
    private static readonly ODataPath.Alias Settings = new("@settings");

    public EncryptionService(Tm1Session session)
        : base(session)
    {
    }

    /// <summary><c>ValidateEncryptionAtRestSettings</c>: checks the provider settings would work now
    /// (the key service is reachable and allows it, and the key exists), changing nothing. Returns the
    /// report: whether they're valid, and if not, the issues; never secrets.</summary>
    [Tm1Endpoint("FunctionImport ValidateEncryptionAtRestSettings")]
    public Task<EncryptionAtRestValidationReport> ValidateAsync(EncryptionAtRestProviderSettingsPatch settings, CancellationToken cancellationToken = default)
    {
        var operation = Tm1Operations.ValidateEncryptionAtRestSettings;
        var json = JsonSerializer.Serialize<Tm1Patch>(Require(settings, nameof(settings)), Tm1Json.Options);
        var path = ODataPath.FunctionImport(ImportName(operation), new Dictionary<string, object?> { ["Settings"] = Settings });
        var request = Tm1Request.Get<EncryptionAtRestValidationReport>(path, ODataQuery.None().Alias(Settings, json)).Requires(operation.Capability());
        return Session.ExecuteAsync(request, cancellationToken);
    }

    /// <summary><c>ConfigureEncryptionAtRest</c>: validates the provider settings, then applies them; a
    /// server not yet encrypted is encrypted.</summary>
    [Tm1Endpoint("ActionImport ConfigureEncryptionAtRest")]
    public Task ConfigureAsync(EncryptionAtRestProviderSettingsPatch settings, CancellationToken cancellationToken = default) =>
        Call(Tm1Operations.ConfigureEncryptionAtRest, new Dictionary<string, object?> { ["Settings"] = Require(settings, nameof(settings)) }, cancellationToken);

    /// <summary><c>DisableEncryptionAtRest</c>: decrypts the model and stops encrypting it.</summary>
    [Tm1Endpoint("ActionImport DisableEncryptionAtRest")]
    public Task DisableAsync(CancellationToken cancellationToken = default) =>
        Call(Tm1Operations.DisableEncryptionAtRest, new Dictionary<string, object?>(), cancellationToken);

    /// <summary><c>RotateDataEncryptionKey</c>: makes a new data encryption key and re-encrypts the
    /// whole model with it.</summary>
    [Tm1Endpoint("ActionImport RotateDataEncryptionKey")]
    public Task RotateDataEncryptionKeyAsync(CancellationToken cancellationToken = default) =>
        Call(Tm1Operations.RotateDataEncryptionKey, new Dictionary<string, object?>(), cancellationToken);

    /// <summary><c>RotateDataModelKey</c>: rotates the model key.</summary>
    [Tm1Endpoint("ActionImport RotateDataModelKey")]
    public Task RotateDataModelKeyAsync(CancellationToken cancellationToken = default) =>
        Call(Tm1Operations.RotateDataModelKey, new Dictionary<string, object?>(), cancellationToken);

    /// <summary><c>EncryptDataFile</c> (schema 11.3.0): encrypts one file, such as an exported cube.
    /// <paramref name="source"/> is relative to the data directory or absolute;
    /// <paramref name="type"/> says what the file is: a model object, a transaction log or an audit log.</summary>
    [Tm1Endpoint("ActionImport EncryptDataFile")]
    public Task EncryptFileAsync(string source, string destination, EncryptionFileType type, CancellationToken cancellationToken = default) =>
        Call(Tm1Operations.EncryptDataFile, File(source, destination, type), cancellationToken);

    /// <summary><c>DecryptDataFile</c> (schema 11.3.0): decrypts one file.</summary>
    [Tm1Endpoint("ActionImport DecryptDataFile")]
    public Task DecryptFileAsync(string source, string destination, EncryptionFileType type, CancellationToken cancellationToken = default) =>
        Call(Tm1Operations.DecryptDataFile, File(source, destination, type), cancellationToken);

    /// <summary><c>EncryptDataModel</c> (schema 11.3.0): encrypts the model, then shuts the server down
    /// after <paramref name="timeUntilShutdown"/>, or TM1's default if null. Completes when it's
    /// started. Deprecated in TM1 schema 11.8.35: use <see cref="ConfigureAsync"/>.</summary>
    [Obsolete("Deprecated in TM1 schema 11.8.35: use ConfigureAsync")]
    [Tm1Endpoint("ActionImport EncryptDataModel")]
    public Task EncryptModelAsync(TimeSpan? timeUntilShutdown = null, CancellationToken cancellationToken = default) =>
        Call(Tm1Operations.EncryptDataModel, Shutdown(timeUntilShutdown), cancellationToken);

    /// <summary><c>DecryptDataModel</c> (schema 11.3.0): decrypts the model, then shuts the server down
    /// after <paramref name="timeUntilShutdown"/>, or TM1's default if null. Completes when it's
    /// started. Deprecated in TM1 schema 11.8.35: use <see cref="DisableAsync"/>.</summary>
    [Obsolete("Deprecated in TM1 schema 11.8.35: use DisableAsync")]
    [Tm1Endpoint("ActionImport DecryptDataModel")]
    public Task DecryptModelAsync(TimeSpan? timeUntilShutdown = null, CancellationToken cancellationToken = default) =>
        Call(Tm1Operations.DecryptDataModel, Shutdown(timeUntilShutdown), cancellationToken);

    private static Dictionary<string, object?> File(string source, string destination, EncryptionFileType type) => new()
    {
        ["SourcePath"] = Require(source, nameof(source)),
        ["DestinationPath"] = Require(destination, nameof(destination)),
        ["FileType"] = Tm1EnumConverter.Describe(typeof(EncryptionFileType))!.Name(type),
    };

    private static Dictionary<string, object?> Shutdown(TimeSpan? timeUntilShutdown) =>
        timeUntilShutdown is { } t ? new() { ["TimeUntilShutdown"] = XmlConvert.ToString(t) } : new();

    private Task Call(Tm1Operation operation, Dictionary<string, object?> parameters, CancellationToken cancellationToken) =>
        Session.ExecuteAsync(Tm1Request.Post(ODataPath.Of(ImportName(operation)), parameters).Requires(operation.Capability()), cancellationToken);

    private static string ImportName(Tm1Operation operation) => operation.ImportName ?? throw new InvalidOperationException(operation.Name);
}
