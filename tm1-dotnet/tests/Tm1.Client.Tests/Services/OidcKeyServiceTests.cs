using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace AjMyers.Tm1.Client.Tests.Services;

/// <summary>OidcKeyService against a fake TM1.</summary>
public class OidcKeyServiceTests
{
    private readonly FakeTm1 _tm1 = new();

    [Fact]
    public async Task ListsAndRemovesKeys()
    {
        _tm1.Respond = _ => FakeTm1.Collection("k1", "k2");
        var tm1 = _tm1.Client();
        Assert.Equal(["k1", "k2"], await tm1.OidcKeys.KeysAsync().ToListAsync());
        Assert.Equal("GET GetOIDCKeys()", $"{_tm1.Last.Method} {_tm1.Last.Target}");

        _tm1.Respond = _ => null;
        await tm1.OidcKeys.RemoveAsync(["k1", "k2"]);
        Assert.Equal(("POST", "RemoveOIDCKeyFromCache", """{"KeyId":["k1","k2"]}"""), (_tm1.Last.Method, _tm1.Last.Target, _tm1.Last.Text));
        await tm1.OidcKeys.RemoveAsync([]);
        Assert.Equal("""{"KeyId":[]}""", _tm1.Last.Text);
    }
}
