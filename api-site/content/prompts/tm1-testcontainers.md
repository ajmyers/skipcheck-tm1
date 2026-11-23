Build me a first prototype in {{language}}: a test that runs against a real IBM Planning Analytics (TM1) server in Docker, using Skipcheck TM1 Testcontainers: the package {{package}}, version {{version}}. It talks to the server with the Skipcheck TM1 client, which it depends on.

Before you write code, ask me what the test should check, unless I've said already. Suggest a small start, such as making a dimension, checking it's there and deleting it again. Then ask me where my TM1 comes from: the folder that holds IBM's tm1server-*-linuxi38664h_bundle.tar.gz download is the quickest. Never copy, upload or share IBM's software: point the tests at where it already is.

1. Check that Docker is installed and running. The tests need it.
2. Set up a {{language}} project with tests, if there isn't one. Testcontainers for TM1 needs {{needs}}.
3. Add it to the tests:

{{install}}

4. Write the test. These walkthroughs do it step by step, with the code in every language:

{{walkthroughs}}

5. Look up each type and method you use in the {{language}} docs: {{docs}}. Its Tm1Source page lists every way to say where TM1 comes from. The landing page, the walkthroughs and every type's overview are also in one file for agents: {{llms-full}}
6. Run the tests with TM1_BUNDLE_DIR set to the folder with the download, and show me the result. The first run installs TM1 into a Docker volume, which takes a few minutes; later runs are fast. A test skips, rather than fails, when Docker or TM1 isn't there: if it skipped, find out why.

To run TM1 on this computer outside the tests, these instructions set it up:

{{skills}}
