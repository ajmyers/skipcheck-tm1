"""Services for dimension (the Java client's ``net.ajmyers.tm1.client.service.dimension``)."""

from __future__ import annotations

from ._base import Service

__all__ = ['DimensionService', 'EdgeService', 'ElementService', 'HierarchyService', 'LevelService', 'MemberService', 'SubsetService']


class DimensionService(Service):
    """Dimensions: ``/Dimensions`` and the operations bound to a dimension."""

    # Not ported yet: see tests/test_java_parity.py


class EdgeService(Service):
    """Edges: ``/Dimensions('d')/Hierarchies('h')/Edges``, the parent-child links of a hierarchy, each keyed by its parent's and its component's names."""

    # Not ported yet: see tests/test_java_parity.py


class ElementService(Service):
    """Elements: ``/Dimensions('d')/Hierarchies('h')/Elements``, an element's parents, components and edges, and the operations bound to an element."""

    # Not ported yet: see tests/test_java_parity.py


class HierarchyService(Service):
    """Hierarchies: ``/Dimensions('d')/Hierarchies``, a dimension's default hierarchy, and the operations bound to a hierarchy. Elements, edges, members, levels and subsets have services of their own."""

    # Not ported yet: see tests/test_java_parity.py


class LevelService(Service):
    """Levels: ``/Dimensions('d')/Hierarchies('h')/Levels``, keyed by number, and their members. Read only: TM1 derives levels from the hierarchy's structure."""

    # Not ported yet: see tests/test_java_parity.py


class MemberService(Service):
    """Members: ``/Dimensions('d')/Hierarchies('h')/Members``, the MDX view of a hierarchy's elements, and a member's parent, children, element, level and hierarchy. Read only: members change through their elements."""

    # Not ported yet: see tests/test_java_parity.py


class SubsetService(Service):
    """Subsets: a hierarchy's public, private and session subsets (``SubsetScope``), their elements, and the operations bound to a subset or creating a session subset."""

    # Not ported yet: see tests/test_java_parity.py
