"""Tofu / Ansible tool workflows."""
from __future__ import annotations

from typing import Any, Callable, Iterable

from big_config import ERR, EXIT
from big_config import pluggable
from big_config import render as bc_render
from big_config import workflow as bc_workflow
from big_config.core import Opts, StepFn
from big_config.step_fns import exit_step_fn, print_error_step_fn
from big_config.utils import deep_merge, keyword_to_path, sort_nested_map
from big_tofu.core import Construct, add_suffix, construct

from .interop import PARAMS

END = "big-config.workflow/end"

step_fns: list[StepFn] = [bc_workflow.print_step_fn, exit_step_fn(END), print_error_step_fn(END)]

delimiters = {"tag-open": "<", "tag-close": ">", "filter-open": "{", "filter-close": "}"}

TOFU = "io.github.amiorin.once.tools/tofu"
TOFU_SMTP = "io.github.amiorin.once.tools/tofu-smtp"
TOFU_DNS = "io.github.amiorin.once.tools/tofu-dns"
TOFU_SMTP_POST = "io.github.amiorin.once.tools/tofu-smtp-post"
ANSIBLE_LOCAL = "io.github.amiorin.once.tools/ansible-local"
ANSIBLE = "io.github.amiorin.once.tools/ansible"

plugin_step = "io.github.amiorin.once.tools/render-tofu-backend"


def run_steps_with_plugin(plugin: str, sfns: list[StepFn], opts: Opts) -> Opts:
    steps: list[str] = []
    for step in opts.get(bc_workflow.STEPS) or []:
        if step == "render":
            steps.extend([step, plugin])
        else:
            steps.append(step)
    return bc_workflow.run_steps(sfns, {**opts, bc_workflow.STEPS: steps})


def _provider_param(opts: Opts, key: str, default: Any) -> Any:
    return (opts.get(PARAMS) or {}).get(key, default)


def _render_tofu_backend(_f, _step: str, sfns: list[StepFn], opts: Opts) -> Opts:
    provider_backend = _provider_param(opts, "provider-backend", "s3")
    prepare_keys = [
        bc_workflow.NAME,
        bc_workflow.PATH_FN,
        bc_workflow.PREFIX,
        bc_workflow.OBJECT_FN,
        bc_workflow.OBJECT_PREFIX,
        bc_workflow.PARAMS,
    ]
    overrides = {k: opts[k] for k in prepare_keys if k in opts}
    prepared = bc_workflow.prepare(
        {
            bc_workflow.NAME: opts.get(bc_workflow.NAME),
            bc_render.TEMPLATES: [
                {
                    "template": keyword_to_path("io.github.amiorin.once.tools/tofu-backend"),
                    "overwrite": True,
                    "provider-backend": provider_backend,
                    "transform": [[provider_backend, delimiters]],
                }
            ],
        },
        overrides,
    )
    plugin_opts = bc_render.templates(sfns, prepared)
    return {
        **opts,
        EXIT: plugin_opts.get(EXIT),
        ERR: plugin_opts.get(ERR),
        plugin_step: [*(opts.get(plugin_step) or []), plugin_opts],
    }


pluggable.defmethod(plugin_step, _render_tofu_backend)


def _ip_data_fn(data: dict[str, Any], _opts: Opts | None = None) -> dict[str, Any]:
    return {**data, "ip": data.get("ip") or "192.168.0.1"}


def _json_string(value: str) -> str:
    import json

    return json.dumps(value, ensure_ascii=False)


def clj_json(value: Any, indent: int = 0) -> str:
    """Pretty JSON with Cheshire's spacing for the rendered fixtures."""
    sp = " " * indent
    child = " " * (indent + 2)
    if isinstance(value, dict):
        if not value:
            return "{ }"
        items = list(value.items())
        lines = ["{"]
        for i, (k, v) in enumerate(items):
            comma = "," if i < len(items) - 1 else ""
            lines.append(f"{child}{_json_string(str(k))} : {clj_json(v, indent + 2)}{comma}")
        lines.append(f"{sp}}}")
        return "\n".join(lines)
    if isinstance(value, list):
        if not value:
            return "[ ]"
        lines = ["["]
        for i, v in enumerate(value):
            comma = "," if i < len(value) - 1 else ""
            lines.append(f"{child}{clj_json(v, indent + 2)}{comma}")
        lines.append(f"{sp}]")
        return "\n".join(lines)
    if isinstance(value, str):
        return _json_string(value)
    if value is True:
        return "true"
    if value is False:
        return "false"
    if value is None:
        return "null"
    return str(value)


def render_fn(src: str, data: dict[str, Any]) -> str:
    """Build Cloudflare DNS record JSON from SMTP records."""
    if src != "smtp":
        raise ValueError(f"unknown render-fn source: {src}")
    constructs: list[dict[str, Any]] = []
    for r in data.get("records") or []:
        name = r.get("name")
        priority = r.get("priority")
        record = r.get("record")
        type_ = r.get("type")
        value = r.get("value")
        block: dict[str, Any] = {
            "zone_id": "${data.cloudflare_zone.domain.id}",
            "name": name,
            "ttl": "1",
            "type": type_,
            "proxied": False,
        }
        if type_ == "TXT":
            block = {**block, "content": f'"{value}"'}
        if type_ == "MX":
            block = {**block, "priority": priority, "content": value}
        constructs.append(
            construct(
                Construct(
                    "resource",
                    "cloudflare_dns_record",
                    add_suffix("io.github.amiorin.once.tools/smtp-dns", f"-{record}-{type_}"),
                    block,
                )
            )
        )
    merged = sort_nested_map(deep_merge(*constructs)) if constructs else {}
    return clj_json(merged)


def ansible_data_fn(data: dict[str, Any], _opts: Opts | None = None) -> dict[str, Any]:
    sudoer = data.get("sudoer") or "root"
    hosts = [data.get("ip") or "64.227.72.100"]
    return {**data, "sudoer": sudoer, "hosts": hosts, "users": []}


def inventory(data: dict[str, Any]) -> str:
    sudoer = data.get("sudoer")
    hosts = data.get("hosts") or []
    users = data.get("users") or []
    live_users = [{**u, "host": host} for u in users if not u.get("remove") for host in hosts]
    admins = [{**a, "host": host, "name": sudoer} for a in [{"ansible_user": sudoer}] for host in hosts]

    users_hosts = {
        f"{u.get('name')}@{u.get('host')}": {
            "ansible_host": u.get("host"),
            "ansible_user": u.get("name"),
            "uid": u.get("uid"),
        }
        for u in live_users
    }
    admins_hosts = {
        f"root@{a.get('host')}": {"ansible_host": a.get("host"), "ansible_user": a.get("name")}
        for a in admins
    }
    inv = {"all": {"children": {"admin": {"hosts": admins_hosts}, "users": {"hosts": users_hosts}}}}
    return clj_json(inv)


def _yaml_scalar(v: Any) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def _yaml_lines(value: Any, indent: str = "") -> list[str]:
    if isinstance(value, list):
        lines: list[str] = []
        for item in value:
            if isinstance(item, dict):
                sub = _yaml_lines(item, indent + "  ")
                if not sub:
                    lines.append(f"{indent}- {{}}")
                    continue
                lines.append(f"{indent}- {sub[0][len(indent) + 2:]}")
                lines.extend(sub[1:])
            else:
                lines.append(f"{indent}- {_yaml_scalar(item)}")
        return lines
    if isinstance(value, dict):
        lines = []
        for k, v in value.items():
            if isinstance(v, list):
                lines.append(f"{indent}{k}:")
                lines.extend(_yaml_lines(v, indent))
            elif isinstance(v, dict):
                lines.append(f"{indent}{k}:")
                lines.extend(_yaml_lines(v, indent + "  "))
            else:
                lines.append(f"{indent}{k}: {_yaml_scalar(v)}")
        return lines
    return [f"{indent}{_yaml_scalar(value)}"]


def _to_yaml(value: Any) -> str:
    return "\n".join(_yaml_lines(value, "")) + "\n"


def ansible_once(data: dict[str, Any]) -> str:
    once = data.get("once") or {}
    domain = data.get("domain")
    smtp = {k: data[k] for k in ["smtp_server", "smtp_port", "smtp_username", "smtp_password"] if k in data}
    smtp["smtp_from"] = f"Info <info@notifications.{domain}>"
    tasks = [
        {
            "name": "Reconcile ONCE applications",
            "become": True,
            "once": {
                **once,
                "applications": [{**app, **smtp} for app in (once.get("applications") or [])],
            },
        }
    ]
    return _to_yaml(tasks)


def render(target: str, data: dict[str, Any]) -> str:
    if target == "inventory":
        return inventory(data)
    if target == "ansible-once":
        return ansible_once(data)
    raise ValueError(f"unknown render target: {target}")


def tofu(sfns: list[StepFn], opts: Opts) -> Opts:
    provider_compute = _provider_param(opts, "provider-compute", "hcloud")
    prepared = bc_workflow.prepare(
        {
            bc_workflow.NAME: TOFU,
            bc_render.TEMPLATES: [
                {
                    "template": keyword_to_path(TOFU),
                    "overwrite": True,
                    "provider-compute": provider_compute,
                    "compute-prevent-destroy": True,
                    "transform": [[provider_compute, delimiters]],
                }
            ],
        },
        opts,
    )
    return run_steps_with_plugin(plugin_step, sfns, prepared)


def tofu_smtp(sfns: list[StepFn], opts: Opts) -> Opts:
    provider_smtp = _provider_param(opts, "provider-smtp", "resend")
    prepared = bc_workflow.prepare(
        {
            bc_workflow.NAME: TOFU_SMTP,
            bc_render.TEMPLATES: [
                {
                    "template": keyword_to_path(TOFU_SMTP),
                    "overwrite": True,
                    "data-fn": _ip_data_fn,
                    "provider-smtp": provider_smtp,
                    "transform": [[provider_smtp, delimiters]],
                }
            ],
        },
        opts,
    )
    return run_steps_with_plugin(plugin_step, sfns, prepared)


def tofu_dns(sfns: list[StepFn], opts: Opts) -> Opts:
    provider_dns = _provider_param(opts, "provider-dns", "cloudflare")
    prepared = bc_workflow.prepare(
        {
            bc_workflow.NAME: TOFU_DNS,
            bc_render.TEMPLATES: [
                {
                    "template": keyword_to_path(TOFU_DNS),
                    "overwrite": True,
                    "data-fn": _ip_data_fn,
                    "provider-dns": provider_dns,
                    "transform": [
                        [provider_dns, delimiters],
                        [render_fn, {"smtp": "smtp.tf.json"}, delimiters],
                    ],
                }
            ],
        },
        opts,
    )
    return run_steps_with_plugin(plugin_step, sfns, prepared)


def tofu_smtp_post(sfns: list[StepFn], opts: Opts) -> Opts:
    provider_smtp = _provider_param(opts, "provider-smtp", "resend")
    prepared = bc_workflow.prepare(
        {
            bc_workflow.NAME: TOFU_SMTP_POST,
            bc_render.TEMPLATES: [
                {
                    "template": keyword_to_path(TOFU_SMTP_POST),
                    "overwrite": True,
                    "provider-smtp": provider_smtp,
                    "transform": [[provider_smtp, delimiters]],
                }
            ],
        },
        opts,
    )
    return run_steps_with_plugin(plugin_step, sfns, prepared)


def ansible(sfns: list[StepFn], opts: Opts) -> Opts:
    prepared = bc_workflow.prepare(
        {
            bc_workflow.NAME: ANSIBLE,
            bc_render.TEMPLATES: [
                {
                    "template": keyword_to_path(ANSIBLE),
                    "overwrite": True,
                    "data-fn": ansible_data_fn,
                    "transform": [
                        [".", delimiters],
                        [render, {"inventory": "inventory.json", "ansible-once": "once.yml"}, delimiters],
                    ],
                }
            ],
        },
        opts,
    )
    return bc_workflow.run_steps(sfns, prepared)


def ansible_local(sfns: list[StepFn], opts: Opts) -> Opts:
    prepared = bc_workflow.prepare(
        {
            bc_workflow.NAME: ANSIBLE_LOCAL,
            bc_render.TEMPLATES: [
                {
                    "template": keyword_to_path(ANSIBLE_LOCAL),
                    "overwrite": True,
                    "transform": [["."]],
                }
            ],
        },
        opts,
    )
    return bc_workflow.run_steps(sfns, prepared)


def tool_star(fn: Callable[[list[StepFn], Opts], Opts]):
    def star(args: str | list[str], opts: Opts | None = None) -> Opts:
        parsed = bc_workflow.parse_args(args)
        return fn(step_fns, {**parsed, "big-config/env": "shell", **(opts or {})})

    return star


tofu_star = tool_star(tofu)
tofu_smtp_star = tool_star(tofu_smtp)
tofu_dns_star = tool_star(tofu_dns)
tofu_smtp_post_star = tool_star(tofu_smtp_post)
ansible_star = tool_star(ansible)
ansible_local_star = tool_star(ansible_local)

# TypeScript-style aliases.
runStepsWithPlugin = run_steps_with_plugin
pluginStep = plugin_step
renderFn = render_fn
tofuSmtp = tofu_smtp
tofuDns = tofu_dns
tofuSmtpPost = tofu_smtp_post
ansibleLocal = ansible_local
tofuStar = tofu_star
tofuSmtpStar = tofu_smtp_star
tofuDnsStar = tofu_dns_star
tofuSmtpPostStar = tofu_smtp_post_star
ansibleStar = ansible_star
ansibleLocalStar = ansible_local_star
