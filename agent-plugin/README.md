# Agent plugin: TM1 developer tools

Lets a coding agent set up and run a local IBM Planning Analytics (TM1) server for you
with [tm1-server](../docker/tm1-server/GETTING-STARTED.md). Ask it something like
*"set up TM1 locally with the bundle in my Downloads"*, and it runs the setup script with
the right options, checks the server is healthy, and tells you how to connect.

For now it has one skill, [`tm1-setup`](skills/tm1-setup/SKILL.md). More TM1 tooling will
be added as the project grows.

> You need your own licensed IBM TM1 Server for Linux. The skill never uploads or
> shares it. Not affiliated with IBM.

## Claude Code

```
/plugin marketplace add ajmyers/tm1-pa-developer-tools
/plugin install tm1@tm1-pa-developer-tools
```

Then ask Claude to set up TM1, or run `/tm1:tm1-setup` (optionally followed by your bundle's
path).

## Codex, Gemini CLI, Cursor and other agents with skills

The skill is a plain [`SKILL.md`](skills/tm1-setup/SKILL.md), so any agent that reads skills
can use it. Copy it into your user skills folder (`~/.agents/skills` is read by Codex and
Gemini CLI, and by Cursor):

```sh
mkdir -p ~/.agents/skills/tm1-setup
curl -fsSL -o ~/.agents/skills/tm1-setup/SKILL.md \
  https://raw.githubusercontent.com/ajmyers/tm1-pa-developer-tools/master/agent-plugin/skills/tm1-setup/SKILL.md
```

Agents without skills can be pointed at the same file: *"follow these instructions to set
up TM1"*, with the URL above.

---

Made by Andrew Myers ([ajmyers.net](https://ajmyers.net)). Bugs and ideas:
[issues](https://github.com/ajmyers/tm1-pa-developer-tools/issues).
