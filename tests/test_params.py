from __future__ import annotations

from once.interop import PARAMS
from once.options import profile_alpha
from once.params import opts_fn


def test_opts_fn_keeps_bc_par_overrides_when_alias_is_stale(monkeypatch):
    monkeypatch.setenv("BC_PAR_DO_TOKEN", "env-token")

    result = opts_fn(profile_alpha)

    assert result[PARAMS]["do-token"] == "env-token"
    assert result["params"]["do-token"] == "env-token"
