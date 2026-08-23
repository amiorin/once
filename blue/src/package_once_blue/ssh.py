"""The machine-access SSH keypair a deployment owns.

Implements the SSH Keypair Standard (workspace ``standards/ssh-keypair.md``):
when the selected compute provider's machine-key configuration key is absent
from desired state, the package generates and manages an ed25519 keypair named
after the profile, in ``.ssh/`` next to colors.yml. When the key is present,
everything here steps aside and the value is used exactly as before the
standard — presence is the only switch.

Key material is like state: losing it loses access to the machine. So the
keypair lives outside the regenerable workdir, an existing key without state
is an error rather than something to overwrite, a provider-side key named
after the profile but absent from our state is an error rather than something
to import, and delete removes the local key only after the compute destroy
succeeded.

Generation shells ``ssh-keygen`` like ``github``: three languages agreeing on
OpenSSH private-key encoding is a parity problem, one subprocess is not. The
private key never enters the opts map — templates receive only paths.
"""

from __future__ import annotations

import json
import urllib.request
from pathlib import Path
from typing import Awaitable, Callable

from blue.runtime import ExecResult, runtime

from .validate import placeholder

_RUN_TIMEOUT_MS = 30_000
_HTTP_TIMEOUT_S = 30

Runner = Callable[..., Awaitable[ExecResult]]


async def _default_runner(args: list[str], *, env: dict | None = None) -> ExecResult:
    return await runtime.exec(args, env=env, timeout_ms=_RUN_TIMEOUT_MS)


# The public key a build or dry-run renders where content (not a path) is
# interpolated. Fixed, so the artifact stays deterministic and byte-identical
# across colours whether or not .ssh/ exists.
placeholder_public = "ssh-ed25519 PLACEHOLDER managed-by-colors"

# Compute provider -> the desired-state key that carries the machine key.
# Absent or placeholder value = keygen mode. no-infra provisions no machine,
# so it has no entry and never generates.
machine_key_keys = {
    "azure": "azure-ssh-authorized-keys",
    "aws": "aws-ssh-authorized-keys",
    "google": "google-ssh-authorized-keys",
    "digitalocean": "digitalocean-ssh-keys",
    "hcloud": "hcloud-ssh-keys",
    "vultr": "vultr-ssh-keys",
    "yandex": "compute-pubkey",
    "oci": "oci-ssh-authorized-keys",
}

# Registered-key providers with a token-bearing REST API the create preflight
# can list account keys through. AWS is exempt by design: aws_key_pair names
# are unique per region and the instance depends on the key pair, so a
# duplicate name fails the apply before any instance exists.
preflight_providers = {"digitalocean", "hcloud", "vultr"}


def keygen(opts: dict) -> bool:
    """Whether this deployment is in keygen mode.

    The selected compute provider takes a machine key and desired state does
    not supply one. Once ``with_machine_key`` has filled the provider key with
    the generated path the desired-state test alone would flip to opt-out, so
    the ``ssh-keygen`` flag it stamps keeps the answer sticky for the rest of
    the run.
    """
    key = machine_key_keys.get(str(opts.get("provider-compute")))
    return bool(opts.get("ssh-keygen") or (key and placeholder(opts.get(key))))


def profile(opts: dict) -> str:
    return str(opts.get("profile") or "default")


def _project_dir(opts: dict) -> Path:
    """The directory holding colors.yml — where .ssh/ lives.

    .ssh/ sits outside the workdir on purpose: the workdir is regenerable
    output and the key is not.
    """
    state_file = opts.get("blue/state-file")
    return Path(str(state_file)).parent if state_file else Path(".")


def ssh_dir(opts: dict) -> str:
    return str(_project_dir(opts) / ".ssh")


def private_key_path(opts: dict) -> str:
    return str(_project_dir(opts) / ".ssh" / profile(opts))


def public_key_path(opts: dict) -> str:
    return f"{private_key_path(opts)}.pub"


def _fail(opts: dict, message: str) -> dict:
    return {**opts, "blue/exit": 1, "blue/err": message}


def with_machine_key(opts: dict, real: bool) -> dict:
    """Fill the template values keygen mode owns, for every event.

    Opt-out opts pass through untouched. Path providers get the absolute
    public-key path (OpenTofu resolves relative paths against the stage
    directory, and the workdir is relocatable while .ssh/ is not); the content
    provider gets the key content on real events and the fixed placeholder
    otherwise, so builds never read .ssh/.
    """
    if not keygen(opts):
        return opts
    key = machine_key_keys[str(opts.get("provider-compute"))]
    prv = str(Path(private_key_path(opts)).absolute())
    pub = str(Path(public_key_path(opts)).absolute())
    if real and Path(pub).exists():
        content = Path(pub).read_text().strip()
    else:
        content = placeholder_public
    return {
        **opts,
        "ssh-keygen": True,
        "ssh-private-key-path": prv,
        "ssh-public-key-path": pub,
        key: content if key == "compute-pubkey" else pub,
    }


def identity_args(opts: dict) -> list[str]:
    """ssh arguments selecting the deployment's key, empty in opt-out mode.

    Every ssh the package runs against the machine (host-key capture,
    describe) threads these, because in keygen mode nothing guarantees an
    agent holds the key.
    """
    if opts.get("ssh-keygen"):
        return ["-o", "IdentitiesOnly=yes", "-i", str(opts.get("ssh-private-key-path"))]
    return []


# ------------------------------------------------------------- permissions


def _enforce_perms(opts: dict) -> str | None:
    """700 on .ssh/, 600 on the private key — on every real run.

    Not only at generation, so a checkout restored with wrong permissions
    fails early.
    """
    try:
        Path(ssh_dir(opts)).chmod(0o700)
        prv = Path(private_key_path(opts))
        if prv.exists():
            prv.chmod(0o600)
        return None
    except Exception as error:
        return f"cannot enforce permissions on {ssh_dir(opts)}: {error}"


# ------------------------------------------------- the create-time matrix


def _keygen_args(opts: dict, path: str) -> list[str]:
    return ["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", f"{profile(opts)} managed by Colors", "-f", path]


async def ensure_key(opts: dict, state_fn, run_fn: Runner = _default_runner) -> dict:
    """The standard's create matrix, generation, and permission enforcement.

    Runs on a real create in keygen mode. ``state_fn`` reads the compute
    stage's applied ``params`` output best-effort (None when no state is
    readable): state and key agreeing means converge, disagreeing means a
    human has to act, and neither existing means first create. An existing key
    without state is never overwritten — it may be the only credential to a
    host that is still alive.

    Threads the state params through ``once/ssh-state-params`` so the provider
    preflight does not read state twice.
    """
    if not keygen(opts):
        return opts
    prv = private_key_path(opts)
    pub = public_key_path(opts)
    has_prv = Path(prv).exists()
    has_pub = Path(pub).exists()
    state = await state_fn(opts)
    threaded = {**opts, "once/ssh-state-params": state}

    if state and not (has_prv or has_pub):
        return _fail(threaded, f"compute state exists but {prv} is missing: this checkout has lost the machine key. Restore .ssh/ from where the deployment was created, or rebuild; a regenerated key cannot reach the existing host.")
    if (has_prv or has_pub) and not (has_prv and has_pub):
        return _fail(threaded, f".ssh/ holds half a keypair for {profile(opts)} (private {'present' if has_prv else 'missing'}, public {'present' if has_pub else 'missing'}): restore the missing half, or — after verifying no host for {profile(opts)} survives — remove both and retry.")
    if not state and has_prv:
        return _fail(threaded, f"{prv} exists but no compute state is readable: the previous delete may be incomplete, or a first create was interrupted. Verify at the provider that no host for {profile(opts)} survives; if it is confirmed gone (or the interrupted create never made one), remove {prv} and {pub} and retry.")
    if has_prv:
        error = _enforce_perms(threaded)
        return _fail(threaded, error) if error else threaded
    Path(prv).parent.mkdir(parents=True, exist_ok=True)
    result = await run_fn(_keygen_args(opts, prv))
    if result.exit != 0:
        return _fail(threaded, f"ssh-keygen failed for {profile(opts)}: {str(result.err or '').strip()}")
    error = _enforce_perms(threaded)
    return _fail(threaded, error) if error else threaded


# ------------------------------------------- the provider-side preflight


def _http_get_json(url: str, headers: dict[str, str]) -> dict:
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=_HTTP_TIMEOUT_S) as response:
        if not 200 <= response.status < 300:
            raise RuntimeError(f"HTTP {response.status} from {url}")
        return json.loads(response.read().decode())


def _normalize_key(value: object) -> str:
    """The comparable part of an OpenSSH public key: type and material."""
    return " ".join(str(value or "").strip().split()[:2])


def fetch_account_keys(provider: str, token: str) -> list[dict]:
    """Every SSH key registered in the provider account.

    ``[{"id", "name", "public"}]``, following pagination. A listing failure
    raises: the preflight answers or the create does not proceed.
    """
    headers = {"Authorization": f"Bearer {token}"}
    keys: list[dict] = []
    if provider == "digitalocean":
        url = "https://api.digitalocean.com/v2/account/keys?per_page=200"
        while url:
            body = _http_get_json(url, headers)
            for key in body.get("ssh_keys") or []:
                keys.append({"id": str(key.get("id")), "name": str(key.get("name")), "public": _normalize_key(key.get("public_key"))})
            url = ((body.get("links") or {}).get("pages") or {}).get("next")
        return keys
    if provider == "hcloud":
        page: int | None = 1
        while page:
            body = _http_get_json(f"https://api.hetzner.cloud/v1/ssh_keys?per_page=50&page={page}", headers)
            for key in body.get("ssh_keys") or []:
                keys.append({"id": str(key.get("id")), "name": str(key.get("name")), "public": _normalize_key(key.get("public_key"))})
            page = ((body.get("meta") or {}).get("pagination") or {}).get("next_page")
        return keys
    cursor = None
    while True:
        url = f"https://api.vultr.com/v2/ssh-keys?per_page=100{f'&cursor={cursor}' if cursor else ''}"
        body = _http_get_json(url, headers)
        for key in body.get("ssh_keys") or []:
            keys.append({"id": str(key.get("id")), "name": str(key.get("name")), "public": _normalize_key(key.get("ssh_key"))})
        cursor = ((body.get("meta") or {}).get("links") or {}).get("next")
        if not cursor:
            return keys


_preflight_tokens = {
    "digitalocean": "do-token",
    "hcloud": "hcloud-token",
    "vultr": "vultr-api-key",
}


def preflight(opts: dict, fetch_fn=fetch_account_keys) -> dict:
    """Refuse a real create when the provider holds a key we do not own.

    A key named after the profile that this deployment's state does not own is
    an error. Ownership is the resource id recorded in state (surfaced through
    the compute stage's ``ssh_key_id`` output param) — names are conventions
    anyone can copy. A found key is never adopted: if state was lost, the
    instance is likely orphaned too, and importing the key would let create
    build a second machine next to the first. The local public key decides the
    message: matching material is our leftover, anything else is foreign and
    must not be deleted.
    """
    provider = str(opts.get("provider-compute"))
    if not (keygen(opts) and provider in preflight_providers):
        return opts
    token = str(opts.get(_preflight_tokens[provider]) or "")
    owned = (opts.get("once/ssh-state-params") or {}).get("ssh_key_id")
    try:
        account_keys = fetch_fn(provider, token)
    except Exception as error:
        return _fail(opts, f"cannot list {provider} SSH keys for the create preflight: {error}")
    found = next((key for key in account_keys if key["name"] == profile(opts)), None)
    if found is None:
        return opts
    if owned is not None and str(owned) == found["id"]:
        return opts
    pub_path = public_key_path(opts)
    local_pub = _normalize_key(Path(pub_path).read_text()) if Path(pub_path).exists() else None
    if local_pub and local_pub == found["public"]:
        return _fail(opts, f"{provider} already has an SSH key named {profile(opts)} (id {found['id']}) that is not in this deployment's state and matches {pub_path}: a previous delete left it behind. Verify no host for {profile(opts)} survives, delete that key at the provider, and retry.")
    return _fail(opts, f"{provider} already has an SSH key named {profile(opts)} (id {found['id']}) that is not in this deployment's state and does not match {pub_path}. Do not delete it: it belongs to something else. Investigate, or change profile.")


# ----------------------------------------------------------------- delete


def cleanup_step(opts: dict) -> dict:
    """Remove the generated keypair after a successful compute destroy.

    The delete DAG wires this after the compute destroy, so reaching it means
    the destroy succeeded and the invariant "key present ⇔ deployment exists"
    holds. A failed or interrupted delete leaves the key, correctly: it is
    still needed. Removes .ssh/ itself only when nothing else lives there.
    """
    if opts.get("blue/event") != "delete" or not keygen(opts):
        return {**opts, "blue/exit": 0}
    for path in (private_key_path(opts), public_key_path(opts)):
        file = Path(path)
        if file.exists():
            file.unlink()
    directory = Path(ssh_dir(opts))
    if directory.exists() and not any(directory.iterdir()):
        directory.rmdir()
    return {**opts, "blue/exit": 0}
