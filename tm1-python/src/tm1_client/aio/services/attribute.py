"""Services for attribute (the Java client's ``net.ajmyers.tm1.client.service.attribute``)."""

from __future__ import annotations

from ._base import Service

__all__ = ['AttributeService', 'LocalizedAttributeService']


class AttributeService(Service):
    """Attribute definitions: which attributes a kind of object has, and their types, for each ``AttributeSet``. Creating one adds it to every object of that kind; the values are cells of the matching <code>&#125;…Attributes</code> control cube."""

    # Not ported yet: see tests/test_java_parity.py


class LocalizedAttributeService(Service):
    """Translated attribute values: an ``AttributeOwner``'s ``LocalizedAttributes``, one per locale, keyed by locale ID (``fr``, ``de-CH``). The values are in ``Attributes``: ``Caption``, and every other attribute in its ``dynamicProperties`` (set with ``AttributesPatch.Builder#property``)."""

    # Not ported yet: see tests/test_java_parity.py
