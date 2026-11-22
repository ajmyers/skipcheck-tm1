"""Services for encryption (the Java client's ``net.ajmyers.tm1.client.service.encryption``)."""

from __future__ import annotations

from ._base import Service

__all__ = ['EncryptionService']


class EncryptionService(Service):
    """Encryption at rest (schema 11.8.35, except where a method says): the model's files encrypted with a data encryption key, itself protected by a key from a key management service. The provider settings are a ``KeyProtectSettingsPatch`` (IBM Key Protect), ``VaultSettingsPatch`` (HashiCorp Vault) or ``LocalKeyFileProviderSettingsPatch`` (a key file made with the local GSKit)."""

    # Not ported yet: see tests/test_java_parity.py
