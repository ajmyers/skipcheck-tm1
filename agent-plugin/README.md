# Agent plugin: TM1 developer tools

Lets a coding agent set up and run IBM Planning Analytics (TM1) on your own computer, in
Docker. Ask it something like *"set up TM1 locally with the bundle in my Downloads"*, or
*"run two TM1 servers with TM1 Web and Cognos users"*. It runs the setup with the right
options, checks everything is healthy, and tells you how to connect.

| Skill | What it sets up |
|---|---|
| [`pa-setup`](skills/pa-setup/SKILL.md) | One TM1 server from your IBM download, or any combination of TM1 servers, a TM1 v12 engine, TM1 Web, PAW, and Cognos security with LDAP and OIDC test users, with [pa-setup](../docker/pa-setup/README.md) and `pa-environment.yaml` |

More TM1 tooling will be added as the project grows.

> You need your own licensed IBM software (TM1 Server for Linux, and TM1 Web, PAW or
> Cognos Analytics if you want them). The skills never upload or share it. Not
> affiliated with IBM.

## Claude Code

```
/plugin marketplace add ajmyers/skipcheck-tm1
/plugin install tm1@skipcheck-tm1
```

Then ask Claude to set up TM1, or run `/tm1:pa-setup` (optionally followed by your bundle's
path, or what you want to run). The `tm1-setup` skill is gone: `pa-setup` sets up one TM1
server too.

## Codex, Gemini CLI, Cursor and other agents with skills

The skill is a plain `SKILL.md`, so any agent that reads skills can use it. Copy it
into your user skills folder (`~/.agents/skills` is read by Codex and Gemini CLI, and by
Cursor):

```sh
for skill in pa-setup; do
  mkdir -p ~/.agents/skills/$skill
  curl -fsSL -o ~/.agents/skills/$skill/SKILL.md \
    https://raw.githubusercontent.com/ajmyers/skipcheck-tm1/master/agent-plugin/skills/$skill/SKILL.md
done
```

Agents without skills can be pointed at the same file: *"follow these instructions to set
up TM1"*, with the URL above.

---

Made by Andrew Myers ([ajmyers.net](https://ajmyers.net)). Bugs and ideas:
[issues](https://github.com/ajmyers/skipcheck-tm1/issues).
