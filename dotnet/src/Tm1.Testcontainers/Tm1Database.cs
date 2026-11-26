// SPDX-License-Identifier: Apache-2.0

using System;
using System.Runtime.CompilerServices;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Testcontainers;

/// <summary>A database of a <see cref="Tm1EngineContainer"/>: what a test of TM1 v12 uses, as the Java
/// <c>Tm1Database</c>. An engine holds instances, and an instance holds databases. The REST API of
/// one database is at <see cref="RestUrl"/>; a user logs in to its instance (<see cref="Login()"/>),
/// and the session serves every database there.</summary>
public sealed class Tm1Database : IEquatable<Tm1Database>
{
    internal Tm1Database(Tm1EngineContainer engine, string instance, string name)
    {
        Engine = engine;
        Instance = instance;
        Name = name;
    }

    /// <summary>The engine this database is in.</summary>
    public Tm1EngineContainer Engine { get; }

    /// <summary>The instance this database is in; <c>tm1</c> unless one was named.</summary>
    public string Instance { get; }

    /// <summary>The database's name.</summary>
    public string Name { get; }

    /// <summary>The REST API's service root as seen from the host, ending in <c>/</c>, e.g.
    /// <c>http://localhost:54321/tm1/api/v1/Databases('planning')/</c>.</summary>
    public string RestUrl => Engine.Url + Instance + "/api/v1/Databases('" + Name.Replace("'", "''", StringComparison.Ordinal) + "')/";

    /// <summary>The version of TM1 the engine runs. Only available once it has started.</summary>
    /// <exception cref="InvalidOperationException">The engine isn't started.</exception>
    public Tm1Version Version => Engine.Tm1Version;

    /// <summary>A login as <c>admin</c>, the administrator every database has.</summary>
    public Tm1Login Login() => Login("admin");

    /// <summary>A login as <paramref name="user"/>. TM1 makes a session for any name; a user who isn't
    /// in this database is then refused here (403).</summary>
    public Tm1Login Login(string user) => Engine.Login(Instance, user);

    /// <summary>A login through OAuth 2.0: as a user the engine's Keycloak knows the password of
    /// (<see cref="Tm1EngineContainer.WithLogin"/>). The login gets a token from Keycloak with that password and sends
    /// it to TM1, as a program acting for the user would. It can be made before the engine has started.</summary>
    /// <exception cref="InvalidOperationException">The engine has no <see cref="Tm1EngineContainer.WithOAuth2"/>.</exception>
    /// <exception cref="ArgumentException">The user has no password.</exception>
    public Tm1Login OAuth2Login(string user) => Engine.OAuth2Login(this, user);

    /// <inheritdoc/>
    public bool Equals(Tm1Database? other) =>
        other is not null
        && ReferenceEquals(Engine, other.Engine)
        && string.Equals(Instance, other.Instance, StringComparison.Ordinal)
        && string.Equals(Name, other.Name, StringComparison.Ordinal);

    /// <inheritdoc/>
    public override bool Equals(object? obj) => Equals(obj as Tm1Database);

    /// <inheritdoc/>
    public override int GetHashCode() => HashCode.Combine(RuntimeHelpers.GetHashCode(Engine), Instance, Name);

    /// <inheritdoc/>
    public override string ToString() => "Tm1Database[" + Instance + "/" + Name + "]";
}
