"""An operation of the REST API, as ``$metadata`` declares it: what the generated
``Tm1Operations`` catalog holds (``Tm1Operations.CUBE_RESERVE``), as in the Java client."""

from __future__ import annotations

import enum
from dataclasses import dataclass, field

from .version import Tm1Capability

__all__ = ["Tm1Operation"]


def _unqualified(name: str) -> str:
    return name[name.rfind(".") + 1 :]


@dataclass(frozen=True)
class Tm1Operation:
    """An action or function: bound to a type (``binding_type``), or imported (``import_name``)."""

    class Kind(enum.Enum):
        """Whether an operation is an action or a function."""

        ACTION = "action"
        FUNCTION = "function"

    @dataclass(frozen=True)
    class Parameter:
        """A parameter: its name, its type as ``$metadata`` writes it, such as ``Edm.String`` or
        ``Collection(tm1.Element)``, and whether it may be null or left out."""

        name: str
        type: str
        nullable: bool

    kind: Tm1Operation.Kind
    name: str
    binding_type: str | None
    import_name: str | None
    parameters: tuple[Tm1Operation.Parameter, ...] = field(default_factory=tuple)
    return_type: str | None = None
    added_in: str | None = None
    deprecated_in: str | None = None

    def __post_init__(self) -> None:
        if (self.binding_type is None) == (self.import_name is None):
            raise ValueError(f"{self.name} needs exactly one of a binding type and an import name")
        object.__setattr__(self, "parameters", tuple(self.parameters))

    def bound(self) -> bool:
        """Whether it's bound to a type, rather than invoked from the service root."""
        return self.binding_type is not None

    def deprecated(self) -> bool:
        """Whether ``$metadata`` marks it deprecated."""
        return self.deprecated_in is not None

    def endpoint(self) -> str:
        """Its coverage-gate ID: ``Action Chore/Activate``, ``ActionImport ExecuteMDX``."""
        kind = "Action" if self.kind is Tm1Operation.Kind.ACTION else "Function"
        if self.binding_type is not None:
            return f"{kind} {_unqualified(self.binding_type)}/{_unqualified(self.name)}"
        return f"{kind}Import {self.import_name}"

    def capability(self) -> Tm1Capability:
        """What a request using it needs the server to have, with the version that added it, for
        :meth:`Tm1Request.requires`."""
        if self.binding_type is not None:
            capability = Tm1Capability.bound_operation(self.binding_type, self.name)
        else:
            assert self.import_name is not None
            capability = Tm1Capability.unbound_operation(self.import_name)
        return capability if self.added_in is None else capability.since(self.added_in)
