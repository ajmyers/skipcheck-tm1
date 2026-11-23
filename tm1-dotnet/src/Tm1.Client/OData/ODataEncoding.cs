using System.Text;

namespace AjMyers.Tm1.Client.OData;

/// <summary>Percent-encoding, as the Java client's <c>ODataEncoding</c>: only unreserved characters
/// stay, plus what each place allows.</summary>
public static class ODataEncoding
{
    private const string Unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    /// <summary>For a path segment: only unreserved characters and <c>'</c> stay.</summary>
    public static string PathValue(string text) => Encode(text, Unreserved + "'");

    /// <summary>For a query option's value, keeping OData's own punctuation.</summary>
    public static string QueryValue(string text) => Encode(text, Unreserved + "$(),;':@/!*");

    internal static string LinkSegment(string segment) => Encode(segment, Unreserved + "'!()");

    private static string Encode(string text, string safe)
    {
        var sb = new StringBuilder(text.Length + 16);
        foreach (var b in Encoding.UTF8.GetBytes(text))
        {
            if (b < 128 && safe.Contains((char)b))
            {
                sb.Append((char)b);
            }
            else
            {
                sb.Append('%').Append(b.ToString("X2"));
            }
        }
        return sb.ToString();
    }
}
