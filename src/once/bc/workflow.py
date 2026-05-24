"""Workflow composition, dynamic step runner, arg parsing, and params helpers."""
from __future__ import annotations

import os
import sys
from dataclasses import dataclass
from typing import Any, Callable

from .core import Opts, StepFn, WfStep, ok, to_step_fn, to_workflow
from .pluggable import to_workflow_star
from .render import render_templates
from .run import run_cmds
from .utils import keyword_to_name, keyword_to_path


def _print_step_before(step: str, opts: Opts) -> None:
    if step == "big-config.run/run-cmd" and (opts.get("cmds") or [None])[0]:
        print(f"> {(opts.get('cmds') or [''])[0]}", file=sys.stderr)


print_step_fn: StepFn = to_step_fn(before_f=_print_step_before)

DEFAULT_GLOBALS = ["env", "shellOpts", "module", "profile", "prefix", "objectPrefix", "globals"]


def select_globals(opts: Opts) -> Opts:
    """Copy global options across workflows."""
    keys = opts.get("globals") or DEFAULT_GLOBALS
    return {k: opts[k] for k in keys if k in opts}


def _resolve_fn(opts: Opts, key: str, default: Any = None, has_default: bool = False) -> Any:
    f = opts.get(key)
    if f is None:
        if not has_default:
            raise KeyError(f"`{key}` not defined")
        return default
    return f


def run_steps(step_fns: list[StepFn], opts: Opts) -> Opts:
    """Dynamic workflow of workflows: run steps listed under ``opts['steps']``."""
    globals_opts = select_globals(opts)
    create_opts = {**(opts.get("createOpts") or {}), **globals_opts}
    delete_opts = {**(opts.get("deleteOpts") or {}), **globals_opts}
    cur: Opts = dict(opts)
    queue = [s if "/" in s else f"big-config.workflow/{s}" for s in (opts.get("steps") or [])]

    def two_arg_ok(_sf: list[StepFn], o: Opts) -> Opts:
        return ok(o)

    def wire(step: str, sfns: list[StepFn]) -> tuple[WfStep, str | None]:
        if step == "big-config.workflow/start":
            return ok, None
        if step == "big-config.workflow/render":
            return lambda o: render_templates(sfns, o), None
        if step == "big-config.workflow/exec":
            return lambda o: run_cmds(sfns, o), None
        if step == "big-config.workflow/create":
            return lambda o: _resolve_fn(opts, "createFn")(sfns, o), None
        if step == "big-config.workflow/delete":
            return lambda o: _resolve_fn(opts, "deleteFn")(sfns, o), None
        if step == "big-config.workflow/validate":
            return lambda o: _resolve_fn(opts, "validateFn", two_arg_ok, True)(sfns, o), None
        if step == "big-config.workflow/describe":
            return lambda o: _resolve_fn(opts, "describeFn", two_arg_ok, True)(sfns, o), None
        return lambda o: o, None

    def next_fn(step: str, _next_step: str | None, o: Opts) -> tuple[str | None, Opts]:
        nonlocal cur
        if step in {"big-config.workflow/create", "big-config.workflow/delete"}:
            cur = {**cur, "exit": o.get("exit"), "err": o.get("err"), step: [*(cur.get(step) or []), o]}
        else:
            cur = o
        if step == "big-config.workflow/end":
            return None, cur
        if isinstance(o.get("exit"), int) and o["exit"] > 0:
            return "big-config.workflow/end", cur
        if queue:
            nxt = queue.pop(0)
            if nxt == "big-config.workflow/create":
                step_opts = create_opts
            elif nxt == "big-config.workflow/delete":
                step_opts = delete_opts
            else:
                step_opts = cur
            return nxt, step_opts
        return "big-config.workflow/end", cur

    wf = to_workflow_star(
        first_step="big-config.workflow/start",
        last_step="big-config.workflow/end",
        wire_fn=wire,
        next_fn=next_fn,
    )
    return wf(step_fns, cur)


PARSE_ARGS_STEPS = {
    "lock",
    "git-check",
    "render",
    "create",
    "delete",
    "validate",
    "describe",
    "exec",
    "git-push",
    "unlock-any",
}


def parse_args(input_: str | list[str] | tuple[str, ...]) -> dict[str, list[str]]:
    """Normalize CLI args into ``{'steps': [...], 'cmds': [...]}``."""
    if isinstance(input_, str):
        xs = [x for x in input_.strip().split() if x]
    else:
        xs = list(input_)
    steps: list[str] = []
    cmds: list[str] = []
    i = 0
    while i < len(xs):
        token = xs[i]
        if token in PARSE_ARGS_STEPS:
            steps.append(token)
            i += 1
            continue
        if token == "--":
            rest = xs[i + 1 :]
            if not rest:
                raise ValueError("-- cannot be without a command")
            if "exec" not in steps:
                steps.append("exec")
            cmds.append(" ".join(rest))
            break
        if "exec" not in steps:
            steps.append("exec")
        cmds.append(token.replace(":", " "))
        i += 1
    return {"steps": steps, "cmds": cmds}


def _hash_hex(s: str) -> str:
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return format(h, "x")


def new_prefix(opts: Opts, first_step: str) -> Opts:
    """Derive deterministic ``prefix`` / ``objectPrefix`` from the profile."""
    prefix = opts.get("prefix") or ".dist"
    object_prefix = opts.get("objectPrefix") or "tofu"
    profile = opts.get("profile") or "default"
    dirs = str(prefix).split("/")
    object_dirs = str(object_prefix).split("/")
    last = dirs[-1]
    profile_found = last.startswith(str(profile))
    prev_hash = last.split("-")[-1] if profile_found else ""
    base_dirs = dirs[:-1] if profile_found else dirs
    base_object_dirs = object_dirs[:-1] if profile_found else object_dirs
    suffix = _hash_hex(first_step + prev_hash)
    return {
        **opts,
        "prefix": "/".join([*base_dirs, f"{profile}-{suffix}"]),
        "objectPrefix": "/".join([*base_object_dirs, f"{profile}-{suffix}"]),
    }


def path(opts: Opts, name: str) -> str:
    """Resolve the directory of a previous workflow."""
    return f"{opts.get('prefix') or '.dist'}/{keyword_to_path(name)}"


def prepare(base: Opts, overrides: Opts) -> Opts:
    """Compute target directories, merge params into templates, and set cwd."""
    name = base.get("name")
    if name is None:
        raise ValueError("Argument name is nil")
    if overrides is None:
        raise ValueError("Argument overrides is nil")
    merged = {**base, **overrides}
    prefix = merged.get("prefix")
    object_prefix = merged.get("objectPrefix")
    path_fn = merged.get("pathFn") or (lambda o: f"{prefix or '.dist'}/{keyword_to_path(o['name'])}")
    object_fn = merged.get("objectFn") or (lambda o: f"{object_prefix or 'tofu'}/{keyword_to_name(o['name'])}")
    directory = path_fn(merged)
    obj = object_fn(merged)
    params = merged.get("params") or {}
    templates = [
        {**t, **params, "targetDir": directory, "target-object": obj}
        for t in (merged.get("templates") or [])
    ]
    return {**merged, "templates": templates, "shellOpts": {**(merged.get("shellOpts") or {}), "dir": directory}}


def merge_params(tools: list[str], params: dict[str, Any], opts: Opts) -> Opts:
    """Merge package params into per-tool create/delete opts."""
    result = dict(opts)
    for tool in tools:
        for slot in ["createOpts", "deleteOpts"]:
            tool_opts = (result.get(slot) or {}).get(tool)
            if isinstance(tool_opts, dict) and isinstance(tool_opts.get("params"), dict):
                result = {
                    **result,
                    slot: {
                        **(result.get(slot) or {}),
                        tool: {**tool_opts, "params": {**params, **tool_opts["params"]}},
                    },
                }
    return result


BC_PAR_PREFIX = "BC_PAR_"


def read_bc_pars(opts: Opts, env: dict[str, str | None] | None = None) -> Opts:
    """Override params from ``BC_PAR_*`` environment variables."""
    env_map = os.environ if env is None else env
    from_env: dict[str, str] = {}
    for k, v in env_map.items():
        if k.startswith(BC_PAR_PREFIX) and v is not None:
            key = k[len(BC_PAR_PREFIX) :].lower().replace("_", "-").replace(".", "-")
            from_env[key] = v
    return {**opts, "params": {**(opts.get("params") or {}), **from_env}}


@dataclass(frozen=True)
class PipelineEntry:
    name: str
    fn: Callable[[list[StepFn], Opts], Opts]
    args: str
    opts_fn: Callable[[Opts], Opts] | None = None


def to_comp_workflow(*, first_step: str, pipeline: list[PipelineEntry], last_step: str | None = None):
    """Create a composite workflow from a pipeline of tool workflows."""

    def comp(step_fns: list[StepFn], opts: Opts) -> Opts:
        last = last_step or f"{first_step.split('/')[0]}/end"
        globals_opts = new_prefix(select_globals(opts), first_step)
        step_info: dict[str, dict[str, Any]] = {}
        for entry in pipeline:
            parsed = parse_args(entry.args)
            override = opts.get(f"{entry.name}-opts") or {}
            step_info[entry.name] = {
                "stepOpts": {**parsed, **globals_opts, **override},
                "optsFn": entry.opts_fn or (lambda o: o),
            }
        order = [first_step, *[e.name for e in pipeline], last]
        next_by_step = {order[i]: order[i + 1] for i in range(len(order) - 1)}
        fn_by_step: dict[str, WfStep] = {first_step: ok, last: lambda o: o}
        for e in pipeline:
            fn_by_step[e.name] = lambda o, e=e: e.fn(step_fns, o)
        steps_set = {e.name for e in pipeline}
        cur: Opts = dict(opts)

        def wire(step: str, _sfns: list[StepFn]) -> tuple[WfStep, str | None]:
            return fn_by_step.get(step, lambda o: o), next_by_step.get(step)

        def next_fn(step: str, next_step: str | None, o: Opts) -> tuple[str | None, Opts]:
            nonlocal cur
            if step in steps_set:
                cur = {**cur, "exit": o.get("exit"), "err": o.get("err"), step: o}
            else:
                cur = o
            if step == last:
                return None, cur
            if isinstance(o.get("exit"), int) and o["exit"] > 0:
                return last, cur
            info = step_info.get(next_step) if next_step else None
            if info:
                return next_step, info["optsFn"](info["stepOpts"])
            return next_step, cur

        wf = to_workflow_star(first_step=first_step, last_step=last, wire_fn=wire, next_fn=next_fn)
        return wf(step_fns, opts)

    return comp


printStepFn = print_step_fn
selectGlobals = select_globals
runSteps = run_steps
parseArgs = parse_args
newPrefix = new_prefix
mergeParams = merge_params
readBcPars = read_bc_pars
toCompWorkflow = to_comp_workflow
