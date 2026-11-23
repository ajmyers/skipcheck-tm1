using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>AttributeService and LocalizedAttributeService against a real TM1, on a dimension's objects: a
/// port of the Java client's AttributeServiceTm1Test.</summary>
public class AttributeServiceLiveTests
{
    private static string Label() => "Label " + Guid.NewGuid().ToString("N")[..4];

    private static AttributeDefinitionPatch StringAttribute(string name) => AttributeDefinitionPatch.Builder().Name(name).Type(AttributeType.String).Build();

    private static LocalizedAttributesPatch French(string attribute, string value) =>
        LocalizedAttributesPatch.Builder().LocaleID("fr").Attributes(AttributesPatch.Builder().Property(attribute, value).Build()).Build();

    private static async Task<string?> Value(Tm1Client tm1, AttributeOwner owner, string attribute)
    {
        var fr = await tm1.LocalizedAttributes.GetAsync(owner, "fr");
        return fr.Attributes?.DynamicProperties is { } p && p.TryGetValue(attribute, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
    }

    private static async Task<List<string>> Names(Tm1Client tm1, AttributeSet set) =>
        (await tm1.Attributes.ListAsync(set).ToListAsync()).Select(a => a.Name!).ToList();

    /// <summary>A dimension with North and South, and a public subset S.</summary>
    private static Task<LiveDimension.Made> Dimension() => MadeWithSubset();

    private static async Task<LiveDimension.Made> MadeWithSubset()
    {
        var made = await LiveDimension.Made.Of(n => LiveDimension.DimensionOf(n, [LiveDimension.Numeric("North"), LiveDimension.Numeric("South")]));
        var d = made.Name;
        await made.Tm1.Subsets.CreateAsync(d, d, SubsetScope.Public, SubsetPatch.Builder().Name("S").Expression($"{{[{d}].[{d}].[North]}}").Build());
        return made;
    }

    [Tm1Fact]
    public async Task CreatesListsReadsAndDeletesAttributeDefinitions()
    {
        await using var made = await Dimension();
        var (tm1, d) = (made.Tm1, made.Name);
        foreach (var set in new[] { AttributeSet.Dimensions(), AttributeSet.Hierarchies(d), AttributeSet.Elements(d, d), AttributeSet.Subsets(d, d) })
        {
            var attribute = "Code " + Guid.NewGuid().ToString("N")[..4];
            Assert.Equal(AttributeType.String, (await tm1.Attributes.CreateAsync(set, StringAttribute(attribute))).Type);
            try
            {
                Assert.Contains(attribute, await Names(tm1, set));
                Assert.Equal(attribute, (await tm1.Attributes.GetAsync(set, attribute)).Name);
            }
            finally
            {
                await tm1.Attributes.DeleteAsync(set, attribute);
            }
            Assert.DoesNotContain(attribute, await Names(tm1, set));
        }
    }

    [Tm1Fact]
    public async Task TranslatesAttributeValues()
    {
        await using var made = await Dimension();
        var (tm1, d) = (made.Tm1, made.Name);
        var cases = new (AttributeSet Set, AttributeOwner Owner, AttributeOwner Reader)[]
        {
            (AttributeSet.Dimensions(), AttributeOwner.Dimension(d), AttributeOwner.Dimension(d)),
            (AttributeSet.Hierarchies(d), AttributeOwner.Hierarchy(d, d), AttributeOwner.Hierarchy(d, d)),
            (AttributeSet.Elements(d, d), AttributeOwner.Element(d, d, "North"), AttributeOwner.Element(d, d, "North")),
            // A member's translations are its element's, and read only
            (AttributeSet.Elements(d, d), AttributeOwner.Element(d, d, "North"), AttributeOwner.Member(d, d, "North")),
            (AttributeSet.Subsets(d, d), AttributeOwner.Subset(d, d, SubsetScope.Public, "S"), AttributeOwner.Subset(d, d, SubsetScope.Public, "S")),
        };
        foreach (var (set, owner, reader) in cases)
        {
            var attribute = Label();
            await tm1.Attributes.CreateAsync(set, StringAttribute(attribute));
            var localized = tm1.LocalizedAttributes;
            try
            {
                await localized.CreateAsync(owner, French(attribute, "Nord"));
                Assert.Contains("fr", (await localized.ListAsync(reader).ToListAsync()).Select(t => t.LocaleID));
                Assert.Equal("Nord", await Value(tm1, reader, attribute));
                var changed = French(attribute, "Le Nord");
                var fr = owner.Path.Nav("LocalizedAttributes").Key("fr");
                await Assert.ThrowsAsync<Tm1BadRequestException>(() => localized.CreateAsync(owner, changed)); // a locale can't be created twice
                await Assert.ThrowsAsync<Tm1BadRequestException>(() => tm1.Session.ExecuteAsync(Tm1Request.Patch(fr, changed))); // nor patched
                await Assert.ThrowsAsync<Tm1BadRequestException>(() => tm1.Session.ExecuteAsync(Tm1Request.Delete(fr))); // nor deleted
                Assert.Equal("Nord", await Value(tm1, reader, attribute));
            }
            finally
            {
                await tm1.Attributes.DeleteAsync(set, attribute);
            }
        }
    }

    [Tm1Fact]
    public async Task NeedsTheAttributeDefinedAndRefusesWritesThroughAMember()
    {
        await using var made = await Dimension();
        var (tm1, d) = (made.Tm1, made.Name);
        var caption = LocalizedAttributesPatch.Builder().LocaleID("fr").Attributes(AttributesPatch.Builder().Caption("Nord").Build()).Build();
        // Caption isn't an attribute until it's defined
        await Assert.ThrowsAsync<Tm1BadRequestException>(() => tm1.LocalizedAttributes.CreateAsync(AttributeOwner.Element(d, d, "North"), caption));
        // POST isn't supported on a member's
        await Assert.ThrowsAsync<Tm1BadRequestException>(() =>
            tm1.LocalizedAttributes.CreateAsync(AttributeOwner.Member(d, d, "North"), LocalizedAttributesPatch.Builder().LocaleID("fr").Build())
        );
    }
}
