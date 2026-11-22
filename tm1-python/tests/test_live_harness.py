"""The live tests' TM1 container (tests/live/tm1_container.py) finds TM1 as Java's
tm1-testcontainers does: the same settings, the same order, the same mounts and cache volume."""

from __future__ import annotations

from pathlib import Path

import pytest

from .live import tm1_container as c


def test_finds_the_first_source_set_in_javas_order(tmp_path: Path) -> None:
    env = {"TM1_BUNDLE_URL": "https://x/b.tar.gz?sig=secret", "TM1_INSTALL_VOLUME": "tm1_tm1-install", "TM1_PRIVATE_IMAGE": " "}
    assert c.discover(env.get) == c.Source("install-volume", "tm1_tm1-install")
    assert c.discover({**env, "TM1_PRIVATE_IMAGE": "me/tm1:11.8"}.get) == c.Source("private-image", "me/tm1:11.8")
    url = c.discover({"TM1_BUNDLE_URL": "https://x/b.tar.gz?sig=secret", "TM1_BUNDLE_SHA256": "ab"}.get)
    assert url == c.Source("bundle-url", "https://x/b.tar.gz?sig=secret", "ab")
    assert "secret" not in url.describe()
    assert c.discover({}.get) is None


def test_refuses_a_source_set_wrong() -> None:
    with pytest.raises(ValueError, match="TM1_BUNDLE_DIR"):
        c.discover({"TM1_BUNDLE_DIR": "/no/such/folder"}.get)


def test_mounts_each_source_as_javas_tm1_server_container(tmp_path: Path) -> None:
    common = ["run", "-d", "--platform", "linux/amd64", "-p", "127.0.0.1::8010", "-e", "TM1_SEED=empty"]
    label = ["--label", "net.ajmyers.tm1.tests=python"]
    assert c.run_arguments(c.Source("private-image", "me/tm1")) == common + label + ["me/tm1"]
    assert c.run_arguments(c.Source("install-dir", "/opt/tm1"), {}.get) == common + ["-v", "/opt/tm1:/opt/ibm/cognos/tm1_64:ro"] + label + [c.DEFAULT_IMAGE]
    assert c.run_arguments(c.Source("bundle", str(tmp_path), "b.tar.gz"), {"TM1_SERVER_IMAGE": "local/tm1-server"}.get) == common + [
        "-v", f"{tmp_path}:/ibm:ro", "-v", "tm1-testcontainers-cache:/tm1/cache", "-e", "TM1_BUNDLE=b.tar.gz"
    ] + label + ["local/tm1-server"]
    assert c.run_arguments(c.Source("bundle-url", "https://x/b", "ab"), {}.get) == common + [
        "-e", "TM1_BUNDLE_URL=https://x/b", "-v", "tm1-testcontainers-cache:/tm1/cache", "-e", "TM1_BUNDLE_SHA256=ab"
    ] + label + [c.DEFAULT_IMAGE]
