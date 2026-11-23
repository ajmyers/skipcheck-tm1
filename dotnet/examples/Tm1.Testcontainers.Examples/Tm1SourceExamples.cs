// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using Skipcheck.Tm1.Testcontainers;

namespace Examples;

/// <summary>Examples for <c>Tm1Source</c>, shown in the guide.</summary>
public static class Tm1SourceExamples
{
    public static Tm1ServerContainer Discover()
    {
        // @start region = discover
        // From TM1_HOME_DIR, TM1_BUNDLE_DIR and the like; null if none is set
        var source = Tm1Source.Discover() ?? throw new InvalidOperationException("Set TM1_HOME_DIR or TM1_BUNDLE_DIR");
        return new Tm1ServerContainer(source);
        // @end
    }

    public static IReadOnlyList<Tm1Source> Explicit()
    {
        // @start region = explicit
        // An installed tm1_64 folder, mounted read-only: nothing to install
        Tm1Source installed = new Tm1Source.InstallDir("/opt/ibm/tm1_64");
        // IBM's bundle, installed once into a shared cache volume; null picks the newest
        Tm1Source bundle = new Tm1Source.Bundle("/downloads/ibm", null);
        // A download link, such as a pre-signed S3 URL, and its SHA-256 or null
        Tm1Source link = new Tm1Source.BundleUrl(
            new Uri("https://downloads.example.com/tm1server.tar.gz"),
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        );
        // An image with TM1 inside, from a private registry: it contains IBM software
        Tm1Source image = new Tm1Source.PrivateImage("registry.example.com/tm1-server:11.8.03500.6");
        return [installed, bundle, link, image];
        // @end
    }

    public static Tm1Source OwnSettings(IReadOnlyDictionary<string, string> testSettings)
    {
        // @start region = own-settings
        // The same settings, read from the test setup's own configuration
        return Tm1Source.Discover(testSettings.GetValueOrDefault) ?? throw new InvalidOperationException("No TM1 source in the test settings");
        // @end
    }
}
