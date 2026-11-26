# SPDX-License-Identifier: Apache-2.0

"""Where each product comes from: Java's ``Tm1Source``, ``CognosSource``, ``Tm1WebSource``,
``PawSource`` and ``Tm1EngineSource``.

No IBM software is in the images, so a source supplies it: an image with it built in, an
installed folder, a Docker volume holding an install, a folder holding IBM's download, or a link
to the download. ``discover()`` reads the same settings as Java, in the same order; Java also
reads system properties, which Python doesn't have, so here they're environment variables only.
A blank setting counts as unset; one that's set but wrong raises :class:`ValueError` rather than
being skipped.
"""

from __future__ import annotations

import os
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import ClassVar
from urllib.parse import urlsplit

__all__ = ["CognosSource", "Lookup", "PawSource", "Tm1EngineSource", "Tm1Source", "Tm1WebSource", "setting"]

Lookup = Callable[[str], str | None] | Mapping[str, str]
"""How ``discover`` reads a setting: a function from its name to its value (``dict.get``), or a
mapping."""


def setting(name: str) -> str | None:
    """A setting, from the environment: Java's ``System.getProperty``, then ``System.getenv``."""
    return os.environ.get(name)


def _getter(lookup: Lookup | None) -> Callable[[str], str | None]:
    read: Callable[[str], str | None]
    if lookup is None:
        read = setting
    elif isinstance(lookup, Mapping):
        read = lookup.get
    else:
        read = lookup

    def get(name: str) -> str | None:
        value = read(name)
        return None if value is None or not value.strip() else value.strip()

    return get


def _require_text(value: str, name: str) -> None:
    if value is None or not str(value).strip():
        raise ValueError(f"{name} must not be blank")


def _absolute(path: Path | str) -> Path:
    if path is None:
        raise ValueError("dir must not be None")
    return Path(os.path.normpath(Path(path).expanduser().absolute()))


def _folder_and_file(self: object, dir_setting: str, file_setting: str, what: str) -> None:
    folder = _absolute(self.dir)  # type: ignore[attr-defined]
    object.__setattr__(self, "dir", folder)
    if not folder.is_dir():
        raise ValueError(f"{dir_setting} is not a folder: {folder}. Point it at the folder holding the {what}; name the file itself with {file_setting}.")
    file = self.file  # type: ignore[attr-defined]
    if file is not None and not file.strip():
        object.__setattr__(self, "file", None)
        file = None
    if file is not None and not (folder / file).is_file():
        raise ValueError(f"{file_setting} names {file}, which isn't in {folder}")


def _url_without_secrets(url: str) -> str:
    """Scheme, host, port and path: never the user info or the query (a signed URL's secret)."""
    parts = urlsplit(url)
    host = parts.netloc.rpartition("@")[2]
    return f"{parts.scheme}://{host}{parts.path}"


def _blank_to_none(self: object, name: str) -> None:
    value = getattr(self, name)
    if value is not None and not value.strip():
        object.__setattr__(self, name, None)


def _has_file(folder: Path, accept: Callable[[str], bool]) -> bool:
    if not folder.is_dir():
        return False
    try:
        return any(accept(child.name) for child in folder.iterdir())
    except OSError as e:
        raise OSError(f"Couldn't list {folder}") from e


# --- TM1 ---------------------------------------------------------------------------------------


class Tm1Source:
    """Where TM1 Server comes from, as the ``tm1-server`` image takes it. One of
    :class:`Tm1Source.PrivateImage`, :class:`Tm1Source.InstallDir`,
    :class:`Tm1Source.InstallVolume`, :class:`Tm1Source.Bundle` or :class:`Tm1Source.BundleUrl`."""

    PRIVATE_IMAGE: ClassVar[str] = "TM1_PRIVATE_IMAGE"
    HOME_DIR: ClassVar[str] = "TM1_HOME_DIR"
    INSTALL_VOLUME: ClassVar[str] = "TM1_INSTALL_VOLUME"
    BUNDLE_DIR: ClassVar[str] = "TM1_BUNDLE_DIR"
    BUNDLE: ClassVar[str] = "TM1_BUNDLE"
    BUNDLE_URL: ClassVar[str] = "TM1_BUNDLE_URL"
    BUNDLE_SHA256: ClassVar[str] = "TM1_BUNDLE_SHA256"

    PrivateImage: ClassVar[type[_Tm1PrivateImage]]
    InstallDir: ClassVar[type[_Tm1InstallDir]]
    InstallVolume: ClassVar[type[_Tm1InstallVolume]]
    Bundle: ClassVar[type[_Tm1Bundle]]
    BundleUrl: ClassVar[type[_Tm1BundleUrl]]

    def describe(self) -> str:
        """For logs and messages: never a secret."""
        raise NotImplementedError

    @staticmethod
    def discover(lookup: Lookup | None = None) -> Tm1Source | None:
        """The first source set, in the image's order: ``TM1_PRIVATE_IMAGE``, ``TM1_HOME_DIR``,
        ``TM1_INSTALL_VOLUME``, ``TM1_BUNDLE_DIR`` (with ``TM1_BUNDLE``), ``TM1_BUNDLE_URL`` (with
        ``TM1_BUNDLE_SHA256``); None if none is."""
        get = _getter(lookup)
        if (image := get(Tm1Source.PRIVATE_IMAGE)) is not None:
            return Tm1Source.PrivateImage(image)
        if (home := get(Tm1Source.HOME_DIR)) is not None:
            return Tm1Source.InstallDir(Path(home))
        if (volume := get(Tm1Source.INSTALL_VOLUME)) is not None:
            return Tm1Source.InstallVolume(volume)
        if (folder := get(Tm1Source.BUNDLE_DIR)) is not None:
            return Tm1Source.Bundle(Path(folder), get(Tm1Source.BUNDLE))
        if (url := get(Tm1Source.BUNDLE_URL)) is not None:
            return Tm1Source.BundleUrl(url, get(Tm1Source.BUNDLE_SHA256))
        return None


@dataclass(frozen=True)
class _Tm1PrivateImage(Tm1Source):
    """An image with TM1 already in it."""

    image: str

    def __post_init__(self) -> None:
        _require_text(self.image, "image")

    def describe(self) -> str:
        return f"private image {self.image}"


@dataclass(frozen=True)
class _Tm1InstallDir(Tm1Source):
    """An installed ``tm1_64`` folder, mounted read-only."""

    dir: Path

    def __post_init__(self) -> None:
        object.__setattr__(self, "dir", _absolute(self.dir))
        if not (self.dir / "bin64" / "tm1s.exe").is_file():
            raise ValueError(
                f"Not a TM1 install: {self.dir} has no bin64/tm1s.exe. Point {Tm1Source.HOME_DIR} at the tm1_64 folder of a Linux TM1 Server install."
            )

    def describe(self) -> str:
        return f"install folder {self.dir}"


@dataclass(frozen=True)
class _Tm1InstallVolume(Tm1Source):
    """A Docker volume holding a TM1 install."""

    name: str

    def __post_init__(self) -> None:
        _require_text(self.name, "name")

    def describe(self) -> str:
        return f"install volume {self.name}"


@dataclass(frozen=True)
class _Tm1Bundle(Tm1Source):
    """A folder holding IBM's TM1 bundle: the newest, or ``file`` within it."""

    dir: Path
    file: str | None = None

    def __post_init__(self) -> None:
        _folder_and_file(self, Tm1Source.BUNDLE_DIR, Tm1Source.BUNDLE, "bundle")

    def describe(self) -> str:
        return "bundle " + (str(self.dir / self.file) if self.file is not None else f"{self.dir} (newest)")


@dataclass(frozen=True)
class _Tm1BundleUrl(Tm1Source):
    """A link to IBM's TM1 bundle, downloaded into the cache volume once."""

    url: str
    sha256: str | None = None

    def __post_init__(self) -> None:
        _require_text(self.url, "url")
        _blank_to_none(self, "sha256")

    def describe(self) -> str:
        return "bundle URL " + _url_without_secrets(self.url)


for _name, _cls in {
    "PrivateImage": _Tm1PrivateImage,
    "InstallDir": _Tm1InstallDir,
    "InstallVolume": _Tm1InstallVolume,
    "Bundle": _Tm1Bundle,
    "BundleUrl": _Tm1BundleUrl,
}.items():
    _cls.__name__, _cls.__qualname__ = _name, f"Tm1Source.{_name}"
    setattr(Tm1Source, _name, _cls)


# --- Cognos ------------------------------------------------------------------------------------


class CognosSource:
    """Where Cognos Analytics comes from, as the ``cognos-server`` image takes it. One of
    :class:`CognosSource.PrivateImage`, :class:`CognosSource.InstallVolume`,
    :class:`CognosSource.Zip` or :class:`CognosSource.ZipUrl`."""

    PRIVATE_IMAGE: ClassVar[str] = "COGNOS_PRIVATE_IMAGE"
    INSTALL_VOLUME: ClassVar[str] = "COGNOS_INSTALL_VOLUME"
    ZIP_DIR: ClassVar[str] = "COGNOS_ZIP_DIR"
    ZIP: ClassVar[str] = "COGNOS_ZIP"
    ZIP_URL: ClassVar[str] = "COGNOS_ZIP_URL"
    ZIP_SHA256: ClassVar[str] = "COGNOS_ZIP_SHA256"

    PrivateImage: ClassVar[type[_CognosPrivateImage]]
    InstallVolume: ClassVar[type[_CognosInstallVolume]]
    Zip: ClassVar[type[_CognosZip]]
    ZipUrl: ClassVar[type[_CognosZipUrl]]

    def describe(self) -> str:
        """A short description for logs and messages. Never includes a URL's query string."""
        raise NotImplementedError

    @staticmethod
    def discover(lookup: Lookup | None = None) -> CognosSource | None:
        """The first source set: ``COGNOS_PRIVATE_IMAGE``, ``COGNOS_INSTALL_VOLUME``,
        ``COGNOS_ZIP_DIR`` (with ``COGNOS_ZIP``), ``COGNOS_ZIP_URL`` (with ``COGNOS_ZIP_SHA256``),
        then ``TM1_BUNDLE_DIR`` when it holds a ``casrv-*-linuxi38664h.zip``."""
        get = _getter(lookup)
        if (image := get(CognosSource.PRIVATE_IMAGE)) is not None:
            return CognosSource.PrivateImage(image)
        if (volume := get(CognosSource.INSTALL_VOLUME)) is not None:
            return CognosSource.InstallVolume(volume)
        if (folder := get(CognosSource.ZIP_DIR)) is not None:
            return CognosSource.Zip(Path(folder), get(CognosSource.ZIP))
        if (url := get(CognosSource.ZIP_URL)) is not None:
            return CognosSource.ZipUrl(url, get(CognosSource.ZIP_SHA256))
        # The TM1 downloads folder, when it holds Cognos too. Skipped quietly when it doesn't:
        # that folder is TM1's setting, and is valid without Cognos in it.
        tm1_dir = get(Tm1Source.BUNDLE_DIR)
        if tm1_dir is not None and (
            get(CognosSource.ZIP) is not None or _has_file(Path(tm1_dir), lambda n: n.startswith("casrv-") and n.endswith("-linuxi38664h.zip"))
        ):
            return CognosSource.Zip(Path(tm1_dir), get(CognosSource.ZIP))
        return None


@dataclass(frozen=True)
class _CognosPrivateImage(CognosSource):
    image: str

    def __post_init__(self) -> None:
        _require_text(self.image, "image")

    def describe(self) -> str:
        return f"private image {self.image}"


@dataclass(frozen=True)
class _CognosInstallVolume(CognosSource):
    name: str

    def __post_init__(self) -> None:
        _require_text(self.name, "name")

    def describe(self) -> str:
        return f"install volume {self.name}"


@dataclass(frozen=True)
class _CognosZip(CognosSource):
    dir: Path
    file: str | None = None

    def __post_init__(self) -> None:
        _folder_and_file(self, CognosSource.ZIP_DIR, CognosSource.ZIP, "zip")

    def describe(self) -> str:
        return "zip " + (str(self.dir / self.file) if self.file is not None else f"{self.dir} (newest)")


@dataclass(frozen=True)
class _CognosZipUrl(CognosSource):
    url: str
    sha256: str | None = None

    def __post_init__(self) -> None:
        _require_text(self.url, "url")
        _blank_to_none(self, "sha256")

    def describe(self) -> str:
        return "zip URL " + _url_without_secrets(self.url)


for _name, _cls in {
    "PrivateImage": _CognosPrivateImage,
    "InstallVolume": _CognosInstallVolume,
    "Zip": _CognosZip,
    "ZipUrl": _CognosZipUrl,
}.items():
    _cls.__name__, _cls.__qualname__ = _name, f"CognosSource.{_name}"
    setattr(CognosSource, _name, _cls)


# --- TM1 Web -----------------------------------------------------------------------------------


class Tm1WebSource:
    """Where TM1 Web comes from, as the ``tm1-web`` image takes it: the same kinds as
    :class:`Tm1Source`, from ``TM1_WEB_*`` settings."""

    PRIVATE_IMAGE: ClassVar[str] = "TM1_WEB_PRIVATE_IMAGE"
    HOME_DIR: ClassVar[str] = "TM1_WEB_HOME_DIR"
    INSTALL_VOLUME: ClassVar[str] = "TM1_WEB_INSTALL_VOLUME"
    BUNDLE_DIR: ClassVar[str] = "TM1_WEB_BUNDLE_DIR"
    BUNDLE: ClassVar[str] = "TM1_WEB_BUNDLE"
    BUNDLE_URL: ClassVar[str] = "TM1_WEB_BUNDLE_URL"
    BUNDLE_SHA256: ClassVar[str] = "TM1_WEB_BUNDLE_SHA256"

    PrivateImage: ClassVar[type[_WebPrivateImage]]
    InstallDir: ClassVar[type[_WebInstallDir]]
    InstallVolume: ClassVar[type[_WebInstallVolume]]
    Bundle: ClassVar[type[_WebBundle]]
    BundleUrl: ClassVar[type[_WebBundleUrl]]

    def describe(self) -> str:
        """A short description for logs and messages. Never includes a URL's query string."""
        raise NotImplementedError

    @staticmethod
    def discover(lookup: Lookup | None = None) -> Tm1WebSource | None:
        """The first source set, as :meth:`Tm1Source.discover` with ``TM1_WEB_*``, then
        ``TM1_BUNDLE_DIR`` when it holds a ``tm1web-*`` bundle."""
        get = _getter(lookup)
        if (image := get(Tm1WebSource.PRIVATE_IMAGE)) is not None:
            return Tm1WebSource.PrivateImage(image)
        if (home := get(Tm1WebSource.HOME_DIR)) is not None:
            return Tm1WebSource.InstallDir(Path(home))
        if (volume := get(Tm1WebSource.INSTALL_VOLUME)) is not None:
            return Tm1WebSource.InstallVolume(volume)
        if (folder := get(Tm1WebSource.BUNDLE_DIR)) is not None:
            return Tm1WebSource.Bundle(Path(folder), get(Tm1WebSource.BUNDLE))
        if (url := get(Tm1WebSource.BUNDLE_URL)) is not None:
            return Tm1WebSource.BundleUrl(url, get(Tm1WebSource.BUNDLE_SHA256))
        # The TM1 downloads folder, when it holds TM1 Web too. Skipped quietly when it doesn't:
        # that folder is TM1's setting, and is valid without TM1 Web in it.
        tm1_dir = get(Tm1Source.BUNDLE_DIR)
        if tm1_dir is not None and (
            get(Tm1WebSource.BUNDLE) is not None
            or _has_file(
                Path(tm1_dir),
                lambda n: n.startswith("tm1web-") and (n.endswith("_bundle.tar.gz") or n.endswith("-linuxi38664h.zip")),
            )
        ):
            return Tm1WebSource.Bundle(Path(tm1_dir), get(Tm1WebSource.BUNDLE))
        return None


@dataclass(frozen=True)
class _WebPrivateImage(Tm1WebSource):
    image: str

    def __post_init__(self) -> None:
        _require_text(self.image, "image")

    def describe(self) -> str:
        return f"private image {self.image}"


@dataclass(frozen=True)
class _WebInstallDir(Tm1WebSource):
    dir: Path

    def __post_init__(self) -> None:
        object.__setattr__(self, "dir", _absolute(self.dir))
        if not (self.dir / "wlp" / "bin" / "server").is_file():
            raise ValueError(
                f"Not a TM1 Web install: {self.dir} has no wlp/bin/server. Point {Tm1WebSource.HOME_DIR} at the tm1web folder of a Linux TM1 Web install."
            )

    def describe(self) -> str:
        return f"install folder {self.dir}"


@dataclass(frozen=True)
class _WebInstallVolume(Tm1WebSource):
    name: str

    def __post_init__(self) -> None:
        _require_text(self.name, "name")

    def describe(self) -> str:
        return f"install volume {self.name}"


@dataclass(frozen=True)
class _WebBundle(Tm1WebSource):
    dir: Path
    file: str | None = None

    def __post_init__(self) -> None:
        _folder_and_file(self, Tm1WebSource.BUNDLE_DIR, Tm1WebSource.BUNDLE, "bundle")

    def describe(self) -> str:
        return "bundle " + (str(self.dir / self.file) if self.file is not None else f"{self.dir} (newest)")


@dataclass(frozen=True)
class _WebBundleUrl(Tm1WebSource):
    url: str
    sha256: str | None = None

    def __post_init__(self) -> None:
        _require_text(self.url, "url")
        _blank_to_none(self, "sha256")

    def describe(self) -> str:
        return "bundle URL " + _url_without_secrets(self.url)


for _name, _cls in {
    "PrivateImage": _WebPrivateImage,
    "InstallDir": _WebInstallDir,
    "InstallVolume": _WebInstallVolume,
    "Bundle": _WebBundle,
    "BundleUrl": _WebBundleUrl,
}.items():
    _cls.__name__, _cls.__qualname__ = _name, f"Tm1WebSource.{_name}"
    setattr(Tm1WebSource, _name, _cls)


# --- PAW ---------------------------------------------------------------------------------------

_PAW_PREFIX = "ipa_workspace_local_"


@dataclass(frozen=True)
class PawSource:
    """IBM's Planning Analytics Workspace Local zip, ``ipa_workspace_local_<release>.<build>.zip``."""

    zip: Path

    ZIP: ClassVar[str] = "PAW_ZIP"
    BUNDLE_DIR: ClassVar[str] = "PAW_BUNDLE_DIR"

    def __post_init__(self) -> None:
        object.__setattr__(self, "zip", _absolute(self.zip))
        if not self.zip.is_file():
            raise ValueError(
                f"Not a file: {self.zip}. Point {PawSource.ZIP} at IBM's ipa_workspace_local_<release>.<build>.zip, or {PawSource.BUNDLE_DIR} at its folder."
            )

    def describe(self) -> str:
        """A short description for logs and messages."""
        return f"PAW Local zip {self.zip}"

    @staticmethod
    def discover(lookup: Lookup | None = None) -> PawSource | None:
        """``PAW_ZIP``, or the newest ``ipa_workspace_local_*.zip`` in ``PAW_BUNDLE_DIR``."""
        get = _getter(lookup)
        if (zip_file := get(PawSource.ZIP)) is not None:
            return PawSource(Path(zip_file))
        if (folder := get(PawSource.BUNDLE_DIR)) is not None:
            return PawSource(_newest_zip(Path(folder)))
        return None


def _newest_zip(folder: Path) -> Path:
    if not folder.is_dir():
        raise ValueError(f"{PawSource.BUNDLE_DIR} is not a folder: {folder}")
    try:
        zips = [f for f in folder.iterdir() if f.name.startswith(_PAW_PREFIX) and f.name.endswith(".zip") and f.is_file()]
    except OSError as e:
        raise OSError(f"Couldn't list {folder}") from e
    if not zips:
        raise ValueError(f"{PawSource.BUNDLE_DIR} has no {_PAW_PREFIX}*.zip: {_absolute(folder)}")
    return max(zips, key=lambda f: f.stat().st_mtime)


# --- TM1 v12 -----------------------------------------------------------------------------------


class Tm1EngineSource:
    """Where a :class:`Tm1EngineContainer` gets TM1 v12 from, as the ``tm1-engine`` image takes it.
    One of :class:`Tm1EngineSource.PrivateImage`, :class:`Tm1EngineSource.InstallDir`,
    :class:`Tm1EngineSource.InstallVolume`, :class:`Tm1EngineSource.Bundle` or
    :class:`Tm1EngineSource.BundleUrl`. The image contains no IBM software, so every container
    needs one. For TM1 11, see :class:`Tm1Source`."""

    PRIVATE_IMAGE: ClassVar[str] = "TM1_ENGINE_PRIVATE_IMAGE"
    HOME_DIR: ClassVar[str] = "TM1_ENGINE_HOME_DIR"
    INSTALL_VOLUME: ClassVar[str] = "TM1_ENGINE_INSTALL_VOLUME"
    BUNDLE_DIR: ClassVar[str] = "TM1_ENGINE_BUNDLE_DIR"
    BUNDLE: ClassVar[str] = "TM1_ENGINE_BUNDLE"
    BUNDLE_URL: ClassVar[str] = "TM1_ENGINE_BUNDLE_URL"
    BUNDLE_SHA256: ClassVar[str] = "TM1_ENGINE_BUNDLE_SHA256"

    PrivateImage: ClassVar[type[_EnginePrivateImage]]
    InstallDir: ClassVar[type[_EngineInstallDir]]
    InstallVolume: ClassVar[type[_EngineInstallVolume]]
    Bundle: ClassVar[type[_EngineBundle]]
    BundleUrl: ClassVar[type[_EngineBundleUrl]]

    def describe(self) -> str:
        """A short description for logs and messages. Never includes a URL's query string."""
        raise NotImplementedError

    @staticmethod
    def discover(lookup: Lookup | None = None) -> Tm1EngineSource | None:
        """The first source set, in this order: ``TM1_ENGINE_PRIVATE_IMAGE``, ``TM1_ENGINE_HOME_DIR``,
        ``TM1_ENGINE_INSTALL_VOLUME``, ``TM1_ENGINE_BUNDLE_DIR`` (with ``TM1_ENGINE_BUNDLE``),
        ``TM1_ENGINE_BUNDLE_URL`` (with ``TM1_ENGINE_BUNDLE_SHA256``), then ``TM1_BUNDLE_DIR``, TM1 11's
        downloads folder, when it holds a ``tm1-v*-standalone-linux.tgz`` too: one folder then
        serves both versions. None if none is."""
        get = _getter(lookup)
        if (image := get(Tm1EngineSource.PRIVATE_IMAGE)) is not None:
            return Tm1EngineSource.PrivateImage(image)
        if (home := get(Tm1EngineSource.HOME_DIR)) is not None:
            return Tm1EngineSource.InstallDir(Path(home))
        if (volume := get(Tm1EngineSource.INSTALL_VOLUME)) is not None:
            return Tm1EngineSource.InstallVolume(volume)
        if (folder := get(Tm1EngineSource.BUNDLE_DIR)) is not None:
            return Tm1EngineSource.Bundle(Path(folder), get(Tm1EngineSource.BUNDLE))
        if (url := get(Tm1EngineSource.BUNDLE_URL)) is not None:
            return Tm1EngineSource.BundleUrl(url, get(Tm1EngineSource.BUNDLE_SHA256))
        # The TM1 downloads folder, when it holds TM1 v12 too. Skipped quietly when it doesn't:
        # that folder is TM1 11's setting, and is valid without v12 in it.
        tm1_dir = get(Tm1Source.BUNDLE_DIR)
        if tm1_dir is not None and (
            get(Tm1EngineSource.BUNDLE) is not None or _has_file(Path(tm1_dir), lambda n: n.startswith("tm1-v") and n.endswith("-standalone-linux.tgz"))
        ):
            return Tm1EngineSource.Bundle(Path(tm1_dir), get(Tm1EngineSource.BUNDLE))
        return None


@dataclass(frozen=True)
class _EnginePrivateImage(Tm1EngineSource):
    """An image built on ``tm1-engine`` with TM1 inside, from a private registry. It contains IBM
    software, so it must never be pushed anywhere public."""

    image: str

    def __post_init__(self) -> None:
        _require_text(self.image, "image")

    def describe(self) -> str:
        return f"private image {self.image}"


@dataclass(frozen=True)
class _EngineInstallDir(Tm1EngineSource):
    """A TM1 v12 install the ``tm1-engine`` image made (``install`` as its command), as a folder:
    the one containing ``bin/tm1sd.exe``. Mounted read-only. An install made by IBM's installer
    isn't one."""

    dir: Path

    def __post_init__(self) -> None:
        object.__setattr__(self, "dir", _absolute(self.dir))
        if not (self.dir / "bin" / "tm1sd.exe").is_file():
            raise ValueError(
                f"Not a TM1 v12 install: {self.dir} has no bin/tm1sd.exe. Point {Tm1EngineSource.HOME_DIR} at an install the tm1-engine image made."
            )

    def describe(self) -> str:
        return f"install folder {self.dir}"


@dataclass(frozen=True)
class _EngineInstallVolume(Tm1EngineSource):
    """A named Docker volume holding an install the ``tm1-engine`` image made. If the volume is
    empty, the container fails to start, since there's nothing to install from."""

    name: str

    def __post_init__(self) -> None:
        _require_text(self.name, "name")

    def describe(self) -> str:
        return f"install volume {self.name}"


@dataclass(frozen=True)
class _EngineBundle(Tm1EngineSource):
    """A folder holding IBM's ``tm1-v<version>-standalone-linux.tgz``, mounted read-only: the
    newest, or ``file`` within it. The image installs TM1 from it on first start into a shared
    cache volume, so later starts are fast. The folder can hold TM1 11, TM1 Web and Cognos
    downloads too: each image picks its own."""

    dir: Path
    file: str | None = None

    def __post_init__(self) -> None:
        _folder_and_file(self, Tm1EngineSource.BUNDLE_DIR, Tm1EngineSource.BUNDLE, "package")

    def describe(self) -> str:
        return "package " + (str(self.dir / self.file) if self.file is not None else f"{self.dir} (newest)")


@dataclass(frozen=True)
class _EngineBundleUrl(Tm1EngineSource):
    """A download link for IBM's package, such as a pre-signed S3, R2 or GCS URL. Links can carry
    credentials in the query string or the user-info part, so neither is ever logged."""

    url: str
    sha256: str | None = None

    def __post_init__(self) -> None:
        _require_text(self.url, "url")
        _blank_to_none(self, "sha256")

    def describe(self) -> str:
        return "package URL " + _url_without_secrets(self.url)


for _name, _cls in {
    "PrivateImage": _EnginePrivateImage,
    "InstallDir": _EngineInstallDir,
    "InstallVolume": _EngineInstallVolume,
    "Bundle": _EngineBundle,
    "BundleUrl": _EngineBundleUrl,
}.items():
    _cls.__name__, _cls.__qualname__ = _name, f"Tm1EngineSource.{_name}"
    setattr(Tm1EngineSource, _name, _cls)
