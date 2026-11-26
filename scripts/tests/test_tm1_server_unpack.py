# SPDX-License-Identifier: Apache-2.0

"""docker/tm1-server's unpack-tm1.py, which installs TM1 from a bundle's repository by following its
manifest: what it installs, and an installable a bundle may not have (marked with a trailing ?).
Planning Analytics 2.1.8 to 2.1.10 have no software tags. The image itself is tested by
docker/tm1-server/ci-smoke-test.sh."""

from __future__ import annotations

import io
import json
import subprocess
import sys
import tarfile
from pathlib import Path

from conftest import REPO

UNPACK = REPO / "docker" / "tm1-server" / "docker" / "unpack-tm1.py"


def tar_with(path: Path, name: str, data: bytes) -> None:
    with tarfile.open(path, "w:gz") as t:
        info = tarfile.TarInfo(name)
        info.size = len(data)
        t.addfile(info, io.BytesIO(data))


def repository(root: Path, installables: list[str]) -> Path:
    """An extracted repository whose manifest has the installables, each one file, bin64/<name>."""
    repo = root / "repo"
    (repo / "p").mkdir(parents=True)
    (repo / "manifest" / "tm1server" / "9.9.9").mkdir(parents=True)
    tar_with(repo / "p" / "license.tar.gz", "license/LA_en", b"license")
    entries = []
    for name in installables:
        tar_with(repo / "p" / f"{name}.tar.gz", f"bin64/{name}", name.encode())
        part = {"name": name, "version": "9.9.9", "repositoryPath": f"p/{name}.tar.gz"}
        entries.append({"name": name, "parts": [part]})
    manifest = {
        "productName": "Fake TM1",
        "productVersion": "9.9.9",
        "platform": "linuxi38664h",
        "license": {"name": "license", "version": "1", "repositoryPath": "p/license.tar.gz"},
        "installables": entries,
    }
    (repo / "manifest" / "tm1server" / "9.9.9" / "m.json").write_text(json.dumps(manifest))
    return repo


def unpack(repo: Path, target: Path, *wanted: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-I", str(UNPACK), str(repo), str(target), *wanted],
        capture_output=True,
        text=True,
        timeout=60,
    )


def test_installs_what_was_asked_for_and_its_license(tmp_path: Path) -> None:
    repo = repository(tmp_path, ["TM1_Server", "TM1_Tools", "TM1_Samples"])
    done = unpack(repo, tmp_path / "t", "TM1_Server", "TM1_Tools")
    assert done.returncode == 0, done.stderr
    assert sorted(p.name for p in (tmp_path / "t" / "bin64").iterdir()) == ["TM1_Server", "TM1_Tools"]
    assert (tmp_path / "t" / "license" / "LA_en").read_text() == "license"


def test_an_optional_installable_is_installed_when_the_bundle_has_it(tmp_path: Path) -> None:
    repo = repository(tmp_path, ["TM1_Server", "TM1_NON-PROD_SWTAG"])
    done = unpack(repo, tmp_path / "t", "TM1_Server", "TM1_NON-PROD_SWTAG?")
    assert done.returncode == 0, done.stderr
    assert (tmp_path / "t" / "bin64" / "TM1_NON-PROD_SWTAG").exists()


def test_an_optional_installable_the_bundle_lacks_is_skipped(tmp_path: Path) -> None:
    # As in 2.1.8 to 2.1.10, which have no software tags
    repo = repository(tmp_path, ["TM1_Server"])
    done = unpack(repo, tmp_path / "t", "TM1_Server", "TM1_NON-PROD_SWTAG?")
    assert done.returncode == 0, done.stderr
    assert "TM1_NON-PROD_SWTAG: not in this bundle, skipped" in done.stdout
    assert (tmp_path / "t" / "bin64" / "TM1_Server").exists()


def test_a_required_installable_the_bundle_lacks_stops_before_installing(tmp_path: Path) -> None:
    repo = repository(tmp_path, ["TM1_Server", "TM1_Tools"])
    done = unpack(repo, tmp_path / "t", "TM1_Server", "TM1_NON-PROD_SWTAG")
    assert done.returncode != 0
    assert "This bundle has no TM1_NON-PROD_SWTAG (it has TM1_Server, TM1_Tools)" in done.stderr
    assert not (tmp_path / "t" / "bin64").exists(), "nothing installed"
