// SPDX-License-Identifier: Apache-2.0

using System;
using System.IO;
using Skipcheck.Tm1.Codegen;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>The generator (Tm1.Codegen), as the other clients' generator tests.</summary>
public class GeneratorTests
{
    [Fact]
    public void RefusesASpecVersionItDoesntRead()
    {
        var dir = Directory.CreateTempSubdirectory();
        try
        {
            // A spec of another version may lack what this one reads (builders): refused, not read
            var spec = Path.Combine(dir.FullName, "spec.json");
            File.WriteAllText(spec, """{"specVersion": 1, "types": []}""");
            var error = new StringWriter();
            var was = Console.Error;
            Console.SetError(error);
            try
            {
                Assert.Equal(1, Program.Main([spec, Path.Combine(dir.FullName, "out")]));
            }
            finally
            {
                Console.SetError(was);
            }
            Assert.Contains("spec version 1; this generator reads 2", error.ToString());
        }
        finally
        {
            dir.Delete(true);
        }
    }
}
