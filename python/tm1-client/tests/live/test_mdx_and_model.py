"""MDX statements, and the model and control cube and dimension functions, against a real TM1: a
port of the Java client's MdxAndModelTm1Test."""

from __future__ import annotations

import pytest

from skipcheck_tm1 import Tm1BadRequestError, Tm1Client

from .conftest import CONFIGURED, REASON

pytestmark = pytest.mark.skipif(not CONFIGURED, reason=REASON)


def test_runs_an_mdx_statement_but_not_create_set(tm1: Tm1Client) -> None:
    tm1.cellsets.execute_statement("SELECT {} ON 0 FROM [}ClientGroups]")
    with pytest.raises(Tm1BadRequestError):
        tm1.cellsets.execute_statement("CREATE SET [}Clients].[Probe] AS {TM1SUBSETALL([}Clients].[}Clients])}")


def test_separates_model_and_control_objects(tm1: Tm1Client) -> None:
    model = [c.name for c in tm1.cubes.model_cubes(select="Name")]
    control = [c.name for c in tm1.cubes.control_cubes(select="Name")]
    assert not any(n.startswith("}") for n in model)
    assert control and all(n.startswith("}") for n in control) and "}ClientGroups" in control

    model_dims = [d.name for d in tm1.dimensions.model_dimensions(select="Name")]
    control_dims = [d.name for d in tm1.dimensions.control_dimensions(select="Name")]
    assert not any(n.startswith("}") for n in model_dims)
    assert control_dims and all(n.startswith("}") for n in control_dims) and "}Clients" in control_dims
