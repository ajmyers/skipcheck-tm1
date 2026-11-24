# SPDX-License-Identifier: Apache-2.0

"""Examples for Tm1Batch, quoted in the guide."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client, Tm1Error
from skipcheck_tm1.model import Cube, Dimension, ElementCreate, Process


def read(tm1: Tm1Client) -> tuple[Cube, Dimension, list[Process]]:
    # @start region = read
    # Each call is queued as it's made, and has its result once the block has run the batch
    with tm1.batch() as batch:
        sales = batch.client.cubes.get("Sales")
        region = batch.client.dimensions.get("Region")
        processes = batch.client.processes.list(ODataQuery.none().select(Process.NAME))
    return sales.result(), region.result(), processes.result()
    # @end


def many(tm1: Tm1Client, cities: list[str]) -> list[str]:
    # @start region = many
    # One request per element, 200 to a $batch; one element's failure is its own
    batch = tm1.batch()
    created = {city: batch.client.elements.create("City", "City", ElementCreate.builder().name(city).build()) for city in cities}
    batch.run(200)

    results = []
    for city, element in created.items():
        try:
            results.append(element.result().name)
        except Tm1Error as e:
            results.append(f"{city} failed: {e}")
    return results
    # @end
