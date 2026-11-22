using System;
using System.Collections.Generic;

namespace AjMyers.Tm1.Client.Services;

/// <summary>How TM1 signs in to the Git remote: nothing (a public or <c>git://</c> remote), a user
/// name and password or token (HTTPS), or an SSH key pair. TM1 doesn't keep them, so every call that
/// reaches the remote takes them again. <see cref="ToString"/> leaves the secrets out.</summary>
/// <param name="Username">The user name, for HTTPS.</param>
/// <param name="PasswordOrToken">The password or access token, for HTTPS (Java's <c>password()</c>;
/// <see cref="Password"/> is the factory).</param>
/// <param name="PublicKey">The SSH public key.</param>
/// <param name="PrivateKey">The SSH private key.</param>
/// <param name="Passphrase">The private key's passphrase.</param>
public sealed record GitCredentials(string? Username, string? PasswordOrToken, string? PublicKey, string? PrivateKey, string? Passphrase)
{
    /// <summary>No credentials.</summary>
    public static GitCredentials None() => new(null, null, null, null, null);

    /// <summary>A user name and password, or access token, for an HTTPS remote.</summary>
    public static GitCredentials Password(string username, string password) =>
        new(username ?? throw new ArgumentNullException(nameof(username)), password ?? throw new ArgumentNullException(nameof(password)), null, null, null);

    /// <summary>An SSH key pair, for an SSH remote, with its passphrase, or null for none.</summary>
    public static GitCredentials SshKey(string publicKey, string privateKey, string? passphrase = null) =>
        new(null, null, publicKey ?? throw new ArgumentNullException(nameof(publicKey)), privateKey ?? throw new ArgumentNullException(nameof(privateKey)), passphrase);

    internal void AddTo(IDictionary<string, object?> parameters)
    {
        Put(parameters, "Username", Username);
        Put(parameters, "Password", PasswordOrToken);
        Put(parameters, "PublicKey", PublicKey);
        Put(parameters, "PrivateKey", PrivateKey);
        Put(parameters, "Passphrase", Passphrase);
    }

    internal static void Put(IDictionary<string, object?> parameters, string name, object? value)
    {
        if (value is not null)
        {
            parameters[name] = value;
        }
    }

    /// <summary>Which credentials these are, without the secrets.</summary>
    public override string ToString() =>
        $"GitCredentials[username={Username}, password={(PasswordOrToken is null ? "none" : "***")}, sshKey={(PrivateKey is null ? "none" : "***")}]";
}

/// <summary>What a push's commit says, and who it's by: its message, and the author's name and email address.</summary>
public sealed record GitCommitDetails
{
    public GitCommitDetails(string message, string author, string email)
    {
        Message = message ?? throw new ArgumentNullException(nameof(message));
        Author = author ?? throw new ArgumentNullException(nameof(author));
        Email = email ?? throw new ArgumentNullException(nameof(email));
    }

    /// <summary>The commit message.</summary>
    public string Message { get; }

    /// <summary>The author's name.</summary>
    public string Author { get; }

    /// <summary>The author's email address.</summary>
    public string Email { get; }
}
