from __future__ import annotations

import os

from blue.cli import read_pars

CONTRACT = 1


def read_once_pars(opts: dict, env: dict[str, str] | None = None) -> dict:
    values = dict(os.environ if env is None else env)
    portable = {
        "BLUE_PAR_" + name.removeprefix("ONCE_PAR_"): value
        for name, value in values.items()
        if name.startswith("ONCE_PAR_")
    }
    return read_pars(read_pars(opts, values), portable)


def registrable_domain(host: object) -> str | None:
    labels = str(host or "").split(".")
    return ".".join(labels[-2:]) if len(labels) >= 2 else None


def apps_domains(opts: dict) -> list[str]:
    apps = (opts.get("once") or {}).get("applications") or []
    return sorted({domain for app in apps if (domain := registrable_domain(app.get("host")))})
