---
title: Run a process and read its result
summary: Create a TurboIntegrator process, run it with a parameter, check what it did, and read why a run failed.
description: Create, run and check an IBM Planning Analytics (TM1) TurboIntegrator process from Java, Python, .NET or TypeScript, and read its error log when a run fails.
order: 3
---
You create a small TurboIntegrator (TI) process on SData, run it with a parameter, and check the cells it wrote. Then you run it with a wrong parameter and read why it failed, and delete it.

## Before you start

- **A client of a TM1 server with IBM's SData sample.** [Connect and list your cubes](walkthroughs/connect/) shows how to make one. The steps here call it `tm1`.
- To run a process of your own instead, skip the first step and use its name and parameters.

## Steps

1. **Create the process.** It has one parameter, `pMonth`, and its prolog sets the month's budget to the actual units plus 10%. TM1 saves a process even when its code has errors, so compile it first.
   ::example ProcessWalkthrough create
2. **Run it.** Give the parameters by name. TM1 answers when the process ends, with how it ended:
   ::example ProcessWalkthrough run
3. **Check what it did.** Read the two cells:
   ::example ProcessWalkthrough check
4. **Read why a run failed.** Run it again with a month the `month` dimension doesn't have. TM1 doesn't refuse the run: the process runs to its end, the cell functions fail, and the run ends with errors. It writes them to an error log, which the result names when you ask for it.
   ::example ProcessWalkthrough fail
   A process that calls `ProcessError`, or fails in a way TI can't go on from, ends `Aborted` instead.
5. **Delete the process.**
   ::example ProcessWalkthrough delete

## Next

- Run TI code once without saving it as a process, with [the process service](docs/ProcessService.html).
- Schedule processes as [chores](docs/ChoreService.html).
- Step through a process line by line with [the debugger](docs/ProcessDebugService.html).
