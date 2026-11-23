"""Examples for EncryptionService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Tm1Client
from skipcheck_tm1.model import KeyProtectSettingsPatch


def configure(tm1: Tm1Client) -> None:
    # @start region = configure
    # Check the settings first: the report says what's wrong, without secrets
    key_protect = (
        KeyProtectSettingsPatch.builder()
        .service_endpoint("https://us-south.kms.cloud.ibm.com")
        .instance_id("8f2c1d6e-0000-4000-8000-000000000000")
        .auth_ref("/run/secrets/key-protect-api-key")
        .key_ref("planning-root-key")
        .build()
    )
    report = tm1.encryption.validate(key_protect)
    if not report.is_valid:
        raise RuntimeError(f"Can't encrypt: {report.issues}")
    tm1.encryption.configure(key_protect)
    # @end


def rotate(tm1: Tm1Client) -> None:
    # @start region = rotate
    # A new data encryption key; TM1 re-encrypts the whole model with it
    tm1.encryption.rotate_data_encryption_key()
    # @end
