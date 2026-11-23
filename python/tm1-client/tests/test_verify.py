# SPDX-License-Identifier: Apache-2.0

"""How the session checks the server's certificate (``verify``), in both flavors. Python only:
the Java client takes the JVM's trust store, or an SSL bundle through Spring."""

from __future__ import annotations

import asyncio
import platform
import ssl
import warnings
from pathlib import Path
from typing import Any

import pytest
import truststore

from skipcheck_tm1 import Tm1Session, auth
from skipcheck_tm1.aio import AsyncTm1Session

from .conftest import BASE

# A self-signed CA made for this test (openssl req -x509), never used to sign anything
CA = """-----BEGIN CERTIFICATE-----
MIIBdjCCARygAwIBAgIUS9slFIFUTI8VoNbGediscS2PFT4wCgYIKoZIzj0EAwIw
IDEeMBwGA1UEAwwVc2tpcGNoZWNrLXRtMSB0ZXN0IENBMCAXDTI2MTEyMzE5MDAz
OFoYDzIxMjYxMDMwMTkwMDM4WjAgMR4wHAYDVQQDDBVza2lwY2hlY2stdG0xIHRl
c3QgQ0EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATrMUuuCImTU9glXHMLvnMF
71fCeXPKYR7CgK7E7df8Qi8W9lMHVNTF+R15+HduUbZ5Qv5d0yRgyvMJoDyhtNtH
ozIwMDAdBgNVHQ4EFgQUFm7e16cpiQY/CmAYkEee33uRj+kwDwYDVR0TAQH/BAUw
AwEB/zAKBggqhkjOPQQDAgNIADBFAiAk7jjjIt1N139TCbA9uVWZ1/4tjKYulZNC
E2fCcpAgCQIhAJmFvs+Ro3r2h1aWptUVuDCVM8TKTmvR1d6xkMVvNuik
-----END CERTIFICATE-----
"""


def ssl_context(flavor: str, verify: Any) -> ssl.SSLContext:
    """The context the session's own HTTP client connects with. It's httpx2's and httpcore2's to
    keep, so this reads their internals: the client, its transport, the transport's pool."""
    if flavor == "sync":
        with Tm1Session(BASE, auth.basic("admin", "apple"), verify=verify) as session:
            return session._connection.http._transport._pool._ssl_context  # type: ignore[attr-defined]

    async def run() -> ssl.SSLContext:
        async with AsyncTm1Session(BASE, auth.basic("admin", "apple"), verify=verify) as session:
            return session._connection.http._transport._pool._ssl_context  # type: ignore[attr-defined]

    return asyncio.run(run())


FLAVORS = pytest.mark.parametrize("flavor", ["sync", "async"])


@FLAVORS
def test_checks_the_certificate_against_the_operating_systems_trust_store_by_default(flavor: str, monkeypatch: pytest.MonkeyPatch) -> None:
    # Either would replace the trust store with a file or directory of its own
    monkeypatch.delenv("SSL_CERT_FILE", raising=False)
    monkeypatch.delenv("SSL_CERT_DIR", raising=False)
    context = ssl_context(flavor, True)
    if platform.system() in ("Windows", "Darwin"):
        assert isinstance(context, truststore.SSLContext)
    else:
        # httpcore2 reads OpenSSL's system CA files there rather than use truststore, which
        # piles up verify paths on OpenSSL before 3.4 (sethmlarson/truststore#212)
        assert not isinstance(context, truststore.SSLContext)
        assert context.cert_store_stats()["x509_ca"] > 0
    assert context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname


@FLAVORS
def test_checks_the_certificate_against_a_ca_bundle_given_as_a_path(flavor: str, tmp_path: Path) -> None:
    bundle = tmp_path / "ca.pem"
    bundle.write_text(CA)
    with warnings.catch_warnings():
        # httpx2 deprecates a path for verify; the session mustn't hand it one
        warnings.simplefilter("error")
        context = ssl_context(flavor, str(bundle))
    assert not isinstance(context, truststore.SSLContext)
    assert [dict(pair[0] for pair in ca["subject"]) for ca in context.get_ca_certs()] == [{"commonName": "skipcheck-tm1 test CA"}]
    assert context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname


@FLAVORS
def test_checks_the_certificate_against_a_directory_of_cas(flavor: str, tmp_path: Path) -> None:
    with warnings.catch_warnings():
        warnings.simplefilter("error")
        context = ssl_context(flavor, str(tmp_path))
    assert not isinstance(context, truststore.SSLContext)
    assert context.verify_mode == ssl.CERT_REQUIRED


@FLAVORS
def test_fails_at_once_on_a_ca_bundle_that_isnt_there(flavor: str, tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        ssl_context(flavor, str(tmp_path / "missing.pem"))


@FLAVORS
def test_connects_with_an_ssl_context_as_it_is(flavor: str) -> None:
    context = ssl.create_default_context()
    assert ssl_context(flavor, context) is context


@FLAVORS
def test_doesnt_check_the_certificate_when_told_not_to(flavor: str) -> None:
    context = ssl_context(flavor, False)
    assert context.verify_mode == ssl.CERT_NONE and not context.check_hostname
