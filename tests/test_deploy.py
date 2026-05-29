from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

import pytest


RESOURCES_DIR = Path(__file__).resolve().parents[1] / "src" / "resources"


DEPLOY_SCRIPT = RESOURCES_DIR / "io" / "github" / "bigconfig-ai" / "once" / "tools" / "ansible" / "files" / "deploy"
HAS_BB = shutil.which("bb") is not None and subprocess.run(["bb", "--version"], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode == 0


@dataclass
class Shim:
    dir: Path
    log: Path


def make_shim(tmp_path: Path, list_output: str) -> Shim:
    directory = tmp_path
    log = directory / "calls.log"
    list_file = directory / "list.output"
    list_file.write_text(list_output, encoding="utf-8")

    sudo = directory / "sudo"
    sudo.write_text('#!/bin/sh\nexec "$@"\n', encoding="utf-8")
    sudo.chmod(0o755)

    once = directory / "once"
    once.write_text(
        f"#!/bin/sh\necho \"$@\" >> {log}\ncase \"$1\" in\n"
        f"  list)   cat {list_file} ;;\n"
        "  update) exit 0 ;;\n"
        "  *)      exit 2 ;;\n"
        "esac\n",
        encoding="utf-8",
    )
    once.chmod(0o755)
    return Shim(directory, log)


def run_deploy(ssh_original_command: str, shim: Shim | None = None) -> tuple[int, str]:
    env = {**os.environ, "SSH_ORIGINAL_COMMAND": ssh_original_command or ""}
    if shim:
        env["PATH"] = f"{shim.dir}:{os.environ.get('PATH', '')}"
    res = subprocess.run(["bb", str(DEPLOY_SCRIPT)], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, check=False)
    return res.returncode, res.stderr


pytestmark = pytest.mark.skipif(not HAS_BB, reason="babashka (bb) not on PATH")


def test_deploy_rejects_empty_ssh_command():
    exit_code, err = run_deploy("")
    assert exit_code == 1
    assert "interactive sessions" in err


def test_deploy_rejects_unrelated_command():
    exit_code, err = run_deploy("rm -rf /")
    assert exit_code == 1
    assert "command not allowed" in err


def test_deploy_rejects_wrong_once_subcommand():
    exit_code, err = run_deploy("sudo once list")
    assert exit_code == 1
    assert "command not allowed" in err


def test_deploy_rejects_too_many_tokens():
    exit_code, err = run_deploy("sudo once update foo bar")
    assert exit_code == 1
    assert "command not allowed" in err


def test_deploy_rejects_chained_command():
    exit_code, err = run_deploy("sudo once update foo.com; rm -rf /")
    assert exit_code == 1
    assert "command not allowed" in err


def test_deploy_rejects_shell_metacharacters_in_host():
    exit_code, err = run_deploy("sudo once update foo;bar")
    assert exit_code == 1
    assert "invalid host" in err


def test_deploy_rejects_host_not_in_once_list(tmp_path):
    shim = make_shim(tmp_path, "bigconfig.website (running)\n")
    exit_code, err = run_deploy("sudo once update bogus.example.com", shim)
    assert exit_code == 1
    assert "host not allowed" in err


def test_deploy_runs_update_for_allowed_host(tmp_path):
    shim = make_shim(tmp_path, "bigconfig.website (running)\nforms.bigconfig.website (running)\n")
    exit_code, _err = run_deploy("sudo once update bigconfig.website", shim)
    assert exit_code == 0
    assert "update bigconfig.website" in shim.log.read_text(encoding="utf-8")


def test_deploy_parses_host_list_with_ansi_escapes(tmp_path):
    ansi = (RESOURCES_DIR / "ansi.output").read_text(encoding="utf-8")
    shim = make_shim(tmp_path, ansi)
    exit_code, _err = run_deploy("sudo once update foo.bigconfig.space", shim)
    assert exit_code == 0
    assert "update foo.bigconfig.space" in shim.log.read_text(encoding="utf-8")
