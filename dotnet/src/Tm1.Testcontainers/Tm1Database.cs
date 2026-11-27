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

    /// <summary>A pass-through login as the TM1 11 server's own user (<see cref="Tm1ServerContainer.Username"/>): the
    /// administrator of a server with its own users, or the first administrator of one in Cognos (CAM) security. The
    /// login sends TM1 v12 what that server takes, and TM1 v12 asks it who that is.</summary>
    /// <returns>The login.</returns>
    /// <exception cref="InvalidOperationException">The engine has no <see cref="Tm1EngineContainer.WithPassThrough(Tm1ServerContainer)"/>.</exception>
    public Tm1Login PassThroughLogin() => Engine.PassThroughLogin(this, null, null);

    /// <summary>A pass-through login as a user whose password the containers know: one of Cognos's test users when the
    /// TM1 11 server is in CAM security, and otherwise that server's own user only.</summary>
    /// <param name="user">The user's name on the TM1 11 server, or in Cognos.</param>
    /// <returns>The login.</returns>
    /// <exception cref="InvalidOperationException">The engine has no <see cref="Tm1EngineContainer.WithPassThrough(Tm1ServerContainer)"/>.</exception>
    /// <exception cref="ArgumentException">The user's password isn't known.</exception>
    public Tm1Login PassThroughLogin(string user)
    {
        ArgumentNullException.ThrowIfNull(user);
        return Engine.PassThroughLogin(this, user, null);
    }

    /// <summary>A pass-through login with a name and password of your own: a user you created on the TM1 11 server, or
    /// a wrong password to see refused. They go as <c>Basic</c>, or as <c>CAMNamespace</c> when the server is in CAM
    /// security.</summary>
    /// <param name="user">The user's name on the TM1 11 server, or in Cognos.</param>
    /// <param name="password">The password.</param>
    /// <returns>The login.</returns>
    /// <exception cref="InvalidOperationException">The engine has no <see cref="Tm1EngineContainer.WithPassThrough(Tm1ServerContainer)"/>.</exception>
    public Tm1Login PassThroughLogin(string user, string password)
    {
        ArgumentNullException.ThrowIfNull(user);
        ArgumentNullException.ThrowIfNull(password);
        return Engine.PassThroughLogin(this, user, password);
    }

    /// <summary>A pass-through login with an <c>Authorization</c> header of your own, which TM1 v12 passes on as it is:
    /// <c>"CAMPassport " + passport</c> from a Cognos logon, say.</summary>
    /// <param name="authorization">The header's value.</param>
    /// <returns>The login, with no user name or password.</returns>
    /// <exception cref="InvalidOperationException">The engine has no <see cref="Tm1EngineContainer.WithPassThrough(Tm1ServerContainer)"/>.</exception>
    public Tm1Login PassThroughLoginWith(string authorization) => Engine.PassThroughLoginWith(this, authorization);

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
