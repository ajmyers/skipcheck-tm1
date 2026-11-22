"""Services for annotation (the Java client's ``net.ajmyers.tm1.client.service.annotation``)."""

from __future__ import annotations

from ._base import Service

__all__ = ['AnnotationService']


class AnnotationService(Service):
    """Annotations: comments on cube cells, as Planning Analytics Workspace makes them, and the application context facets that tie them to cubes."""

    # Not ported yet: see tests/test_java_parity.py
