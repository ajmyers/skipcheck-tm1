Build me a first prototype in {{language}} that works with IBM Planning Analytics (TM1) through its REST API, using the Skipcheck TM1 client: the package {{package}}, version {{version}}.

Before you write code, ask me what the prototype should do, unless I've said already. Suggest a small start, such as connecting, listing the cubes and reading a few cells of a view. Then ask me for my TM1 server's REST address (it ends in /api/v1/) and login. Keep the password out of the code: read it from an environment variable.

1. Set up a {{language}} project. The client needs {{needs}}.
2. Add the client:

{{install}}

3. Write the code. These walkthroughs do common tasks step by step, with the code in every language and what it printed against a real TM1:

{{walkthroughs}}

4. Look up each type and method you use in the {{language}} docs: {{docs}}. They say where TM1 behaves in surprising ways, on the method it affects ("TM1 (11.8) ..."), so read them before working around an error. The landing page, the walkthroughs and every type's overview are also in one file for agents: {{llms-full}}
5. Run the prototype against my server, and show me what it printed.

If I don't have a TM1 server, one can run on this computer in Docker, from my own licensed TM1 Server download. These instructions set it up: tm1-setup for one server, pa-setup for more, or for TM1 Web, Workspace or Cognos security:

{{skills}}
