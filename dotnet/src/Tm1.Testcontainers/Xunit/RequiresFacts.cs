// SPDX-License-Identifier: Apache-2.0

using System;
using Xunit;

namespace Skipcheck.Tm1.Testcontainers.Xunit;

/// <summary>A test against TM1 in Docker, as Java's <c>@RequiresTm1</c>: skipped when Docker isn't
/// available or no <see cref="Tm1Source"/> is configured; run (and failing) when a source is set but
/// wrong. The test gets the shared server through <see cref="Tm1ServerFixture"/> (an
/// <c>IClassFixture</c>) or <see cref="Tm1ServerContainer.SharedAsync"/>.</summary>
/// <example><code>
/// public class CubeTests(Tm1ServerFixture tm1) : IClassFixture&lt;Tm1ServerFixture&gt;
/// {
///     [RequiresTm1Fact]
///     public void ListsCubes() { using var client = tm1.Client(); … }
/// }
/// </code></example>
[AttributeUsage(AttributeTargets.Method)]
public class RequiresTm1FactAttribute : FactAttribute
{
    /// <summary>Why a test is skipped without a source.</summary>
    public const string NoSourceMessage =
        "No TM1 source configured. Set one of "
        + Tm1Source.PrivateImageSetting
        + ", "
        + Tm1Source.HomeDir
        + ", "
        + Tm1Source.InstallVolumeSetting
        + ", "
        + Tm1Source.BundleDir
        + " or "
        + Tm1Source.BundleUrlSetting
        + " (as an environment variable or system property) to run tests against TM1.";

    /// <summary>Why a test is skipped without Docker.</summary>
    public const string NoDockerMessage = "Docker isn't available, so there's no TM1 to test against";

    /// <summary>Skips unless there's TM1 to test against.</summary>
    public RequiresTm1FactAttribute()
    {
        Skip = Tm1SkipReason();
    }

    /// <summary>Why a TM1 test is skipped, or null to run it (a wrong source runs it, to fail).</summary>
    public static string? Tm1SkipReason()
    {
        if (!DockerHost.Available())
        {
            return NoDockerMessage;
        }
        return Discovers(Tm1Source.Discover) ? null : NoSourceMessage;
    }

    /// <summary>Whether a source is set: true for one set wrong, so the test runs and fails on it.</summary>
    private protected static bool Discovers(Func<object?> discover)
    {
        try
        {
            return discover() is not null;
        }
        catch (ArgumentException)
        {
            return true;
        }
    }
}

/// <summary>A test against a TM1 v12 engine in Docker, as Java's <c>@RequiresTm1Engine</c>: skipped
/// when Docker isn't available or no <see cref="Tm1EngineSource"/> is configured; run (and failing)
/// when a source is set but wrong. For TM1 11, use <see cref="RequiresTm1FactAttribute"/>.
/// <para>One engine serves the whole test run (<see cref="Tm1EngineContainer.SharedAsync"/>), and
/// <b>each test class gets a database of its own</b> through
/// <see cref="Tm1DatabaseFixture{TTestClass}"/> (an <c>IClassFixture</c>), created before the class's
/// first test and dropped after its last, so classes don't see each other's objects. A database
/// takes about 10 seconds to create and 7 to drop. <see cref="Tm1EngineFixture"/> gives a class the
/// engine's one shared database instead, at no cost (Java's <c>shareDatabase = true</c>): its tests
/// then need uniquely named objects, and to delete them afterwards.</para>
/// <para>"A test class" is the unit each language's test framework shares set-up over: a class in
/// xUnit here, a JUnit class in Java, a module in pytest, and a file in vitest.</para>
/// <para>Java's <c>minVersion</c> has no counterpart: xUnit 2 can't skip a test once the engine is
/// started, so a test for a newer TM1 checks <see cref="Tm1Database.Version"/> and returns.</para></summary>
/// <example><code>
/// public class CubeTests(Tm1DatabaseFixture&lt;CubeTests&gt; tm1) : IClassFixture&lt;Tm1DatabaseFixture&lt;CubeTests&gt;&gt;
/// {
///     [RequiresTm1EngineFact]
///     public async Task ListsCubes() { var db = tm1.Database; … }
/// }
/// </code></example>
[AttributeUsage(AttributeTargets.Method)]
public class RequiresTm1EngineFactAttribute : FactAttribute
{
    /// <summary>Why a test is skipped without a source.</summary>
    public const string NoSourceMessage =
        "No TM1 v12 source configured. Set one of "
        + Tm1EngineSource.PrivateImageSetting
        + ", "
        + Tm1EngineSource.HomeDir
        + ", "
        + Tm1EngineSource.InstallVolumeSetting
        + ", "
        + Tm1EngineSource.BundleDir
        + " or "
        + Tm1EngineSource.BundleUrlSetting
        + ", or put a tm1-v*-standalone-linux.tgz in "
        + Tm1Source.BundleDir
        + " (as an environment variable or system property) to run tests against TM1 v12.";

    /// <summary>Skips unless there's a TM1 v12 engine to test against.</summary>
    public RequiresTm1EngineFactAttribute()
    {
        Skip = EngineSkipReason();
    }

    /// <summary>Why a TM1 v12 test is skipped, or null to run it (a wrong source runs it, to fail).</summary>
    public static string? EngineSkipReason()
    {
        if (!DockerHost.Available())
        {
            return RequiresTm1FactAttribute.NoDockerMessage;
        }
        try
        {
            return Tm1EngineSource.Discover() is null ? NoSourceMessage : null;
        }
        catch (ArgumentException)
        {
            return null;
        }
    }
}

/// <summary>A test against Cognos (and TM1), as Java's <c>@RequiresCognos</c>; get the source from
/// <see cref="CognosFixture"/>.</summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class RequiresCognosFactAttribute : RequiresTm1FactAttribute
{
    /// <summary>Why a test is skipped without a source.</summary>
    public new const string NoSourceMessage =
        "No Cognos source configured. Set one of "
        + CognosSource.PrivateImageSetting
        + ", "
        + CognosSource.InstallVolumeSetting
        + ", "
        + CognosSource.ZipDir
        + " or "
        + CognosSource.ZipUrlSetting
        + ", or put a casrv-*-linuxi38664h.zip in "
        + Tm1Source.BundleDir
        + " (as an environment variable or system property) to run tests against Cognos.";

    /// <summary>Skips unless there's TM1 and Cognos to test against.</summary>
    public RequiresCognosFactAttribute()
    {
        Skip ??= Discovers(CognosSource.Discover) ? null : NoSourceMessage;
    }
}

/// <summary>A test against TM1 Web (and TM1), as Java's <c>@RequiresTm1Web</c>; get the shared
/// TM1 Web from <see cref="Tm1WebFixture"/>.</summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class RequiresTm1WebFactAttribute : RequiresTm1FactAttribute
{
    /// <summary>Why a test is skipped without a source.</summary>
    public new const string NoSourceMessage =
        "No TM1 Web source configured. Set one of "
        + Tm1WebSource.PrivateImageSetting
        + ", "
        + Tm1WebSource.HomeDir
        + ", "
        + Tm1WebSource.InstallVolumeSetting
        + ", "
        + Tm1WebSource.BundleDir
        + " or "
        + Tm1WebSource.BundleUrlSetting
        + ", or put a tm1web bundle in "
        + Tm1Source.BundleDir
        + " (as an environment variable or system property) to run tests against TM1 Web.";

    /// <summary>Skips unless there's TM1 and TM1 Web to test against.</summary>
    public RequiresTm1WebFactAttribute()
    {
        Skip ??= Discovers(Tm1WebSource.Discover) ? null : NoSourceMessage;
    }
}

/// <summary>A test against PAW (and TM1), as Java's <c>@RequiresPaw</c>: also skipped until IBM's
/// license is accepted. Get the shared PAW from <see cref="PawFixture"/>.</summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class RequiresPawFactAttribute : RequiresTm1FactAttribute
{
    /// <summary>Why a test is skipped without a source.</summary>
    public new const string NoSourceMessage =
        "No Planning Analytics Workspace source configured. Set "
        + PawSource.ZipSetting
        + " to IBM's ipa_workspace_local_*.zip, or "
        + PawSource.BundleDir
        + " to its folder (as an environment variable or system property), to run tests against PAW.";

    /// <summary>Why a test is skipped until the license is accepted.</summary>
    public const string NoLicenseMessage =
        "IBM's Planning Analytics Workspace license hasn't been accepted. Read it (licenses/LA_en"
        + " in the PAW Local zip), then set "
        + PawContainer.AcceptLicenseSetting
        + "=true (as an environment variable or system property) to run tests against PAW.";

    /// <summary>Skips unless there's TM1 and PAW to test against.</summary>
    public RequiresPawFactAttribute()
    {
        Skip ??=
            !Discovers(PawSource.Discover) ? NoSourceMessage
            : !PawContainer.LicenseAcceptedBySetting() ? NoLicenseMessage
            : null;
    }
}
