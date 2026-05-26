"""Parameter extraction from OpenTofu outputs."""
from __future__ import annotations

import json
import subprocess
from typing import Any

from big_config import workflow as bc_workflow
from big_config.core import Opts

from .interop import PARAMS, read_bc_pars, sync_aliases, to_bc_opts

START_STEP = "io.github.bigconig-ai.once.package/start-create-or-delete"
TOFU = "io.github.bigconig-ai.once.tools/tofu"
TOFU_SMTP = "io.github.bigconig-ai.once.tools/tofu-smtp"


def tofu_output(directory: str) -> dict[str, Any] | None:
    try:
        res = subprocess.run(
            ["tofu", "output", "--json"],
            cwd=directory,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if res.returncode != 0:
            return None
        parsed = json.loads(res.stdout or "{}")
        value = (parsed.get("params") or {}).get("value")
        return value if isinstance(value, dict) else None
    except Exception:  # noqa: BLE001 - Clojure implementation silently falls back.
        return None


def fallback_compute_params(params: dict[str, Any]) -> dict[str, Any]:
    name = params.get("package") or "once"
    provider = params.get("provider-compute")
    if provider == "oci":
        return {"ip": "192.168.0.1", "sudoer": "ubuntu", "uid": "1001", "name": name, "user": "ubuntu"}
    if provider == "no-infra":
        out = {
            "ip": params.get("no-infra-compute-ip") or "192.168.0.1",
            "sudoer": params.get("no-infra-compute-sudoer") or "root",
            "name": name,
            "user": params.get("no-infra-compute-user") or "root",
        }
        if params.get("no-infra-compute-uid") is not None:
            out["uid"] = params["no-infra-compute-uid"]
        return out
    return {"ip": "192.168.0.1", "sudoer": "root", "name": name, "user": "root"}


def fallback_smtp_params(params: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {"id": "domain-id-not-defined", "records": []}
    provider = params.get("provider-smtp")
    if provider == "no-infra":
        out.update(
            {
                "smtp_username": params.get("no-infra-smtp-username"),
                "smtp_password": params.get("no-infra-smtp-password"),
                "smtp_server": params.get("no-infra-smtp-server"),
                "smtp_port": params.get("no-infra-smtp-port"),
                "smtp_use_starttls": True,
            }
        )
    elif provider == "resend":
        out.update(
            {
                "smtp_username": params.get("resend-username"),
                "smtp_password": params.get("resend-password"),
                "smtp_server": params.get("resend-server"),
                "smtp_port": params.get("resend-port"),
                "smtp_use_starttls": True,
            }
        )
    return out


def _merge_params(opts: Opts, new_params: dict[str, Any]) -> Opts:
    params = dict(opts.get(PARAMS) or {})
    return {**opts, PARAMS: {**params, **new_params}}


def tofu_params(opts: Opts) -> Opts:
    opts = to_bc_opts(opts)
    params = dict(opts.get(PARAMS) or {})
    directory = bc_workflow.path(opts, TOFU)
    return sync_aliases(_merge_params(opts, {**fallback_compute_params(params), **(tofu_output(directory) or {})}))


def tofu_smtp_params(opts: Opts) -> Opts:
    opts = to_bc_opts(opts)
    params = dict(opts.get(PARAMS) or {})
    directory = bc_workflow.path(opts, TOFU_SMTP)
    return sync_aliases(_merge_params(opts, {**fallback_smtp_params(params), **(tofu_output(directory) or {})}))


def opts_fn(opts: Opts) -> Opts:
    """Compose env overrides with SMTP and compute Tofu outputs."""
    return sync_aliases(tofu_params(tofu_smtp_params(read_bc_pars(opts))))


def once_opts(opts: Opts) -> Opts:
    """``opts_fn`` after stamping the deterministic create/delete prefix."""
    return opts_fn(bc_workflow.new_prefix(to_bc_opts(opts), START_STEP))


tofuParams = tofu_params
tofuSmtpParams = tofu_smtp_params
optsFn = opts_fn
onceOpts = once_opts
