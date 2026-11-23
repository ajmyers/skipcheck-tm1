# SPDX-License-Identifier: Apache-2.0

"""Versions and capabilities, as the Java client's ``io.skipcheck.tm1.client.version``:
:class:`Tm1Version`, :class:`Tm1Capability` (something a request needs the server to have),
:class:`Tm1Capabilities` (what a server's own ``$metadata`` has) and :class:`Tm1Dialect`
(how an address becomes a service root)."""

from __future__ import annotations

import enum
import functools
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field, replace
from typing import Any
from urllib.parse import urlsplit

from .errors import Tm1ProtocolError, Tm1UnsupportedOperationError

__all__ = ["Tm1Capabilities", "Tm1Capability", "Tm1Dialect", "Tm1Version"]

_FORMAT = re.compile(r"\d+(\.\d+)*\Z")


@functools.total_ordering
class Tm1Version:
    """A TM1 version, such as ``11.8.03500.6`` or schema ``11.8.35``: compared part by part,
    missing parts as 0 (``11.8`` == ``11.8.0``)."""

    __slots__ = ("_text", "_parts")

    def __init__(self, text: str, parts: tuple[int, ...]):
        self._text = text
        self._parts = parts

    @staticmethod
    def parse(text: str) -> Tm1Version:
        """Parses a version such as ``11.8.03500.6``. Surrounding whitespace is ignored; a
        ValueError if the text isn't dot-separated numbers."""
        trimmed = text.strip()
        if not _FORMAT.match(trimmed):
            raise ValueError(f"Not a TM1 version: {text!r} (expected numbers separated by dots, like 11.8.03500.6)")
        parts = []
        for part in trimmed.split("."):
            value = int(part)
            if value > 2**31 - 1:
                raise ValueError(f"Not a TM1 version: {text!r} (part {part!r} is too large)")
            parts.append(value)
        return Tm1Version(trimmed, tuple(parts))

    def _key(self) -> tuple[int, ...]:
        parts = list(self._parts)
        while parts and parts[-1] == 0:
            parts.pop()
        return tuple(parts)

    def is_at_least(self, other: Tm1Version | str) -> bool:
        """True if this version is the same as or newer than ``other``."""
        return self >= _version(other)

    def is_before(self, other: Tm1Version | str) -> bool:
        """True if this version is older than ``other``."""
        return self < _version(other)

    def __eq__(self, other: object) -> bool:
        return isinstance(other, Tm1Version) and self._key() == other._key()

    def __lt__(self, other: Tm1Version) -> bool:
        a, b = self._parts, other._parts
        n = max(len(a), len(b))
        return a + (0,) * (n - len(a)) < b + (0,) * (n - len(b))

    def __hash__(self) -> int:
        return hash(self._key())

    def __str__(self) -> str:
        return self._text

    def __repr__(self) -> str:
        return f"Tm1Version({self._text!r})"


def _version(v: Tm1Version | str) -> Tm1Version:
    return v if isinstance(v, Tm1Version) else Tm1Version.parse(v)


def _simple(qualified: str) -> str:
    return qualified[qualified.rfind(".") + 1 :]


@dataclass(frozen=True)
class Tm1Capability:
    """Something a request needs the server's ``$metadata`` to define: an entity set, a
    singleton, an operation (bound to a type, or imported) or a property of a type."""

    class Kind(enum.Enum):
        """The kinds of element a request can need."""

        ENTITY_SET = "entity-set"
        SINGLETON = "singleton"
        BOUND_OPERATION = "bound-operation"
        UNBOUND_OPERATION = "unbound-operation"
        PROPERTY = "property"

    kind: Tm1Capability.Kind
    type: str | None
    name: str
    added_in: str | None = None

    def __post_init__(self) -> None:
        if self.kind in (Tm1Capability.Kind.BOUND_OPERATION, Tm1Capability.Kind.PROPERTY) and self.type is None:
            raise ValueError(f"{self.kind.name} needs the type it belongs to")

    @staticmethod
    def entity_set(name: str) -> Tm1Capability:
        """An entity set, by its name, such as ``GitPlans``."""
        return Tm1Capability(Tm1Capability.Kind.ENTITY_SET, None, name)

    @staticmethod
    def singleton(name: str) -> Tm1Capability:
        """A singleton, by its name, such as ``ActiveSession``."""
        return Tm1Capability(Tm1Capability.Kind.SINGLETON, None, name)

    @staticmethod
    def bound_operation(binding_type: str, operation: str) -> Tm1Capability:
        """An action or function bound to a type, or to one of its base types: the qualified type,
        such as ``tm1.Cube``, and the qualified operation name, such as ``tm1.Reserve``."""
        return Tm1Capability(Tm1Capability.Kind.BOUND_OPERATION, binding_type, operation)

    @staticmethod
    def unbound_operation(name: str) -> Tm1Capability:
        """An action or function import, invoked from the service root, by the import's name, such
        as ``ExecuteMDX``."""
        return Tm1Capability(Tm1Capability.Kind.UNBOUND_OPERATION, None, name)

    @staticmethod
    def property(type: str, prop: str) -> Tm1Capability:
        """A structural or navigation property of a type, or of one of its base types: the qualified
        type, such as ``tm1.Cube``, and the property's name, such as ``Rules``."""
        return Tm1Capability(Tm1Capability.Kind.PROPERTY, type, prop)

    def since(self, schema_version: str) -> Tm1Capability:
        """A copy recording the schema version that added it: the version from ``Core.Revisions``,
        such as ``11.8.34``."""
        return replace(self, added_in=schema_version)

    def added_in_version(self) -> str | None:
        """The schema version that added it, or None if not known."""
        return self.added_in

    def describe(self) -> str:
        """How it reads in messages, with aliases and namespaces dropped: ``Cube.Reserve``, ``entity
        set GitPlans``."""
        k = Tm1Capability.Kind
        if self.kind is k.ENTITY_SET:
            return "entity set " + self.name
        if self.kind is k.SINGLETON:
            return "singleton " + self.name
        if self.kind is k.UNBOUND_OPERATION:
            return "operation " + self.name
        return f"{_simple(self.type or '')}.{_simple(self.name)}"

    def __eq__(self, other: object) -> bool:
        # Equal whatever version it was added in, as in Java (the record compares all four,
        # but tests compare capabilities made with and without since(); keep it strict)
        return isinstance(other, Tm1Capability) and (self.kind, self.type, self.name, self.added_in) == (other.kind, other.type, other.name, other.added_in)

    def __hash__(self) -> int:
        return hash((self.kind, self.type, self.name, self.added_in))


_EDM = "{http://docs.oasis-open.org/odata/ns/edm}"


@dataclass
class _Type:
    name: str
    base: str | None
    members: set[str] = field(default_factory=set)


class Tm1Capabilities:
    """What a server's ``$metadata`` defines, and its versions: what requests check before
    they're sent, as the Java client's ``Tm1Capabilities``."""

    def __init__(
        self,
        schema_version: str | None,
        product_version: Tm1Version | None,
        endpoints: frozenset[str],
        types: dict[str, _Type],
        bound: dict[str, set[str]],
        container: dict[str, set[str]],
        aliases: dict[str, str],
    ):
        self._schema_version = schema_version
        self._product_version = product_version
        self.endpoints = endpoints
        self._types = types
        self._bound = bound
        self._container = container
        self._aliases = aliases

    @staticmethod
    def parse(metadata: bytes, product_version: Tm1Version | None = None) -> Tm1Capabilities:
        """Reads a ``$metadata`` document; its endpoints are made the way the spec's inventory
        is (``Endpoints`` in the Java client). Whatever stops it being read is a
        :class:`~skipcheck_tm1.errors.Tm1ProtocolError`, never the parser's own error, so a caller
        of a session has one error to catch for a server that answers ``$metadata`` with
        something else. An operation marked bound that has no binding parameter isn't an error
        here, as it is in the Java client, whose model refuses it: it's left out."""
        try:
            return Tm1Capabilities._parse(metadata, product_version)
        except Tm1ProtocolError:
            raise
        except Exception as e:  # noqa: BLE001 (whatever it is, it's a document this client can't read)
            raise Tm1ProtocolError(f"TM1 sent $metadata that isn't valid CSDL: {e}") from e

    @staticmethod
    def _parse(metadata: bytes, product_version: Tm1Version | None) -> Tm1Capabilities:
        try:
            root = ET.fromstring(metadata)
        except (ET.ParseError, LookupError, ValueError) as e:
            # Not only ParseError: the parser raises LookupError or ValueError for an encoding it doesn't know or can't read
            raise Tm1ProtocolError(f"TM1's $metadata isn't XML: {e}") from e
        endpoints: set[str] = set()
        types: dict[str, _Type] = {}
        bound: dict[str, set[str]] = {}
        container: dict[str, set[str]] = {"EntitySet": set(), "Singleton": set(), "Import": set()}
        aliases: dict[str, str] = {}
        version = None
        schemas = list(root.iter(f"{_EDM}Schema"))
        if not schemas:
            raise Tm1ProtocolError("TM1 sent $metadata that isn't valid CSDL")
        for schema in schemas:
            if schema.get("Alias"):
                aliases[schema.get("Alias", "")] = schema.get("Namespace", "")

        def qualify(name: str) -> str:
            prefix, _, rest = name.rpartition(".")
            return f"{aliases.get(prefix, prefix)}.{rest}" if prefix else name

        for schema in schemas:
            ns = schema.get("Namespace", "")
            for annotation in schema.findall(f"{_EDM}Annotation"):
                if annotation.get("Term", "").endswith("SchemaVersion"):
                    text = annotation.get("String") or "".join(annotation.itertext()).strip()
                    version = text or version
            for kind in ("Action", "Function"):
                for op in schema.findall(f"{_EDM}{kind}"):
                    if op.get("IsBound") != "true":
                        continue
                    params = op.findall(f"{_EDM}Parameter")
                    if not params:
                        continue
                    binding = params[0].get("Type", "")
                    collection = binding.startswith("Collection(")
                    inner = binding.removeprefix("Collection(").removesuffix(")")
                    simple = _simple(inner)
                    endpoints.add(f"{kind} {'Collection(' + simple + ')' if collection else simple}/{op.get('Name')}")
                    bound.setdefault(qualify(inner), set()).add(op.get("Name", ""))
            for tag in ("EntityType", "ComplexType"):
                for t in schema.findall(f"{_EDM}{tag}"):
                    q = f"{ns}.{t.get('Name')}"
                    base = t.get("BaseType")
                    info = types.setdefault(q, _Type(q, qualify(base) if base else None))
                    for p in t.findall(f"{_EDM}Property"):
                        info.members.add(p.get("Name", ""))
                    for nav in t.findall(f"{_EDM}NavigationProperty"):
                        info.members.add(nav.get("Name", ""))
                        endpoints.add(f"Navigation {t.get('Name')}/{nav.get('Name')}")
            for c in schema.findall(f"{_EDM}EntityContainer"):
                for tag, kind in (
                    ("EntitySet", "EntitySet"),
                    ("Singleton", "Singleton"),
                    ("ActionImport", "ActionImport"),
                    ("FunctionImport", "FunctionImport"),
                ):
                    for item in c.findall(f"{_EDM}{tag}"):
                        endpoints.add(f"{kind} {item.get('Name')}")
                        container["Import" if "Import" in kind else kind].add(item.get("Name", ""))
        return Tm1Capabilities(version, product_version, frozenset(endpoints), types, bound, container, aliases)

    def with_product_version(self, product_version: Tm1Version | None) -> Tm1Capabilities:
        """A copy with the server's product version, which ``$metadata`` doesn't say, for messages."""
        return Tm1Capabilities(self._schema_version, product_version, self.endpoints, self._types, self._bound, self._container, self._aliases)

    @property
    def schema_version(self) -> str | None:
        """The REST API schema version the server reports in ``Core.SchemaVersion``, such as
        ``11.8.35``, or None if it reports none. Not the product version: a different number."""
        return self._schema_version

    @property
    def product_version(self) -> Tm1Version | None:
        """The server's product version, such as ``11.8.03500.6``, or None if not known."""
        return self._product_version

    def _qualify(self, name: str) -> str:
        prefix, _, rest = name.rpartition(".")
        return f"{self._aliases.get(prefix, prefix)}.{rest}" if prefix else name

    def _hierarchy(self, type_name: str) -> list[_Type]:
        out = []
        current = self._types.get(self._qualify(type_name))
        while current is not None:
            out.append(current)
            current = self._types.get(current.base) if current.base else None
        return out

    def supports(self, capability: Tm1Capability | str) -> bool:
        """Whether the server defines it: a capability, or an endpoint ID of the spec."""
        if isinstance(capability, str):
            return capability in self.endpoints
        k = Tm1Capability.Kind
        if capability.kind is k.ENTITY_SET:
            return capability.name in self._container["EntitySet"]
        if capability.kind is k.SINGLETON:
            return capability.name in self._container["Singleton"]
        if capability.kind is k.UNBOUND_OPERATION:
            return capability.name in self._container["Import"]
        assert capability.type is not None
        if capability.kind is k.BOUND_OPERATION:
            name = _simple(capability.name)
            return any(name in self._bound.get(t.name, set()) for t in self._hierarchy(capability.type))
        return any(capability.name in t.members for t in self._hierarchy(capability.type))

    def check(self, capability: Tm1Capability, server: str) -> Tm1UnsupportedOperationError | None:
        """None if the server has it; otherwise the error saying what's missing, as Java's."""
        if self.supports(capability):
            return None
        missing = capability.describe()
        message = f"{missing} isn't defined by the TM1 server at {server}"
        added = capability.added_in_version()
        if added:
            message += f": it was added in schema {added}, and "
        else:
            message += " ("
        message += f"the server reports schema {self._schema_version}" if self._schema_version else "the server reports no schema version"
        if self._product_version is not None:
            message += f", product version {self._product_version}"
        if not added:
            message += ")"
        return Tm1UnsupportedOperationError(message, missing)

    def __repr__(self) -> str:
        return f"Tm1Capabilities[schema {self._schema_version or 'unknown'}, product {self._product_version or 'unknown'}]"


class Tm1Dialect:
    """How a server's address becomes its REST API's service root: ``V11`` for TM1 11
    (``http://tm1:8010`` → ``http://tm1:8010/api/v1/``)."""

    V11: Tm1Dialect

    def __init__(self, name: str, service_root: Any):
        self._name = name
        self._service_root = service_root

    @property
    def name(self) -> str:
        """The deployment kind, for logs and messages, such as ``TM1 11``."""
        return self._name

    def service_root(self, address: str) -> str:
        """The REST API's service root for a server's address, ending in ``/``. ``address`` is where
        the server is, such as ``http://tm1:8010``; a full service root is accepted too. A
        ValueError if it isn't an address."""
        return self._service_root(address)

    def __repr__(self) -> str:
        return f"Tm1Dialect[{self._name}]"


def _v11(address: str) -> str:
    parts = urlsplit(address.strip())
    if not parts.scheme or not parts.netloc or parts.query or parts.fragment:
        raise ValueError(f"A TM1 11 address is the server's scheme, host and HTTP port, such as http://tm1:8010: {address}")
    path = parts.path.rstrip("/")
    if not path.endswith("/api/v1"):
        path += "/api/v1"
    return f"{parts.scheme}://{parts.netloc}{path}/"


Tm1Dialect.V11 = Tm1Dialect("TM1 11", _v11)
