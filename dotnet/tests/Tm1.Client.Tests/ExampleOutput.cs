// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace Skipcheck.Tm1.Client.Tests;

/// <summary>What an example printed, recorded for the docs: the walkthroughs show each step's output under its
/// code (api-site/README.md, "Walkthroughs, and what the examples print"). A live test runs an example's steps through
/// <see cref="RecordAsync"/>, and <see cref="Dispose"/> writes
/// <c>api-site/example-output/&lt;project&gt;/dotnet/&lt;Example&gt;.json</c>, with each region's output and a hash of
/// its code. The site's build fails when a region that prints has no recording, when its code has changed since
/// (the hash differs), or when the languages printed different things. A port of the Java client's
/// ExampleOutput: it hashes a region and splits what was printed as Java does (any line ending, as Java's
/// <c>\R</c>, and Java's whitespace, which isn't quite .NET's).
/// <para>It captures the console (<see cref="Console.SetOut"/>), which is the whole process's: that works because
/// this assembly's tests never run in parallel (Live/Sequential.cs).</para></summary>
public sealed partial class ExampleOutput : IDisposable
{
    private readonly string _project;
    private readonly string _example;
    private readonly string _source;
    private readonly SortedDictionary<string, (string Source, List<string> Output)> _regions = new(StringComparer.Ordinal);
    private bool _failed;

    /// <param name="project">The project whose examples these are, such as <c>tm1-client</c>.</param>
    /// <param name="example">The examples class, such as <c>ConnectWalkthrough</c>.</param>
    public ExampleOutput(string project, string example)
    {
        _project = project;
        _example = example;
        _source = File.ReadAllText(Path.Combine(Repo.Root, "dotnet", "examples", ExamplesProject(project), example + ".cs"));
    }

    /// <summary>The examples project of a project: <c>tm1-client</c>'s is <c>Tm1.Client.Examples</c>.</summary>
    public static string ExamplesProject(string project) =>
        string.Join(".", project.Split('-').Select(part => char.ToUpperInvariant(part[0]) + part[1..])) + ".Examples";

    /// <summary>Runs a region's code, keeping what it prints to the console, which still gets it.</summary>
    public async Task RecordAsync(string region, Func<Task> step)
    {
        var hash = SourceHash(_source, region);
        var original = Console.Out;
        using var printed = new StringWriter(CultureInfo.InvariantCulture);
        Console.SetOut(printed);
        try
        {
            await step();
        }
        catch
        {
            _failed = true;
            throw;
        }
        finally
        {
            Console.SetOut(original);
        }
        var text = printed.ToString();
        original.Write(text);
        _regions[region] = (hash, Lines(text));
    }

    /// <summary>What a region printed, as <see cref="RecordAsync"/> keeps it.</summary>
    public IReadOnlyList<string> Output(string region) => _regions[region].Output;

    /// <summary>Writes the recording, replacing the one before, unless a step failed: then the one before stays.</summary>
    public void Dispose()
    {
        if (_failed)
        {
            return;
        }
        var regions = new JsonObject();
        foreach (var (name, (source, output)) in _regions)
        {
            regions[name] = new JsonObject { ["source"] = source, ["output"] = new JsonArray([.. output.Select(line => JsonValue.Create(line))]) };
        }
        var file = new JsonObject
        {
            ["example"] = _example,
            ["language"] = "dotnet",
            ["regions"] = regions,
        };
        var path = Path.Combine(Repo.Root, "api-site", "example-output", _project, "dotnet", _example + ".json");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        // Quotes and non-ASCII text as they are, as the other languages write them (this isn't HTML)
        var options = new JsonSerializerOptions { WriteIndented = true, Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };
        File.WriteAllText(path, file.ToJsonString(options) + "\n");
    }

    /// <summary>The hash of a region's code, as every language and the site's build work it out: the lines between
    /// its <c>@start region = &lt;name&gt;</c> and <c>@end</c>, each without the whitespace around it, blank ones
    /// left out, joined with newlines, as the SHA-256 of their UTF-8, in hexadecimal. Indentation doesn't count,
    /// so reformatting a file only changes the hash where a line's own text changes.</summary>
    /// <exception cref="ArgumentException">The file has no such region.</exception>
    public static string SourceHash(string source, string region)
    {
        var lines = new List<string>();
        var inside = false;
        var found = false;
        foreach (var line in LineBreak().Split(source))
        {
            if (Start().Match(line) is { Success: true } start)
            {
                inside = start.Groups[1].Value == region;
                found |= inside;
            }
            else if (inside && End().IsMatch(line))
            {
                inside = false;
            }
            else if (inside && !line.All(IsWhitespace))
            {
                lines.Add(StripTrailing(line[line.TakeWhile(IsWhitespace).Count()..]));
            }
        }
        if (!found)
        {
            throw new ArgumentException("no region " + region, nameof(region));
        }
        return Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("\n", lines))));
    }

    /// <summary>Printed text as lines, without their trailing whitespace and without the empty lines at the end.</summary>
    public static List<string> Lines(string text)
    {
        var lines = LineBreak().Split(text).Select(StripTrailing).ToList();
        while (lines.Count > 0 && lines[^1].Length == 0)
        {
            lines.RemoveAt(lines.Count - 1);
        }
        return lines;
    }

    // Java's Character.isWhitespace, which its strip and isBlank use: not the no-break spaces, which
    // char.IsWhiteSpace counts, nor U+0085; but U+001C to U+001F, which it doesn't
    private static bool IsWhitespace(char c) =>
        c is '\t' or '\n' or '\x000B' or '\f' or '\r' or (>= '\x001C' and <= '\x001F')
        || (
            c is not ('\x00A0' or '\x2007' or '\x202F')
            && char.GetUnicodeCategory(c) is UnicodeCategory.SpaceSeparator or UnicodeCategory.LineSeparator or UnicodeCategory.ParagraphSeparator
        );

    private static string StripTrailing(string line)
    {
        var end = line.Length;
        while (end > 0 && IsWhitespace(line[end - 1]))
        {
            end--;
        }
        return line[..end];
    }

    // Java's \R: any line ending
    [GeneratedRegex("\r\n|[\n\x000B\f\r\x0085\x2028\x2029]")]
    private static partial Regex LineBreak();

    // Java's @start\s+region\s*=\s*"?([\w-]+)"? and @end\b, whose \s and \w are ASCII only
    [GeneratedRegex("@start[ \t\n\x000B\f\r]+region[ \t\n\x000B\f\r]*=[ \t\n\x000B\f\r]*\"?([A-Za-z0-9_-]+)\"?")]
    private static partial Regex Start();

    [GeneratedRegex("@end(?![A-Za-z0-9_])")]
    private static partial Regex End();
}
