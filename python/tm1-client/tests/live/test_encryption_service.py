# SPDX-License-Identifier: Apache-2.0

"""EncryptionService against a real TM1: a port of the Java client's EncryptionServiceTm1Test.

It needs a server that isn't encrypted and lacks GSKit's ``gsk9capicmd_64``, as the
docker/tm1-server image does; it changes nothing on it.
"""

from __future__ import annotations

import pytest

from skipcheck_tm1 import Tm1BadRequestError, Tm1Client, Tm1Error
from skipcheck_tm1.model import EncryptionFileType, LocalKeyFileProviderSettingsPatch

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)

LOCAL = LocalKeyFileProviderSettingsPatch.builder().key_ref("/tmp/tm1-client.key").build()


def test_validates_settings_without_changing_anything(tm1: Tm1Client) -> None:
    report = tm1.encryption.validate(LOCAL)
    assert report.is_valid is False
    assert len(report.issues) == 1
    assert "Could not generate a new key using the local GSKit installation" in str(report.issues[0])


def test_refuses_changes_with_bare_code_names(tm1: Tm1Client) -> None:
    with pytest.raises(Tm1BadRequestError, match="DataModelConversion"):
        tm1.encryption.configure(LOCAL)
    with pytest.raises(Tm1Error, match="DataModelConversion"):
        tm1.encryption.disable()
    with pytest.raises(Tm1Error, match="DataEncryptionKeyRotationFailed"):
        tm1.encryption.rotate_data_encryption_key()
    with pytest.raises(Tm1Error, match="RotateDataModelKey"):
        tm1.encryption.rotate_data_model_key()
    with pytest.raises(Tm1Error, match="DataFileConversion"):
        tm1.encryption.encrypt_file("tm1s.cfg", "/tmp/tm1s.cfg.enc", EncryptionFileType.OBJECT)
    with pytest.raises(Tm1Error, match="DataFileConversion"):
        tm1.encryption.decrypt_file("}clientgroups.cub", "/tmp/clientgroups.cub", EncryptionFileType.OBJECT)
