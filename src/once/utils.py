"""Small once-specific helpers."""
from __future__ import annotations

import re


def strip_ansi(s: str) -> str:
    """Strip ANSI color sequences and OSC 8 hyperlinks."""
    return re.sub(r"\x1b\[[0-9;]*m", "", re.sub(r"\x1b\]8;[^\x07]*\x07", "", s))


stripAnsi = strip_ansi
