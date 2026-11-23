import { CubeUpdatePatch, Tuples, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for CellService, quoted in its documentation.

export function update(tm1: Tm1Client): Promise<void> {
  // @start region = update
  // The cell: an element for every dimension of the cube, in the cube's order
  const cell = { Version: "Budget", Region: "North", Month: "Jan", Measure: "Revenue" };
  return tm1.cells.update("Sales", cell, 1200);
  // @end
}

export function updateCells(tm1: Tm1Client): Promise<void> {
  // @start region = update-cells
  // A budget for North, one value per month, written in one request
  const budget = { Jan: 1200, Feb: 1350, Mar: 1100 };
  const updates = Object.entries(budget).map(([month, value]) => {
    const cell = { Version: "Budget", Region: "North", Month: month, Measure: "Revenue" };
    return CubeUpdatePatch.builder()
      .bindTuple(...Tuples.of(cell))
      .value(value)
      .build();
  });
  return tm1.cells.updateCells("Sales", updates);
  // @end
}
