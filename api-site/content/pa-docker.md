---
tagline: The whole suite on your own computer, from one command
description: Run IBM Planning Analytics in Docker for development, tests and learning: TM1 Server, TM1 Web, Planning Analytics Workspace and Cognos security, set up from one file.
card: TM1 Server, TM1 Web, Workspace and Cognos security on your own computer, for development and tests, started from one file.
kicker: Docker images · Setup tool
callout: **Bring your own IBM software** Nothing here contains any. You need your own licensed IBM Planning Analytics downloads, and you're responsible for following your IBM license terms.
---
Run IBM Planning Analytics locally for development, testing and learning: TM1 Server, TM1 Web, Planning Analytics Workspace, and Cognos security with test users. Describe the parts you want in one file, and one command starts them and tells you where to log in. Free and open source, for Mac, Windows and Linux.

## How it works

1. **Tick what you want on the [setup page](/pa-docker/setup/).** It tells you which IBM downloads you need and how much memory to give Docker, and writes the command for your choices.
2. **Run the command.** [pa-setup](pa-setup/) reads your choices from `pa-environment.yaml`, starts everything in the right order and waits until it's healthy.
3. **Open it.** The setup ends by listing every address and login, for example TM1 at `http://localhost:8010/api/v1/`.

Change `pa-environment.yaml` and run the command again to add or remove parts; your data is kept. Only want one TM1 server? [Getting started with tm1-server](tm1-server/getting-started/) walks you through it on Mac, Windows or Linux.

## Let an agent do it

Hand this prompt to a coding agent. It checks Docker, asks what you want to run and where your IBM downloads are, sets it up with the instructions it names, and tells you where to log in.

::prompt
