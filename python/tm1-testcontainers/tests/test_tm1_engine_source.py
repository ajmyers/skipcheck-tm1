# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1EngineSourceTest."""

from __future__ import annotations

from pathlib import Path

import pytest

from skipcheck_tm1_testcontainers import Tm1EngineSource, Tm1Source

PACKAGE = "tm1-v12.6.7-standalone-linux.tgz"


def discover(settings: dict[str, str]) -> Tm1EngineSource | None:
    return Tm1EngineSource.discover(settings.get)


def fake_install(temp: Path) -> Path:
    install = temp / "tm1-engine"
    (install / "bin").mkdir(parents=True)
    (install / "bin" / "tm1sd.exe").touch()
    return install


def test_finds_nothing_when_nothing_is_set() -> None:
    assert discover({}) is None
    assert discover({Tm1EngineSource.HOME_DIR: "  "}) is None


def test_tries_sources_in_the_images_order(tmp_path: Path) -> None:
    install = fake_install(tmp_path)
    found = discover({Tm1EngineSource.PRIVATE_IMAGE: "registry.example.com/private/tm1-engine:12.6.7", Tm1EngineSource.HOME_DIR: str(install)})
    assert isinstance(found, Tm1EngineSource.PrivateImage)
    assert discover({Tm1EngineSource.HOME_DIR: str(install), Tm1EngineSource.INSTALL_VOLUME: "v"}) == Tm1EngineSource.InstallDir(install)
    assert discover({Tm1EngineSource.INSTALL_VOLUME: "v", Tm1EngineSource.BUNDLE_DIR: str(tmp_path)}) == Tm1EngineSource.InstallVolume("v")
    assert discover({Tm1EngineSource.BUNDLE_DIR: str(tmp_path), Tm1EngineSource.BUNDLE_URL: "https://example.com/p.tgz"}) == Tm1EngineSource.Bundle(
        tmp_path, None
    )
    assert discover({Tm1EngineSource.BUNDLE_URL: "https://example.com/p.tgz", Tm1EngineSource.BUNDLE_SHA256: "abc"}) == Tm1EngineSource.BundleUrl(
        "https://example.com/p.tgz", "abc"
    )


def test_uses_the_tm1_bundle_folder_when_it_holds_tm1_v12(tmp_path: Path) -> None:
    # TM1 11's folder without a v12 package in it: no source, so the tests are skipped
    (tmp_path / "tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz").touch()
    assert discover({Tm1Source.BUNDLE_DIR: str(tmp_path)}) is None

    (tmp_path / PACKAGE).touch()
    assert discover({Tm1Source.BUNDLE_DIR: str(tmp_path)}) == Tm1EngineSource.Bundle(tmp_path, None)
    # TM1_ENGINE_BUNDLE names a file within it
    assert discover({Tm1Source.BUNDLE_DIR: str(tmp_path), Tm1EngineSource.BUNDLE: PACKAGE}) == Tm1EngineSource.Bundle(tmp_path, PACKAGE)
    # Its own folder setting wins
    other = tmp_path / "other"
    other.mkdir()
    assert discover({Tm1Source.BUNDLE_DIR: str(tmp_path), Tm1EngineSource.BUNDLE_DIR: str(other)}) == Tm1EngineSource.Bundle(other, None)


def test_fails_on_a_wrong_setting_rather_than_skipping(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="no bin/tm1sd.exe"):
        discover({Tm1EngineSource.HOME_DIR: str(tmp_path)})
    with pytest.raises(ValueError, match="is not a folder"):
        discover({Tm1EngineSource.BUNDLE_DIR: str(tmp_path / "missing")})
    with pytest.raises(ValueError, match="which isn't in"):
        discover({Tm1EngineSource.BUNDLE_DIR: str(tmp_path), Tm1EngineSource.BUNDLE: "missing.tgz"})


def test_describes_a_url_without_its_secrets() -> None:
    source = Tm1EngineSource.BundleUrl("https://user:token@downloads.example.com:8443/tm1/p.tgz?X-Amz-Signature=secret", None)
    assert source.describe() == "package URL https://downloads.example.com:8443/tm1/p.tgz"
