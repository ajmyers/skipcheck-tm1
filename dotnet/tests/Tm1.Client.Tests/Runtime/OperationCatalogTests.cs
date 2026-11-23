// SPDX-License-Identifier: Apache-2.0

using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client.Tests.Runtime;

/// <summary>The Java client's <c>OperationCatalogTest</c>: the generated operation catalog
/// (&lt;c&gt;Tm1Operations&lt;/c&gt;) matches the spec the coverage gate reads, and the snapshot.</summary>
public class OperationCatalogTests
{
    [Fact]
    public void HasOneDescriptorPerOperationEndpointOfTheCoverageGate()
    {
        var operations = CoverageGateTests.Endpoints().Where(e => Regex.IsMatch(e, "^(Action|Function|ActionImport|FunctionImport) ")).Order().ToList();
        var catalog = Tm1Operations.All.Select(op => op.Endpoint()).ToList();
        Assert.Equal(catalog.Count, catalog.Distinct().Count());
        Assert.Equal(operations, catalog.Order());
        Assert.Equal(105, catalog.Count);
    }

    [Fact]
    public void EveryCapabilityIsOneTheSnapshotHas()
    {
        var snapshot = Tm1Capabilities.Parse(File.ReadAllText(Repo.Snapshot));
        Assert.All(Tm1Operations.All, op => Assert.True(snapshot.Supports(op.Capability()), op.Endpoint()));
    }

    [Fact]
    public void CarriesRevisionsParametersAndReturnTypes()
    {
        Assert.Equal("11.8.3", Tm1Operations.ChoreActivate.DeprecatedIn);
        var mdx = Tm1Operations.ExecuteMdx;
        Assert.Equal(Tm1Operation.OperationKind.Action, mdx.Kind);
        Assert.Equal("ExecuteMDX", mdx.ImportName);
        Assert.Equal([new Tm1Operation.Parameter("MDX", "Edm.String", false)], mdx.Parameters);
        Assert.Equal("tm1.Cellset", mdx.ReturnType);
        Assert.Equal("10.2.2.1", mdx.AddedIn);
        Assert.Contains(Tm1Operations.All, op => op.Deprecated());
    }
}
