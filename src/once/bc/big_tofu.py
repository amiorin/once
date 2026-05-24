"""OpenTofu/Terraform construct helpers."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any


def fqn_to_name(fqn: str, sep: str = "_") -> str:
    """Sanitize a ``ns/name`` keyword into a Terraform-safe identifier."""

    def sanitize(s: str) -> str:
        return s.replace("-", sep).replace(".", sep)

    if "/" in fqn:
        ns, name = fqn.split("/", 1)
        return f"{sanitize(ns)}{sep}{sanitize(name)}"
    return sanitize(fqn)


def add_suffix(fqn: str, suffix: str) -> str:
    """Append a suffix to the name part (matching the Clojure helper behavior)."""
    return f"{fqn}{suffix}"


@dataclass(frozen=True)
class Construct:
    group: str
    type: str
    fqn: str
    block: dict[str, Any]


def make_construct(group: str, type_: str, fqn: str, block: dict[str, Any]) -> Construct:
    return Construct(group=group, type=type_, fqn=fqn, block=block)


def construct(c: Construct) -> dict[str, Any]:
    """Render a construct into ``{group: {type: {name: block}}}``."""
    return {c.group: {c.type: {fqn_to_name(c.fqn): c.block}}}


fqnToName = fqn_to_name
addSuffix = add_suffix
makeConstruct = make_construct
