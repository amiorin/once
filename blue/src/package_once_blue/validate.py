from __future__ import annotations

import re
from typing import Any

from blue.cli import par_name

providers: dict[str, dict[str, dict[str, Any]]] = {
    "provider-compute": {
        "digitalocean": {"required": ["digitalocean-name", "digitalocean-region", "digitalocean-size", "digitalocean-image", "digitalocean-ssh-keys"], "secrets": ["do-token"], "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"}},
        "hcloud": {"required": ["hcloud-name", "hcloud-image", "hcloud-server-type", "hcloud-location", "hcloud-ssh-keys"], "secrets": ["hcloud-token"], "tofu-env": {"hcloud-token": "HCLOUD_TOKEN"}},
        "yandex": {"required": ["yandex-cloud-id", "yandex-folder-id", "yandex-zone", "yandex-image-family", "yandex-name", "yandex-subnet-cidr", "yandex-platform-id", "yandex-cores", "yandex-memory-gb", "yandex-core-fraction", "yandex-disk-size-gb", "compute-pubkey"], "secrets": ["yandex-token"], "tofu-env": {"yandex-token": "YC_TOKEN"}},
        "oci": {"required": ["oci-config-file-profile", "oci-subnet-id", "oci-compartment-id", "oci-availability-domain", "oci-display-name", "oci-shape", "oci-ocpus", "oci-memory-in-gbs", "oci-boot-volume-size-in-gbs", "oci-boot-volume-vpus-per-gb", "oci-ssh-authorized-keys"], "secrets": [], "tofu-env": {}},
        "no-infra": {"required": ["no-infra-compute-ip", "no-infra-compute-user", "no-infra-compute-sudoer", "no-infra-compute-uid"], "secrets": [], "tofu-env": {}},
    },
    "provider-smtp": {
        "resend": {"required": [], "secrets": ["resend-api-key", "resend-password"], "tofu-env": {"resend-api-key": "RESEND_API_KEY"}},
        "no-infra": {"required": ["no-infra-smtp-server", "no-infra-smtp-port", "no-infra-smtp-username"], "secrets": ["no-infra-smtp-password"], "tofu-env": {}},
    },
    "provider-dns": {
        "cloudflare": {"required": [], "secrets": ["cloudflare-api-token"], "tofu-env": {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}},
        "no-infra": {"required": [], "secrets": [], "tofu-env": {}},
    },
    "provider-backend": {
        "local": {"required": [], "secrets": [], "tofu-env": {}},
        "s3": {"required": ["s3-bucket", "s3-region"], "secrets": [], "tofu-env": {}},
        "r2": {"required": ["r2-bucket", "r2-endpoint"], "secrets": ["r2-access-key-id", "r2-secret-access-key"], "tofu-env": {"r2-access-key-id": "AWS_ACCESS_KEY_ID", "r2-secret-access-key": "AWS_SECRET_ACCESS_KEY"}},
    },
}

_slots = ["provider-compute", "provider-smtp", "provider-dns", "provider-backend"]
_domain_re = re.compile(r"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
_env_re = re.compile(r"^[A-Z_][A-Z0-9_]*$")


def _entry(opts: dict, slot: str) -> dict:
    return providers.get(slot, {}).get(str(opts.get(slot)), {})


def tofu_env(opts: dict, slot: str) -> dict[str, str]:
    return _entry(opts, slot).get("tofu-env", {})


def _slot_keys(opts: dict, field: str) -> list[str]:
    return [key for slot in _slots for key in _entry(opts, slot).get(field, [])]


def placeholder(value: object) -> bool:
    return value is None or (isinstance(value, str) and (not value.strip() or value.upper() == "REPLACE_ME"))


def _applications(opts: dict) -> list | None:
    value = (opts.get("once") or {}).get("applications")
    return value if isinstance(value, list) else None


def state_errors(opts: dict) -> list[str]:
    errors: list[str] = []
    for key in ["profile", "workdir", "deploy-pubkey", *_slot_keys(opts, "required")]:
        if placeholder(opts.get(key)):
            errors.append(f"{key} is required")
    for slot in _slots:
        if opts.get(slot) not in providers[slot]:
            errors.append(f"unsupported {slot} {opts.get(slot)!r}")
    apps = _applications(opts)
    if not apps:
        errors.append("once applications must be a non-empty sequence")
    for index, app in enumerate(apps or []):
        if placeholder(app.get("host")) or not _domain_re.fullmatch(str(app.get("host"))):
            errors.append(f"once applications[{index}] has an invalid host")
        if placeholder(app.get("image")):
            errors.append(f"once applications[{index}] requires image")
        env = app.get("env")
        if env is not None and not isinstance(env, (dict, list)):
            errors.append(f"once applications[{index}] env must map container variable names to blue.yml keys")
        if isinstance(env, dict):
            for name, key in env.items():
                if not _env_re.fullmatch(str(name)):
                    errors.append(f"once applications[{index}] has an invalid container variable name {name}")
                if placeholder(key):
                    errors.append(f"once applications[{index}] env {name} needs a blue.yml key")
    if not isinstance(opts.get("compute-prevent-destroy"), bool):
        errors.append("compute-prevent-destroy must be true or false")
    if not str(opts.get("deploy-pubkey") or "").startswith("ssh-"):
        errors.append("deploy-pubkey must be an SSH public key")
    key = opts.get("compute-pubkey")
    if not placeholder(key) and not str(key).startswith("ssh-"):
        errors.append("compute-pubkey must be an SSH public key")
    return errors


def secret_errors(opts: dict) -> list[str]:
    apps = _applications(opts) or []
    app_keys = (
        [str(key) for app in apps if isinstance(app.get("env"), dict) for key in app["env"].values()]
        if opts.get("blue/event") == "create"
        else []
    )
    keys = list(dict.fromkeys([*_slot_keys(opts, "secrets"), *app_keys]))
    return [f"required credential is not set: {par_name(key)}" for key in keys if placeholder(opts.get(key))]
