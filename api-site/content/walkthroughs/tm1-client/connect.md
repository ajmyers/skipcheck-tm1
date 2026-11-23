---
title: Connect and list your cubes
summary: Connect to TM1, check the connection, and list the cubes on the server.
description: Connect to IBM Planning Analytics (TM1) from Java, Python, .NET or TypeScript, check the connection, and list the server's cubes, step by step.
order: 1
---
You connect to a TM1 server, ask for its version, and list its cubes. Each step shows its code in your language, and what it printed when we ran it against IBM's SData sample.

## Before you start

- **A TM1 server with IBM's SData sample.** [Run Planning Analytics on your own computer](/projects/pa-docker/setup/) starts one if you ask for the sample too. It answers at `http://localhost:8011/api/v1/`, and you log in as `admin` with the password `apple`. You need your own licensed copy of TM1 Server.

## Steps

1. **Add the client to your project.**
   ::install
2. **Make a client.** Give it the server's address and your login. Nothing is sent to the server yet.
   ::example ConnectWalkthrough connect
3. **Check the connection.** The first call logs in. Ask for the server's version:
   ::example ConnectWalkthrough version
   If it fails, check the address, the port and the login. TM1's REST API ends in `/api/v1/`.
4. **List the cubes.** These are the cubes someone made. TM1's control cubes, whose names start with `}`, are left out:
   ::example ConnectWalkthrough list-cubes
5. **Close the client.** This logs out and ends your session on the server.
   ::example ConnectWalkthrough close

## Next

[Read and write cells](walkthroughs/cells/) in SalesCube, one of these cubes. Or look through [the client](docs/Tm1Client.html), which has a service for each area of TM1, such as [cubes](docs/CubeService.html).
