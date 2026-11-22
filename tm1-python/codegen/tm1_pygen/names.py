"""Python names for TM1's: ``DrillthroughRules`` → ``drillthrough_rules``, ``ExecuteMDX`` →
``execute_mdx``, ``NativeView`` → ``NATIVE_VIEW``."""

from __future__ import annotations

import keyword
import re

# Names a model field can't take: pydantic's BaseModel's own (any model_* too), and the
# model base's. Listed rather than read from pydantic, so generating needs no dependencies.
_MODEL_RESERVED = {
    "construct",
    "copy",
    "dict",
    "from_orm",
    "json",
    "parse_file",
    "parse_obj",
    "parse_raw",
    "schema",
    "schema_json",
    "update_forward_refs",
    "validate",
    "fields",

    "odata_type",
    "odata_name",
    "odata_subtypes",
    "to_odata",
    "Fields",
    "etag",
}

# Names a resource member can't take: the base classes' own
RESOURCE_RESERVED = {
    "get",
    "update",
    "delete",
    "list",
    "iter",
    "count",
    "create",
    "path",
    "session",
    "model",
    "entity",
    "key_names",
}


def snake(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    s = re.sub(r"[^A-Za-z0-9_]", "_", s).lower()
    if s[0].isdigit():
        s = "_" + s
    # Soft keywords (match, case, type) are identifiers; only hard ones are renamed, so the
    # output is the same on every Python version
    if keyword.iskeyword(s):
        s += "_"
    return s


def constant(name: str) -> str:
    s = snake(name).rstrip("_").upper()
    return s


def field_name(prop: str) -> str:
    s = snake(prop)
    if s in _MODEL_RESERVED or s.startswith("model_"):
        s += "_"
    return s


def unique(name: str, taken: set[str]) -> str:
    candidate, n = name, 2
    while candidate in taken:
        candidate = f"{name}{n}"
        n += 1
    taken.add(candidate)
    return candidate


def entity_class(type_name: str) -> str:
    return type_name + "Entity"


def collection_class(type_name: str) -> str:
    return type_name + "Collection"
