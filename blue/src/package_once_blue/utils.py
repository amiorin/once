from __future__ import annotations

# Bump on any change a launcher pinned to an older commit could not survive.
#
# 3: deploy keys are generated per application on every create instead of being
#    supplied as deploy-pubkey, and an application may name a GitHub repository
#    whose environment receives the connection details. A launcher pinned older
#    still expects deploy-pubkey in desired state and would ignore github
#    silently, publishing nothing.
# 4: the machine keypair moves from .ssh/ next to colors.yml to the operator's
#    ~/.ssh, still profile-named. A launcher pinned older generates and
#    resolves the key inside the checkout, so it cannot see a keypair living
#    in ~/.ssh and would generate a second one beside a live deployment's
#    state.
CONTRACT = 4


def registrable_domain(host: object) -> str | None:
    labels = str(host or "").split(".")
    return ".".join(labels[-2:]) if len(labels) >= 2 else None


def apps_domains(opts: dict) -> list[str]:
    apps = (opts.get("once") or {}).get("applications") or []
    return sorted({domain for app in apps if (domain := registrable_domain(app.get("host")))})
