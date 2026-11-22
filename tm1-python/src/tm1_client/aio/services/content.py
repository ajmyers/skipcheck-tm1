"""Services for content (the Java client's ``net.ajmyers.tm1.client.service.content``)."""

from __future__ import annotations

from ._base import Service

__all__ = ['ContentService']


class ContentService(Service):
    """The contents tree, where Planning Analytics keeps applications: folders, documents, links, and references to cubes, dimensions, subsets, views, processes and chores. Entries are addressed by ``ContentPath``."""

    # Not ported yet: see tests/test_java_parity.py
