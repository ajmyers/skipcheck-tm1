"""Examples for Tm1Source, quoted in the guide."""

from __future__ import annotations

from pathlib import Path

from skipcheck_tm1_testcontainers import Tm1ServerContainer, Tm1Source


def discover() -> Tm1ServerContainer:
    # @start region = discover
    # From TM1_HOME_DIR, TM1_BUNDLE_DIR and the like; None if none is set
    source = Tm1Source.discover()
    if source is None:
        raise RuntimeError("Set TM1_HOME_DIR or TM1_BUNDLE_DIR")
    return Tm1ServerContainer(source)
    # @end


def explicit() -> list[Tm1Source]:
    # @start region = explicit
    # An installed tm1_64 folder, mounted read-only: nothing to install
    installed = Tm1Source.InstallDir(Path("/opt/ibm/tm1_64"))
    # IBM's bundle, installed once into a shared cache volume; with no file named, the newest
    bundle = Tm1Source.Bundle(Path("/downloads/ibm"))
    # A download link, such as a pre-signed S3 URL, and its SHA-256 if there is one
    link = Tm1Source.BundleUrl(
        "https://downloads.example.com/tm1server.tar.gz",
        "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    )
    # An image with TM1 inside, from a private registry: it contains IBM software
    image = Tm1Source.PrivateImage("registry.example.com/tm1-server:11.8.03500.6")
    return [installed, bundle, link, image]
    # @end


def own_settings(test_settings: dict[str, str]) -> Tm1Source:
    # @start region = own-settings
    # The same settings, read from the test setup's own configuration
    source = Tm1Source.discover(test_settings.get)
    if source is None:
        raise RuntimeError("The test settings name no TM1")
    return source
    # @end
