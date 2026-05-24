"""Describe configured providers, SSH reachability, and deployed ONCE apps."""
from __future__ import annotations

import json
import os
import re
import subprocess
from typing import Any, Callable, TypedDict

from .bc.core import Opts, ok
from .params import once_opts

RUN_TIMEOUT_MS = 30000
SSH_PROBE_TIMEOUT_MS = 10000
REGISTRY_TIMEOUT_MS = 30000


class RunResult(TypedDict):
    ok: bool
    exit: int
    out: str
    err: str


RunOpts = dict[str, Any]
RunFn = Callable[[list[str], RunOpts | None], RunResult]


def run(args: list[str], opts: RunOpts | None = None) -> RunResult:
    opts = opts or {}
    timeout_ms = opts.get("timeoutMs", RUN_TIMEOUT_MS)
    env = {**os.environ, **(opts.get("extraEnv") or {})}
    try:
        res = subprocess.run(
            args,
            input="",
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout_ms / 1000,
            env=env,
            check=False,
        )
        return {"ok": res.returncode == 0, "exit": res.returncode, "out": res.stdout or "", "err": res.stderr or ""}
    except subprocess.TimeoutExpired:
        return {"ok": False, "exit": -1, "out": "", "err": f"command timed out after {timeout_ms}ms"}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "exit": -1, "out": "", "err": str(exc)}


def trim_snippet(s: str | None) -> str | None:
    t = (s or "").strip()
    if t == "":
        return None
    return f"{t[:200]}…" if len(t) > 200 else t


def result_detail(label: str, r: RunResult) -> str:
    snippet = trim_snippet(r.get("err")) or trim_snippet(r.get("out"))
    return f"{label} failed (exit {r.get('exit', -1)})" + (f" — {snippet}" if snippet else "")


def once_command_not_found(r: RunResult) -> bool:
    text = f"{r.get('err') or ''}\n{r.get('out') or ''}".lower()
    return (
        r.get("exit") == 127
        or "once: command not found" in text
        or "once: not found" in text
        or "command not found: once" in text
    )


class ComputeTarget(TypedDict, total=False):
    ip: str | None
    user: str


def ssh_base_args(compute: ComputeTarget) -> list[str]:
    return [
        "ssh",
        "-o",
        "BatchMode=yes",
        "-o",
        "ConnectTimeout=5",
        "-o",
        "StrictHostKeyChecking=accept-new",
        f"{compute.get('user')}@{compute.get('ip')}",
    ]


def _call_run_fn(run_fn: RunFn, args: list[str], opts: RunOpts | None = None) -> RunResult:
    try:
        return run_fn(args, opts)
    except TypeError:
        return run_fn(args)  # type: ignore[misc]


def ssh_run(run_fn: RunFn, compute: ComputeTarget, remote_args: list[str], timeout_ms: int = RUN_TIMEOUT_MS) -> RunResult:
    return _call_run_fn(run_fn, [*ssh_base_args(compute), *remote_args], {"timeoutMs": timeout_ms})


once_command_check_args = [
    "command",
    "-v",
    "once",
    ">/dev/null",
    "2>&1",
    "||",
    "test",
    "-x",
    "/usr/local/bin/once",
    "||",
    "{",
    "echo",
    "once:",
    "command",
    "not",
    "found",
    ">&2",
    ";",
    "exit",
    "127",
    ";",
    "}",
]


# -------------------------------------------------------------- providers + compute

def provider_summary(params: dict[str, Any]) -> dict[str, Any]:
    return {
        "compute": params.get("provider-compute"),
        "backend": params.get("provider-backend"),
        "smtp": params.get("provider-smtp"),
        "dns": params.get("provider-dns"),
    }


def blank(s: Any) -> bool:
    return s is None or str(s).strip() == ""


def non_empty(s: Any) -> str | None:
    return s if isinstance(s, str) and s != "" else None


def compute_target(params: dict[str, Any]) -> ComputeTarget:
    pc = params.get("provider-compute")
    ip = params.get("ip")
    ni_ip = params.get("no-infra-compute-ip")
    resolved_ip = ni_ip if pc == "no-infra" and (blank(ip) or ip == "192.168.0.1") and not blank(ni_ip) else ip
    return {
        "ip": resolved_ip,
        "user": non_empty(params.get("user"))
        or non_empty(params.get("no-infra-compute-user"))
        or non_empty(params.get("sudoer"))
        or non_empty(params.get("no-infra-compute-sudoer"))
        or "root",
    }


def compute_status(run_fn: RunFn, params: dict[str, Any]) -> dict[str, Any]:
    target = compute_target(params)
    if blank(target.get("ip")):
        return {**target, "running": False, "detail": "missing IP address"}
    r = ssh_run(run_fn, target, ["true"], SSH_PROBE_TIMEOUT_MS)
    return {
        **target,
        "running": bool(r["ok"]),
        "detail": "ssh ok"
        if r["ok"]
        else result_detail("ssh", r) + ("; no Tofu output found or host is down" if target.get("ip") == "192.168.0.1" else ""),
    }


# -------------------------------------------------------------- once list parsing

def strip_ansi(s: str | None) -> str:
    return re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]", "", re.sub(r"\x1b\]8;[^\x07]*\x07", "", s or ""))


host_status_rx = re.compile(r"([A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?\.[A-Za-z]{2,})(?:\s+\(([^)]*)\))?")


def parse_once_list(output: str) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for line in strip_ansi(output).splitlines():
        m = host_status_rx.search(line)
        if m:
            app: dict[str, Any] = {"host": m.group(1)}
            if m.group(2) and m.group(2).strip() != "":
                app["status"] = m.group(2)
            result.append(app)
    return result


# -------------------------------------------------------------- image helpers

def image_to_repository_tag(image: str | None) -> dict[str, str] | None:
    img = str(image or "").strip()
    if img == "" or re.fullmatch(r"sha256:[A-Fa-f0-9]+", img):
        return None
    without_digest = img.split("@", 1)[0]
    last_slash = without_digest.rfind("/")
    last_colon = without_digest.rfind(":")
    has_tag = last_colon > last_slash
    repository = without_digest[:last_colon] if has_tag else without_digest
    tag = without_digest[last_colon + 1 :] if has_tag else "latest"
    return {"repository": repository, "tag": tag, "image": f"{repository}:{tag}"}


def matching_repo_digest(repository: str, repo_digests: list[str] | None) -> str | None:
    for repo_digest in repo_digests or []:
        s = str(repo_digest)
        repo, sep, digest = s.partition("@")
        if repo == repository:
            return digest if sep else None
    return None


def update_available(running_digest: str | None, registry_digest_: str | None) -> bool | None:
    if running_digest and running_digest.strip() and registry_digest_ and registry_digest_.strip():
        return running_digest != registry_digest_
    return None


def registry_digest(run_fn: RunFn, image: str, os_: str, arch: str) -> dict[str, Any]:
    args = ["skopeo", "inspect", "--no-tags"]
    if not blank(os_):
        args.extend(["--override-os", os_])
    if not blank(arch):
        args.extend(["--override-arch", arch])
    args.append(f"docker://{image}")
    r = _call_run_fn(run_fn, args, {"timeoutMs": REGISTRY_TIMEOUT_MS})
    if r["ok"]:
        try:
            return {"digest": json.loads(r.get("out") or "{}").get("Digest")}
        except Exception as exc:  # noqa: BLE001
            return {"digest": None, "detail": f"registry response was not valid JSON: {exc}"}
    return {"digest": None, "detail": result_detail("skopeo inspect", r)}


# -------------------------------------------------------------- docker parsing

def parse_json_vector(s: str) -> list[Any]:
    try:
        v = json.loads(s if s and s.strip() != "" else "[]")
        return v if isinstance(v, list) else []
    except Exception:
        return []


def string_leaves(x: Any) -> list[str]:
    if x is None:
        return []
    if isinstance(x, str):
        return [x]
    if isinstance(x, list):
        return [leaf for item in x for leaf in string_leaves(item)]
    if isinstance(x, dict):
        leaves: list[str] = []
        for k, v in x.items():
            leaves.extend(string_leaves(k))
            leaves.extend(string_leaves(v))
        return leaves
    return [str(x)]


def host_variants(host: str) -> list[str]:
    h = host.lower()
    out: list[str] = []
    for v in [h, h.replace(".", "-"), h.replace(".", "_")]:
        if v not in out:
            out.append(v)
    return out


def container_search_text(container: dict[str, Any]) -> str:
    picked = {k: container[k] for k in ["Name", "Config", "NetworkSettings"] if k in container}
    return "\n".join(string_leaves(picked)).lower()


def container_for_host(host: str, container: dict[str, Any]) -> bool:
    text = container_search_text(container)
    return any(v in text for v in host_variants(host))


def find_container_for_host(containers: list[dict[str, Any]], host: str) -> dict[str, Any] | None:
    for c in containers:
        if container_for_host(host, c):
            return c
    return None


def image_identifiers(containers: list[dict[str, Any]]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for c in containers:
        for x in [c.get("Image"), (c.get("Config") or {}).get("Image") if isinstance(c.get("Config"), dict) else None]:
            if x and x not in seen:
                seen.add(x)
                out.append(x)
    return out


def image_info_for_container(image_infos: list[dict[str, Any]], container: dict[str, Any], parsed_image: dict[str, str] | None) -> dict[str, Any] | None:
    image_id = container.get("Image")
    image_ref = (container.get("Config") or {}).get("Image") if isinstance(container.get("Config"), dict) else None
    normalized = parsed_image.get("image") if parsed_image else None
    repository = parsed_image.get("repository") if parsed_image else None
    for info in image_infos:
        if info.get("Id") == image_id:
            return info
    for info in image_infos:
        if image_ref in (info.get("RepoTags") or []):
            return info
    if normalized:
        for info in image_infos:
            if normalized in (info.get("RepoTags") or []):
                return info
    if repository:
        for info in image_infos:
            if matching_repo_digest(repository, info.get("RepoDigests")):
                return info
    return None


def application_report(run_fn: RunFn, containers: list[dict[str, Any]], image_infos: list[dict[str, Any]], app: dict[str, Any]) -> dict[str, Any]:
    container = find_container_for_host(containers, app["host"])
    image_ref = (container.get("Config") or {}).get("Image") if container and isinstance(container.get("Config"), dict) else None
    parsed_image = image_to_repository_tag(image_ref)
    image_info = image_info_for_container(image_infos, container, parsed_image) if container else None
    os_ = (image_info or {}).get("Os") or "linux"
    arch = (image_info or {}).get("Architecture") or "amd64"
    running_digest = matching_repo_digest(parsed_image["repository"], (image_info or {}).get("RepoDigests")) if parsed_image else None
    fallback_digest = (image_info or {}).get("Id") or (container or {}).get("Image")
    registry = registry_digest(run_fn, parsed_image["image"], os_, arch) if parsed_image else None
    reg_digest = (registry or {}).get("digest")
    report: dict[str, Any] = {
        "host": app.get("host"),
        "status": ((container or {}).get("State") or {}).get("Status") if isinstance((container or {}).get("State"), dict) else None,
        "image": parsed_image.get("image") if parsed_image else image_ref,
        "version": parsed_image.get("tag") if parsed_image else None,
        "digest": running_digest or fallback_digest,
        "digestSource": "repo-digest" if running_digest else ("image-id" if fallback_digest else None),
        "registryDigest": reg_digest,
        "newVersion": update_available(running_digest, reg_digest),
    }
    if not report["status"]:
        report["status"] = app.get("status") or "unknown"
    if registry and registry.get("detail"):
        report["registryDetail"] = registry["detail"]
    return report


# -------------------------------------------------------------- remote discovery

def remote_applications(run_fn: RunFn, compute: ComputeTarget) -> dict[str, Any]:
    once_check = ssh_run(run_fn, compute, once_command_check_args)
    if not once_check["ok"]:
        return {"ok": False, "fatal": True, "detail": result_detail("once command check", once_check)}

    once_result = ssh_run(run_fn, compute, ["sudo", "-n", "once", "list"])
    if not once_result["ok"]:
        result = {"ok": False, "detail": result_detail("once list", once_result)}
        if once_command_not_found(once_result):
            result["fatal"] = True
        return result

    once_apps = parse_once_list(once_result.get("out") or "")
    if not once_apps:
        return {"ok": True, "applications": []}

    ps_result = ssh_run(run_fn, compute, ["sudo", "-n", "docker", "ps", "-q"])
    if not ps_result["ok"]:
        return {"ok": False, "detail": result_detail("docker ps", ps_result)}
    ids = [s.strip() for s in (ps_result.get("out") or "").splitlines() if s.strip()]
    if not ids:
        return {
            "ok": True,
            "applications": [
                {**a, "status": a.get("status") or "unknown", "image": None, "version": None, "digest": None, "registryDigest": None, "newVersion": None}
                for a in once_apps
            ],
        }

    container_result = ssh_run(run_fn, compute, ["sudo", "-n", "docker", "inspect", "--type", "container", *ids])
    if not container_result["ok"]:
        return {"ok": False, "detail": result_detail("docker inspect", container_result)}
    containers = parse_json_vector(container_result.get("out") or "")
    image_ids = image_identifiers(containers)
    if not image_ids:
        return {"ok": True, "applications": [application_report(run_fn, containers, [], a) for a in once_apps]}

    image_result = ssh_run(run_fn, compute, ["sudo", "-n", "docker", "image", "inspect", *image_ids])
    if not image_result["ok"]:
        return {"ok": False, "detail": result_detail("docker image inspect", image_result)}
    image_infos = parse_json_vector(image_result.get("out") or "")
    return {"ok": True, "applications": [application_report(run_fn, containers, image_infos, a) for a in once_apps]}


# -------------------------------------------------------------- top-level

def resolve_once_opts(opts: Opts, once_opts_fn: Callable[[Opts], Opts]) -> dict[str, Any]:
    try:
        return {"opts": once_opts_fn(opts)}
    except Exception as exc:  # noqa: BLE001
        return {"opts": opts, "detail": f"could not resolve OpenTofu parameters: {exc}"}


def describe_report(opts: Opts, run_fn: RunFn = run, once_opts_fn: Callable[[Opts], Opts] = once_opts) -> dict[str, Any]:
    """Build a describe report from opts."""
    resolved = resolve_once_opts(opts, once_opts_fn)
    resolved_opts = resolved["opts"]
    params = resolved_opts.get("params") or {}
    providers = provider_summary(params)
    compute = compute_status(run_fn, params)
    if resolved.get("detail"):
        compute = {**compute, "detail": f"{compute.get('detail')}; {resolved['detail']}"}
    app_result = remote_applications(run_fn, compute) if compute.get("running") else {"ok": False, "detail": "not checked because compute is not reachable"}
    return {
        "profile": resolved_opts.get("profile"),
        "providers": providers,
        "compute": compute,
        "applications": app_result.get("applications") or [],
        "applicationsError": None if app_result.get("ok") else app_result.get("detail"),
        "fatalError": bool(app_result.get("fatal")),
    }


# -------------------------------------------------------------- reporting

def present(x: Any) -> str:
    return "unknown" if blank(x) else str(x)


def update_label(x: bool | None) -> str:
    if x is True:
        return "yes"
    if x is False:
        return "no"
    return "unknown"


def print_report(result: dict[str, Any]) -> None:
    profile = result.get("profile")
    providers = result.get("providers") or {}
    compute = result.get("compute") or {}
    applications = result.get("applications") or []
    applications_error = result.get("applicationsError")

    print(f"Profile: {present(profile)}")
    print("")
    print("Providers:")
    print(f"  Compute: {present(providers.get('compute'))}")
    print(f"  Backend: {present(providers.get('backend'))}")
    print(f"  SMTP: {present(providers.get('smtp'))}")
    print(f"  DNS: {present(providers.get('dns'))}")
    print("")
    print("Compute:")
    print(f"  IP: {present(compute.get('ip'))}")
    print(f"  SSH user: {present(compute.get('user'))}")
    print(f"  Status: {'running' if compute.get('running') else 'not reachable'}" + (f" ({compute.get('detail')})" if compute.get("detail") else ""))
    print("")
    if applications_error:
        print(f"Applications: {applications_error}.")
    elif not applications:
        print("Applications: none found.")
    else:
        print("Applications:")
        for app in applications:
            print(f"  - {present(app.get('host'))}")
            print(f"    status: {present(app.get('status'))}")
            print(f"    image: {present(app.get('image'))}")
            print(f"    version: {present(app.get('version'))}")
            suffix = " (image id; digest comparison unknown)" if app.get("digestSource") == "image-id" else ""
            print(f"    digest: {present(app.get('digest'))}{suffix}")
            print(f"    registry digest: {present(app.get('registryDigest'))}")
            print(f"    update available: {update_label(app.get('newVersion'))}")
            if app.get("registryDetail"):
                print(f"    registry check: {app['registryDetail']}")


def describe(_step_fns: Any, opts: Opts, report_fn: Callable[[Opts], dict[str, Any]] = describe_report) -> Opts:
    """Workflow step for ``once describe``."""
    result = report_fn(opts)
    print_report(result)
    return {
        **opts,
        "describe/result": result,
        **({"exit": 1, "err": result.get("applicationsError") or "describe failed"} if result.get("fatalError") else ok()),
    }


# TypeScript-style aliases.
providerSummary = provider_summary
computeTarget = compute_target
stripAnsi = strip_ansi
parseOnceList = parse_once_list
imageToRepositoryTag = image_to_repository_tag
matchingRepoDigest = matching_repo_digest
updateAvailable = update_available
findContainerForHost = find_container_for_host
onceCommandCheckArgs = once_command_check_args
describeReport = describe_report
