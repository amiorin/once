from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
CLOJURE_DIR = ROOT / "once" / "clojure"
PYTHON_DIR = ROOT / "once" / "python"
HAS_BB = shutil.which("bb") is not None


def _run(cmd: list[str], cwd: Path, env: dict[str, str] | None = None) -> None:
    res = subprocess.run(cmd, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    assert res.returncode == 0, f"{cmd!r} failed\nstdout:\n{res.stdout}\nstderr:\n{res.stderr}"


def _clean_build_dir(project: Path) -> None:
    shutil.rmtree(project / ".dist", ignore_errors=True)


def _files(base: Path) -> list[Path]:
    return sorted(p.relative_to(base) for p in base.rglob("*") if p.is_file())


def _single_output_dir(project: Path) -> Path:
    outputs = sorted(p for p in (project / ".dist").iterdir() if p.is_dir())
    assert len(outputs) == 1
    return outputs[0]


@pytest.mark.skipif(not HAS_BB, reason="babashka (bb) not on PATH")
def test_python_build_matches_clojure_byte_for_byte():
    _clean_build_dir(CLOJURE_DIR)
    _clean_build_dir(PYTHON_DIR)

    _run(["bb", "run", "once", "package", "build"], CLOJURE_DIR)

    env = os.environ.copy()
    extra_pythonpath = [
        str(PYTHON_DIR / "src"),
        str(ROOT / "big-config" / "python" / "src"),
        str(ROOT / "selmer" / "python" / "src"),
    ]
    env["PYTHONPATH"] = os.pathsep.join([*extra_pythonpath, env.get("PYTHONPATH", "")])
    _run([sys.executable, "run", "package", "build"], PYTHON_DIR, env)

    clj_out = _single_output_dir(CLOJURE_DIR)
    py_out = _single_output_dir(PYTHON_DIR)
    assert py_out.name == clj_out.name
    assert _files(py_out) == _files(clj_out)
    for rel in _files(clj_out):
        assert (py_out / rel).read_bytes() == (clj_out / rel).read_bytes(), str(rel)
