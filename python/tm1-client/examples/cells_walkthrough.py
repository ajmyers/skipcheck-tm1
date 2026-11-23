# SPDX-License-Identifier: Apache-2.0

"""The walkthrough "Read and write cells" (api-site/content/walkthroughs/tm1-client), on SData's
SalesCube. The live tests run its steps and record what they print
(tests/live/test_walkthrough_examples.py)."""

from __future__ import annotations

from skipcheck_tm1 import ODataQuery, Tm1Client
from skipcheck_tm1.model import Cellset, CellsetCell


def main() -> None:
    # Leaving the with block closes the session
    with Tm1Client.builder().base_url("http://localhost:8011/api/v1/").basic_auth("admin", "apple").build() as tm1:
        read(tm1)
        write(tm1)
        read_back(tm1)


def read(tm1: Tm1Client) -> None:
    # @start region = read
    # Units of one model sold in France, January to March
    mdx = """
        SELECT {[month].[month].[Jan], [month].[month].[Feb], [month].[month].[Mar]} ON COLUMNS
        FROM [SalesCube]
        WHERE ([actvsbud].[actvsbud].[Actual], [region].[region].[France],
          [model].[model].[S Series 1.8 L Sedan], [account1].[account1].[Units])"""
    # TM1 keeps the result as a cellset: get its cells' values with it, then delete it
    cellset = tm1.cellsets.execute(mdx, ODataQuery.none().expand(Cellset.CELLS, lambda cells: cells.select(CellsetCell.VALUE)))
    for month, cell in zip(["Jan", "Feb", "Mar"], cellset.cells, strict=True):
        print(f"{month}: {cell.value}")
    tm1.cellsets.delete(cellset.id)
    # @end


def write(tm1: Tm1Client) -> None:
    # @start region = write
    # A cell is an element of each of the cube's dimensions, in the cube's order
    cell = {"actvsbud": "Budget", "region": "France", "model": "S Series 1.8 L Sedan", "account1": "Units", "month": "Jan"}
    tm1.cells.update("SalesCube", cell, 150)
    # @end


def read_back(tm1: Tm1Client) -> None:
    # @start region = read-back
    mdx = (
        "SELECT {[month].[month].[Jan]} ON COLUMNS FROM [SalesCube]"
        " WHERE ([actvsbud].[actvsbud].[Budget], [region].[region].[France],"
        " [model].[model].[S Series 1.8 L Sedan], [account1].[account1].[Units])"
    )
    cellset = tm1.cellsets.execute(mdx, ODataQuery.none().expand(Cellset.CELLS, lambda cells: cells.select(CellsetCell.VALUE)))
    print(f"Budget for Jan: {cellset.cells[0].value}")
    tm1.cellsets.delete(cellset.id)
    # @end


if __name__ == "__main__":
    main()
