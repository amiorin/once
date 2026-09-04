# Drive the Compute Provider Standard's operations — selection, the network
# contract, the name rules, the §4 switch and legacy refusals, the state read,
# adoption and the missing-ip refusal — through blue's `compute` module with a
# two-provider stub spec, printing one normalized
# `case <name> exit=<n> errors=<messages joined by " | ">` line per scenario
# (value-bearing scenarios append ` value=<fields>`). Green and red print the
# same shape, so parity.sh can diff them: none of this logic reaches a build
# artifact, and the messages are contract for every package that delegates to
# ONCE. Exit is the real `blue/exit` where a scenario returns opts and 2 (the
# CLI's validation exit) where it returns messages.
import asyncio
import os
import re
import tempfile

from blue.workflow import StepError
from package_once_blue import compute

registry = {
    "vultr": {
        "required": ["vultr-region", "vultr-plan", "vultr-os-id", "vultr-ssh-sources", "vultr-http-sources"],
        "secrets": ["vultr-api-key"],
        "tofu-env": {"vultr-api-key": "VULTR_API_KEY"},
    },
    "digitalocean": {
        "required": ["digitalocean-region", "digitalocean-size", "digitalocean-image",
                     "digitalocean-ssh-sources", "digitalocean-http-sources"],
        "secrets": ["do-token"],
        "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"},
    },
}

spec = {
    "registry": registry,
    "default": "vultr",
    "sources": {"non_empty": ["ssh-sources"], "may_be_empty": ["http-sources"]},
}
three = {**spec, "sources": {"non_empty": ["ssh-sources"], "may_be_empty": ["http-sources", "stun-sources"]}}
own = {**spec, "name_rules": {"vultr": {"re": re.compile(r"x"), "message": "must be x"}}}


def vultr(**kvs):
    return {"profile": "prod", "provider-compute": "vultr", **kvs}


def digitalocean(**kvs):
    return {"profile": "prod", "provider-compute": "digitalocean", **kvs}


def b(x) -> str:
    return "true" if x else "false"


def line(case_name: str, exit: int, errors: list[str], value: str | None = None) -> None:
    joined = " | ".join(e.replace("\n", "\\n") for e in errors)
    print(f"case {case_name} exit={exit} errors={joined}" + ("" if value is None else f" value={value}"))


def errs(case_name: str, errors: list[str]) -> None:
    line(case_name, 0 if not errors else 2, errors)


def out(case_name: str, opts: dict, value: str | None = None) -> None:
    err = opts.get("blue/err")
    line(case_name, opts.get("blue/exit") or 0, [] if err is None else [str(err)], value)


def tmp_dir() -> str:
    dir = tempfile.mkdtemp(prefix="once-compute-parity")
    os.environ["HOME"] = dir
    return dir


# --- selection
errs("selection-unknown", compute.selection_errors(spec, {"provider-compute": "hetzner"}))
errs("selection-unselected-skips-checks",
     compute.state_errors(spec, {"provider-compute": "hetzner", "hetzner-ssh-sources": ["nope"], "hetzner-name": "BAD NAME"}))
errs("selection-ignores-other-provider",
     compute.state_errors(spec, vultr(**{"vultr-ssh-sources": ["10.0.0.0/8"], "vultr-os-id": 2284,
                                          "digitalocean-ssh-sources": ["nope"], "digitalocean-vpc-uuid": "vpc-123",
                                          "digitalocean-name": "BAD NAME"})))
line("required-keys", 0, [], ";".join([
    ",".join(compute.required_keys(spec, vultr())),
    ",".join(compute.required_keys(spec, digitalocean())),
    str(len(compute.required_keys(spec, {}))),
]))
line("secrets-and-tofu-env", 0, [], ";".join([
    ",".join(compute.secrets(spec, vultr())),
    ",".join(compute.secrets(spec, digitalocean())),
    str(len(compute.secrets(spec, {}))),
    ",".join(f"{k}={v}" for k, v in compute.tofu_env(spec, vultr()).items()),
    ",".join(f"{k}={v}" for k, v in compute.tofu_env(spec, digitalocean()).items()),
    str(len(compute.tofu_env(spec, {}))),
]))
line("compute-key-and-name", 0, [], ";".join([
    compute.compute_key(vultr(), "ssh-sources"),
    compute.compute_key(digitalocean(), "name"),
    compute.compute_name(vultr()),
    compute.compute_name(vultr(**{"vultr-name": " box "})),
    compute.compute_name(vultr(**{"vultr-name": "REPLACE_ME"})),
    compute.compute_name(vultr(**{"vultr-name": ""})),
    compute.compute_name(vultr(**{"digitalocean-name": "other"})),
]))

# --- sources
errs("source-empty-non-empty", compute.source_errors(spec, vultr(**{"vultr-ssh-sources": []})))
errs("source-empty-may-be-empty", compute.source_errors(spec, vultr(**{"vultr-ssh-sources": ["10.0.0.0/8"], "vultr-http-sources": []})))
errs("source-malformed-per-key",
     compute.source_errors(spec, vultr(**{"vultr-ssh-sources": ["10.0.0.0/8", "nope"], "vultr-http-sources": ["::1/129", "1.2.3.4/32"]})))
errs("source-overlay-string", compute.source_errors(spec, vultr(**{"vultr-ssh-sources": "10.0.0.0/8, 192.0.2.0/24 bad"})))
errs("source-absent-skipped", compute.source_errors(spec, vultr()))
errs("source-blank-skipped", compute.source_errors(spec, vultr(**{"vultr-ssh-sources": "  "})))
errs("source-v4-grammar", compute.source_errors(spec, vultr(**{"vultr-ssh-sources": [
    "10.0.0.0/8", "0.0.0.0/0", "203.0.113.7/32", "10.0.0.0/33", "256.0.0.1/8", "example.com/32", "10.0.0.0", "10.0.0.0/", "é/32", "a\"b/32", "a\\b/32", "10.0.0.0/8/8"]})))
errs("source-v6-grammar", compute.source_errors(spec, vultr(**{"vultr-ssh-sources": [
    "2001:db8::/32", "::/0", "::1/128", "1:2:3:4:5:6:7:8/128", "2001:db8:::1/64", "1:2:3:4:5:6:7:8:9/64", "2001:db8::/129", "2001:db8::g/64"]})))
errs("source-v4-tail", compute.source_errors(spec, vultr(**{"vultr-ssh-sources": [
    "::ffff:203.0.113.7/128", "64:ff9b::192.0.2.33/96", "::ffff:300.0.0.1/128", "192.0.2.1::/96"]})))
errs("source-stun-spec", compute.source_errors(three, vultr(**{"vultr-ssh-sources": ["10.0.0.0/8"], "vultr-stun-sources": ["x"]})))
errs("source-stun-outside-spec", compute.source_errors(spec, vultr(**{"vultr-stun-sources": ["x"]})))

# --- provider
errs("name-vultr-override-bad", compute.provider_errors(spec, vultr(**{"vultr-name": "bad name!"})))
errs("name-vultr-profile-bad", compute.provider_errors(spec, vultr(profile="bad name!")))
errs("name-do-override-bad", compute.provider_errors(spec, digitalocean(**{"digitalocean-name": "Upper"})))
errs("name-do-profile-bad", compute.provider_errors(spec, digitalocean(profile="under_score")))
errs("name-do-placeholder-falls-through", compute.provider_errors(spec, digitalocean(profile="Bad", **{"digitalocean-name": "REPLACE_ME"})))
errs("name-do-too-long", compute.provider_errors(spec, digitalocean(**{"digitalocean-name": "a" * 64})))
errs("name-ok", [
    *compute.provider_errors(spec, digitalocean(**{"digitalocean-name": "a" * 63})),
    *compute.provider_errors(spec, digitalocean(**{"digitalocean-name": "prod-1.example"})),
    *compute.provider_errors(spec, vultr(**{"vultr-name": " Prod_1 "})),
    *compute.provider_errors(spec, digitalocean(profile="")),
])
errs("name-spec-rules-win", [
    *compute.provider_errors(own, vultr(**{"vultr-name": "prod"})),
    *compute.provider_errors(own, digitalocean(**{"digitalocean-name": "Upper"})),
])
errs("vultr-os-id-string", compute.provider_errors(spec, vultr(**{"vultr-os-id": "2284"})))
errs("vultr-os-id-int", compute.provider_errors(spec, vultr(**{"vultr-os-id": 2284})))
errs("do-vpc-bans", compute.provider_errors(spec, digitalocean(**{"digitalocean-vpc-uuid": "vpc-123", "digitalocean-vpc-cidr": "10.0.0.0/16"})))
errs("provider-other-selected", [
    *compute.provider_errors(spec, vultr(**{"digitalocean-vpc-uuid": "vpc-123", "digitalocean-name": "BAD NAME"})),
    *compute.provider_errors(spec, digitalocean(**{"vultr-os-id": "2284", "vultr-name": "bad name!"})),
])
errs("state-errors-order", compute.state_errors(spec, digitalocean(**{"digitalocean-ssh-sources": ["nope"], "digitalocean-name": "Upper"})))

# --- provider-state
errs("pse-nil", compute.provider_state_errors(spec, vultr(), None))
errs("pse-match", compute.provider_state_errors(spec, vultr(), {"provider": "vultr", "ip": "1.2.3.4"}))
errs("pse-mismatch-do-on-vultr", compute.provider_state_errors(spec, vultr(), {"provider": "digitalocean"}))
errs("pse-mismatch-vultr-on-do", compute.provider_state_errors(spec, digitalocean(), {"provider": "vultr"}))
errs("pse-legacy-default", compute.provider_state_errors(spec, vultr(), {"ip": "1.2.3.4"}))
errs("pse-legacy-non-default", compute.provider_state_errors(spec, digitalocean(), {"ip": "1.2.3.4"}))
errs("pse-legacy-empty-recorded", compute.provider_state_errors(spec, digitalocean(), {"provider": ""}))

# --- params
fb = compute.fallback_params(vultr(**{"vultr-name": "box"}))
line("fallback-params", 0, [], ";".join([fb["provider"], fb["ip"], fb["user"], fb["sudoer"], fb["name"]]))
line("lifecycle-event", 0, [], ";".join(b(compute.lifecycle_event(ctx)) for ctx in [
    {"event": "create", "real": True}, {"event": "delete", "real": True},
    {"event": "create", "real": False}, {"event": "build", "real": True}]))
out("resolved-missing-ip", compute.resolved_compute({}, compute.fallback_params(vultr()), None))
out("resolved-no-ip-key", compute.resolved_compute({}, compute.fallback_params(vultr()), {"name": "prod"}))
o = compute.resolved_compute({}, compute.fallback_params(vultr()), {"ip": "1.2.3.4", "name": "box"})
out("resolved-present-ip", o, ";".join([o["provider"], o["ip"], o["user"], o["sudoer"], o["name"]]))
p = compute.output_params({"tofu/outputs": {"params": {"ip": "1.2.3.4", "ssh_key_id": "77"}}})
line("output-params", 0, [], ";".join([p["ip"], p["ssh_key_id"], b(compute.output_params({}) is None)]))


# --- read-state: each SDK's typed failure is constructed here, since no tofu
# runs. Blue's is the StepError blue.tofu raises.
def rs(case_name: str, reader) -> None:
    try:
        r = asyncio.run(compute.read_state(vultr(), reader))
    except Exception as error:  # noqa: BLE001 — the scenario is "does it propagate"
        r = {"propagated": str(error)}
    if "propagated" in r:
        value = f"propagated:{r['propagated']}"
    elif "params" in r:
        params = r["params"]
        value = f"params:{params['ip']},{params['seen']}" if params else "params:none"
    else:
        value = "error"
    line(case_name, 1 if r.get("error") else 0, [r["error"]] if r.get("error") else [], value)


async def step_error(_opts):
    raise StepError("tofu output failed: boom")


async def step_error_no_message(_opts):
    raise StepError("")


async def nothing(_opts):
    return None


async def params_reader(opts):
    return {"ip": "1.2.3.4", "seen": opts.get("profile")}


async def other(_opts):
    raise RuntimeError("defect")


async def untyped(_opts):
    raise TypeError("defect")


rs("read-state-step-error", step_error)
rs("read-state-no-message", step_error_no_message)
rs("read-state-empty-message", step_error_no_message)
rs("read-state-nil", nothing)
rs("read-state-params", params_reader)
rs("read-state-other-propagates", other)
rs("read-state-untyped-propagates", untyped)

# --- provider-validator
called = 0


def thunk():
    global called
    called += 1
    return ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]


def v(case_name: str, params) -> None:
    e = compute.provider_validator(spec, vultr(), params, thunk)
    line(case_name, 0 if not e else 2, e, f"thunk-calls:{called}")


v("validator-mismatch", {"provider": "digitalocean"})
v("validator-match", {"provider": "vultr"})
v("validator-no-state", None)

# --- adopt-state
opt_out = vultr(**{"vultr-ssh-keys": "key-uuid"})
out("adopt-delete-error", compute.adopt_state(opt_out, "delete", {"error": "HTTP 403 from backend"}))
out("adopt-rehearse-error", compute.adopt_state(opt_out, "rehearse", {"error": "HTTP 403 from backend"}))
out("adopt-describe-error", compute.adopt_state(opt_out, "describe", {"error": "HTTP 403 from backend"}))
o = compute.adopt_state({**opt_out, "ip": "9.9.9.9"}, "delete", {"params": {"ip": "1.2.3.4", "ssh_key_id": "77", "provider": "vultr"}})
out("adopt-params", o, f"ip:{o['ip']};ssh_key_id:{o['ssh_key_id']};keygen:{b('ssh-keygen' in o)}")
empty = compute.adopt_state(opt_out, "delete", {"params": None})
out("adopt-nil-params", empty, f"ip:{b('ip' in empty)}")
dir = tmp_dir()
k = compute.adopt_state(vultr(), "delete", {"params": {"ip": "1.2.3.4"}})
out("adopt-keygen", k, f"ip:{k['ip']};keygen:{b(k.get('ssh-keygen'))};key-under-home:{b(str(k.get('vultr-ssh-keys')).startswith(dir))}")
