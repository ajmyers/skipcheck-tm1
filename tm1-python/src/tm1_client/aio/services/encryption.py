"""Services for encryption at rest (the Java client's ``net.ajmyers.tm1.client.service.encryption``)."""

from __future__ import annotations

import json
from datetime import timedelta
from typing import Any

from ..._calls import endpoint
from ..._generated.model import EncryptionAtRestValidationReport, EncryptionFileType
from ..._generated.operations import Tm1Operations
from ...odata import Alias, ODataPath, ODataQuery, duration
from ...operation import Tm1Operation
from ...request import Tm1Request, body_of
from ._base import Service, _require

__all__ = ["EncryptionService"]

_SETTINGS = Alias("@settings")


class EncryptionService(Service):
    """Encryption at rest (schema 11.8.35, except where a method says): the model's files
    encrypted with a data encryption key, itself protected by a key from a key management
    service. The provider settings are a ``KeyProtectSettingsPatch`` (IBM Key Protect),
    ``VaultSettingsPatch`` (HashiCorp Vault) or ``LocalKeyFileProviderSettingsPatch`` (a key
    file made with the local GSKit).

    What TM1 (11.8) does here that you might not expect:

    * The local key file provider needs GSKit's ``gsk9capicmd_64``, which a TM1 install may
      lack (the Docker image used in this project's tests does): validation then reports
      "Could not generate a new key using the local GSKit installation".
    * On a server that isn't encrypted, the changing actions fail with a bare 400 and an error
      code's name for a message: ``DataModelConversion`` (configure, disable),
      ``DataFileConversion`` (encrypt or decrypt a file), ``DataEncryptionKeyRotationFailed``
      and ``RotateDataModelKey``.
    * Each must be run by a full administrator.

    Encrypt the model with a key from IBM Key Protect, checking the settings first (the report
    says what's wrong, without secrets)::

        key_protect = (
            KeyProtectSettingsPatch.builder()
            .service_endpoint("https://us-south.kms.cloud.ibm.com")
            .instance_id("8f2c1d6e-0000-4000-8000-000000000000")
            .auth_ref("/run/secrets/key-protect-api-key")
            .key_ref("planning-root-key")
            .build()
        )
        report = await tm1.encryption.validate(key_protect)
        if not report.is_valid:
            raise RuntimeError(f"Can't encrypt: {report.issues}")
        await tm1.encryption.configure(key_protect)

    Rotate the data encryption key; TM1 re-encrypts the whole model with it::

        await tm1.encryption.rotate_data_encryption_key()
    """

    @endpoint("FunctionImport ValidateEncryptionAtRestSettings")
    async def validate(self, settings: Any) -> EncryptionAtRestValidationReport:
        """``ValidateEncryptionAtRestSettings``: checks the provider settings would work now
        (the key service is reachable and allows it, and the key exists), changing nothing.
        Returns the report: whether they're valid, and if not, the issues; never secrets."""
        operation = Tm1Operations.VALIDATE_ENCRYPTION_AT_REST_SETTINGS
        text = json.dumps(body_of(_require(settings, "settings")), separators=(",", ":"), ensure_ascii=False)
        path = ODataPath.function_import(_import_name(operation), {"Settings": _SETTINGS})
        request = Tm1Request.get(path, ODataQuery.none().alias(_SETTINGS, text), EncryptionAtRestValidationReport)
        return await self._session.execute(request.requires(operation.capability()))

    @endpoint("ActionImport ConfigureEncryptionAtRest")
    async def configure(self, settings: Any) -> None:
        """``ConfigureEncryptionAtRest``: validates the provider settings, then applies them; a
        server not yet encrypted is encrypted."""
        await self._call(Tm1Operations.CONFIGURE_ENCRYPTION_AT_REST, {"Settings": _require(settings, "settings")})

    @endpoint("ActionImport DisableEncryptionAtRest")
    async def disable(self) -> None:
        """``DisableEncryptionAtRest``: decrypts the model and stops encrypting it."""
        await self._call(Tm1Operations.DISABLE_ENCRYPTION_AT_REST, {})

    @endpoint("ActionImport RotateDataEncryptionKey")
    async def rotate_data_encryption_key(self) -> None:
        """``RotateDataEncryptionKey``: makes a new data encryption key and re-encrypts the whole
        model with it."""
        await self._call(Tm1Operations.ROTATE_DATA_ENCRYPTION_KEY, {})

    @endpoint("ActionImport RotateDataModelKey")
    async def rotate_data_model_key(self) -> None:
        """``RotateDataModelKey``: rotates the model key."""
        await self._call(Tm1Operations.ROTATE_DATA_MODEL_KEY, {})

    @endpoint("ActionImport EncryptDataFile")
    async def encrypt_file(self, source: str, destination: str, type: EncryptionFileType) -> None:
        """``EncryptDataFile`` (schema 11.3.0): encrypts one file, such as an exported cube.
        ``source`` is relative to the data directory or absolute; ``type`` says what the file is:
        a model object, a transaction log or an audit log."""
        await self._call(Tm1Operations.ENCRYPT_DATA_FILE, _file(source, destination, type))

    @endpoint("ActionImport DecryptDataFile")
    async def decrypt_file(self, source: str, destination: str, type: EncryptionFileType) -> None:
        """``DecryptDataFile`` (schema 11.3.0): decrypts one file."""
        await self._call(Tm1Operations.DECRYPT_DATA_FILE, _file(source, destination, type))

    @endpoint("ActionImport EncryptDataModel")
    async def encrypt_model(self, time_until_shutdown: float | timedelta | None = None) -> None:
        """``EncryptDataModel`` (schema 11.3.0): encrypts the model, then shuts the server down
        after ``time_until_shutdown`` (seconds), or TM1's wait if None.

        .. deprecated:: 11.8.35 Deprecated in TM1 schema 11.8.35: use :meth:`configure`."""
        await self._call(Tm1Operations.ENCRYPT_DATA_MODEL, _shutdown(time_until_shutdown))

    @endpoint("ActionImport DecryptDataModel")
    async def decrypt_model(self, time_until_shutdown: float | timedelta | None = None) -> None:
        """``DecryptDataModel`` (schema 11.3.0): decrypts the model, then shuts the server down.

        .. deprecated:: 11.8.35 Deprecated in TM1 schema 11.8.35: use :meth:`disable`."""
        await self._call(Tm1Operations.DECRYPT_DATA_MODEL, _shutdown(time_until_shutdown))

    async def _call(self, operation: Tm1Operation, parameters: dict[str, Any]) -> None:
        request = Tm1Request.post(ODataPath.of(_import_name(operation)), parameters, None)
        await self._session.execute(request.requires(operation.capability()))


def _file(source: str, destination: str, type: EncryptionFileType) -> dict[str, Any]:
    return {
        "SourcePath": _require(source, "source"),
        "DestinationPath": _require(destination, "destination"),
        "FileType": _require(type, "type").value,
    }


def _shutdown(time_until_shutdown: float | timedelta | None) -> dict[str, Any]:
    if time_until_shutdown is None:
        return {}
    value = (
        time_until_shutdown if isinstance(time_until_shutdown, timedelta) else timedelta(seconds=time_until_shutdown)
    )
    return {"TimeUntilShutdown": duration(value)}


def _import_name(operation: Tm1Operation) -> str:
    return _require(operation.import_name, operation.name)
