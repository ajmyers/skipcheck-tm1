# SPDX-License-Identifier: Apache-2.0

"""Java's Tm1WebSourceTest."""

from __future__ import annotations

from pathlib import Path

import pytest

from skipcheck_tm1_testcontainers import Tm1Source, Tm1WebSource


def _fake_web_install(temp: Path) -> Path:
    install = temp / "tm1web"
    (install / "wlp" / "bin").mkdir(parents=True)
    (install / "wlp" / "bin" / "server").touch()
    return install


def test_finds_nothing_when_nothing_is_set() -> None:
    assert Tm1WebSource.discover({}.get) is None
    assert Tm1WebSource.discover({Tm1WebSource.HOME_DIR: "  "}.get) is None


def test_tries_sources_in_the_images_order(tmp_path: Path) -> None:
    install = _fake_web_install(tmp_path)
    d = Tm1WebSource.discover
    assert isinstance(
        d({Tm1WebSource.PRIVATE_IMAGE: "registry.example.com/private/tm1-web:2.1.19", Tm1WebSource.HOME_DIR: str(install)}.get),
        Tm1WebSource.PrivateImage,
    )
    assert d({Tm1WebSource.HOME_DIR: str(install), Tm1WebSource.INSTALL_VOLUME: "v"}.get) == Tm1WebSource.InstallDir(install)
    assert d({Tm1WebSource.INSTALL_VOLUME: "v", Tm1WebSource.BUNDLE_DIR: str(tmp_path)}.get) == Tm1WebSource.InstallVolume("v")
    assert d({Tm1WebSource.BUNDLE_DIR: str(tmp_path), Tm1WebSource.BUNDLE_URL: "https://example.com/b.tar.gz"}.get) == Tm1WebSource.Bundle(tmp_path, None)
    assert d({Tm1WebSource.BUNDLE_URL: "https://example.com/b.tar.gz", Tm1WebSource.BUNDLE_SHA256: "abc"}.get) == Tm1WebSource.BundleUrl(
        "https://example.com/b.tar.gz", "abc"
    )


def test_uses_the_tm1_bundle_folder_when_it_holds_tm1_web(tmp_path: Path) -> None:
    d = Tm1WebSource.discover
    assert d({Tm1Source.BUNDLE_DIR: str(tmp_path)}.get) is None
    name = "tm1web-11.0.112-26031316-2.1.19-linuxi38664h_bundle.tar.gz"
    (tmp_path / name).touch()
    assert d({Tm1Source.BUNDLE_DIR: str(tmp_path)}.get) == Tm1WebSource.Bundle(tmp_path, None)
    # TM1_WEB_BUNDLE names a file within it, as in the compose setup
    assert d({Tm1Source.BUNDLE_DIR: str(tmp_path), Tm1WebSource.BUNDLE: name}.get) == Tm1WebSource.Bundle(tmp_path, name)
    # Its own folder setting wins
    other = tmp_path / "other"
    other.mkdir()
    assert d({Tm1Source.BUNDLE_DIR: str(tmp_path), Tm1WebSource.BUNDLE_DIR: str(other)}.get) == Tm1WebSource.Bundle(other, None)


def test_fails_on_a_wrong_setting_rather_than_skipping(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="no wlp/bin/server"):
        Tm1WebSource.discover({Tm1WebSource.HOME_DIR: str(tmp_path)}.get)
    with pytest.raises(ValueError, match="is not a folder"):
        Tm1WebSource.discover({Tm1WebSource.BUNDLE_DIR: str(tmp_path / "missing")}.get)
    with pytest.raises(ValueError, match="isn't in"):
        Tm1WebSource.discover({Tm1WebSource.BUNDLE_DIR: str(tmp_path), Tm1WebSource.BUNDLE: "nope.tar.gz"}.get)


def test_never_describes_a_url_with_its_credentials() -> None:
    source = Tm1WebSource.BundleUrl("https://user:token@example.com:8443/b/tm1web.tar.gz?X-Amz-Signature=SECRET", None)
    assert source.describe() == "bundle URL https://example.com:8443/b/tm1web.tar.gz"
