from __future__ import annotations

from pathlib import Path

from once.utils import strip_ansi

RESOURCES_DIR = Path(__file__).resolve().parents[1] / "src" / "resources"


def test_strip_ansi_strips_escape_sequences_and_osc8_hyperlinks():
    ansi = (RESOURCES_DIR / "ansi.output").read_text(encoding="utf-8")
    normal = (RESOURCES_DIR / "normal.output").read_text(encoding="utf-8")
    assert strip_ansi(ansi) == normal
