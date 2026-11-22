"""Reading the language-neutral API spec (``spec/tm1-api.json``), with the lookups every
emitter needs. Nothing here is Python-specific: another language's generator can start
from a copy of this module."""

from __future__ import annotations

import json
from dataclasses import dataclass
from functools import cached_property
from pathlib import Path
from typing import Any

SUPPORTED_SPEC_VERSION = 1


@dataclass
class Spec:
    doc: dict[str, Any]

    @staticmethod
    def load(path: str | Path) -> Spec:
        doc = json.loads(Path(path).read_text(encoding="utf-8"))
        version = doc.get("specVersion")
        if version != SUPPORTED_SPEC_VERSION:
            raise ValueError(f"{path}: spec version {version}; this generator reads {SUPPORTED_SPEC_VERSION}")
        return Spec(doc)

    # Basics

    @property
    def schema_version(self) -> str:
        return self.doc["source"]["schemaVersion"]

    @property
    def enums(self) -> list[dict[str, Any]]:
        return self.doc["enums"]

    @property
    def types(self) -> list[dict[str, Any]]:
        return self.doc["types"]

    @property
    def operations(self) -> list[dict[str, Any]]:
        return self.doc["operations"]

    @property
    def container(self) -> dict[str, Any]:
        return self.doc["container"]

    @property
    def endpoints(self) -> list[dict[str, Any]]:
        return self.doc["endpoints"]

    # Lookups

    @cached_property
    def type_by_name(self) -> dict[str, dict[str, Any]]:
        return {t["qualifiedName"]: t for t in self.types}

    @cached_property
    def enum_by_name(self) -> dict[str, dict[str, Any]]:
        return {e["qualifiedName"]: e for e in self.enums}

    @cached_property
    def endpoint_by_id(self) -> dict[str, dict[str, Any]]:
        return {e["id"]: e for e in self.endpoints}

    @cached_property
    def operation_by_endpoint(self) -> dict[str, dict[str, Any]]:
        return {o["endpoint"]: o for o in self.operations if o["bound"]}

    @cached_property
    def unbound_operation(self) -> dict[str, dict[str, Any]]:
        return {o["qualifiedName"]: o for o in self.operations if not o["bound"]}

    def endpoint_note(self, endpoint_id: str) -> dict[str, Any]:
        return self.endpoint_by_id.get(endpoint_id, {})

    def root(self, t: dict[str, Any]) -> dict[str, Any]:
        while t.get("baseType"):
            t = self.type_by_name[t["baseType"]]
        return t

    def chain(self, t: dict[str, Any]) -> list[dict[str, Any]]:
        """The type and the types it derives from, root first."""
        out = [t]
        while out[0].get("baseType"):
            out.insert(0, self.type_by_name[out[0]["baseType"]])
        return out

    def bound_to(self, qualified_name: str, collection: bool = False) -> list[dict[str, Any]]:
        return [
            o
            for o in self.operations
            if o["bound"] and o["binding"]["type"] == qualified_name and o["binding"]["collection"] == collection
        ]

    def is_structured(self, type_name: str) -> bool:
        return type_name in self.type_by_name

    def is_entity(self, type_name: str) -> bool:
        t = self.type_by_name.get(type_name)
        return t is not None and t["kind"] == "entity"

    def derived(self, t: dict[str, Any]) -> list[dict[str, Any]]:
        return [self.type_by_name[d] for d in t.get("derivedTypes", [])]

    def alias_name(self, qualified: str) -> str:
        """``ibm.tm1.api.v1.NativeView`` → ``tm1.NativeView``, by the schema's alias."""
        for schema in self.doc["schemas"]:
            ns = schema["namespace"] + "."
            if qualified.startswith(ns) and schema.get("alias"):
                return schema["alias"] + "." + qualified[len(ns) :]
        return qualified
