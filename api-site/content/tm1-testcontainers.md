---
kicker: Testing
tagline: Test against real Planning Analytics, in Docker
description: Run your tests against a real IBM Planning Analytics (TM1) server in Docker, with Testcontainers, from Java, Python, .NET or TypeScript: TM1 Server, Workspace, TM1 Web and Cognos security.
card: Run your tests against real Planning Analytics in Docker, from any of the four languages: TM1 Server, Workspace, TM1 Web and Cognos security with test users.
---
Start a real TM1 server for your tests, shared across the test run, and skipped cleanly where Docker or TM1 isn't available. Add TM1 Web, Planning Analytics Workspace and Cognos security with test users when you need them. No IBM software is included: you supply your own licensed TM1.

## Quick start

1. **Add it to your tests.**
   ::install
2. **Tell it where your TM1 is.** The quickest way is to set `TM1_BUNDLE_DIR` to the folder holding IBM's `tm1server-*-linuxi38664h_bundle.tar.gz` download when you run your tests, with Docker running. [Tm1Source](docs/Tm1Source.html) lists the other ways, such as an installed TM1 or a private image, which start in seconds.
3. **Write a test.** It gets a TM1 server that is started once for the test run and shared, and is skipped when Docker or TM1 isn't available:
   ::example HelloTm1TestExamples hello-test

Next: [test classes in full](docs/RequiresTm1.html), and skipping by TM1 version; [a server of your own](docs/Tm1ServerContainer.html), with the sample data or your own database; and [whole environments](docs/PlanningAnalyticsEnvironment.html), with several servers, TM1 Web, Workspace and Cognos security together.

## What's in it

- **One server per test run:** started on first use, handed to each test that asks for it, and skipped, not failed, when it can't start. In Java that is the `@RequiresTm1` annotation; the other languages have the same as a fixture.
- **Your TM1, your way:** from IBM's download file, a download link, an installed folder or a private image.
- **The whole suite:** TM1 Web, Planning Analytics Workspace, an Admin Server with several servers, and Cognos (CAM) or OIDC security with test users.
- **Sample data:** start from IBM's SData sample, or your own database.
