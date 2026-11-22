using System;
using System.Collections.Generic;
using System.IO;

namespace AjMyers.Tm1.Testcontainers.Tests;

/// <summary>A folder for one test, removed afterwards, as JUnit's <c>@TempDir</c>.</summary>
public abstract class TempDir : IDisposable
{
    protected TempDir()
    {
        Temp = Directory.CreateTempSubdirectory("tm1-tc-").FullName;
    }

    protected string Temp { get; }

    public void Dispose()
    {
        Directory.Delete(Temp, true);
        GC.SuppressFinalize(this);
    }

    protected string Touch(string name)
    {
        var path = Path.Combine(Temp, name);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, []);
        return path;
    }

    protected static Func<string, string?> Settings(Dictionary<string, string> settings) => name => settings.GetValueOrDefault(name);

    protected static Dictionary<string, string> Without(Dictionary<string, string> settings, params string[] names)
    {
        var copy = new Dictionary<string, string>(settings);
        foreach (var name in names)
        {
            copy.Remove(name);
        }
        return copy;
    }
}

/// <summary>Where the repository's files are.</summary>
public static class Repo
{
    public static string Root { get; } = Find();

    public static string JavaSources => Path.Combine(Root, "tm1-testcontainers", "src", "main", "java", "net", "ajmyers", "tm1", "testcontainers");

    private static string Find()
    {
        var dir = AppContext.BaseDirectory;
        while (dir is not null && !Directory.Exists(Path.Combine(dir, "tm1-testcontainers")))
        {
            dir = Path.GetDirectoryName(dir);
        }
        return dir ?? throw new InvalidOperationException("Can't find the repository root (tm1-testcontainers)");
    }
}
