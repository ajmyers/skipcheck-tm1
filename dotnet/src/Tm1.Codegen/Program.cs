// SPDX-License-Identifier: Apache-2.0

// Generates the .NET client's model from the language-neutral API spec (spec/tm1-api.json),
// as tm1-codegen does for Java and tm1_pygen for Python: run by the build (Tm1.Client.csproj's
// GenerateTm1Model target) into obj/, never committed.
//
//   dotnet Tm1.Codegen.dll <spec.json> <output directory>

using System;
using System.IO;
using System.Text.Json;

namespace Skipcheck.Tm1.Codegen;

public static class Program
{
    public const int SupportedSpecVersion = 2;

    public static int Main(string[] args)
    {
        if (args.Length != 2)
        {
            Console.Error.WriteLine("Usage: Tm1.Codegen <spec.json> <output directory>");
            return 2;
        }
        using var doc = JsonDocument.Parse(File.ReadAllBytes(args[0]));
        var spec = new Spec(doc.RootElement);
        if (spec.Version != SupportedSpecVersion)
        {
            Console.Error.WriteLine($"{args[0]}: spec version {spec.Version}; this generator reads {SupportedSpecVersion}");
            return 1;
        }
        Directory.CreateDirectory(args[1]);
        foreach (var old in Directory.GetFiles(args[1], "*.g.cs"))
        {
            File.Delete(old);
        }
        Write(args[1], "Model.g.cs", new ModelEmitter(spec).Emit());
        Write(args[1], "Patches.g.cs", new PatchEmitter(spec).Emit());
        Write(args[1], "Operations.g.cs", new OperationEmitter(spec).Emit());
        return 0;
    }

    private static void Write(string directory, string name, string text)
    {
        File.WriteAllText(Path.Combine(directory, name), text.Replace("\r\n", "\n"));
    }
}
