---
title: Read and write cells
summary: Read values from a cube with MDX, write a value, and read it back.
description: Read cell values from an IBM Planning Analytics (TM1) cube with MDX, write a value, and read it back, from Java, Python, .NET or TypeScript.
order: 2
---
You read a few values from SData's SalesCube with an MDX query, write a budget value, and read it back.

## Before you start

- **A client of a TM1 server with IBM's SData sample.** [Connect and list your cubes](walkthroughs/connect/) shows how to make one. The steps here call it `tm1`.
- **SalesCube's dimensions,** in order: `actvsbud` (Actual and Budget), `region` (countries), `model` (car models), `account1` (Units, Sales and more) and `month`.

## Steps

1. **Read with MDX.** An MDX query picks the cells: here, the units of one model sold in France from January to March. TM1 answers with a cellset, which stays on the server until you delete it.
   ::example CellsWalkthrough read
2. **Write a cell.** Name an element of each dimension, in the cube's order, and give the value. The cell must be a leaf: TM1 doesn't spread a value over a consolidation, such as a quarter.
   ::example CellsWalkthrough write
3. **Read it back.** The same kind of query, for the cell you wrote:
   ::example CellsWalkthrough read-back

## Next

- Write many cells in one request with [the cell service](docs/CellService.html), or by position in a cellset with [the cellset service](docs/CellsetService.html).
- Read a large query a page at a time with the cellset service's stream.
- Try changes out without touching the cube's data, in [a sandbox](docs/SandboxService.html).
