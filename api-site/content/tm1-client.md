---
tagline: The IBM Planning Analytics (TM1) REST API, in four languages
description: The IBM Planning Analytics (TM1) REST API as a typed client for Java, Python, .NET and TypeScript: every endpoint as a service, with a model generated from TM1's own metadata.
card: The whole TM1 REST API as a typed client for Java, Python, .NET and TypeScript. All four behave the same, and are tested against a real TM1.
---
Every endpoint of the TM1 REST API as a typed service: cubes, dimensions, cellsets, processes, security and the server itself. The same client in Java, Python, .NET and TypeScript, with the same services and methods, each named its language's way. Its model is generated from TM1's own metadata, so it covers what your server can do, and its documentation says where TM1 behaves in surprising ways.

## Quick start

1. **Have a TM1 server to talk to.** To run one on your own computer, follow [Run Planning Analytics on your own computer](/projects/pa-docker/setup/). You need your own licensed copy of TM1 Server.
2. **Add the client to your project.**
   ::install
3. **Say hello.** This connects, prints the server's version and lists its cubes:
   ::example HelloWorld hello-world

Next: [the client](docs/Tm1Client.html), with a service for each area of TM1, such as [cubes](docs/CubeService.html) and [cellsets](docs/CellsetService.html) for reading data; and [the session](docs/Tm1Session.html), for logging in with CAM, sandboxes, and sending a request no service covers. Every page shows its examples in each language.

## What's in it

- **A service for each area of TM1:** dimensions, hierarchies, elements and subsets; cubes, views and rules; cellsets, cell writes, sandboxes and drillthrough; processes, chores and debugging; users, groups, sessions and threads; server configuration and logs.
- **Each language's own style:** reactive in Java, on Project Reactor; sync and asyncio in Python; async in .NET and TypeScript. Large results stream instead of loading at once.
- **Typed:** a model generated from TM1's `$metadata`, with typed queries and filters.
- **TM1's surprises, documented:** where TM1 ignores, refuses or reinterprets a request, the method's documentation says so, and a test against a real server pins it.
- **Logins:** TM1 security and Cognos (CAM), with session pooling and impersonation.
