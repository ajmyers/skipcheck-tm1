"""Services for data (the Java client's ``net.ajmyers.tm1.client.service.data``)."""

from __future__ import annotations

from ._base import Service

__all__ = ['CellService', 'CellsetService', 'ChangeSetService', 'DrillthroughService', 'MemberSetService', 'SandboxService']


class CellService(Service):
    """Cell writes to a cube, by coordinates. Cells are given as ``Tuples#of`` maps of dimension to element; reading is through cellsets (``CellsetService``), and writing into one by ordinal is there too."""

    # Not ported yet: see tests/test_java_parity.py


class CellsetService(Service):
    """Cellsets: the server-side results of an MDX query or a view, with their axes and cells."""

    # Not ported yet: see tests/test_java_parity.py


class ChangeSetService(Service):
    """Change sets: groups of cell writes that can be undone together."""

    # Not ported yet: see tests/test_java_parity.py


class DrillthroughService(Service):
    """Drillthrough: from a cell to the detail behind it, through a drillthrough process (<code>&#125;Drill_…</code>). A cube drillthrough returns a cellset, of a view the process returns; a relational one returns rows from an ODBC source, directly or kept as a ``Rowset``."""

    # Not ported yet: see tests/test_java_parity.py


class MemberSetService(Service):
    """Member sets: the results of MDX set expressions, as a single axis of tuples, kept on the server in ``/MemberSets`` and keyed by an ordinal TM1 assigns (1, 2, … in the session)."""

    # Not ported yet: see tests/test_java_parity.py


class SandboxService(Service):
    """Sandboxes: private copies of the data a user writes to before publishing. They belong to the user the session is for."""

    # Not ported yet: see tests/test_java_parity.py
