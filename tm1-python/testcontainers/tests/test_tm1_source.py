"""Java's Tm1SourceTest."""

from __future__ import annotations

from pathlib import Path

import pytest

from tm1_testcontainers import Tm1ServerContainer, Tm1Source


def discover(settings: dict[str, str]) -> Tm1Source | None:
    return Tm1Source.discover(settings.get)


def without(settings: dict[str, str], *names: str) -> dict[str, str]:
    return {k: v for k, v in settings.items() if k not in names}


def fake_install(temp: Path) -> Path:
    install = temp / "tm1_64"
    (install / "bin64").mkdir(parents=True)
    (install / "bin64" / "tm1s.exe").touch()
    return install


def test_finds_nothing_when_nothing_is_set() -> None:
    assert discover({}) is None
    assert discover({Tm1Source.HOME_DIR: "  "}) is None


def test_tries_sources_in_the_images_order(tmp_path: Path) -> None:
    install = fake_install(tmp_path)
    everything = {
        Tm1Source.PRIVATE_IMAGE: "registry.example.com/private/tm1-server:2.1.19",
        Tm1Source.HOME_DIR: str(install),
        Tm1Source.INSTALL_VOLUME: "tm1_tm1-install",
        Tm1Source.BUNDLE_DIR: str(tmp_path),
        Tm1Source.BUNDLE_URL: "https://example.com/b.tar.gz",
    }
    assert isinstance(discover(everything), Tm1Source.PrivateImage)
    assert discover(without(everything, Tm1Source.PRIVATE_IMAGE)) == Tm1Source.InstallDir(install)
    assert discover(without(everything, Tm1Source.PRIVATE_IMAGE, Tm1Source.HOME_DIR)) == Tm1Source.InstallVolume("tm1_tm1-install")
    assert discover(without(everything, Tm1Source.PRIVATE_IMAGE, Tm1Source.HOME_DIR, Tm1Source.INSTALL_VOLUME)) == Tm1Source.Bundle(tmp_path, None)
    assert discover({Tm1Source.BUNDLE_URL: "https://example.com/b.tar.gz", Tm1Source.BUNDLE_SHA256: "abc"}) == Tm1Source.BundleUrl(
        "https://example.com/b.tar.gz", "abc"
    )


def test_fails_on_a_wrong_setting_rather_than_skipping(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="no bin64/tm1s.exe"):
        discover({Tm1Source.HOME_DIR: str(tmp_path)})
    with pytest.raises(ValueError, match="is not a folder"):
        discover({Tm1Source.BUNDLE_DIR: str(tmp_path / "missing")})
    with pytest.raises(ValueError, match="isn't in"):
        discover({Tm1Source.BUNDLE_DIR: str(tmp_path), Tm1Source.BUNDLE: "nope.tar.gz"})


def test_names_a_bundle_file_within_its_folder(tmp_path: Path) -> None:
    name = "tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz"
    (tmp_path / name).touch()
    assert discover({Tm1Source.BUNDLE_DIR: str(tmp_path), Tm1Source.BUNDLE: name}) == Tm1Source.Bundle(tmp_path, name)


def test_never_describes_a_urls_query_string() -> None:
    url = Tm1Source.BundleUrl("https://bucket.s3.amazonaws.com/b.tar.gz?X-Amz-Signature=secret", None)
    assert url.describe() == "bundle URL https://bucket.s3.amazonaws.com/b.tar.gz"
    assert "secret" not in url.describe()


def test_never_describes_credentials_in_a_url() -> None:
    url = Tm1Source.BundleUrl("https://ci-user:s3cr3t@artifacts.example.com:8443/tm1/b.tar.gz", None)
    assert url.describe() == "bundle URL https://artifacts.example.com:8443/tm1/b.tar.gz"
    assert "ci-user" not in url.describe() and "s3cr3t" not in url.describe()


def test_tags_snapshots_with_edge() -> None:
    assert Tm1ServerContainer.image_tag("0.1.0-SNAPSHOT") == "edge"
    assert Tm1ServerContainer.image_tag("0.1.0.dev0") == "edge"  # Python's snapshots
    assert Tm1ServerContainer.image_tag("0.1.0") == "0.1.0"


# Python's own: the image and mounts for each source, as Java's Tm1ServerContainer sets them


def test_mounts_each_source_as_the_image_takes_it(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(Tm1ServerContainer.SERVER_IMAGE, raising=False)
    install = fake_install(tmp_path)
    tm1 = Tm1ServerContainer(Tm1Source.PrivateImage("me/tm1:11.8"))
    assert tm1.image == "me/tm1:11.8"
    assert tm1.volumes == {}
    assert tm1.env == {"TM1_SEED": "empty"}
    assert tm1._kwargs["platform"] == "linux/amd64"
    assert list(tm1.ports) == ["8010"]

    tm1 = Tm1ServerContainer(Tm1Source.InstallDir(install))
    assert tm1.image.startswith(Tm1ServerContainer.DEFAULT_IMAGE + ":")
    assert tm1.volumes == {str(install): {"bind": "/opt/ibm/cognos/tm1_64", "mode": "ro"}}

    assert Tm1ServerContainer(Tm1Source.InstallVolume("tm1_tm1-install")).volumes == {"tm1_tm1-install": {"bind": "/opt/ibm/cognos/tm1_64", "mode": "rw"}}

    (tmp_path / "b.tar.gz").touch()
    monkeypatch.setenv(Tm1ServerContainer.SERVER_IMAGE, " local/tm1-server ")
    tm1 = Tm1ServerContainer(Tm1Source.Bundle(tmp_path, "b.tar.gz"))
    assert tm1.image == "local/tm1-server"
    assert tm1.volumes == {
        str(tmp_path): {"bind": "/ibm", "mode": "ro"},
        Tm1ServerContainer.CACHE_VOLUME: {"bind": "/tm1/cache", "mode": "rw"},
    }
    assert tm1.env["TM1_BUNDLE"] == "b.tar.gz"

    tm1 = Tm1ServerContainer(Tm1Source.BundleUrl("https://x/b?sig=1", "ab"))
    assert tm1.env == {"TM1_SEED": "empty", "TM1_BUNDLE_URL": "https://x/b?sig=1", "TM1_BUNDLE_SHA256": "ab"}
    assert tm1.volumes == {Tm1ServerContainer.CACHE_VOLUME: {"bind": "/tm1/cache", "mode": "rw"}}


def test_logs_in_as_the_seeds_user() -> None:
    from tm1_testcontainers import Tm1Seed

    tm1 = Tm1ServerContainer(Tm1Source.PrivateImage("me/tm1")).with_seed(Tm1Seed.SDATA)
    assert tm1.env["TM1_SEED"] == "sdata"
    assert (tm1.username, tm1.password) == ("admin", "apple")
    assert tm1.authorization == "Basic YWRtaW46YXBwbGU="
    with pytest.raises(ValueError, match="Not a folder"):
        tm1.with_data("/no/such/folder")
    with pytest.raises(RuntimeError, match="isn't started"):
        _ = tm1.tm1_version
