"""Pre-flight validation for the active profile."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any, Callable, Literal, TypedDict
from urllib.parse import quote

from big_config import workflow as bc_workflow
from big_config.core import Opts

from .interop import ok_alias, params_of, profile_of, status, to_bc_opts

CheckKind = Literal["schema", "tool", "credential", "image"]


class CheckError(TypedDict):
    check: CheckKind
    detail: str


class ValidateResult(TypedDict):
    ok: bool
    errors: list[CheckError]


class RunResult(TypedDict):
    ok: bool
    exit: int
    out: str
    err: str


Runner = Callable[[list[str], dict[str, str] | None], RunResult]

# -------------------------------------------------------------- regexes

domain_rx = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
hostname_rx = domain_rx
image_rx = re.compile(r"^[a-z0-9.-]+/[a-z0-9._-]+(/[a-z0-9._-]+)*(:[a-zA-Z0-9._-]+)?$")
ssh_pubkey_rx = re.compile(r"^ssh-(ed25519|rsa|dss|ecdsa) [A-Za-z0-9+/=]+( .*)?$")

PLACEHOLDER = "REPLACE_ME"
PLACEHOLDER_MSG = "must replace REPLACE_ME with a real value"


def is_placeholder(v: Any) -> bool:
    return isinstance(v, str) and PLACEHOLDER in v


def blank_or_placeholder(v: Any) -> bool:
    return v is None or (isinstance(v, str) and (v.strip() == "" or is_placeholder(v)))


def real_value(v: Any) -> bool:
    return not blank_or_placeholder(v)


FieldCheck = Callable[[Any], str | None]


def string_value(v: Any) -> str | None:
    if not isinstance(v, str):
        return "should be a string"
    if is_placeholder(v):
        return PLACEHOLDER_MSG
    return None


def int_value(v: Any) -> str | None:
    if is_placeholder(v):
        return PLACEHOLDER_MSG
    if type(v) is not int:  # noqa: E721 - bool must not pass
        return "should be an integer"
    return None


def non_empty_string(v: Any) -> str | None:
    if not isinstance(v, str):
        return "should be a string"
    if is_placeholder(v):
        return PLACEHOLDER_MSG
    if len(v) == 0:
        return "must be a non-empty string"
    return None


def re_check(rx: re.Pattern[str], msg: str) -> FieldCheck:
    def check(v: Any) -> str | None:
        if not isinstance(v, str):
            return "should be a string"
        if is_placeholder(v):
            return PLACEHOLDER_MSG
        if rx.fullmatch(v) is None:
            return msg
        return None

    return check


Emit = Callable[[str, str], None]


def required(obj: dict[str, Any], key: str, check: FieldCheck, emit: Emit, prefix: str = "workflow/params") -> None:
    if key not in obj:
        emit(f"{prefix} → {key}", "missing required key")
        return
    msg = check(obj[key])
    if msg:
        emit(f"{prefix} → {key}", msg)


def check_base_params(params: dict[str, Any], emit: Emit) -> None:
    required(params, "domain", re_check(domain_rx, "must be a valid domain"), emit)
    required(params, "package", non_empty_string, emit)
    required(params, "compute-pubkey", re_check(ssh_pubkey_rx, "must look like an SSH public key"), emit)
    required(params, "deploy-pubkey", re_check(ssh_pubkey_rx, "must look like an SSH public key"), emit)

    once = params.get("once")
    if once is None:
        emit("workflow/params → once", "missing required key")
        return
    if not isinstance(once, dict):
        emit("workflow/params → once", "should be a map")
        return
    apps = once.get("applications")
    if apps is None:
        emit("workflow/params → once → applications", "missing required key")
        return
    if not isinstance(apps, list):
        emit("workflow/params → once → applications", "should be a vector")
        return
    for i, app in enumerate(apps):
        prefix = f"workflow/params → once → applications → {i}"
        if not isinstance(app, dict):
            emit(prefix, "should be a map")
            continue
        required(app, "host", re_check(hostname_rx, "must be a valid hostname"), emit, prefix)
        required(app, "image", re_check(image_rx, "must be a valid image ref (e.g. ghcr.io/org/name:tag)"), emit, prefix)
        if "env" in app:
            env = app["env"]
            if not isinstance(env, list):
                emit(f"{prefix} → env", "should be a vector")
            else:
                for j, e in enumerate(env):
                    msg = string_value(e)
                    if msg:
                        emit(f"{prefix} → env → {j}", msg)


def check_smtp(params: dict[str, Any], emit: Emit) -> None:
    match params.get("provider-smtp"):
        case "resend":
            for k, check in [
                ("resend-server", string_value),
                ("resend-port", int_value),
                ("resend-username", string_value),
                ("resend-api-key", string_value),
                ("resend-password", string_value),
            ]:
                required(params, k, check, emit)
        case "no-infra":
            for k, check in [
                ("no-infra-smtp-server", string_value),
                ("no-infra-smtp-port", int_value),
                ("no-infra-smtp-username", string_value),
                ("no-infra-smtp-password", string_value),
            ]:
                required(params, k, check, emit)
        case _:
            emit("workflow/params → provider-smtp", "invalid dispatch value")


def check_dns(params: dict[str, Any], emit: Emit) -> None:
    match params.get("provider-dns"):
        case "cloudflare":
            required(params, "cloudflare-api-token", string_value, emit)
        case "no-infra":
            pass
        case _:
            emit("workflow/params → provider-dns", "invalid dispatch value")


def check_backend(params: dict[str, Any], emit: Emit) -> None:
    match params.get("provider-backend"):
        case "s3":
            required(params, "s3-bucket", string_value, emit)
            required(params, "s3-region", string_value, emit)
        case "r2":
            required(params, "r2-bucket", non_empty_string, emit)
            required(params, "r2-endpoint", non_empty_string, emit)
            required(params, "r2-access-key-id", non_empty_string, emit)
            required(params, "r2-secret-access-key", non_empty_string, emit)
        case "local":
            pass
        case _:
            emit("workflow/params → provider-backend", "invalid dispatch value")


def check_compute(params: dict[str, Any], emit: Emit) -> None:
    match params.get("provider-compute"):
        case "oci":
            for k in [
                "oci-config-file-profile",
                "oci-subnet-id",
                "oci-compartment-id",
                "oci-availability-domain",
                "oci-display-name",
                "oci-shape",
                "oci-ssh-authorized-keys",
            ]:
                required(params, k, string_value, emit)
            for k in ["oci-ocpus", "oci-memory-in-gbs", "oci-boot-volume-size-in-gbs", "oci-boot-volume-vpus-per-gb"]:
                required(params, k, int_value, emit)
        case "hcloud":
            for k in ["hcloud-name", "hcloud-image", "hcloud-server-type", "hcloud-location", "hcloud-ssh-keys", "hcloud-token"]:
                required(params, k, string_value, emit)
        case "digitalocean":
            for k in [
                "digitalocean-name",
                "digitalocean-region",
                "digitalocean-size",
                "digitalocean-image",
                "digitalocean-vpc-uuid",
                "digitalocean-ssh-keys",
                "do-token",
            ]:
                required(params, k, string_value, emit)
        case "no-infra":
            for k in [
                "no-infra-compute-ip",
                "no-infra-compute-user",
                "no-infra-compute-sudoer",
                "no-infra-compute-uid",
                "no-infra-compute-name",
            ]:
                required(params, k, string_value, emit)
        case _:
            emit("workflow/params → provider-compute", "invalid dispatch value")


def hosts_match_domain(params: dict[str, Any]) -> bool:
    domain = params.get("domain")
    apps = (params.get("once") or {}).get("applications") or [] if isinstance(params.get("once"), dict) else []
    return all(isinstance(a, dict) and a.get("host") and (a["host"] == domain or str(a["host"]).endswith(f".{domain}")) for a in apps)


def schema_errors(opts: Opts) -> list[CheckError] | None:
    """Validate the merged profile against the schema."""
    errors: list[CheckError] = []

    def emit(path: str, msg: str) -> None:
        errors.append({"check": "schema", "detail": f"{path}: {msg}"})

    profile_msg = string_value(profile_of(opts))
    if profile_msg:
        emit("render/profile", profile_msg)

    params = params_of(opts)
    if not isinstance(params, dict):
        emit("workflow/params", "missing required key")
    else:
        check_base_params(params, emit)
        check_smtp(params, emit)
        check_dns(params, emit)
        check_backend(params, emit)
        check_compute(params, emit)
        if not hosts_match_domain(params):
            emit("workflow/params", "every :once :applications :host must equal or be a subdomain of :domain")
    return errors or None


# -------------------------------------------------------------- tools

class ToolSpec(TypedDict):
    cmd: str
    name: str
    hint: str


AWS_HINT = "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"

base_tools: list[ToolSpec] = [
    {"cmd": "tofu", "name": "OpenTofu", "hint": "https://opentofu.org/docs/intro/install/"},
    {"cmd": "ansible-playbook", "name": "Ansible", "hint": "pipx install ansible"},
    {"cmd": "ssh", "name": "OpenSSH", "hint": "your distro's openssh-client package"},
    {"cmd": "curl", "name": "curl", "hint": "your distro's curl package"},
    {"cmd": "skopeo", "name": "skopeo", "hint": "https://github.com/containers/skopeo/blob/main/install.md"},
]


def provider_tools(params: dict[str, Any]) -> list[ToolSpec]:
    tools: list[ToolSpec] = []
    compute = params.get("provider-compute")
    backend = params.get("provider-backend")
    if compute == "oci":
        tools.append({"cmd": "oci", "name": "OCI CLI", "hint": "pip install oci-cli"})
    if compute == "hcloud":
        tools.append({"cmd": "hcloud", "name": "hcloud", "hint": "https://github.com/hetznercloud/cli"})
    if compute == "digitalocean":
        tools.append({"cmd": "doctl", "name": "doctl", "hint": "https://docs.digitalocean.com/reference/doctl/how-to/install/"})
    if backend in {"s3", "r2"}:
        tools.append({"cmd": "aws", "name": "AWS CLI", "hint": AWS_HINT})
    return tools


def which(cmd: str) -> bool:
    return shutil.which(cmd) is not None


def tool_errors(params: dict[str, Any], which_fn: Callable[[str], bool] = which) -> list[CheckError]:
    return [
        {"check": "tool", "detail": f"{t['name']} not found on PATH. Install: {t['hint']}"}
        for t in [*base_tools, *provider_tools(params)]
        if not which_fn(t["cmd"])
    ]


# -------------------------------------------------------------- credentials

RUN_TIMEOUT_MS = 30000


def run(args: list[str], extra_env: dict[str, str] | None = None) -> RunResult:
    try:
        env = {**os.environ, **(extra_env or {})}
        res = subprocess.run(
            args,
            input="",
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=RUN_TIMEOUT_MS / 1000,
            env=env,
            check=False,
        )
        return {"ok": res.returncode == 0, "exit": res.returncode, "out": res.stdout or "", "err": res.stderr or ""}
    except subprocess.TimeoutExpired:
        return {"ok": False, "exit": -1, "out": "", "err": f"command timed out after {RUN_TIMEOUT_MS}ms"}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "exit": -1, "out": "", "err": str(exc)}


def trim_snippet(s: str | None) -> str | None:
    t = (s or "").strip()
    if t == "":
        return None
    return f"{t[:200]}…" if len(t) > 200 else t


def _call_runner(run_fn: Runner, args: list[str], extra_env: dict[str, str] | None = None) -> RunResult:
    try:
        return run_fn(args, extra_env)
    except TypeError:
        return run_fn(args)  # type: ignore[misc]


def bearer_check(label: str, url: str, token: str, run_fn: Runner) -> str | None:
    r = _call_runner(run_fn, ["curl", "-sf", "-o", "/dev/null", "-H", f"Authorization: Bearer {token}", url])
    if r["ok"]:
        return None
    snippet = trim_snippet(r.get("err"))
    return f"{label}: token rejected (curl exit {r['exit']})" + (f" — {snippet}" if snippet else "")


def cloudflare_zone_check(domain: str, token: str, run_fn: Runner) -> str | None:
    url = f"https://api.cloudflare.com/client/v4/zones?name={quote(str(domain))}&status=active&per_page=1"
    r = _call_runner(run_fn, ["curl", "-sf", "-H", f"Authorization: Bearer {token}", url])
    if not r["ok"]:
        snippet = trim_snippet(r.get("err"))
        return f"Cloudflare API: token rejected (curl exit {r['exit']})" + (f" — {snippet}" if snippet else "")
    try:
        parsed = json.loads(r.get("out") or "")
        if parsed.get("success") is False:
            snippet = trim_snippet(json.dumps(parsed.get("errors")))
            return "Cloudflare API: zone lookup failed" + (f" — {snippet}" if snippet else "")
        if not parsed.get("result"):
            return f"Cloudflare zone: {domain} not found or not active"
        return None
    except Exception as exc:  # noqa: BLE001
        return f"Cloudflare API: invalid zone lookup response — {exc}"


def cli_check(label: str, args: list[str], extra_env: dict[str, str] | None, run_fn: Runner) -> str | None:
    r = _call_runner(run_fn, args, extra_env)
    if r["ok"]:
        return None
    return f"{label}: {trim_snippet(r.get('err')) or 'command failed'}"


def oci_config_path() -> str:
    return os.environ.get("OCI_CLI_CONFIG_FILE") or os.environ.get("OCI_CONFIG_FILE") or str(Path.home() / ".oci" / "config")


def oci_config_error() -> str | None:
    p = oci_config_path()
    if not Path(p).exists():
        return f"OCI: config file not found at {p} — run 'oci setup config' to create one"
    return None


def classify_head_bucket_error(err: str) -> Literal["missing-bucket", "bad-credentials", "unknown"]:
    s = (err or "").lower()
    if "(404)" in s or "not found" in s or "nosuchbucket" in s:
        return "missing-bucket"
    if any(x in s for x in ["(401)", "(403)", "forbidden", "unauthorized", "invalidaccesskey", "signaturedoesnotmatch"]):
        return "bad-credentials"
    return "unknown"


def r2_errors(params: dict[str, Any], run_fn: Runner) -> list[str]:
    bucket = params.get("r2-bucket")
    endpoint = params.get("r2-endpoint")
    access_key = params.get("r2-access-key-id")
    secret_key = params.get("r2-secret-access-key")
    missing: list[str] = []
    if blank_or_placeholder(endpoint):
        missing.append("r2-endpoint")
    if blank_or_placeholder(bucket):
        missing.append("r2-bucket")
    if blank_or_placeholder(access_key):
        missing.append("r2-access-key-id")
    if blank_or_placeholder(secret_key):
        missing.append("r2-secret-access-key")
    if missing:
        return [f"R2: missing or placeholder credentials: {', '.join(missing)}"]
    if not which("aws"):
        return []
    r = _call_runner(
        run_fn,
        ["aws", "s3api", "head-bucket", "--bucket", str(bucket), "--endpoint-url", str(endpoint)],
        {
            "AWS_ACCESS_KEY_ID": str(access_key),
            "AWS_SECRET_ACCESS_KEY": str(secret_key),
            "AWS_DEFAULT_REGION": "auto",
        },
    )
    if r["ok"]:
        return []
    snippet = trim_snippet(r.get("err")) or "head-bucket failed"
    kind = classify_head_bucket_error(r.get("err", ""))
    if kind == "missing-bucket":
        return [f"R2 (bucket): {bucket} not found at {endpoint} — {snippet}"]
    if kind == "bad-credentials":
        return [f"R2 (auth): credentials rejected at {endpoint} — {snippet}"]
    return [f"R2: head-bucket on {bucket} at {endpoint} failed — {snippet}"]


CLOUD_COMPUTE_PROVIDERS = {"oci", "hcloud", "digitalocean"}


def cloud_compute(params: dict[str, Any]) -> bool:
    return params.get("provider-compute") in CLOUD_COMPUTE_PROVIDERS


def ssh_pubkey_identity(s: str | None) -> str | None:
    parts = (s or "").strip().split()
    if len(parts) >= 2 and parts[0] and parts[1]:
        return f"{parts[0]} {parts[1]}"
    return None


def ssh_agent_errors(params: dict[str, Any], env: dict[str, str | None], run_fn: Runner = run) -> list[str]:
    if not cloud_compute(params):
        return []
    compute_pubkey = params.get("compute-pubkey")
    sock = (env.get("SSH_AUTH_SOCK") or "").strip()
    if is_placeholder(compute_pubkey):
        return ["SSH agent: :compute-pubkey still contains REPLACE_ME"]
    if sock == "":
        return ["SSH agent: SSH_AUTH_SOCK is not set; start ssh-agent and run ssh-add for :compute-pubkey"]
    r = _call_runner(run_fn, ["ssh-add", "-L"], {"SSH_AUTH_SOCK": sock})
    wanted = ssh_pubkey_identity(compute_pubkey if isinstance(compute_pubkey, str) else None)
    agent_msg = f"{r.get('err', '')}\n{r.get('out', '')}"
    if wanted is None:
        return ["SSH agent: :compute-pubkey is not a parseable SSH public key"]
    if r["ok"]:
        loaded = {x for x in (ssh_pubkey_identity(line) for line in (r.get("out") or "").splitlines()) if x is not None}
        if wanted in loaded:
            return []
        return [f"SSH agent: :compute-pubkey is not loaded in ssh-agent at SSH_AUTH_SOCK={sock}"]
    if "no identities" in agent_msg.lower():
        return [f"SSH agent: :compute-pubkey is not loaded; the agent at SSH_AUTH_SOCK={sock} has no identities"]
    snippet = trim_snippet(r.get("err"))
    return [f"SSH agent: ssh-add -L failed for SSH_AUTH_SOCK={sock} (exit {r['exit']})" + (f" — {snippet}" if snippet else "")]


def credential_errors(
    params: dict[str, Any],
    env: dict[str, str | None] | None = None,
    run_fn: Runner = run,
) -> list[CheckError]:
    env = os.environ if env is None else env
    p_smtp = params.get("provider-smtp")
    p_dns = params.get("provider-dns")
    p_compute = params.get("provider-compute")
    p_backend = params.get("provider-backend")
    domain = params.get("domain")
    single = [
        bearer_check("Resend API", "https://api.resend.com/api-keys", params.get("resend-api-key"), run_fn)
        if p_smtp == "resend" and real_value(params.get("resend-api-key"))
        else None,
        cloudflare_zone_check(str(domain), params.get("cloudflare-api-token"), run_fn)
        if p_dns == "cloudflare" and real_value(domain) and real_value(params.get("cloudflare-api-token"))
        else None,
        bearer_check("Hetzner Cloud API", "https://api.hetzner.cloud/v1/server_types", params.get("hcloud-token"), run_fn)
        if p_compute == "hcloud" and real_value(params.get("hcloud-token"))
        else None,
        bearer_check("DigitalOcean API", "https://api.digitalocean.com/v2/account", params.get("do-token"), run_fn)
        if p_compute == "digitalocean" and real_value(params.get("do-token"))
        else None,
        (oci_config_error() or cli_check("OCI", ["oci", "iam", "region", "list", "--output", "json"], None, run_fn))
        if p_compute == "oci" and which("oci")
        else None,
        cli_check("AWS (S3 backend)", ["aws", "sts", "get-caller-identity"], None, run_fn)
        if p_backend == "s3" and which("aws")
        else None,
    ]
    multi = [*(r2_errors(params, run_fn) if p_backend == "r2" else []), *ssh_agent_errors(params, env, run_fn)]
    return [{"check": "credential", "detail": m} for m in [*(x for x in single if x is not None), *multi]]


# -------------------------------------------------------------- images

def image_errors(params: dict[str, Any], run_fn: Runner = run) -> list[CheckError]:
    if not which("skopeo"):
        return []
    apps = (params.get("once") or {}).get("applications") or [] if isinstance(params.get("once"), dict) else []
    errors: list[CheckError] = []
    for app in apps:
        image = app.get("image") if isinstance(app, dict) else None
        if real_value(image):
            r = _call_runner(run_fn, ["skopeo", "inspect", "--no-tags", "--override-os", "linux", f"docker://{image}"])
            if not r["ok"]:
                errors.append({"check": "image", "detail": f"{image} — {trim_snippet(r.get('err')) or 'manifest unknown'}"})
    return errors


# -------------------------------------------------------------- top-level

def validate_report(opts: Opts, env: dict[str, str | None] | None = None) -> ValidateResult:
    """Validate the merged active profile."""
    env = os.environ if env is None else env
    merged = bc_workflow.read_bc_pars(to_bc_opts(opts), env)
    params = params_of(merged)
    errors = [
        *(schema_errors(merged) or []),
        *tool_errors(params),
        *credential_errors(params, env),
        *image_errors(params),
    ]
    return {"ok": len(errors) == 0, "errors": errors}


def group_name(k: CheckKind) -> str:
    return {"schema": "Schema", "tool": "Tools", "credential": "Credentials", "image": "Images"}.get(k, str(k))


def print_report(result: ValidateResult) -> None:
    if result["ok"]:
        print("All checks passed.")
        return
    n = len(result["errors"])
    print(f"Validation failed ({n} issue{'' if n == 1 else 's'}):")
    for k in ["schema", "tool", "credential", "image"]:
        es = [e for e in result["errors"] if e["check"] == k]
        if not es:
            continue
        print("")
        print(f"  {group_name(k)}:")
        for e in es:
            print(f"    - {e['detail']}")


def validate(_step_fns: Any, opts: Opts, report_fn: Callable[[Opts], ValidateResult] = validate_report) -> Opts:
    """Workflow step for ``once validate``."""
    result = report_fn(opts)
    print_report(result)
    base = {**opts, "validation/result": result}
    return ok_alias(base) if result["ok"] else status(base, 1, "validation failed")


# TypeScript-style aliases.
schemaErrors = schema_errors
providerTools = provider_tools
toolErrors = tool_errors
credentialErrors = credential_errors
sshAgentErrors = ssh_agent_errors
validateReport = validate_report
