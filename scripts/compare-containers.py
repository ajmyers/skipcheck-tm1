#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

"""Compares the containers each language's tm1-testcontainers started for the same scenario.

Each language's ContainerConformanceTest starts a scenario (testcontainers-conformance/scenarios/
<name>.yaml, a pa-environment.yaml) and, while it runs, writes what Docker says it got to
build/container-conformance/<language>/<name>.json:

    {"scenario": "one-server", "language": "java",
     "network": <docker network inspect>,
     "containers": {"tm1/planning": <docker inspect>, "adminServer": ..., "tm1Web": ...}}

This script reads every log the same way: it keeps the settings a caller chooses (image, command,
environment, labels, ports, mounts, extra hosts, the networks a container is on and its aliases
there) and which container started before which, puts a placeholder where a value is the run's own
(a container's ID or address, the network's name), and leaves out what
testcontainers-conformance/differences.txt lists, each with its reason. Then every language's
reading of a scenario must be equal, and the first differences are printed. With fewer than two
logs of a scenario there's nothing to compare: it says so and passes.

    scripts/compare-containers.py [build/container-conformance]

Exits 1 if two languages got different containers, or if all four ran and a line of
differences.txt matched nothing. Standard library only.
"""

from __future__ import annotations

import fnmatch
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

HOME = Path(__file__).resolve().parents[1] / "testcontainers-conformance"
LANGUAGES = ("java", "python", "dotnet", "typescript")
KINDS = ("label", "alias", "option")
# On every container this library starts; its value is the language that started it
LIBRARY_LABEL = "io.skipcheck.tm1.testcontainers"
RULE = re.compile(r"^(\w+) (\S+): (.+)$")
UNORDERED = re.compile(r"^# unordered: (.+)$")


@dataclass
class Rule:
    """A line of differences.txt: what's left out of every reading, and why."""

    kind: str
    pattern: str
    reason: str
    used: bool = field(default=False, compare=False)

    def matches(self, kind: str, value: str) -> bool:
        if kind == self.kind and fnmatch.fnmatchcase(value, self.pattern):
            self.used = True
            return True
        return False


def rules(text: str) -> list[Rule]:
    """differences.txt: "<kind> <glob>: reason", one a line, where the kind is label (a label's name),
    alias (a network alias) or option (a network option's name); # comments."""
    found = []
    for number, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = RULE.match(line)
        if not match or match[1] not in KINDS:
            raise ValueError(
                f"differences.txt:{number}: write '<{'|'.join(KINDS)}> <glob>: <reason>'"
            )
        if match[3].strip().lower().rstrip(".") in ("not ported yet", "todo", "tbd"):
            raise ValueError(f"differences.txt:{number}: that isn't a reason")
        found.append(Rule(match[1], match[2], match[3]))
    return found


def unordered(scenario: str) -> list[set[str]]:
    """The containers a scenario says start in no set order ("# unordered: a b"): they don't depend
    on each other, so a language may start them one after the other or at once."""
    return [
        set(match[1].split())
        for line in scenario.splitlines()
        if (match := UNORDERED.match(line.strip()))
    ]


def _one(inspected: Any) -> dict[str, Any]:
    # docker inspect prints a list, even for one
    if isinstance(inspected, list):
        if len(inspected) != 1:
            raise ValueError(f"expected one inspected object, got {len(inspected)}")
        inspected = inspected[0]
    if not isinstance(inspected, dict):
        raise ValueError("expected an inspected object")
    return inspected


def _placeholders(
    network: dict[str, Any], containers: dict[str, dict[str, Any]]
) -> list[tuple[str, str]]:
    """The run's own values and what stands for each, longest first, so an ID's short form doesn't
    replace the start of the whole one."""
    found: dict[str, str] = {}
    for role, container in containers.items():
        found[container["Id"]] = f"<id of {role}>"
        found[container["Id"][:12]] = f"<id of {role}>"
        networks = (container.get("NetworkSettings") or {}).get("Networks") or {}
        for attached in networks.values():
            for key in ("IPAddress", "GlobalIPv6Address"):
                if attached.get(key):
                    found[attached[key]] = f"<address of {role}>"
    if network.get("Id"):
        found[network["Id"]] = "<network>"
    if network.get("Name"):
        found[network["Name"]] = "<network>"
    return sorted(found.items(), key=lambda pair: -len(pair[0]))


def _replace(value: Any, placeholders: list[tuple[str, str]]) -> Any:
    if isinstance(value, str):
        for text, placeholder in placeholders:
            value = value.replace(text, placeholder)
        return value
    if isinstance(value, list):
        return [_replace(item, placeholders) for item in value]
    if isinstance(value, dict):
        return {
            _replace(key, placeholders): _replace(item, placeholders)
            for key, item in value.items()
        }
    return value


def _kept(kind: str, values: Any, exclusions: list[Rule]) -> list[str]:
    # every rule sees every value, so one another rule also covers still counts as used
    return sorted(
        value
        for value in values or []
        if not [rule for rule in exclusions if rule.matches(kind, value)]
    )


def _ports(bindings: dict[str, Any] | None) -> dict[str, list[str]]:
    """Where each port is published: a host port Docker picks is "random"."""
    published = {}
    for port, hosts in sorted((bindings or {}).items()):
        published[port] = sorted(
            (host.get("HostIp") or "")
            + ":"
            + ("random" if host.get("HostPort") in (None, "", "0") else host["HostPort"])
            for host in hosts or []
        )
    return published


def _binds(binds: list[str] | None) -> list[str]:
    """Each bind with its mode: one written without is read-write, which Docker doesn't add."""
    return sorted(bind if bind.count(":") >= 2 else bind + ":rw" for bind in binds or [])


def _labels(labels: dict[str, str] | None, language: str, exclusions: list[Rule]) -> dict[str, str]:
    """The labels compared. The library's own names the language, which is right when it's this
    log's: then a placeholder stands for it."""
    kept = {name: (labels or {})[name] for name in _kept("label", labels, exclusions)}
    if language and kept.get(LIBRARY_LABEL) == language:
        kept[LIBRARY_LABEL] = "<language>"
    return kept


def _mounts(mounts: list[dict[str, Any]] | None) -> list[dict[str, Any]]:
    """What's mounted where. A volume Docker named itself has no name to compare."""
    read = []
    for mount in mounts or []:
        source = mount.get("Source", "")
        if mount.get("Type") == "volume":
            name = mount.get("Name", "")
            source = "<anonymous>" if re.fullmatch(r"[0-9a-f]{64}", name) else name
        read.append(
            {
                "type": mount.get("Type"),
                "source": source,
                "destination": mount.get("Destination"),
                "readOnly": not mount.get("RW", True),
            }
        )
    return sorted(read, key=lambda mount: str(mount["destination"]))


def _container(
    container: dict[str, Any], language: str, exclusions: list[Rule]
) -> dict[str, Any]:
    config = container.get("Config") or {}
    host = container.get("HostConfig") or {}
    networks = (container.get("NetworkSettings") or {}).get("Networks") or {}
    # Docker adds a container's own ID and hostname to its aliases
    own = {container["Id"], container["Id"][:12], config.get("Hostname")}
    return {
        "image": config.get("Image"),
        "imageId": container.get("Image"),
        "entrypoint": config.get("Entrypoint"),
        "command": config.get("Cmd"),
        "environment": sorted(config.get("Env") or []),
        "labels": _labels(config.get("Labels"), language, exclusions),
        "exposedPorts": sorted(config.get("ExposedPorts") or {}),
        "hostname": config.get("Hostname"),
        "user": config.get("User") or "",
        "workingDirectory": config.get("WorkingDir") or "",
        "publishedPorts": _ports(host.get("PortBindings")),
        "publishAllPorts": bool(host.get("PublishAllPorts")),
        "binds": _binds(host.get("Binds")),
        "mounts": _mounts(container.get("Mounts")),
        "tmpfs": host.get("Tmpfs") or {},
        "extraHosts": sorted(host.get("ExtraHosts") or []),
        "networkMode": host.get("NetworkMode"),
        "networks": {
            name: _kept(
                "alias",
                [alias for alias in attached.get("Aliases") or [] if alias not in own],
                exclusions,
            )
            for name, attached in sorted(networks.items())
        },
        "privileged": bool(host.get("Privileged")),
        "capabilities": sorted(host.get("CapAdd") or []),
        "memory": host.get("Memory") or 0,
        "sharedMemory": host.get("ShmSize") or 0,
        "restart": (host.get("RestartPolicy") or {}).get("Name") or "no",
        "removedOnExit": bool(host.get("AutoRemove")),
    }


def _started(container: dict[str, Any]) -> str:
    return str((container.get("State") or {}).get("StartedAt") or "")


def normalize(
    log: dict[str, Any], exclusions: list[Rule], loose: list[set[str]]
) -> dict[str, Any]:
    """A log as what's compared: each container's settings, the network's, and which container
    started before which."""
    network = _one(log.get("network"))
    language = str(log.get("language") or "")
    containers = {
        role: _one(inspected) for role, inspected in (log.get("containers") or {}).items()
    }
    placeholders = _placeholders(network, containers)
    roles = {container["Id"]: role for role, container in containers.items()}
    order = sorted(containers, key=lambda role: _started(containers[role]))
    reading = {
        "containers": {
            role: _container(containers[role], language, exclusions)
            for role in sorted(containers)
        },
        "network": {
            "driver": network.get("Driver"),
            "internal": bool(network.get("Internal")),
            "ipv6": bool(network.get("EnableIPv6")),
            "attachable": bool(network.get("Attachable")),
            "options": {
                name: (network.get("Options") or {})[name]
                for name in _kept("option", network.get("Options"), exclusions)
            },
            "labels": _labels(network.get("Labels"), language, exclusions),
            # The scenario's containers, by role; any other is named, as it shouldn't be there
            "containers": sorted(
                roles.get(identifier, (attached or {}).get("Name") or identifier)
                for identifier, attached in (network.get("Containers") or {}).items()
            ),
        },
        "startedBefore": [
            f"{first} before {second}"
            for index, first in enumerate(order)
            for second in order[index + 1 :]
            if not [group for group in loose if {first, second} <= group]
        ],
    }
    reading["startedBefore"].sort()
    return _replace(reading, placeholders)  # type: ignore[no-any-return]


def _differences(path: str, a: Any, b: Any) -> list[tuple[str, Any, Any]]:
    if isinstance(a, dict) and isinstance(b, dict):
        found = []
        for key in sorted(set(a) | set(b)):
            found += _differences(
                f"{path}.{key}" if path else str(key), a.get(key), b.get(key)
            )
        return found
    return [] if a == b else [(path, a, b)]


def compare(readings: dict[str, dict[str, Any]]) -> list[str]:
    """Each difference between the first language's reading and another's."""
    languages = sorted(readings, key=lambda name: (name != "java", name))
    if len(languages) < 2:
        return []
    first = languages[0]
    problems = []
    for other in languages[1:]:
        for path, ours, theirs in _differences("", readings[first], readings[other]):
            problems.append(
                f"{path}\n  {first}: {json.dumps(ours, sort_keys=True)}\n  {other}: {json.dumps(theirs, sort_keys=True)}"
            )
    return problems


def main(argv: list[str]) -> int:
    root = (
        Path(argv[1])
        if len(argv) > 1
        else Path(__file__).resolve().parents[1] / "build" / "container-conformance"
    )
    home = Path(argv[2]) if len(argv) > 2 else HOME
    if not root.is_dir():
        print(f"No container logs in {root}: nothing to compare")
        return 0
    exclusions = rules((home / "differences.txt").read_text(encoding="utf-8"))
    scenarios: dict[str, dict[str, dict[str, Any]]] = {}
    for path in sorted(root.glob("*/*.json")):
        definition = home / "scenarios" / f"{path.stem}.yaml"
        if not definition.is_file():
            print(f"{path.stem}: {path.parent.name} ran a scenario that isn't in {definition.parent}")
            return 1
        log = json.loads(path.read_text(encoding="utf-8"))
        scenarios.setdefault(path.stem, {})[path.parent.name] = normalize(
            log, exclusions, unordered(definition.read_text(encoding="utf-8"))
        )
    failed = False
    for scenario, readings in sorted(scenarios.items()):
        problems = compare(readings)
        if len(readings) < 2:
            print(f"{scenario}: only {', '.join(readings)} ran it, nothing to compare")
        elif problems:
            failed = True
            print(f"{scenario}: the languages got different containers")
            for problem in problems:
                print("  " + problem.replace("\n", "\n  "))
        else:
            count = len(next(iter(readings.values()))["containers"])
            print(
                f"{scenario}: {', '.join(sorted(readings))} got the same {count} container{'s' if count != 1 else ''}"
            )
    # Only a run of all four can tell that a line is stale: it may be another language's
    if any(set(readings) >= set(LANGUAGES) for readings in scenarios.values()):
        for rule in exclusions:
            if not rule.used:
                failed = True
                print(
                    f"differences.txt: '{rule.kind} {rule.pattern}' matched nothing: remove it"
                )
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
