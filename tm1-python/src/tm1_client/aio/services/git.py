"""Services for git (the Java client's ``net.ajmyers.tm1.client.service.git``)."""

from __future__ import annotations

from ._base import Service

__all__ = ['GitService']


class GitService(Service):
    """TM1 Git (schema 11.5.0): ties the model to a Git repository, pushes its objects there as JSON files (``dimensions/Region.json``, …), and pulls them back, on this server or another."""

    # Not ported yet: see tests/test_java_parity.py
