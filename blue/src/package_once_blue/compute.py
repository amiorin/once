"""The operations of the Compute Provider Standard, over a package-owned registry.

Implements the workspace ``standards/compute-provider.md``. A package
describes itself with one spec value and passes it to every function that
needs it::

    spec: ComputeSpec = {
        "registry": compute_providers,        # provider -> {required, secrets, tofu-env}
        "default": "vultr",                   # what a legacy state without params.provider is
        "sources": {"non_empty": ["ssh-sources"],      # suffixes; each must list a CIDR
                    "may_be_empty": ["http-sources"]},  # suffixes; may be []
        "name_rules": default_name_rules,     # optional; this value by default
    }

Nothing here is stateful: no factory, no closure, no global a package could
mutate, so every stub in every package test keeps working. The registry data,
the default provider, the templates, the fixtures and the lifecycle wiring
stay the package's; what lives here is the logic that was copied into six
packages in three colours and had already drifted. Template lookup
deliberately stays package-local.

The error strings are contract. They are printed by ``scripts/compute-*`` and
diffed across colours by ``scripts/parity.sh``, because none of this reaches a
build artifact and a message that differs per colour is a bug no rendered file
can show. Green's keys are keywords, so every key-bearing message here carries
the same leading colon.
"""

from __future__ import annotations

import re
from types import MappingProxyType
from typing import Any, Awaitable, Callable, Mapping, NotRequired, TypedDict

from blue.workflow import StepError

from .ssh import with_machine_key
from .validate import placeholder

ProviderEntry = TypedDict("ProviderEntry", {
    "required": list[str],
    "secrets": list[str],
    "tofu-env": dict[str, str],
})

Registry = Mapping[str, ProviderEntry]


class NameRule(TypedDict):
    re: re.Pattern[str]  # matched with ``fullmatch``
    message: str


NameRules = Mapping[str, NameRule]


class Sources(TypedDict):
    # The non-empty rule is a named field, never an array position, so a
    # reorder cannot weaken SSH validation.
    non_empty: list[str]
    may_be_empty: list[str]


class ComputeSpec(TypedDict):
    registry: Registry
    default: str
    sources: Sources
    name_rules: NotRequired[NameRules]


Params = dict[str, Any]
# One read of the compute state: ``{"params": m}`` where ``m`` may be None
# (nothing recorded), or ``{"error": message}`` (nothing readable).
StateRead = dict[str, Any]
StateReader = Callable[[dict], Awaitable[Params | None]]

# What each provider accepts as a machine name, checked before the apply
# rather than discovered mid-apply. DigitalOcean droplet names are
# hostname-like; Vultr labels are free-form console text, held to a safe
# subset. An immutable value: a package that needs different rules passes its
# own under ``name_rules`` in the spec.
default_name_rules: NameRules = MappingProxyType({
    "digitalocean": MappingProxyType({
        "re": re.compile(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"),
        "message": "must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters",
    }),
    "vultr": MappingProxyType({
        "re": re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,62}"),
        "message": "must be a safe 1-63 character name",
    }),
})


def _s(value: object) -> str:
    """Clojure's ``str``: None renders empty, booleans lowercase."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _missing(value: object) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


# ----------------------------------------------------------- selection


def provider(spec: ComputeSpec, opts: dict) -> ProviderEntry | None:
    """The selected registry entry, or None when ``provider-compute`` names
    none."""
    return spec["registry"].get(_s(opts.get("provider-compute")))


def compute_key(opts: dict, suffix: str) -> str:
    """Desired state names compute keys after the provider, so the shared
    steps reach them through the selected provider rather than a fixed
    prefix: ``<provider>-<suffix>``."""
    return f"{_s(opts.get('provider-compute'))}-{suffix}"


def compute_name(opts: dict) -> str:
    """What this deployment calls its machine (Compute Name Standard §2).

    The selected provider's ``<provider>-name`` when present and not a
    placeholder, else the profile; trimmed. The one function that answers it,
    so every label derives from the same value.
    """
    override = opts.get(compute_key(opts, "name"))
    return _s(opts.get("profile") if placeholder(override) else override).strip()


def selection_errors(spec: ComputeSpec, opts: dict) -> list[str]:
    """The §2 refusal: a ``provider-compute`` outside the registry, naming the
    advertised providers sorted."""
    if provider(spec, opts):
        return []
    return [":provider-compute must be one of " + ", ".join(sorted(spec["registry"]))]


def required_keys(spec: ComputeSpec, opts: dict) -> list[str]:
    """The selected entry's non-secret keys; ``[]`` when nothing is selected.
    The package concatenates its own required list and reports the missing
    ones."""
    return list((provider(spec, opts) or {}).get("required", []))


def secrets(spec: ComputeSpec, opts: dict) -> list[str]:
    """The selected entry's credentials; ``[]`` when nothing is selected."""
    return list((provider(spec, opts) or {}).get("secrets", []))


def tofu_env(spec: ComputeSpec, opts: dict) -> dict[str, str]:
    """The selected entry's OpenTofu environment mapping; ``{}`` when nothing
    is selected."""
    return dict((provider(spec, opts) or {}).get("tofu-env", {}))


# ------------------------------------------------------------- sources


def cidrs(opts: dict, key: str) -> list[str]:
    """A source list as desired state or an overlay string carries it: a YAML
    list, or one string of comma- or space-separated entries."""
    value = opts.get(key)
    xs = value if isinstance(value, list) else re.split(r"[,\s]+", _s(value))
    return [s for s in (_s(x).strip() for x in xs) if s]


# Syntactic CIDR checks, the same in every colour and deliberately not a
# resolver: an address library that accepts a hostname would let a firewall
# source depend on DNS at apply time.
_ipv4_re = re.compile(
    r"(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}")
_hex_group_re = re.compile(r"[0-9A-Fa-f]{1,4}")


def _fold_ipv4_tail(s: str) -> str | None:
    """An IPv4-embedded address (``::ffff:192.0.2.1``, ``64:ff9b::192.0.2.33``)
    carries a dotted quad in last position only. It stands for two 16-bit
    groups, so it is checked as IPv4 and folded into two zero groups before
    the group arithmetic; None when the tail is dotted but not an IPv4
    address. A dotted quad anywhere else falls through to the hex-group check
    and fails there."""
    i = s.rfind(":")
    tail = s[i + 1:] if i >= 0 else s
    if "." not in tail:
        return s
    if i >= 0 and _ipv4_re.fullmatch(tail):
        return s[:i + 1] + "0:0"
    return None


def _ipv6_address(raw: str) -> bool:
    s = _fold_ipv4_tail(raw)
    if s is None:
        return False

    def groups(part: str) -> list[str]:
        return [] if not part.strip() else part.split(":")
    if "::" in s:
        halves = s.split("::")
        if len(halves) != 2:
            return False
        gs = [g for half in halves for g in groups(half)]
        return len(gs) <= 7 and all(_hex_group_re.fullmatch(g) for g in gs)
    gs = groups(s)
    return len(gs) == 8 and all(_hex_group_re.fullmatch(g) for g in gs)


def cidr(s: object) -> bool:
    """Whether ``s`` is a syntactically valid IPv4 or IPv6 CIDR: an address, a
    slash, and a prefix length the address family allows."""
    parts = _s(s).split("/")
    if len(parts) != 2 or not re.fullmatch(r"\d{1,3}", parts[1]):
        return False
    address, n = parts[0], int(parts[1])
    if _ipv4_re.fullmatch(address):
        return 0 <= n <= 32
    if _ipv6_address(address):
        return 0 <= n <= 128
    return False


def source_errors(spec: ComputeSpec, opts: dict) -> list[str]:
    """The §5 network contract over the spec's ``sources``.

    Every ``non_empty`` suffix must list at least one CIDR — a machine nobody
    can reach is not a deployment — and every entry of every listed suffix
    must be one. A ``may_be_empty`` list may be ``[]`` and means no public
    access on that port set. Keys absent from opts are skipped: presence is
    ``required_keys``' job. Refusing beats defaulting: a silent default-open
    is worse than a validation error.
    """
    non_empty = spec["sources"]["non_empty"]
    may_be_empty = spec["sources"]["may_be_empty"]
    non_empty_keys = [compute_key(opts, suffix) for suffix in non_empty]
    all_keys = [compute_key(opts, suffix) for suffix in [*non_empty, *may_be_empty]]
    errors: list[str] = []
    for key in non_empty_keys:
        if not _missing(opts.get(key)) and not cidrs(opts, key):
            errors.append(f":{key} must list at least one CIDR")
    for key in all_keys:
        if _missing(opts.get(key)):
            continue
        for entry in cidrs(opts, key):
            if not cidr(entry):
                errors.append(f':{key} entry "{entry}" is not an IPv4 or IPv6 CIDR')
    return errors


# ------------------------------------------------------------ provider


def provider_errors(spec: ComputeSpec, opts: dict) -> list[str]:
    """Checks that hold only for the selected provider.

    Keys of another provider are ignored, never refused. The *resolved*
    machine name is validated against the provider's rules (Compute Name
    Standard §2): an override is checked as itself, and a profile that falls
    through as the name is checked too, because a profile Vultr accepts as a
    label can be a droplet name DigitalOcean refuses mid-apply. The error
    names the key the value came from. A blank resolved value is skipped, so
    a missing profile reports ``is required`` alone.
    """
    selected = _s(opts.get("provider-compute"))
    name_key = compute_key(opts, "name")
    rule = (spec.get("name_rules") or default_name_rules).get(selected)
    resolved = compute_name(opts)
    source = (f":profile (the {selected} machine name)"
              if placeholder(opts.get(name_key)) else f":{name_key}")
    errors: list[str] = []
    if rule and resolved.strip() and (len(resolved) > 63 or not rule["re"].fullmatch(resolved)):
        errors.append(f"{source} {rule['message']}")
    if selected == "vultr":
        os_id = opts.get("vultr-os-id")
        if not (_missing(os_id) or (isinstance(os_id, int) and not isinstance(os_id, bool))):
            errors.append(":vultr-os-id must be Vultr's numeric operating-system id")
    elif selected == "digitalocean":
        # No VPC is created: the region's default is discovered at plan time,
        # and a pinned UUID or a CIDR would make the package start owning one.
        if "digitalocean-vpc-uuid" in opts:
            errors.append(":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime")
        if "digitalocean-vpc-cidr" in opts:
            errors.append(":digitalocean-vpc-cidr must be absent; this package must not create a VPC")
    return errors


def state_errors(spec: ComputeSpec, opts: dict) -> list[str]:
    """Selection, then — only when a provider is selected — the source and
    the provider checks, in that order. Presence of the required keys is
    reported by the package over ``required_keys``."""
    errors = selection_errors(spec, opts)
    if provider(spec, opts):
        errors += source_errors(spec, opts) + provider_errors(spec, opts)
    return errors


def provider_state_errors(spec: ComputeSpec, opts: dict, params: Params | None) -> list[str]:
    """The §4 switch and legacy rules.

    Provider switching is a rebuild, never an apply: every provider shares
    one state key, so a changed provider-compute on a profile whose state
    already holds compute would plan a cross-provider replacement — and a
    delete would render and destroy the *selected* provider's template
    against the wrong lifecycle. ``params`` is the compute stage's recorded
    output, or None when the state holds none; its ``provider`` is the
    registry name the template that produced it belongs to. A recorded output
    without one predates the package recording it, which makes it the spec's
    ``default`` provider's.
    """
    if params is None:
        return []
    selected = _s(opts.get("provider-compute"))
    default = spec["default"]
    recorded = _s(params.get("provider"))
    if recorded and recorded != selected:
        return [f"state holds a {recorded} machine; set provider-compute back to "
                f"{recorded} and delete first"]
    if not recorded and selected != default:
        return ["state holds a machine with no recorded provider, created before this "
                f"package recorded one, which makes it a {default} "
                f"machine; set provider-compute back to {default} "
                "and delete first"]
    return []


# --------------------------------------------------------------- params


def fallback_params(opts: dict) -> Params:
    """What ``build`` and ``--dry-run`` render in place of a compute output:
    the documentation address, shaped like the selected provider's real
    ``params`` so every later stage sees the same keys either way."""
    return {"provider": opts.get("provider-compute"), "ip": "192.0.2.10",
            "user": "root", "sudoer": "root", "name": compute_name(opts)}


def output_params(result: dict) -> Params | None:
    """The compute stage's ``params`` output, untouched: the SSH Keypair
    Standard reads ``ssh_key_id`` with the underscore from this map, and a
    renamed key reads as a key the deployment does not own."""
    return (result.get("tofu/outputs") or {}).get("params")


def resolved_compute(result: dict, fallback: Params, outputs: Params | None) -> dict:
    """Refuse to hand 192.0.2.10 to Ansible.

    That is the documentation address the credential-free build and dry-run
    paths render with; on a real converge a missing compute output must fail
    loudly rather than quietly point the whole playbook at TEST-NET.
    """
    if outputs and outputs.get("ip"):
        return {**result, **fallback, **outputs}
    return {**result, "blue/exit": 1,
            "blue/err": ("compute produced no ip output; refusing to converge "
                         "against the documentation address")}


# ---------------------------------------------------------------- state

_NO_MESSAGE = "state read failed without a message"


async def read_state(opts: dict, reader: StateReader) -> StateRead:
    """One read of the compute state per run.

    Shaped so a caller can tell nothing recorded from nothing readable:
    ``{"params": m}`` where ``m`` may be None, or ``{"error": message}``.
    ``reader`` is the package's ``state_output`` — it keeps that function
    local so ``monkeypatch.setattr(workflow, "state_output", ...)`` in its
    tests keeps working — and it raises when the backend is unreadable.

    Only the SDK's ``StepError`` (exported by ``blue.workflow``) is caught:
    ``blue.tofu`` imports it and raises it from the output read, which is the
    shape this function depends on, as its green and red twins depend on
    ``green.tofu/outputs`` throwing an ex-info carrying ``:dir`` and
    ``red/tofu`` throwing ``StepError``. A message-less step error reads as
    the fixed string ``state read failed without a message``. Any other
    exception propagates: a programmer defect in the reader must not read as
    "no state" and skip the switch guard.
    """
    try:
        return {"params": await reader(opts)}
    except StepError as error:
        return {"error": str(error) or _NO_MESSAGE}


def lifecycle_event(context: dict) -> bool:
    """A real create or delete: the two events that touch a provider."""
    return bool(context.get("real") and context.get("event") in ("create", "delete"))


def provider_validator(spec: ComputeSpec, opts: dict, params: Params | None,
                       secret_errors_fn: Callable[[], list[str]]) -> list[str]:
    """Standard §4 before the credentials.

    The recorded provider is compared with the selected one first, so a
    mistaken provider edit reports the actionable error — put it back and
    delete — rather than a missing token for the provider that was just
    selected; validators aggregate, which is why a mismatch pre-empts the
    secrets check rather than sitting beside it. ``secret_errors_fn`` is the
    package's thunk, carrying its event and its application secrets, so ONCE
    never learns about them. On a create an unreadable backend counts as no
    state (a fresh clone has none) and the credentials are checked as usual;
    on a delete ``adopt_state`` refuses it after validation.
    """
    mismatch = provider_state_errors(spec, opts, params)
    return mismatch if mismatch else list(secret_errors_fn())


def adopt_state(opts: dict, event: str, state: StateRead) -> dict:
    """Events that run against the existing machine take its address from
    state rather than from a fresh apply.

    A readable state without compute params leaves ``ip`` unset — a delete's
    cleanup step then skips itself — while an unreadable backend fails
    loudly: swallowing it is how a live teardown once ended up converging
    against 192.0.2.10 (§4). Delete keeps the standard's wording; a package's
    rehearse or describe reads its own event name.

    No address override: the recorded params win over anything already in
    opts, and nothing here reads an ``ip`` from desired state or the overlay.
    A package that wants one (posthog's ``COLORS_PAR_IP``) wraps this
    function; the others must not gain a way to point a delete's cleanup at
    an arbitrary host. Synchronous in every colour.
    """
    error = state.get("error")
    if error is not None:
        what = "the delete cleanup" if event == "delete" else event
        return {**opts, "blue/exit": 1,
                "blue/err": (f"could not read the infrastructure state for {what}: {error}\n"
                             f"fix the backend credentials and retry; a {event} that "
                             "cannot see its state has nothing to address")}
    return {**with_machine_key(opts, True), **(state.get("params") or {}), "blue/exit": 0}
