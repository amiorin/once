"""Parameter extraction from OpenTofu outputs."""
from __future__ import annotations

import json
import subprocess
from typing import Any

from .bc.core import Opts
from .bc.workflow import new_prefix, path, read_bc_pars

START_STEP = "io.github.amiorin.once.package/start-create-or-delete"


def tofu_output(directory: str) -> dict[str, Any]:
    res = subprocess.run(
        ["tofu", "output", "--json"],
        cwd=directory,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if res.returncode != 0:
        raise RuntimeError(res.stderr or "tofu output failed")
    return json.loads(res.stdout)


def tofu_params(opts: Opts) -> Opts:
    """Merge the IP (and other compute outputs) from the ``tofu`` stage."""
    directory = path(opts, "io.github.amiorin.once.tools/tofu")
    try:
        value = tofu_output(directory).get("params", {}).get("value") or {"ip": "192.168.0.1"}
    except Exception:
        value = {"ip": "192.168.0.1"}
    return {**opts, "params": {**(opts.get("params") or {}), **value}}


def tofu_smtp_params(opts: Opts) -> Opts:
    """Merge SMTP records / domain id from the ``tofu-smtp`` stage."""
    directory = path(opts, "io.github.amiorin.once.tools/tofu-smtp")
    default = {"id": "domain-id-not-defined", "records": []}
    try:
        value = tofu_output(directory).get("params", {}).get("value") or default
    except Exception:
        value = default
    return {**opts, "params": {**(opts.get("params") or {}), **value}}


def opts_fn(opts: Opts) -> Opts:
    """Compose env overrides with SMTP and compute Tofu outputs."""
    return tofu_params(tofu_smtp_params(read_bc_pars(opts)))


def once_opts(opts: Opts) -> Opts:
    """``opts_fn`` after stamping the deterministic create/delete prefix."""
    return opts_fn(new_prefix(opts, START_STEP))


tofuParams = tofu_params
tofuSmtpParams = tofu_smtp_params
optsFn = opts_fn
onceOpts = once_opts
