using System;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AjMyers.Tm1.Client.Auth;

/// <summary>How a session logs in: the <c>Authorization</c> header of its first request, as the
/// Java client's <c>Tm1Authenticator</c>. TM1 answers with a <c>TM1SessionId</c> cookie, which
/// later requests send instead.</summary>
public abstract class Tm1Authenticator
{
    /// <summary>The header to log in with; asked for at each login.</summary>
    public abstract ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default);

    /// <summary>Who logs in, without secrets, for logs and errors.</summary>
    public abstract string Describe();

    public override string ToString() => $"Tm1Authenticator[{Describe()}]";

    /// <summary>TM1's own security (modes 1 and 2).</summary>
    public static Tm1Authenticator Basic(string user, string? password) =>
        new Fixed("Basic " + Base64($"{user ?? throw new ArgumentNullException(nameof(user))}:{password ?? ""}"), "Basic " + user);

    /// <summary>CAM security (modes 4 and 5), with a user in a namespace.</summary>
    public static Tm1Authenticator CamNamespace(string user, string password, string @namespace)
    {
        ArgumentNullException.ThrowIfNull(user);
        ArgumentNullException.ThrowIfNull(password);
        ArgumentNullException.ThrowIfNull(@namespace);
        return new Fixed("CAMNamespace " + Base64($"{user}:{password}:{@namespace}"), $"CAMNamespace {@namespace}/{user}");
    }

    /// <summary>CAM security with a passport, fetched at each login (a passport expires).</summary>
    public static Tm1Authenticator CamPassport(Func<CancellationToken, ValueTask<string>> passport) =>
        new Passport(passport ?? throw new ArgumentNullException(nameof(passport)));

    private static string Base64(string text) => Convert.ToBase64String(Encoding.UTF8.GetBytes(text));

    private sealed class Fixed(string value, string description) : Tm1Authenticator
    {
        public override ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default) => ValueTask.FromResult(value);

        public override string Describe() => description;
    }

    private sealed class Passport(Func<CancellationToken, ValueTask<string>> passport) : Tm1Authenticator
    {
        public override async ValueTask<string> AuthorizationAsync(CancellationToken cancellationToken = default) =>
            "CAMPassport " + await passport(cancellationToken).ConfigureAwait(false);

        public override string Describe() => "CAMPassport";
    }
}
