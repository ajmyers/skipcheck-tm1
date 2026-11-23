---
kicker: Spring Boot
tagline: The TM1 client, as Spring Boot beans
description: The IBM Planning Analytics (TM1) client for Java as Spring Boot beans, configured from properties, with per-user sessions and a Testcontainers service connection for tests.
card: The Java client as Spring Boot beans, configured from properties, with per-user sessions and a Testcontainers service connection for tests.
---
Add the starter, set a few `tm1.*` properties, and inject a ready `Tm1Client`. Session pooling, per-user impersonation and metrics come with it, and the companion module wires a TM1 container into your Spring Boot tests.

## Quick start

1. **Have a TM1 server to talk to.** To run one on your own computer, follow [Run Planning Analytics on your own computer](/projects/pa-docker/setup/). You need your own licensed copy of TM1 Server.
2. **Add the starter to a Spring Boot 4 application.**
   ::install
3. **Tell it where TM1 is.** The starter connects with the `tm1` settings in `application.yml`; these are the defaults of a server set up with the link above:
   ::file java/tm1-spring-boot-starter/src/examples/java/examples/application.yml yaml
   For a server elsewhere, change the address. Keep a real password out of the file, for example with `password: ${TM1_PASSWORD}` and an environment variable. [Tm1Properties](docs/Tm1Properties.html) lists every property.
4. **Say hello.** The starter builds a `Tm1Client` from those settings; ask for it, and use it. This prints the server's version and lists its cubes when the application starts:
   ::example HelloTm1Application hello-app
   Run it with `./gradlew bootRun` (or `mvn spring-boot:run`).

Next: [the beans it creates](docs/Tm1AutoConfiguration.html), and a session per user from the pool; [several servers](#several-servers), each by name; [logging in your own way](docs/Tm1ConnectionDetails.html), such as with CAM; and [tm1-spring-boot-testcontainers](/tm1-spring-boot-testcontainers/docs/), for a real TM1 in your Spring Boot tests.

## Several servers

Give each server a name under `tm1.servers`, with the same settings as `tm1`:

::file java/tm1-spring-boot-starter/src/examples/java/examples/application-servers.yml yaml

The starter builds a client for each, and a session pool for each that turns the pool on, with its metrics tagged `server` with the name. Ask for a server's client by its name, with `@Qualifier`:

::example Tm1AutoConfigurationExamples servers

The `tm1` settings are the server named `default`, the same as `tm1.servers.default`; set one or the other. Its client is the one you get without a qualifier. Every other server's is injected only by its name, even when there's just one. In Spring Boot tests, `Tm1EnvironmentServers` from tm1-spring-boot-testcontainers wires in each server of a `PlanningAnalyticsEnvironment` by its name. [Tm1ServersProperties](docs/Tm1ServersProperties.html) has the details.

## Walkthroughs

::walkthroughs

## What's in it

- **Auto-configuration:** a `Tm1Client` bean from `tm1.*` properties.
- **Several servers:** a client per server under `tm1.servers.<name>`, injected by its name.
- **Session pooling and impersonation:** a session per user, from a pool.
- **Your own login:** replace the connection details, for example for CAM.
- **Tests:** `@ServiceConnection` for a TM1 container, from tm1-spring-boot-testcontainers.
