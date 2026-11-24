// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Services;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>Attributes of processes and chores against a real TM1: a port of the Java client's
/// ProcessAttributeTm1Test.</summary>
public class ProcessAttributeLiveTests
{
    private static string Label() => "Label " + Guid.NewGuid().ToString("N")[..4];

    private static AttributeDefinitionCreate StringAttribute(string name) => AttributeDefinitionCreate.Builder().Name(name).Type(AttributeType.String).Build();

    private static LocalizedAttributesPatch French(string attribute, string value) =>
        LocalizedAttributesPatch.Builder().LocaleID("fr").Attributes(AttributesPatch.Builder().Property(attribute, value).Build()).Build();

    private static async Task<string?> Value(Tm1Client tm1, AttributeOwner owner, string attribute)
    {
        var fr = await tm1.LocalizedAttributes.GetAsync(owner, "fr");
        return fr.Attributes?.DynamicProperties is { } p && p.TryGetValue(attribute, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
    }

    private static async Task<List<string>> Names(Tm1Client tm1, AttributeSet set) =>
        (await tm1.Attributes.ListAsync(set).ToListAsync()).Select(a => a.Name!).ToList();

    [Tm1Fact]
    public async Task DefinesAndTranslatesAttributes()
    {
        await using var tm1 = Live.Client();
        var process = Live.Name();
        var chore = process + "-chore";
        await tm1.Processes.CreateAsync(ProcessCreate.Builder().Name(process).PrologProcedure("nX = 1;\n").Build());
        try
        {
            await tm1.Chores.CreateAsync(
                ChoreCreate
                    .Builder()
                    .Name(chore)
                    .StartTime(new DateTimeOffset(2026, 1, 1, 2, 0, 0, TimeSpan.Zero))
                    .DstSensitive(false)
                    .Active(false)
                    .ExecutionMode(ChoreExecutionMode.SingleCommit)
                    .Frequency(TimeSpan.FromDays(1))
                    .Tasks([ChoreTaskPatch.Builder().BindProcess(ODataPath.Of("Processes").Key(process)).Build()])
                    .Build()
            );
            try
            {
                foreach (
                    var (set, owner) in new[]
                    {
                        (AttributeSet.Processes(), AttributeOwner.Process(process)),
                        (AttributeSet.Chores(), AttributeOwner.Chore(chore)),
                    }
                )
                {
                    var attribute = Label();
                    await tm1.Attributes.CreateAsync(set, StringAttribute(attribute));
                    try
                    {
                        Assert.Contains(attribute, await Names(tm1, set));
                        await tm1.LocalizedAttributes.CreateAsync(owner, French(attribute, "Charger"));
                        Assert.Equal("Charger", await Value(tm1, owner, attribute));
                    }
                    finally
                    {
                        await tm1.Attributes.DeleteAsync(set, attribute);
                    }
                }
            }
            finally
            {
                await tm1.Chores.DeleteAsync(chore);
            }
        }
        finally
        {
            await tm1.Processes.DeleteAsync(process);
        }
    }
}
