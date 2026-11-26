# SPDX-License-Identifier: Apache-2.0

"""Java's PawSourceTest."""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from skipcheck_tm1_testcontainers import PawContainer, PawSource


def test_finds_nothing_when_nothing_is_set() -> None:
    assert PawSource.discover({}.get) is None
    assert PawSource.discover({PawSource.ZIP: "  ", PawSource.BUNDLE_DIR: ""}.get) is None


def test_uses_the_zip_set_directly(tmp_path: Path) -> None:
    zip_file = tmp_path / "ipa_workspace_local_2.1.19.1260.zip"
    zip_file.touch()
    assert PawSource.discover({PawSource.ZIP: str(zip_file)}.get) == PawSource(zip_file)


def test_picks_the_newest_zip_in_the_bundle_folder(tmp_path: Path) -> None:
    older = tmp_path / "ipa_workspace_local_2.1.18.1000.zip"
    newer = tmp_path / "ipa_workspace_local_2.1.19.1260.zip"
    for f in (older, newer, tmp_path / "something-else.zip"):
        f.touch()
    os.utime(older, (1, 1))
    os.utime(newer, (2, 2))
    assert PawSource.discover({PawSource.BUNDLE_DIR: str(tmp_path)}.get) == PawSource(newer)


def test_prefers_the_zip_over_the_bundle_folder(tmp_path: Path) -> None:
    zip_file = tmp_path / "ipa_workspace_local_2.1.19.1260.zip"
    zip_file.touch()
    assert PawSource.discover({PawSource.ZIP: str(zip_file), PawSource.BUNDLE_DIR: "/nowhere"}.get) == PawSource(zip_file)


def test_fails_on_a_wrong_setting_rather_than_skipping(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="Not a file"):
        PawSource.discover({PawSource.ZIP: str(tmp_path / "missing.zip")}.get)
    with pytest.raises(ValueError, match=r"has no ipa_workspace_local_\*\.zip"):
        PawSource.discover({PawSource.BUNDLE_DIR: str(tmp_path)}.get)
    with pytest.raises(ValueError, match="is not a folder"):
        PawSource.discover({PawSource.BUNDLE_DIR: str(tmp_path / "nope")}.get)


def test_refuses_to_start_without_the_license(tmp_path: Path) -> None:
    zip_file = tmp_path / "ipa_workspace_local_2.1.19.1260.zip"
    zip_file.touch()
    paw = PawContainer(PawSource(zip_file))
    if not PawContainer.license_accepted_by_setting():
        with pytest.raises(RuntimeError, match=PawContainer.ACCEPT_LICENSE):
            paw.start()
    with pytest.raises(ValueError):
        paw.with_setting("TM1_HOME_DIR", "x")
