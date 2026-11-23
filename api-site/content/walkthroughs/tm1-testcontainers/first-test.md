---
title: Test your code against a real TM1
summary: Add tm1-testcontainers, point it at your TM1, and write tests that get a real server, with their own objects or IBM's sample data.
description: Test code that uses IBM Planning Analytics (TM1) against a real TM1 server in Docker, from Java, Python, .NET or TypeScript, with tm1-testcontainers.
order: 1
---
You write tests that run against a real TM1 server in Docker. The server starts once for the test run, and the tests skip, rather than fail, where Docker or TM1 isn't there.

## Before you start

- **Docker,** running on the computer that runs the tests.
- **Your own licensed TM1 Server.** No IBM software comes with this library. The quickest source is IBM's download, `tm1server-*-linuxi38664h_bundle.tar.gz`.

## Steps

1. **Add it to your tests.**
   ::install
2. **Tell it where TM1 is.** Set `TM1_BUNDLE_DIR` to the folder that holds IBM's download, then run your tests as usual. The first start installs TM1 from it into a Docker volume, which takes a few minutes; later starts are fast. [Tm1Source](docs/Tm1Source.html) lists the other sources, such as an installed TM1 (`TM1_HOME_DIR`) or a private image.
3. **Write a test.** Ask for the server, and connect to it with its address and login:
   ::example HelloTm1TestExamples hello-test
4. **Make what a test needs, and clean up.** Every test in the run shares the server, so give your objects names no other test uses, and delete them afterwards:
   ::example RequiresTm1Examples test-class
5. **Test against sample data.** For a test that reads a model, start a server of its own with IBM's SData sample. It's stopped when the block ends:
   ::example Tm1ServerContainerExamples sample

## Next

- Skip a test on servers older than a feature: [RequiresTm1](docs/RequiresTm1.html).
- Start TM1 with your own database: [Tm1ServerContainer](docs/Tm1ServerContainer.html).
- Test with TM1 Web, Workspace or Cognos security: [whole environments](docs/PlanningAnalyticsEnvironment.html).
