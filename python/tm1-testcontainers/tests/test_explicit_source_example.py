# SPDX-License-Identifier: Apache-2.0

"""Java's ExplicitSourceExampleTest: choosing where TM1 comes from in code, instead of with
settings. Each test runs only where that kind of source is available, read from the same
environment variables discover() uses, so they skip where it isn't set up."""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from skipcheck_tm1_testcontainers import Tm1ServerContainer, Tm1Source

pytestmark = pytest.mark.requires_tm1


def enabled_if_set(name: str) -> pytest.MarkDecorator:
    """Java's @EnabledIfEnvironmentVariable(named = name, matches = ".+")."""
    return pytest.mark.skipif(not os.environ.get(name), reason=f"Environment variable {name} isn't set")


def assert_starts(container: Tm1ServerContainer) -> None:
    with container:
        assert container.tm1_version is not None


@enabled_if_set(Tm1Source.HOME_DIR)
def test_from_an_installed_folder() -> None:
    # The tm1_64 folder of a Linux TM1 Server install. Fastest to start: nothing to install.
    source = Tm1Source.InstallDir(Path(os.environ[Tm1Source.HOME_DIR]))
    assert_starts(Tm1ServerContainer(source))


@enabled_if_set(Tm1Source.BUNDLE_DIR)
def test_from_ibms_bundle_file() -> None:
    # IBM's download, as is. Installed on first use into a shared cache volume. Pass a file name
    # as the second argument to pick one of several bundles.
    source = Tm1Source.Bundle(Path(os.environ[Tm1Source.BUNDLE_DIR]), None)
    assert_starts(Tm1ServerContainer(source))


@enabled_if_set(Tm1Source.BUNDLE_URL)
def test_from_a_download_link() -> None:
    # A pre-signed S3/R2/GCS link, or any URL curl can fetch. The SHA-256 is optional but
    # recommended. The URL's query string (where pre-signed credentials live) is never logged.
    source = Tm1Source.BundleUrl(os.environ[Tm1Source.BUNDLE_URL], os.environ.get(Tm1Source.BUNDLE_SHA256))
    assert_starts(Tm1ServerContainer(source))


@enabled_if_set(Tm1Source.INSTALL_VOLUME)
def test_from_a_docker_volume() -> None:
    # A volume that already holds an install, such as the one the tm1-server compose setup fills
    # (tm1_tm1-install), so tests reuse the TM1 you already run.
    source = Tm1Source.InstallVolume(os.environ[Tm1Source.INSTALL_VOLUME])
    assert_starts(Tm1ServerContainer(source))


@enabled_if_set(Tm1Source.PRIVATE_IMAGE)
def test_from_a_private_image_with_tm1_inside() -> None:
    # Built from docker/tm1-server/examples/private-image and pushed to a PRIVATE registry (it
    # contains IBM software). Fastest for CI: nothing to mount or install.
    source = Tm1Source.PrivateImage(os.environ[Tm1Source.PRIVATE_IMAGE])
    assert_starts(Tm1ServerContainer(source))
