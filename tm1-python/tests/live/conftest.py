"""Tests against a real TM1, skipped unless one is configured:

* ``TM1_URL``: the REST API's root, such as ``https://localhost:8010/api/v1``
* ``TM1_USER``, ``TM1_PASSWORD`` (default ``admin`` and ``apple``), ``TM1_NAMESPACE`` for CAM
* ``TM1_VERIFY=false`` to accept a self-signed certificate (the docker/tm1-server image's)

They make objects with names starting ``tm1py_test_`` and delete them afterwards.
"""

from __future__ import annotations

import os
import uuid
from typing import Any, Iterator

import pytest

from tm1_client import Tm1Client

URL = os.environ.get("TM1_URL")

pytestmark = pytest.mark.skipif(URL is None, reason="no TM1 configured (TM1_URL)")


def _builder(builder: Any) -> Any:
    from tm1_client import auth

    user = os.environ.get("TM1_USER", "admin")
    password = os.environ.get("TM1_PASSWORD", "apple")
    namespace = os.environ.get("TM1_NAMESPACE")
    builder = builder.base_url(URL).verify(os.environ.get("TM1_VERIFY", "true").lower() != "false")
    return builder.authenticator(auth.cam_namespace(user, password, namespace) if namespace else auth.basic(user, password))


def client() -> Tm1Client:
    """A new client of the configured TM1."""
    return _builder(Tm1Client.builder()).build()


def async_client() -> Any:
    """A new asyncio client of the configured TM1."""
    from tm1_client.aio import AsyncTm1Client

    return _builder(AsyncTm1Client.builder()).build()


@pytest.fixture(scope="session")
def tm1() -> Iterator[Tm1Client]:
    if URL is None:
        pytest.skip("no TM1 configured (TM1_URL)")
    with client() as c:
        yield c


@pytest.fixture
def name() -> str:
    return "tm1py_test_" + uuid.uuid4().hex[:8]
