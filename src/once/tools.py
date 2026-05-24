"""Tofu / Ansible tool workflows."""
from __future__ import annotations

import json
from typing import Any, Callable

from .bc.big_tofu import add_suffix, construct, make_construct
from .bc.core import Opts, StepFn
from .bc.pluggable import register_step
from .bc.render import Delimiters, render_templates
from .bc.step_fns import exit_step_fn, print_error_step_fn
from .bc.utils import deep_merge, keyword_to_path, sort_nested_map
from .bc.workflow import parse_args, prepare, print_step_fn, run_steps

END = "big-config.workflow/end"

step_fns: list[StepFn] = [print_step_fn, exit_step_fn(END), print_error_step_fn(END)]

# Custom delimiters for file content: <{ var }>.
delimiters = Delimiters(tag_open="<", tag_close=">", filter_open="{", filter_close="}")

plugin_step = "io.github.amiorin.once.tools/render-tofu-backend"


def run_steps_with_plugin(plugin: str, sfns: list[StepFn], opts: Opts) -> Opts:
    steps: list[str] = []
    for step in opts.get("steps") or []:
        if step == "render":
            steps.extend([step, plugin])
        else:
            steps.append(step)
    return run_steps(sfns, {**opts, "steps": steps})


def _render_tofu_backend(_f, _step: str, sfns: list[StepFn], opts: Opts) -> Opts:
    prepare_keys = ["name", "pathFn", "prefix", "objectFn", "objectPrefix", "params"]
    overrides = {k: opts[k] for k in prepare_keys if k in opts}
    prepared = prepare(
        {
            "name": opts.get("name"),
            "templates": [
                {
                    "template": keyword_to_path("io.github.amiorin.once.tools/tofu-backend"),
                    "overwrite": True,
                    "provider-backend": "s3",
                    "transform": [["{{ provider-backend }}", delimiters]],
                }
            ],
        },
        overrides,
    )
    plugin_opts = render_templates(sfns, prepared)
    return {
        **opts,
        "exit": plugin_opts.get("exit"),
        "err": plugin_opts.get("err"),
        plugin_step: [*(opts.get(plugin_step) or []), plugin_opts],
    }


register_step(plugin_step, _render_tofu_backend)


def _ip_data_fn(data: dict[str, Any], _opts: Opts | None = None) -> dict[str, Any]:
    return {**data, "ip": data.get("ip") or "192.168.0.1"}


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
                make_construct(
                    "resource",
                    "cloudflare_dns_record",
                    add_suffix("io.github.amiorin.once.tools/smtp-dns", f"-{record}-{type_}"),
                    block,
                )
            )
        )
    merged = sort_nested_map(deep_merge(*constructs)) if constructs else {}
    return json.dumps(merged, indent=2)


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
    return json.dumps(inv, indent=2)


def _yaml_scalar(v: Any) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return str(v)
    return json.dumps(str(v))


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
    """Multi-target render function for inventory and ONCE task file."""
    if target == "inventory":
        return inventory(data)
    if target == "ansible-once":
        return ansible_once(data)
    raise ValueError(f"unknown render target: {target}")


def tofu(sfns: list[StepFn], opts: Opts) -> Opts:
    prepared = prepare(
        {
            "name": "io.github.amiorin.once.tools/tofu",
            "templates": [
                {
                    "template": keyword_to_path("io.github.amiorin.once.tools/tofu"),
                    "overwrite": True,
                    "provider-compute": "hcloud",
                    "compute-prevent-destroy": True,
                    "transform": [["{{ provider-compute }}", delimiters]],
                }
            ],
        },
        opts,
    )
    return run_steps_with_plugin(plugin_step, sfns, prepared)


def tofu_smtp(sfns: list[StepFn], opts: Opts) -> Opts:
    prepared = prepare(
        {
            "name": "io.github.amiorin.once.tools/tofu-smtp",
            "templates": [
                {
                    "template": keyword_to_path("io.github.amiorin.once.tools/tofu-smtp"),
                    "overwrite": True,
                    "dataFn": _ip_data_fn,
                    "provider-smtp": "resend",
                    "transform": [["{{ provider-smtp }}", delimiters]],
                }
            ],
        },
        opts,
    )
    return run_steps_with_plugin(plugin_step, sfns, prepared)


def tofu_dns(sfns: list[StepFn], opts: Opts) -> Opts:
    prepared = prepare(
        {
            "name": "io.github.amiorin.once.tools/tofu-dns",
            "templates": [
                {
                    "template": keyword_to_path("io.github.amiorin.once.tools/tofu-dns"),
                    "overwrite": True,
                    "dataFn": _ip_data_fn,
                    "provider-dns": "cloudflare",
                    "transform": [
                        ["{{ provider-dns }}", delimiters],
                        [render_fn, {"smtp": "smtp.tf.json"}, delimiters],
                    ],
                }
            ],
        },
        opts,
    )
    return run_steps_with_plugin(plugin_step, sfns, prepared)


def tofu_smtp_post(sfns: list[StepFn], opts: Opts) -> Opts:
    prepared = prepare(
        {
            "name": "io.github.amiorin.once.tools/tofu-smtp-post",
            "templates": [
                {
                    "template": keyword_to_path("io.github.amiorin.once.tools/tofu-smtp-post"),
                    "overwrite": True,
                    "provider-smtp": "resend",
                    "transform": [["{{ provider-smtp }}", delimiters]],
                }
            ],
        },
        opts,
    )
    return run_steps_with_plugin(plugin_step, sfns, prepared)


def ansible(sfns: list[StepFn], opts: Opts) -> Opts:
    prepared = prepare(
        {
            "name": "io.github.amiorin.once.tools/ansible",
            "templates": [
                {
                    "template": keyword_to_path("io.github.amiorin.once.tools/ansible"),
                    "overwrite": True,
                    "dataFn": ansible_data_fn,
                    "transform": [
                        [".", delimiters],
                        [render, {"inventory": "inventory.json", "ansible-once": "once.yml"}, delimiters],
                    ],
                }
            ],
        },
        opts,
    )
    return run_steps(sfns, prepared)


def ansible_local(sfns: list[StepFn], opts: Opts) -> Opts:
    prepared = prepare(
        {
            "name": "io.github.amiorin.once.tools/ansible-local",
            "templates": [
                {
                    "template": keyword_to_path("io.github.amiorin.once.tools/ansible-local"),
                    "overwrite": True,
                    "transform": [["."]],
                }
            ],
        },
        opts,
    )
    return run_steps(sfns, prepared)


def tool_star(fn: Callable[[list[StepFn], Opts], Opts]):
    def star(args: str | list[str], opts: Opts | None = None) -> Opts:
        parsed = parse_args(args)
        return fn(step_fns, {**parsed, "env": "shell", **(opts or {})})

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
