using System.Threading.Tasks;
using Skipcheck.Tm1.Client;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Services;

namespace Examples;

/// <summary>Examples for <c>LocalizedAttributeService</c>, shown in the guide.</summary>
public static class LocalizedAttributeServiceExamples
{
    public static async Task<LocalizedAttributes> Translate(Tm1Client tm1)
    {
        // @start region = translate
        // The French for an element's Long Name, an attribute its hierarchy has. A
        // locale's translations are created once: TM1 can't change them here.
        return await tm1.LocalizedAttributes.CreateAsync(
            AttributeOwner.Element("Region", "Region", "North"),
            LocalizedAttributesPatch.Builder().LocaleID("fr").Attributes(AttributesPatch.Builder().Property("Long Name", "Région Nord").Build()).Build()
        );
        // @end
    }

    public static async Task<string?> Read(Tm1Client tm1)
    {
        // @start region = read
        // Attributes other than Caption come back in DynamicProperties
        var french = await tm1.LocalizedAttributes.GetAsync(AttributeOwner.Element("Region", "Region", "North"), "fr");
        return french.Attributes?.DynamicProperties is { } values && values.TryGetValue("Long Name", out var name) ? name.GetString() : null;
        // @end
    }
}
