# SPDX-License-Identifier: Apache-2.0

"""EncryptionService against a real TM1: a port of the Java client's EncryptionServiceTm1Test.

It needs a server that isn't encrypted and lacks GSKit's ``gsk9capicmd_64``, as the
docker/tm1-server image does; it changes nothing on it.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import pytest

from skipcheck_tm1 import Tm1BadRequestError, Tm1Client, Tm1UnsupportedOperationError, Tm1Version
from skipcheck_tm1._generated.operations import Tm1Operations
from skipcheck_tm1.model import EncryptionFileType, LocalKeyFileProviderSettingsPatch

from .conftest import CONFIGURED, REASON
from .support import assume_supports, product_version

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

LOCAL = LocalKeyFileProviderSettingsPatch.builder().key_ref("/tmp/tm1-client.key").build()


def _makes_its_own_key(tm1: Tm1Client) -> bool:
    """From TM1 11.8.040 the server makes the local key file itself, and the settings check as
    valid; before, the image's missing GSKit command line stops it."""
    return product_version(tm1) >= Tm1Version.parse("11.8.04000")


def _refuses(tm1: Tm1Client, operation: Any, call: Callable[[], object], code: str) -> None:
    """TM1's refusal, with its bare code name in the message, where the server defines the
    operation; the client's own refusal, sending nothing, where it doesn't."""
    if tm1.session.capabilities().supports(operation.capability()):
        with pytest.raises(Tm1BadRequestError, match=code):
            call()
    else:
        with pytest.raises(Tm1UnsupportedOperationError):
            call()


def test_validates_settings_without_changing_anything(tm1: Tm1Client) -> None:
    assume_supports(tm1, Tm1Operations.VALIDATE_ENCRYPTION_AT_REST_SETTINGS)
    report = tm1.encryption.validate(LOCAL)
    if _makes_its_own_key(tm1):
        assert report.is_valid is True
        assert not report.issues
    else:
        assert report.is_valid is False
        assert len(report.issues) == 1
        assert "Could not generate a new key using the local GSKit installation" in str(report.issues[0])


def test_refuses_changes_with_bare_code_names(tm1: Tm1Client) -> None:
    # From 11.8.040 the server can make the key, so configuring would really convert it and stop
    # it: not tried there
    if not _makes_its_own_key(tm1):
        _refuses(tm1, Tm1Operations.CONFIGURE_ENCRYPTION_AT_REST, lambda: tm1.encryption.configure(LOCAL), "DataModelConversion")
    _refuses(tm1, Tm1Operations.DISABLE_ENCRYPTION_AT_REST, tm1.encryption.disable, "DataModelConversion")
    _refuses(tm1, Tm1Operations.ROTATE_DATA_ENCRYPTION_KEY, tm1.encryption.rotate_data_encryption_key, "DataEncryptionKeyRotationFailed")
    _refuses(tm1, Tm1Operations.ROTATE_DATA_MODEL_KEY, tm1.encryption.rotate_data_model_key, "RotateDataModelKey")
    _refuses(
        tm1,
        Tm1Operations.ENCRYPT_DATA_FILE,
        lambda: tm1.encryption.encrypt_file("tm1s.cfg", "/tmp/tm1s.cfg.enc", EncryptionFileType.OBJECT),
        "DataFileConversion",
    )
    _refuses(
        tm1,
        Tm1Operations.DECRYPT_DATA_FILE,
        lambda: tm1.encryption.decrypt_file("}clientgroups.cub", "/tmp/clientgroups.cub", EncryptionFileType.OBJECT),
        "DataFileConversion",
    )
