// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Tests.Runtime;

/// <summary>The Java client's <c>ODataCollectionDecoderTest</c>, through the session: this client reads
/// each page of a collection whole, so the Java tests of its buffers (how bytes are split, demand,
/// release on cancel) have no counterpart here (coverage/unit-excluded.txt). What a collection
/// response may hold is the same.</summary>
public class ODataCollectionDecoderTests
{
    public sealed record Element(string Name, string Type);

    private const string Body = """
        {"@odata.context":"$metadata#Elements","@odata.count":3,"value":[
        {"@odata.etag":"W/\"1\"","Name":"Q1","Type":"Numeric"},
        {"Name":"Q'2 {x}","Type":"Numeric","Extra":{"nested":[1,[2,{"a":null}]]}},
        {"Name":"Total","Type":"Consolidated"}],"@odata.nextLink":"Elements?$skiptoken=3"}
        """;

    private readonly ScriptedTm1 _tm1 = new();

    private IAsyncEnumerable<T> Stream<T>(string body)
    {
        _tm1.On("GET Elements", _ => ScriptedTm1.Json(200, body));
        return _tm1.Session().StreamAsync(Tm1Request.List<T>(ODataPath.Of("Elements")));
    }

    [Fact]
    public async Task DecodesItemsAndControlInformation()
    {
        _tm1.On("GET Elements?$skiptoken=3", _ => ScriptedTm1.Json(200, """{"@odata.count":3,"value":[]}"""));
        var items = await Stream<Element>(Body).ToListAsync();
        Assert.Equal(["Q1", "Q'2 {x}", "Total"], items.Select(e => e.Name));
        Assert.Equal(["Numeric", "Numeric", "Consolidated"], items.Select(e => e.Type));
        // The control information around the items is read, not taken for items: the next link is followed
        Assert.Single(_tm1.Requests("GET Elements?$skiptoken=3"));
    }

    [Fact]
    public async Task DecodesCollectionsOfPrimitives()
    {
        // Java's items() skips a null, as Reactor can't carry one, and its decode() shows it; an
        // IAsyncEnumerable carries it
        Assert.Equal(["a", null, "b"], await Stream<string?>("""{"value":["a",null,"b"]}""").ToListAsync());
    }

    [Fact]
    public async Task DecodesAnEmptyCollection() => Assert.Empty(await Stream<Element>("""{"@odata.context":"x","value":[]}""").ToListAsync());
}
