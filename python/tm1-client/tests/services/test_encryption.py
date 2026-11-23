"""EncryptionService, in both flavors, against a fake TM1."""

from __future__ import annotations

import json
from datetime import timedelta

import httpx
import pytest

from skipcheck_tm1 import Tm1BadRequestError
from skipcheck_tm1.model import EncryptionFileType, KeyProtectSettingsPatch

from ..conftest import Api, FakeTm1, target


def body(request: httpx.Request) -> object:
    return json.loads(request.content) if request.content else None


KEY_PROTECT = (
    KeyProtectSettingsPatch.builder()
    .service_endpoint("https://us-south.kms.cloud.ibm.com")
    .instance_id("8f2c")
    .auth_ref("/run/secrets/key")
    .key_ref("root")
    .build()
)


def test_validates_through_a_parameter_alias(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: {"IsValid": False, "Issues": ["no key"]}
    report = api.run(api.client.encryption.validate(KEY_PROTECT))
    assert report.is_valid is False and report.issues == ["no key"]
    assert target(fake.last).startswith("GET ValidateEncryptionAtRestSettings(Settings=@settings)?@settings=")
    assert json.loads(fake.last.url.params["@settings"]) == {
        "@odata.type": "#ibm.tm1.api.v1.KeyProtectSettings",
        "ServiceEndpoint": "https://us-south.kms.cloud.ibm.com",
        "InstanceID": "8f2c",
        "AuthRef": "/run/secrets/key",
        "KeyRef": "root",
    }


def test_the_actions(api: Api, fake: FakeTm1) -> None:
    fake.respond = lambda r: None
    api.run(api.client.encryption.configure(KEY_PROTECT))
    assert target(fake.last) == "POST ConfigureEncryptionAtRest"
    assert body(fake.last)["Settings"]["KeyRef"] == "root"  # type: ignore[index]
    for method, action in (
        ("disable", "DisableEncryptionAtRest"),
        ("rotate_data_encryption_key", "RotateDataEncryptionKey"),
        ("rotate_data_model_key", "RotateDataModelKey"),
        ("encrypt_model", "EncryptDataModel"),
        ("decrypt_model", "DecryptDataModel"),
    ):
        api.run(getattr(api.client.encryption, method)())
        assert (target(fake.last), body(fake.last)) == (f"POST {action}", {})
    api.run(api.client.encryption.encrypt_model(timedelta(minutes=1)))
    assert body(fake.last) == {"TimeUntilShutdown": "PT1M"}
    api.run(api.client.encryption.decrypt_model(90))
    assert body(fake.last) == {"TimeUntilShutdown": "PT1M30S"}
    api.run(api.client.encryption.encrypt_file("a.cub", "b.cub", EncryptionFileType.OBJECT))
    assert (target(fake.last), body(fake.last)) == (
        "POST EncryptDataFile",
        {"SourcePath": "a.cub", "DestinationPath": "b.cub", "FileType": "Object"},
    )
    api.run(api.client.encryption.decrypt_file("b.cub", "a.cub", EncryptionFileType.AUDIT_LOG))
    assert target(fake.last) == "POST DecryptDataFile"


def test_an_unencrypted_server_refuses_with_a_bare_400(api: Api, fake: FakeTm1) -> None:
    # TM1 (11.8): the message is an error code's name
    fake.respond = lambda r: httpx.Response(400, json={"error": {"code": "", "message": "DataModelConversion"}})
    with pytest.raises(Tm1BadRequestError, match="DataModelConversion"):
        api.run(api.client.encryption.disable())
