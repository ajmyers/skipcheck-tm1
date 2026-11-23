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
   For a server elsewhere, change the address. Keep a real password out of the file, for example with `password: ${TM1_PASSWORD}` and an environment variable. [Tm1Properties](api/java/io/skipcheck/tm1/spring/boot/autoconfigure/Tm1Properties.html) lists every property.
4. **Say hello.** The starter builds a `Tm1Client` from those settings; ask for it, and use it. This prints the server's version and lists its cubes when the application starts:
   ::example HelloTm1Application hello-app
   Run it with `./gradlew bootRun` (or `mvn spring-boot:run`).

Next: [the beans it creates](api/java/io/skipcheck/tm1/spring/boot/autoconfigure/Tm1AutoConfiguration.html), and a session per user from the pool; [logging in your own way](api/java/io/skipcheck/tm1/spring/boot/autoconfigure/Tm1ConnectionDetails.html), such as with CAM; and [tm1-spring-boot-testcontainers](/tm1-spring-boot-testcontainers/api/java/), for a real TM1 in your Spring Boot tests.

## What's in it

- **Auto-configuration:** a `Tm1Client` bean from `tm1.*` properties.
- **Session pooling and impersonation:** a session per user, from a pool.
- **Your own login:** replace the connection details, for example for CAM.
- **Tests:** `@ServiceConnection` for a TM1 container, from tm1-spring-boot-testcontainers.
