"""Compatibility helpers between ONCE's friendly aliases and BigConfig keys."""
from __future__ import annotations

from typing import Any, Mapping

from big_config import ERR, EXIT
from big_config import render as bc_render
from big_config import workflow as bc_workflow
from big_config.core import Opts, ok

PROFILE = bc_render.PROFILE
PARAMS = bc_workflow.PARAMS


def to_bc_opts(opts: Mapping[str, Any] | None) -> Opts:
    """Return opts with BigConfig namespaced keys populated from aliases."""
    out: Opts = dict(opts or {})
    if "profile" in out:
        out[PROFILE] = out["profile"]
    elif PROFILE in out:
        out["profile"] = out[PROFILE]
    if "params" in out:
        out[PARAMS] = out["params"]
    elif PARAMS in out:
        out["params"] = out[PARAMS]
    return out


def sync_aliases(opts: Mapping[str, Any] | None) -> Opts:
    """Return opts with friendly aliases mirrored from BigConfig keys."""
    out: Opts = dict(opts or {})
    if PROFILE in out:
        out["profile"] = out[PROFILE]
    if PARAMS in out:
        out["params"] = out[PARAMS]
    if EXIT in out:
        out["exit"] = out[EXIT]
    if ERR in out:
        out["err"] = out[ERR]
    return out


def params_of(opts: Mapping[str, Any] | None) -> dict[str, Any]:
    return dict(to_bc_opts(opts).get(PARAMS) or {})


def profile_of(opts: Mapping[str, Any] | None) -> Any:
    return to_bc_opts(opts).get(PROFILE)


def ok_alias(opts: Mapping[str, Any] | None = None) -> Opts:
    return sync_aliases(ok(opts))


def status(opts: Mapping[str, Any] | None, exit_code: int, err: Any = None) -> Opts:
    out: Opts = dict(opts or {})
    out[EXIT] = exit_code
    out[ERR] = err
    return sync_aliases(out)
