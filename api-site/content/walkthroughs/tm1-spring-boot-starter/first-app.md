---
title: Add TM1 to a Spring Boot application
summary: Add the starter, configure the server in application.yml, and use the client it builds, at startup and in your own beans.
description: Add IBM Planning Analytics (TM1) to a Spring Boot 4 application with the TM1 Spring Boot starter, configured in application.yml.
order: 1
---
You add TM1 to a Spring Boot application. The starter builds a TM1 client from your settings, and you inject it where you need it.

## Before you start

- **A Spring Boot 4 application.** [start.spring.io](https://start.spring.io) makes one.
- **A TM1 server.** [Run Planning Analytics on your own computer](/projects/pa-docker/setup/) starts one on `http://localhost:8010`, with the login `admin` and no password. You need your own licensed copy of TM1 Server.

## Steps

1. **Add the starter.**
   ::install
2. **Tell it where TM1 is.** Put the server's address and your login in `application.yml`:
   ::file java/tm1-spring-boot-starter/src/examples/java/examples/application.yml yaml
   Keep a real password out of the file, for example with `password: ${TM1_PASSWORD}` and an environment variable. [Tm1Properties](docs/Tm1Properties.html) lists every setting.
3. **Use the client.** The starter makes a `Tm1Client` bean. This application asks for it, and prints the server's version and its cubes when it starts. The server set up above has an empty database, so it has no cubes yet:
   ::example HelloTm1Application hello-app
   Run it with `./gradlew bootRun` (or `mvn spring-boot:run`).
4. **Inject it into your own beans.** Ask for `Tm1Client` in a constructor. Its calls return `Mono` and `Flux`, which Spring WebFlux returns as they are; in Spring MVC, block for the result.
   ::example Tm1AutoConfigurationExamples inject

## Next

- A session per user, from a pool: [the beans the starter makes](docs/Tm1AutoConfiguration.html).
- [Several servers](./#several-servers), each by its name.
- A real TM1 in your Spring Boot tests: [tm1-spring-boot-testcontainers](/tm1-spring-boot-testcontainers/docs/).
