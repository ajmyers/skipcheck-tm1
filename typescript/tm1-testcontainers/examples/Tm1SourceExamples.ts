// SPDX-License-Identifier: Apache-2.0

import { Tm1ServerContainer, Tm1Source } from "@skipcheck/tm1-testcontainers";

// Examples for Tm1Source, quoted in its documentation.

export function discover(): Tm1ServerContainer {
  // @start region = discover
  // From TM1_HOME_DIR, TM1_BUNDLE_DIR and the like; undefined if none is set
  const source = Tm1Source.discover();
  if (!source) throw new Error("Set TM1_HOME_DIR or TM1_BUNDLE_DIR");
  return new Tm1ServerContainer(source);
  // @end
}

export function explicit(): Tm1Source[] {
  // @start region = explicit
  // An installed tm1_64 folder, mounted read-only: nothing to install
  const installed = new Tm1Source.InstallDir("/opt/ibm/tm1_64");
  // IBM's bundle, installed once into a shared cache volume; with no file named, the newest
  const bundle = new Tm1Source.Bundle("/downloads/ibm");
  // A download link, such as a pre-signed S3 URL, and optionally its SHA-256
  const link = new Tm1Source.BundleUrl("https://downloads.example.com/tm1server.tar.gz", "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
  // An image with TM1 inside, from a private registry: it contains IBM software
  const image = new Tm1Source.PrivateImage("registry.example.com/tm1-server:11.8.03500.6");
  return [installed, bundle, link, image];
  // @end
}

export function ownSettings(testSettings: Record<string, string | undefined>): Tm1Source {
  // @start region = own-settings
  // The same settings, read from the test setup's own configuration
  const source = Tm1Source.discover((name) => testSettings[name]);
  if (!source) throw new Error("No TM1 source in the test settings");
  return source;
  // @end
}
