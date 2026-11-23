using System;

namespace AjMyers.Tm1.Client.Version;

/// <summary>How a server's address becomes its REST API's service root, as the Java client's
/// <c>Tm1Dialect</c>: <see cref="V11"/> turns <c>http://tm1:8010</c> into <c>http://tm1:8010/api/v1/</c>.</summary>
public abstract class Tm1Dialect
{
    public static readonly Tm1Dialect V11 = new Tm111();

    public abstract string Name();

    public abstract string ServiceRoot(string address);

    public override string ToString() => $"Tm1Dialect[{Name()}]";

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
}
