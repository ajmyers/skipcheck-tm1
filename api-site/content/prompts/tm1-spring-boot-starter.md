Build me a first prototype: a Spring Boot 4 application in {{language}} that works with IBM Planning Analytics (TM1), using the Skipcheck TM1 Spring Boot starter: the package {{package}}, version {{version}}. The starter builds a TM1 client from the application's settings.

Before you write code, ask me what the application should do, unless I've said already. Suggest a small start, such as printing the server's version and listing its cubes at startup, then a REST endpoint that reads a few cells of a view. Then ask me for my TM1 server's REST address (it ends in /api/v1/) and login. Keep the password out of application.yml: read it from an environment variable.

1. Make a Spring Boot 4 application, if there isn't one (start.spring.io makes one). It needs {{needs}}.
2. Add the starter:

{{install}}

3. Write the code. This walkthrough does it step by step, with the settings and what it printed against a real TM1:

{{walkthroughs}}

4. Look up the settings, the beans and each method you use in the docs: {{docs}}. The client's own docs are at {{site}}/tm1-client/docs/java/; they say where TM1 behaves in surprising ways, on the method it affects ("TM1 (11.8) ..."), so read them before working around an error. The landing pages, the walkthroughs and every type's overview are also in one file for agents: {{llms-full}}
5. Start the application against my server, and show me what it printed.

If I don't have a TM1 server, one can run on this computer in Docker, from my own licensed TM1 Server download. These instructions set it up, one server or more, with TM1 Web, Workspace or Cognos security if I want them:

{{skills}}
