"""Command execution and the ``exec`` workflow."""
from __future__ import annotations

import os
import re
import shlex
import subprocess
from typing import Any, Callable

from .core import Opts, StepFn, WfStep, ok, to_workflow

ShellOpts = dict[str, Any]
Proc = dict[str, Any]
Runner = Callable[[ShellOpts, str | list[str]], Proc]


def _tokenize(cmd: str) -> list[str]:
    return shlex.split(cmd)


def default_runner(shell_opts: ShellOpts, cmd: str | list[str]) -> Proc:
    """Default command runner: spawn a child process."""
    argv = cmd if isinstance(cmd, list) else _tokenize(cmd)
    if not argv:
        return {"exit": 0, "out": "", "err": ""}
    capture = shell_opts.get("out") != "inherit"
    env = {**os.environ, **(shell_opts.get("extraEnv") or {})}
    try:
        res = subprocess.run(
            argv,
            cwd=shell_opts.get("dir"),
            env=env,
            text=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE if capture else None,
            stderr=subprocess.PIPE if capture else None,
            check=False,
        )
        return {"exit": res.returncode, "out": res.stdout or "", "err": res.stderr or ""}
    except OSError as exc:
        return {"exit": -1, "out": "", "err": str(exc)}


_runner: Runner = default_runner


def set_runner(r: Runner) -> None:
    global _runner
    _runner = r


ANSI_COLOR = re.compile(r"\x1B\[[0-9;]+m")


def _handle_cmd(opts: Opts, proc: Proc) -> Opts:
    out = proc.get("out", "")
    err = proc.get("err", "")
    res = {
        "exit": proc.get("exit", 0),
        "out": ANSI_COLOR.sub("", out) if isinstance(out, str) else out,
        "err": ANSI_COLOR.sub("", err) if isinstance(err, str) else err,
    }
    return {**opts, "procs": [*(opts.get("procs") or []), res], "exit": res["exit"], "err": res["err"]}


def _push_nil(opts: Opts) -> Opts:
    cmds = opts.get("cmds") or []
    return ok({**opts, "cmds": [None, *cmds] if cmds else [None]})


def _run_cmd(opts: Opts) -> Opts:
    base_shell = {**(opts.get("shellOpts") or {}), "continue": True}
    if opts.get("env") == "lib":
        shell_opts = {"out": "string", "err": "string", **base_shell}
    else:
        shell_opts = {"out": "inherit", "err": "inherit", **base_shell}
    cmd = (opts.get("cmds") or [""])[0]
    return _handle_cmd(opts, _runner(shell_opts, cmd))


def _wire(step: str, _step_fns: list[StepFn]) -> tuple[WfStep, str | None]:
    if step == "big-config.run/start":
        return _push_nil, "big-config.run/run-cmd"
    if step == "big-config.run/run-cmd":
        return _run_cmd, "big-config.run/run-cmd"
    return (lambda o: o), None


def _next(step: str, _next_step: str | None, opts: Opts) -> tuple[str | None, Opts]:
    cmds = opts.get("cmds") or []
    if len(cmds) > 1 and (opts.get("exit") == 0 or opts.get("exit") is None):
        return "big-config.run/run-cmd", {**opts, "cmds": cmds[1:]}
    if step == "big-config.run/end":
        return None, opts
    return "big-config.run/end", opts


_run_cmds_workflow = to_workflow(first_step="big-config.run/start", wire_fn=_wire, next_fn=_next)


def run_cmds(step_fns: list[StepFn], opts: Opts) -> Opts:
    """Run a sequence of commands, stopping on failure."""
    return _run_cmds_workflow(step_fns, opts)


setRunner = set_runner
runCmds = run_cmds
defaultRunner = default_runner
