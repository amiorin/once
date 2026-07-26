from __future__ import annotations

CONTRACT = 2


def registrable_domain(host: object) -> str | None:
    labels = str(host or "").split(".")
    return ".".join(labels[-2:]) if len(labels) >= 2 else None


def apps_domains(opts: dict) -> list[str]:
    apps = (opts.get("once") or {}).get("applications") or []
    return sorted({domain for app in apps if (domain := registrable_domain(app.get("host")))})
