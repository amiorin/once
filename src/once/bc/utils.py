"""Shared utility helpers."""
from __future__ import annotations

from collections.abc import Mapping
from typing import Any


def _is_plain_object(x: Any) -> bool:
    return isinstance(x, dict)


def deep_merge(*maps: dict[str, Any]) -> dict[str, Any]:
    """Recursively merge dicts. Later values win; lists are replaced."""

    def merge(a: Any, b: Any) -> Any:
        if _is_plain_object(a) and _is_plain_object(b):
            out = dict(a)
            for k, v in b.items():
                out[k] = merge(out[k], v) if k in out else v
            return out
        return b

    acc: dict[str, Any] = {}
    for m in maps:
        acc = merge(acc, m)
    return acc


def sort_nested_map(m: Any) -> Any:
    """Recursively sort dict keys for deterministic serialization."""
    if isinstance(m, dict):
        return {k: sort_nested_map(m[k]) for k in sorted(m)}
    if isinstance(m, list):
        return [sort_nested_map(x) for x in m]
    return m


def keyword_to_path(kw: str) -> str:
    """Convert ``ns/name`` into a resource path."""
    return kw.replace(".", "/")


def keyword_to_name(kw: str) -> str:
    """Convert ``ns/name`` into a file/object-safe name."""
    return kw.replace("/", "-").replace(".", "-")


keywordToPath = keyword_to_path
keywordToName = keyword_to_name
sortNestedMap = sort_nested_map
deepMerge = deep_merge
