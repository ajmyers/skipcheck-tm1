"""Examples for CubeService, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import Filter, ODataPath, ODataQuery, Tm1Client
from skipcheck_tm1.model import Cube, CubePatch


def list_names(tm1: Tm1Client) -> list[str | None]:
    # @start region = list
    # The names of the model cubes starting with "Sales", in name order
    sales = ODataQuery.none().select(Cube.NAME).filter(Filter.starts_with(Cube.NAME, "Sales")).order_by(Cube.NAME)
    return [cube.name for cube in tm1.cubes.model_cubes(sales)]
    # @end


def create(tm1: Tm1Client) -> Cube:
    # @start region = create
    # A cube over two existing dimensions, in this order
    dimensions = ODataPath.of("Dimensions")
    return tm1.cubes.create(CubePatch.builder().name("Sales").bind_dimensions(dimensions.key("Region"), dimensions.key("Month")).build())
    # @end


def update_rules(tm1: Tm1Client) -> None:
    # @start region = update-rules
    # TM1 saves rules even when they don't compile, so check them first
    rules = "SKIPCHECK;\n['Margin'] = N: ['Revenue'] - ['Cost'];"
    errors = tm1.cubes.check_rules("Sales", rules)
    if errors:
        raise ValueError(f"The rules don't compile: {errors}")
    tm1.cubes.update("Sales", CubePatch.builder().rules(rules).build())
    # @end
