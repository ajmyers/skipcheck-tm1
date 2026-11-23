// SPDX-License-Identifier: Apache-2.0

using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Xunit;

namespace Skipcheck.Tm1.Testcontainers.Tests;

/// <summary>PawSession's reading of PAW's answers, without PAW.</summary>
public class PawSessionTests
{
    [Fact]
    public async Task ReadsABodyWhoseCharsetDotNetDoesntKnow()
    {
        var content = new ByteArrayContent(Encoding.UTF8.GetBytes("<p>Région</p>"));
        content.Headers.ContentType = MediaTypeHeaderValue.Parse("text/html; charset=no-such-charset");
        Assert.Equal("<p>Région</p>", await PawSession.ReadTextAsync(content, CancellationToken.None));
    }

    [Fact]
    public async Task ReadsABodyInTheCharsetItNames()
    {
        var content = new ByteArrayContent(Encoding.Latin1.GetBytes("Région"));
        content.Headers.ContentType = MediaTypeHeaderValue.Parse("text/plain; charset=iso-8859-1");
        Assert.Equal("Région", await PawSession.ReadTextAsync(content, CancellationToken.None));
    }
}
