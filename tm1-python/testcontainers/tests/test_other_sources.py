"""Java's CognosSourceTest, Tm1WebSourceTest and PawSourceTest."""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from tm1_testcontainers import CognosSource, PawContainer, PawSource, Tm1Source, Tm1WebSource

# --- CognosSourceTest --------------------------------------------------------------------------


def test_cognos_finds_nothing_when_nothing_is_set() -> None:
    assert CognosSource.discover({}.get) is None
    assert CognosSource.discover({CognosSource.ZIP_DIR: "  "}.get) is None


def test_cognos_tries_sources_in_order(tmp_path: Path) -> None:
    d = CognosSource.discover
    assert d({CognosSource.PRIVATE_IMAGE: "cognos-server:12.1.1-private", CognosSource.INSTALL_VOLUME: "v"}.get) == CognosSource.PrivateImage(
        "cognos-server:12.1.1-private"
    )
    assert d({CognosSource.INSTALL_VOLUME: "v", CognosSource.ZIP_DIR: str(tmp_path)}.get) == CognosSource.InstallVolume("v")
    assert d({CognosSource.ZIP_DIR: str(tmp_path), CognosSource.ZIP_URL: "https://example.com/c.zip"}.get) == CognosSource.Zip(tmp_path, None)
    assert d({CognosSource.ZIP_URL: "https://example.com/c.zip", CognosSource.ZIP_SHA256: "abc"}.get) == CognosSource.ZipUrl("https://example.com/c.zip", "abc")


def test_cognos_uses_the_tm1_bundle_folder_when_it_holds_cognos(tmp_path: Path) -> None:
    assert CognosSource.discover({Tm1Source.BUNDLE_DIR: str(tmp_path)}.get) is None
    name = "casrv-12.1.1-2512181837-linuxi38664h.zip"
    (tmp_path / name).touch()
    assert CognosSource.discover({Tm1Source.BUNDLE_DIR: str(tmp_path)}.get) == CognosSource.Zip(tmp_path, None)
    assert CognosSource.discover({Tm1Source.BUNDLE_DIR: str(tmp_path), CognosSource.ZIP: name}.get) == CognosSource.Zip(tmp_path, name)


def test_cognos_fails_on_a_wrong_setting_rather_than_skipping(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match=CognosSource.ZIP_DIR):
        CognosSource.discover({CognosSource.ZIP_DIR: str(tmp_path / "missing")}.get)
    with pytest.raises(ValueError, match="nope.zip"):
        CognosSource.discover({CognosSource.ZIP_DIR: str(tmp_path), CognosSource.ZIP: "nope.zip"}.get)


def test_cognos_never_describes_a_urls_credentials() -> None:
    source = CognosSource.ZipUrl("https://user:secret@example.com:8443/c.zip?X-Amz-Signature=SECRET", None)
    assert source.describe() == "zip URL https://example.com:8443/c.zip"
    assert "secret" not in source.describe() and "SECRET" not in source.describe()


# --- Tm1WebSourceTest --------------------------------------------------------------------------


def _fake_web_install(temp: Path) -> Path:
    install = temp / "tm1web"
    (install / "wlp" / "bin").mkdir(parents=True)
    (install / "wlp" / "bin" / "server").touch()
    return install


def test_web_finds_nothing_when_nothing_is_set() -> None:
    assert Tm1WebSource.discover({}.get) is None
    assert Tm1WebSource.discover({Tm1WebSource.HOME_DIR: "  "}.get) is None


def test_web_tries_sources_in_the_images_order(tmp_path: Path) -> None:
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


def test_web_uses_the_tm1_bundle_folder_when_it_holds_tm1_web(tmp_path: Path) -> None:
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


def test_web_fails_on_a_wrong_setting_rather_than_skipping(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="no wlp/bin/server"):
        Tm1WebSource.discover({Tm1WebSource.HOME_DIR: str(tmp_path)}.get)
    with pytest.raises(ValueError, match="is not a folder"):
        Tm1WebSource.discover({Tm1WebSource.BUNDLE_DIR: str(tmp_path / "missing")}.get)
    with pytest.raises(ValueError, match="isn't in"):
        Tm1WebSource.discover({Tm1WebSource.BUNDLE_DIR: str(tmp_path), Tm1WebSource.BUNDLE: "nope.tar.gz"}.get)


def test_web_never_describes_a_url_with_its_credentials() -> None:
    source = Tm1WebSource.BundleUrl("https://user:token@example.com:8443/b/tm1web.tar.gz?X-Amz-Signature=SECRET", None)
    assert source.describe() == "bundle URL https://example.com:8443/b/tm1web.tar.gz"


# --- PawSourceTest -----------------------------------------------------------------------------


def test_paw_finds_nothing_when_nothing_is_set() -> None:
    assert PawSource.discover({}.get) is None
    assert PawSource.discover({PawSource.ZIP: "  ", PawSource.BUNDLE_DIR: ""}.get) is None


def test_paw_uses_the_zip_set_directly(tmp_path: Path) -> None:
    zip_file = tmp_path / "ipa_workspace_local_2.1.19.1260.zip"
    zip_file.touch()
    assert PawSource.discover({PawSource.ZIP: str(zip_file)}.get) == PawSource(zip_file)


def test_paw_picks_the_newest_zip_in_the_bundle_folder(tmp_path: Path) -> None:
    older = tmp_path / "ipa_workspace_local_2.1.18.1000.zip"
    newer = tmp_path / "ipa_workspace_local_2.1.19.1260.zip"
    for f in (older, newer, tmp_path / "something-else.zip"):
        f.touch()
    os.utime(older, (1, 1))
    os.utime(newer, (2, 2))
    assert PawSource.discover({PawSource.BUNDLE_DIR: str(tmp_path)}.get) == PawSource(newer)


def test_paw_prefers_the_zip_over_the_bundle_folder(tmp_path: Path) -> None:
    zip_file = tmp_path / "ipa_workspace_local_2.1.19.1260.zip"
    zip_file.touch()
    assert PawSource.discover({PawSource.ZIP: str(zip_file), PawSource.BUNDLE_DIR: "/nowhere"}.get) == PawSource(zip_file)


def test_paw_fails_on_a_wrong_setting_rather_than_skipping(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="Not a file"):
        PawSource.discover({PawSource.ZIP: str(tmp_path / "missing.zip")}.get)
    with pytest.raises(ValueError, match=r"has no ipa_workspace_local_\*\.zip"):
        PawSource.discover({PawSource.BUNDLE_DIR: str(tmp_path)}.get)
    with pytest.raises(ValueError, match="is not a folder"):
        PawSource.discover({PawSource.BUNDLE_DIR: str(tmp_path / "nope")}.get)


def test_paw_refuses_to_start_without_the_license(tmp_path: Path) -> None:
    zip_file = tmp_path / "ipa_workspace_local_2.1.19.1260.zip"
    zip_file.touch()
    paw = PawContainer(PawSource(zip_file))
    if not PawContainer.license_accepted_by_setting():
        with pytest.raises(RuntimeError, match=PawContainer.ACCEPT_LICENSE):
            paw.start()
    with pytest.raises(ValueError):
        paw.with_setting("TM1_HOME_DIR", "x")
