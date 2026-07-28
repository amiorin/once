from __future__ import annotations

import os
from pathlib import Path

from blue import dry_run, progress, tofu
from blue.cli import par_name
from blue.workflow import advice_add, workflow

from blue.cli import read_pars

from . import github, tools
from .validate import secret_errors, state_errors


async def _with_deploy_keys(opts: dict, real: bool) -> dict:
    """Attach the keys ansible-remote installs and the github step publishes.

    Generating them is a create-time side effect, so a build or a dry-run takes
    fixed placeholders instead: a fresh key rendered into the artifact would make
    the build nondeterministic and break byte parity between the colours.
    """
    if real and opts.get("blue/event") == "create":
        keys, err = await github.generate_keys(opts)
        if err:
            return {**opts, "blue/exit": 1, "blue/err": err}
        key_dir = {"once/key-dir": str(Path(keys[0]["private-file"]).parent)} if keys else {}
        return {**opts, "blue/exit": 0, "once/deploy-keys": keys, **key_dir}
    return {**opts, "blue/exit": 0, "once/deploy-keys": github.placeholder_keys(opts)}


async def _state_output(opts: dict, tool: str) -> dict | None:
    try:
        return (await tofu.outputs(tools.tool_dir(opts, tool), tools.backend_credential_env(opts))).get("params")
    except Exception:
        return None


async def _adopt_existing_state(opts: dict) -> dict:
    compute = await _state_output(opts, "tofu-compute")
    smtp = await _state_output(opts, "tofu-smtp")
    return {**opts, **(compute or {}), **(smtp or {}), **({"once/compute-params": compute} if compute else {}), **({"once/smtp-params": smtp} if smtp else {})}


async def start_step(original: dict, env: dict[str, str] | None = None) -> dict:
    opts = read_pars({"compute-prevent-destroy": True, **original}, os.environ if env is None else env)
    event, real = opts.get("blue/event"), not opts.get("blue/dry-run")
    lifecycle = event in ("create", "delete")
    errors = [*state_errors(opts), *(secret_errors(opts) if real and lifecycle else [])]
    if real and event == "delete" and opts.get("compute-prevent-destroy"):
        errors.append(f"compute destruction is protected; set {par_name('compute-prevent-destroy')}=false to delete")
    if errors:
        return {**opts, "blue/exit": 2, "blue/err": "\n".join(errors)}
    if real and event == "delete":
        return {**(await _adopt_existing_state(opts)), "blue/exit": 0}
    return await _with_deploy_keys(opts, real)


async def ansible_cleanup_step(opts: dict) -> dict:
    return await tools.ansible_remote_step(await tools.ansible_local_step(opts))


tofu_steps = ["once/tofu-compute", "once/tofu-smtp", "once/tofu-dns", "once/tofu-smtp-post"]
side_effecting_steps = [*tofu_steps, "once/ansible-local", "once/ansible-remote", "once/ansible-cleanup", "once/github"]


def wire_fn(step: str, run_opts: dict):
    if run_opts.get("blue/event") == "delete":
        return {
            # Revoking runs before anything is destroyed: a withdrawn credential
            # against a live host is a loud, recoverable broken deploy, while a
            # live credential against a destroyed host is silent. It needs no
            # key material, so it also works when the box is already gone.
            "once/start": (start_step, "once/github"),
            "once/github": (github.github_step, "once/ansible-cleanup"),
            "once/ansible-cleanup": (ansible_cleanup_step, "once/tofu-smtp-post"),
            "once/tofu-smtp-post": (tools.tofu_smtp_post_step, "once/tofu-dns"),
            "once/tofu-dns": (tools.tofu_dns_step, "once/tofu-smtp", "once/tofu-compute"),
            "once/tofu-smtp": (tools.tofu_smtp_step,),
            "once/tofu-compute": (tools.tofu_compute_step,),
        }.get(step)
    return {
        "once/start": (start_step, "once/tofu-compute", "once/tofu-smtp"),
        "once/tofu-compute": (tools.tofu_compute_step, "once/tofu-dns"),
        "once/tofu-smtp": (tools.tofu_smtp_step, "once/tofu-dns"),
        "once/tofu-dns": (tools.tofu_dns_step, "once/tofu-smtp-post"),
        "once/tofu-smtp-post": (tools.tofu_smtp_post_step, "once/ansible-local", "once/ansible-remote"),
        "once/ansible-local": (tools.ansible_local_step,),
        # Publishing follows the remote stage, not the local one: the credentials
        # describe a configured host, and a workstation-side failure should not
        # gate them.
        "once/ansible-remote": (tools.ansible_remote_step, "once/github"),
        "once/github": (github.github_step,),
    }.get(step)


def backend_advice(tool: str):
    dir_fn = lambda opts: tools.tool_dir(opts, tool)
    state_key = lambda opts: f"{opts.get('profile') or 'default'}/{tool}.tfstate"
    return tofu.backends(
        lambda opts: str(opts.get("provider-backend") or "local"),
        {
            "local": tofu.local_backend_advice(dir_fn),
            "s3": tofu.s3_backend_advice(dir_fn, lambda opts: {"bucket": opts.get("s3-bucket"), "key": state_key(opts), "region": opts.get("s3-region")}),
            "r2": tofu.r2_backend_advice(dir_fn, lambda opts: {"bucket": opts.get("r2-bucket"), "key": state_key(opts), "endpoint": opts.get("r2-endpoint")}),
        },
    )


def create_workflow():
    result = workflow(start="once/start", wire_fn=wire_fn)
    for step in tofu_steps:
        tool = step.removeprefix("once/")
        result = advice_add(result, step, "before", "once.workflow/backend", backend_advice(tool))
    result = progress.advise(result)
    return dry_run.advise(result, side_effecting_steps)


once_workflow = create_workflow()
