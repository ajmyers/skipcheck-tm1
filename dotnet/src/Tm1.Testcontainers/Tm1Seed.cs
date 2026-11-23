namespace Skipcheck.Tm1.Testcontainers;

/// <summary>The database a <see cref="Tm1ServerContainer"/> starts with (<c>TM1_SEED</c>), and its login.</summary>
public enum Tm1Seed
{
    /// <summary>An empty database; <c>admin</c> with no password.</summary>
    Empty,

    /// <summary>IBM's SData sample; <c>admin</c> / <c>apple</c>.</summary>
    Sdata,
}

/// <summary>What the image calls each seed, and its login (package-private in Java).</summary>
internal static class Tm1SeedExtensions
{
    public static string Value(this Tm1Seed seed) => seed == Tm1Seed.Sdata ? "sdata" : "empty";

    public static string Username(this Tm1Seed seed) => "admin";

    public static string Password(this Tm1Seed seed) => seed == Tm1Seed.Sdata ? "apple" : "";
}
