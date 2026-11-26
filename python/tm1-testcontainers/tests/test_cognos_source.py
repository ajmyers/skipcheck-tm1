# SPDX-License-Identifier: Apache-2.0

"""Java's CognosSourceTest."""

from __future__ import annotations

from pathlib import Path

import pytest

from skipcheck_tm1_testcontainers import CognosSource, Tm1Source


def test_finds_nothing_when_nothing_is_set() -> None:
    assert CognosSource.discover({}.get) is None
    assert CognosSource.discover({CognosSource.ZIP_DIR: "  "}.get) is None


def test_tries_sources_in_order(tmp_path: Path) -> None:
    d = CognosSource.discover
    assert d({CognosSource.PRIVATE_IMAGE: "cognos-server:12.1.1-private", CognosSource.INSTALL_VOLUME: "v"}.get) == CognosSource.PrivateImage(
        "cognos-server:12.1.1-private"
    )
    assert d({CognosSource.INSTALL_VOLUME: "v", CognosSource.ZIP_DIR: str(tmp_path)}.get) == CognosSource.InstallVolume("v")
    assert d({CognosSource.ZIP_DIR: str(tmp_path), CognosSource.ZIP_URL: "https://example.com/c.zip"}.get) == CognosSource.Zip(tmp_path, None)
    assert d({CognosSource.ZIP_URL: "https://example.com/c.zip", CognosSource.ZIP_SHA256: "abc"}.get) == CognosSource.ZipUrl("https://example.com/c.zip", "abc")


def test_uses_the_tm1_bundle_folder_when_it_holds_cognos(tmp_path: Path) -> None:
    assert CognosSource.discover({Tm1Source.BUNDLE_DIR: str(tmp_path)}.get) is None
    name = "casrv-12.1.1-2512181837-linuxi38664h.zip"
    (tmp_path / name).touch()
    assert CognosSource.discover({Tm1Source.BUNDLE_DIR: str(tmp_path)}.get) == CognosSource.Zip(tmp_path, None)
    assert CognosSource.discover({Tm1Source.BUNDLE_DIR: str(tmp_path), CognosSource.ZIP: name}.get) == CognosSource.Zip(tmp_path, name)


def test_fails_on_a_wrong_setting_rather_than_skipping(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match=CognosSource.ZIP_DIR):
        CognosSource.discover({CognosSource.ZIP_DIR: str(tmp_path / "missing")}.get)
    with pytest.raises(ValueError, match="nope.zip"):
        CognosSource.discover({CognosSource.ZIP_DIR: str(tmp_path), CognosSource.ZIP: "nope.zip"}.get)


def test_never_describes_a_urls_credentials() -> None:
    source = CognosSource.ZipUrl("https://user:secret@example.com:8443/c.zip?X-Amz-Signature=SECRET", None)
    assert source.describe() == "zip URL https://example.com:8443/c.zip"
    assert "secret" not in source.describe() and "SECRET" not in source.describe()
