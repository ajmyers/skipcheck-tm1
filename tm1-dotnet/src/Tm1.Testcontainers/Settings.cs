using System;
using System.IO;
using System.Linq;
using System.Reflection;

namespace AjMyers.Tm1.Testcontainers;

/// <summary>Where the settings come from: an <see cref="AppContext"/> switch (.NET's counterpart of a
/// Java system property: <c>RuntimeHostConfigurationOption</c> in a test project, or
/// <c>AppContext.SetData</c>), then the environment variable of the same name.</summary>
internal static class Settings
{
    public static string? Get(string name) => AppContext.GetData(name) as string ?? Environment.GetEnvironmentVariable(name);

    /// <summary>A setting's value, trimmed; null when unset or blank.</summary>
    public static string? Text(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();

    public static Func<string, string?> Trimmed(Func<string, string?> lookup) => name => Text(lookup(name));

    public static void RequireText(string? value, string name)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new ArgumentException(name + " must not be blank", name);
        }
    }

    /// <summary>An absolute, normalized path, as Java's <c>toAbsolutePath().normalize()</c>.</summary>
    public static string Absolute(string path) => Path.TrimEndingDirectorySeparator(Path.GetFullPath(path));

    /// <summary>A URL for logs: scheme, host, port and path, never the user info or the query (a
    /// signed URL's secret).</summary>
    public static string Safe(Uri url) => url.Scheme + "://" + url.Host + (url.IsDefaultPort ? "" : ":" + url.Port) + url.AbsolutePath;

    /// <summary>Whether <paramref name="dir"/> is a folder holding a file whose name passes <paramref name="match"/>.</summary>
    public static bool HasFile(string dir, Func<string, bool> match) =>
        Directory.Exists(dir) && Directory.EnumerateFiles(dir).Select(Path.GetFileName).Any(n => n is not null && match(n));

    /// <summary>This library's version, as Java reads tm1-testcontainers.properties.</summary>
    public static string LibraryVersion()
    {
        var version = typeof(Settings).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0.0.0-SNAPSHOT";
        var plus = version.IndexOf('+', StringComparison.Ordinal);
        return plus < 0 ? version : version[..plus];
    }
}
