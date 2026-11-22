"""Docstrings from the spec's Markdown-ish text."""

from __future__ import annotations

import textwrap
from typing import Any


def revisions(item: dict[str, Any]) -> str:
    """``Since 11.8.20.`` and ``Deprecated in 11.8.3: …``, as a trailing paragraph, or nothing."""
    parts = []
    if item.get("since"):
        parts.append(f"Since TM1 schema {item['since']}.")
    deprecated = item.get("deprecated")
    if deprecated:
        text = f".. deprecated:: {deprecated['version']}"
        if deprecated.get("reason"):
            text += " " + " ".join(deprecated["reason"].split())
        parts.append(text)
    return ("\n\n" + "\n\n".join(parts)) if parts else ""


def md(text: str) -> str:
    """The overlay's Markdown `code` as reST ``code``."""
    parts = text.split("`")
    return "".join(p if i % 2 == 0 else f"``{p}``" for i, p in enumerate(parts))


def docstring(text: str, indent: str) -> list[str]:
    text = text.strip().replace("\\", "\\\\").replace('"""', '\\"\\"\\"')
    paragraphs = [" ".join(p.split()) if not p.lstrip().startswith("..") else p.strip() for p in text.split("\n\n")]
    lines: list[str] = []
    for i, paragraph in enumerate(paragraphs):
        if i:
            lines.append("")
        lines.extend(textwrap.wrap(paragraph, width=96 - len(indent), break_long_words=False, break_on_hyphens=False) or [""])
    if text.endswith('"'):
        lines[-1] += " "
    if len(lines) == 1:
        return [f'{indent}"""{lines[0]}"""']
    return [f'{indent}"""{lines[0]}'] + [f"{indent}{line}" if line else "" for line in lines[1:]] + [f'{indent}"""']
