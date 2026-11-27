Set up IBM Planning Analytics (TM1) on this computer in Docker for me, so I can develop and test against it. Use Skipcheck's Planning Analytics in Docker: {{docs}}

First check that Docker is installed and running. Then ask me what I want to run, unless I've said already, and where my IBM downloads are (look in ~/Downloads first). Nothing from IBM comes with it: I need my own licensed downloads. Never copy, upload or share them, and never accept IBM's license for me.

Follow one of these:

{{skills}}

pa-setup sets up one TM1 server from my download, or any mix of TM1 servers, a TM1 v12 engine, TM1 Web, Planning Analytics Workspace and Cognos security with test users, from one file, pa-environment.yaml. Its README has every option: {{source}}/docker/pa-setup/README.md

When it's running, tell me every address and login, and check that TM1's REST API answers. Then suggest a next step: the TM1 client, to work with the server from code ({{site}}/tm1-client/), or TM1 Testcontainers, to test against it ({{site}}/tm1-testcontainers/).
