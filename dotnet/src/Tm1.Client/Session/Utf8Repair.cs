// SPDX-License-Identifier: Apache-2.0

using System.Text;
using System.Text.Unicode;

namespace Skipcheck.Tm1.Client.Session;

/// <summary>Makes a response's bytes valid UTF-8 before they're read as JSON, as the Java client's
/// <c>Utf8Repair</c>: each byte that isn't part of a character becomes U+FFFD, the replacement
/// character. TM1 (11.8) writes some values as the bytes it holds: the transaction log gives a
/// password's hash that isn't UTF-8, and a JSON parser refuses the whole response for it.</summary>
internal static class Utf8Repair
{
    /// <summary>The body as valid UTF-8; the same array if it already was.</summary>
    public static byte[] Whole(byte[] body) => Utf8.IsValid(body) ? body : Encoding.UTF8.GetBytes(Encoding.UTF8.GetString(body));
}
