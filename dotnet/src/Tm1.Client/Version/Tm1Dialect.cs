// SPDX-License-Identifier: Apache-2.0

using System;
using System.Text.RegularExpressions;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Version;

/// <summary>How a server's address becomes its REST API's service root and how a session is closed,
/// as the Java client's <c>Tm1Dialect</c>: <see cref="V11"/> is TM1 11 on-premises and
/// <see cref="V12(string)"/> is TM1 12 (Planning Analytics Engine).</summary>
public abstract class Tm1Dialect
{
    private static readonly Regex InstanceSegment = new(@"^[A-Za-z0-9._~-]+$", RegexOptions.Compiled);
    private static readonly Regex DatabaseRootSuffix = new(@"/api/v1/Databases\([^/]+\)$", RegexOptions.Compiled);
    private static readonly Regex PercentHex = new(@"%[0-9a-fA-F]{2}", RegexOptions.Compiled);

    /// <summary>TM1 11 on-premises: the REST API is at
    /// <c>http(s)://host:port/api/v1/</c>.</summary>
    public static readonly Tm1Dialect V11 = new Tm111();

    /// <summary>TM1 12 (Planning Analytics Engine) for a database in the default <c>tm1</c>
    /// instance: <c>http(s)://host:port/tm1/api/v1/Databases('&lt;database&gt;')/</c>.</summary>
    /// <param name="database">The database name.</param>
    /// <exception cref="ArgumentException"><paramref name="database"/> is blank.</exception>
    public static Tm1Dialect V12(string database) => V12("tm1", database);

    /// <summary>TM1 12 (Planning Analytics Engine) for a database in a named instance:
    /// <c>http(s)://host:port/&lt;instance&gt;/api/v1/Databases('&lt;database&gt;')/</c>.</summary>
    /// <param name="instance">The instance name, a single URL path segment such as <c>tm1</c>.</param>
    /// <param name="database">The database name.</param>
    /// <exception cref="ArgumentException"><paramref name="instance"/> or <paramref name="database"/>
    /// is blank, or <paramref name="instance"/> isn't a single URL path segment.</exception>
    public static Tm1Dialect V12(string instance, string database) => new Tm112(instance, database);

    /// <summary>The deployment kind, for logs and messages, such as <c>TM1 11</c> or
    /// <c>TM1 12</c>.</summary>
    public abstract string Name();

    /// <summary>The REST API's service root, ending in <c>/</c>, for a server's address.</summary>
    /// <param name="address">Where the server is, such as <c>http://tm1:8010</c>; a full service
    /// root is accepted too.</param>
    /// <exception cref="ArgumentException"><paramref name="address"/> isn't an address of this
    /// kind.</exception>
    public abstract string ServiceRoot(string address);

    /// <summary>The URL a session posts to in order to close itself on TM1. TM1 11 closes at
    /// <c>&lt;serviceRoot&gt;ActiveSession/tm1.Close</c>; TM1 12 closes at the instance level
    /// <c>/&lt;instance&gt;/api/v1/ActiveSession/tm1s.Close</c>.</summary>
    /// <param name="serviceRoot">The database REST API's service root.</param>
    /// <exception cref="ArgumentException"><paramref name="serviceRoot"/> isn't a valid service
    /// root for this dialect.</exception>
    public virtual string CloseUrl(string serviceRoot)
    {
        var uri = ParseAbsoluteUri(serviceRoot, nameof(serviceRoot), "Not a service root: " + serviceRoot);
        var path = uri.AbsolutePath.TrimEnd('/');
        return $"{uri.Scheme}://{uri.Authority}{path}/ActiveSession/tm1.Close";
    }

    /// <inheritdoc/>
    public override string ToString() => $"Tm1Dialect[{Name()}]";

    private static Uri ParseAbsoluteUri(string raw, string paramName, string message)
    {
        ArgumentNullException.ThrowIfNull(raw, paramName);
        var trimmed = raw.Trim();
        if (
            trimmed.Length == 0
            || trimmed.Contains('?')
            || trimmed.Contains('#')
            || !Uri.TryCreate(trimmed, UriKind.Absolute, out var uri)
            || string.IsNullOrEmpty(uri.Host)
        )
        {
            throw new ArgumentException(message);
        }
        return uri;
    }

    private sealed class Tm111 : Tm1Dialect
    {
        public override string Name() => "TM1 11";

        public override string ServiceRoot(string address)
        {
            ArgumentNullException.ThrowIfNull(address);
            if (
                !Uri.TryCreate(address.Trim(), UriKind.Absolute, out var uri)
                || uri.Scheme is not ("http" or "https")
                || uri.Query.Length > 0
                || uri.Fragment.Length > 0
            )
            {
                throw new ArgumentException("A TM1 11 address is the server's scheme, host and HTTP port, such as http://tm1:8010: " + address);
            }
            var path = uri.AbsolutePath.TrimEnd('/');
            if (!path.EndsWith("/api/v1", StringComparison.Ordinal))
            {
                path += "/api/v1";
            }
            return $"{uri.Scheme}://{uri.Authority}{path}/";
        }
    }

    private sealed class Tm112 : Tm1Dialect
    {
        private readonly string _instance;
        private readonly string _database;
        private readonly string _instancePath;
        private readonly string _databaseSegment;

        public Tm112(string instance, string database)
        {
            ArgumentNullException.ThrowIfNull(instance);
            ArgumentNullException.ThrowIfNull(database);
            if (string.IsNullOrWhiteSpace(instance))
            {
                throw new ArgumentException("instance must not be blank", nameof(instance));
            }
            if (instance is "." or ".." || !InstanceSegment.IsMatch(instance))
            {
                throw new ArgumentException("A TM1 12 instance name must be a single URL path segment: " + instance, nameof(instance));
            }
            if (string.IsNullOrWhiteSpace(database))
            {
                throw new ArgumentException("database must not be blank", nameof(database));
            }
            _instance = instance;
            _database = database;
            _instancePath = $"/{instance}/api/v1";
            _databaseSegment = "/" + ODataPath.Of("Databases").Key(database);
        }

        public override string Name() => "TM1 12";

        public override string ServiceRoot(string address)
        {
            var uri = ParseHttpUri(
                address,
                nameof(address),
                "A TM1 12 address is the gateway's scheme, host and HTTP port, such as http://tm1:4444: " + address
            );
            var path = uri.AbsolutePath.TrimEnd('/');
            var fullSuffix = _instancePath + _databaseSegment;
            var normalizedPath = PercentHex.Replace(path, m => m.Value.ToUpperInvariant());
            if (normalizedPath.EndsWith(fullSuffix, StringComparison.Ordinal))
            {
                path = path[..^fullSuffix.Length] + fullSuffix;
            }
            else if (DatabaseRootSuffix.IsMatch(path))
            {
                throw new ArgumentException("The address already names another TM1 12 database: " + address);
            }
            else if (path.EndsWith(_instancePath, StringComparison.Ordinal))
            {
                path += _databaseSegment;
            }
            else
            {
                path += fullSuffix;
            }
            return $"{uri.Scheme}://{uri.Authority}{path}/";
        }

        public override string CloseUrl(string serviceRoot)
        {
            var uri = ParseHttpUri(serviceRoot, nameof(serviceRoot), "Not a TM1 12 service root: " + serviceRoot);
            var path = uri.AbsolutePath.TrimEnd('/');
            var lastSlash = path.LastIndexOf('/');
            if (lastSlash <= 0)
            {
                throw new ArgumentException("A TM1 12 database service root must include its instance API path: " + serviceRoot);
            }
            var instanceRoot = path[..lastSlash];
            var lastSegment = path[(lastSlash + 1)..];
            if (
                !instanceRoot.EndsWith("/api/v1", StringComparison.Ordinal)
                || !lastSegment.StartsWith("Databases(", StringComparison.Ordinal)
                || !lastSegment.EndsWith(')')
            )
            {
                throw new ArgumentException("A TM1 12 database service root must end with /api/v1/Databases('<db>')/: " + serviceRoot);
            }
            return $"{uri.Scheme}://{uri.Authority}{instanceRoot}/ActiveSession/tm1s.Close";
        }

        public override bool Equals(object? obj) =>
            obj is Tm112 other
            && string.Equals(_instance, other._instance, StringComparison.Ordinal)
            && string.Equals(_database, other._database, StringComparison.Ordinal);

        public override int GetHashCode() => HashCode.Combine(_instance, _database);

        public override string ToString() => $"Tm1Dialect[{Name()}, {_instance}/{_database}]";

        private static Uri ParseHttpUri(string raw, string paramName, string message)
        {
            var uri = ParseAbsoluteUri(raw, paramName, message);
            if (uri.Scheme is not ("http" or "https"))
            {
                throw new ArgumentException(message);
            }
            return uri;
        }
    }
}
