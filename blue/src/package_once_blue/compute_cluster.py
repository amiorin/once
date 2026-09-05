"""The multi-node contract the Compute Provider Standard defers in its §1,
as a sibling of the single-node ``compute`` module: the same registry, the
same selection, the same sources and name rules — called, never copied — plus
what a cluster adds: roles and counts, one id per node, the fallback
addresses ``build`` renders with, and a refusal for every state that does not
describe the whole cluster.

A package describes itself with the compute spec plus three keys::

    spec: ClusterSpec = {
        "registry": compute_providers,   # entries as in compute, plus "network"
        "default": "vultr",
        "sources": {"non_empty": ["ssh-sources"], "may_be_empty": []},
        "roles": [{"role": None, "count_key": "node-count", "count": 3}],
        "entry": {"role": None, "index": 0},     # optional; default the first node
        "fallback_subnet": "10.110.0.0/20",      # optional; discovered networks only
    }

``roles`` is a list in play order. ``role`` is a string, or None for a
homogeneous cluster (then the only entry). ``count_key`` names the
desired-state integer that sets the count and ``count`` is the fixed count,
or the default when the key is absent. ``fallback_offset`` is the offset of
the role's first fallback address; default 10 plus the number of nodes in the
roles before it. A registry entry's ``network`` is
``{"mode": "created", "key": <cidr key>}``, ``{"mode": "discovered"}`` or
``{"mode": "none"}``; absent means none.

**The one representation of compute state is ``params``**: ONCE reads exactly
``provider``, ``ssh_key_id`` and ``nodes``, and on every node the five fields
``ip``, ``vpc_ip``, ``name``, ``user``, ``sudoer`` plus its ``role`` and
``index``. Node keys are spelled as ``output_params`` delivers them — the
underscore kept: ``ip vpc_ip name user sudoer role index``, never hyphenated —
and fallback nodes use the same spelling so every later stage sees one shape.
Anything else a package emits, on a node (``droplet_id``) or at the top level
(``reserved_ip``, ``vpc_id``, ``vpc_ip_range``), is preserved verbatim under
``once/cluster``.

``spec_errors`` is developer-facing and raises; every other ``_errors``
function returns a list of operator-facing messages that are contract,
printed by ``scripts/cluster-*`` and diffed across colours. Green's keys are
keywords, so every key-bearing message here carries the same leading colon
and names the spec keys as green spells them (``:fallback-subnet``).
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any, Callable, Iterable, NotRequired, TypedDict

from . import compute
from .compute import (
    ComputeSpec,
    NameRules,
    Params,
    Registry,
    Sources,
    StateRead,
    StateReader,
    _s,
)
from .ssh import with_machine_key
from .validate import placeholder

Network = TypedDict("Network", {
    "mode": str,               # "created" | "discovered" | "none"
    "key": NotRequired[str],   # the CIDR key of a created network
})

ClusterProviderEntry = TypedDict("ClusterProviderEntry", {
    "required": list[str],
    "secrets": list[str],
    "tofu-env": dict[str, str],
    "network": NotRequired[Network],
})

ClusterRegistry = Registry


class RoleSpec(TypedDict):
    role: str | None
    count: NotRequired[int]
    count_key: NotRequired[str]
    fallback_offset: NotRequired[int]


class NodeId(TypedDict):
    role: str | None
    index: int


class Node(TypedDict, total=False):
    role: str | None
    index: int
    name: str
    ip: str
    vpc_ip: str
    user: str
    sudoer: str


class ClusterSpec(TypedDict):
    registry: ClusterRegistry
    default: str
    sources: Sources
    name_rules: NotRequired[NameRules]
    roles: list[RoleSpec]
    entry: NotRequired[NodeId]
    fallback_subnet: NotRequired[str]


__all__ = [
    "ClusterError", "ClusterProviderEntry", "ClusterSpec", "ComputeSpec", "Network", "Node",
    "NodeId", "NO_PARAMS_MESSAGE", "PUBLIC_FALLBACK_NETWORK", "ROLE_RE", "RoleSpec",
    "adopt_state", "aliases", "entry_id", "fallback_cidr", "fallback_ip", "fallback_node_name",
    "fallback_nodes", "fallback_offset", "fallback_vpc_ip", "ipv4_network", "network",
    "network_errors", "network_mode", "node_count", "node_errors", "node_id_str", "node_ids",
    "nodes", "output_params", "provider_state_errors", "provider_validator", "read_state",
    "resolved_cluster", "roles", "spec_errors", "ssh_config_hosts", "state_errors",
    "topology_errors",
]

# What a role may be called: lowercase, digits, single hyphens between words.
# Alias-safe, because ``<profile>-<role>`` and ``<profile>-<role>-<n>`` must
# not collide with ``<profile>-<n>`` or with another role. Matched with
# ``fullmatch``, as green's ``re-matches`` does.
ROLE_RE = re.compile(r"[a-z][a-z0-9]*(-[a-z0-9]+)*")

# TEST-NET-1: where ``build`` and ``--dry-run`` put every public fallback
# address, offset + index from its network address.
PUBLIC_FALLBACK_NETWORK = "192.0.2.0/24"

_CANONICAL_MESSAGE = " must be a canonical IPv4 network such as 10.40.0.0/24"

# The ``resolved_cluster`` refusal: a real converge never falls back.
NO_PARAMS_MESSAGE = "compute produced no params output; refusing to converge against the documentation addresses"


class ClusterError(ValueError):
    """What green throws as ``ex-info``: the message, and the same data map
    under ``data``."""

    def __init__(self, message: str, data: dict[str, Any]):
        super().__init__(message)
        self.data = data


def _spec_error(msg: str) -> None:
    raise ClusterError(msg, {"once/compute-cluster": msg})


def _get(m: object, key: str) -> Any:
    """Clojure's ``get``: None for anything that is not a map."""
    return m.get(key) if isinstance(m, Mapping) else None


def _same(a: object, b: object) -> bool:
    """Clojure's ``=`` over the scalars an id carries: ``1`` is not ``True``
    and not ``1.0``."""
    return type(a) is type(b) and a == b


def _same_id(a: dict, b: dict) -> bool:
    return set(a) == set(b) and all(_same(a[k], b[k]) for k in a)


def _int(x: object) -> bool:
    return isinstance(x, int) and not isinstance(x, bool)


def _pos_int(x: object) -> bool:
    return _int(x) and x > 0


def _nat_int(x: object) -> bool:
    return _int(x) and x >= 0


def _non_blank_string(x: object) -> bool:
    return isinstance(x, str) and bool(x.strip())


def _distinct_ids(ids: Iterable[dict]) -> list[dict]:
    out: list[dict] = []
    for id in ids:
        if not any(_same_id(id, seen) for seen in out):
            out.append(id)
    return out


# ------------------------------------------------------------ addresses


def _ipv4_to_int(s: str) -> int:
    n = 0
    for octet in s.split("."):
        n = n * 256 + int(octet)
    return n


def _int_to_ipv4(n: int) -> str:
    n &= 0xFFFFFFFF
    return ".".join(str((n >> shift) & 255) for shift in (24, 16, 8, 0))


def ipv4_network(s: object) -> dict[str, Any] | None:
    """``s`` parsed as a canonical IPv4 network — compute's ``cidr`` grammar,
    IPv4 only, host bits zero — as ``{cidr, address, prefix, first, last}``
    with the network address and the first and last usable host as 32-bit
    integers, or None. A /31 or /32 parses and has no usable host."""
    if not (compute.cidr(s) and ":" not in _s(s)):
        return None
    address, prefix = _s(s).split("/")
    n = int(prefix)
    a = _ipv4_to_int(address)
    mask = (-1 << (32 - n)) & 0xFFFFFFFF
    size = 1 << (32 - n)
    if a != a & mask:
        return None
    return {"cidr": _s(s), "address": a, "prefix": n, "first": a + 1, "last": a + size - 2}


# -------------------------------------------------------------- network


def network(spec: ClusterSpec, opts: dict) -> Network:
    """The selected entry's network declaration; ``{"mode": "none"}`` when
    absent or when nothing is selected."""
    return _get(compute.provider(spec, opts), "network") or {"mode": "none"}


def network_mode(spec: ClusterSpec, opts: dict) -> str | None:
    return network(spec, opts).get("mode")


def fallback_cidr(spec: ClusterSpec, opts: dict) -> str | None:
    """The CIDR the private fallback addresses are cut from: the created
    network's key value, the spec's ``fallback_subnet`` for a discovered one,
    None for none. On a real run the discovered CIDR is the package's
    ``params.vpc_ip_range``; this exists for ``build`` alone."""
    net = network(spec, opts)
    mode = net.get("mode")
    if mode == "created":
        return opts.get(net.get("key"))
    if mode == "discovered":
        return spec.get("fallback_subnet")
    return None


# ---------------------------------------------------------------- roles


def roles(spec: ClusterSpec) -> list[RoleSpec]:
    return list(spec.get("roles") or [])


def _role_entry(spec: ClusterSpec, role: str | None) -> RoleSpec | None:
    for entry in roles(spec):
        if _same(entry.get("role"), role):
            return entry
    return None


def node_count(spec: ClusterSpec, opts: dict, role: str | None) -> Any:
    """How many nodes ``role`` (a declared role name, None for the
    homogeneous role) has: the count key's value whenever the key is present
    in opts — whatever it is, validation refuses a present non-positive-
    integer before any derivation runs — and ``count`` only when the key is
    absent or the role declares none."""
    entry = _role_entry(spec, role)
    count_key = _get(entry, "count_key")
    if count_key is not None and count_key in opts:
        return opts[count_key]
    return _get(entry, "count")


def _counts_valid(spec: ClusterSpec, opts: dict) -> bool:
    return all(_pos_int(node_count(spec, opts, entry.get("role"))) for entry in roles(spec))


def node_ids(spec: ClusterSpec, opts: dict) -> list[NodeId]:
    """``[{role, index}]`` over ``roles`` in declared order, ``index`` 0-based
    per role. Assumes valid counts; run ``topology_errors`` first."""
    return [{"role": entry.get("role"), "index": i}
            for entry in roles(spec)
            for i in range(node_count(spec, opts, entry.get("role")))]


def node_id_str(id: dict) -> str:
    """How an id renders in a message: ``<index>`` for the None role,
    ``<role>-<index>`` otherwise. A None index (a legacy state's
    ``index: null``) renders as ``null`` in every colour."""
    role, index = id.get("role"), id.get("index")
    i = "null" if index is None else _s(index)
    return i if role is None else f"{_s(role)}-{i}"


def _ids_str(ids: Iterable[dict]) -> str:
    return ", ".join(node_id_str(id) for id in ids)


def entry_id(spec: ClusterSpec) -> dict:
    """The node the bare ``<profile>`` alias points to: the spec's ``entry``,
    else the first node of the first role."""
    entry = spec.get("entry")
    if entry is not None:
        return {k: entry[k] for k in ("role", "index") if k in entry}
    rs = roles(spec)
    return {"role": rs[0].get("role") if rs else None, "index": 0}


def fallback_offset(spec: ClusterSpec, opts: dict, role: str | None) -> int | None:
    """The offset of ``role``'s first fallback address inside each fallback
    network: the role's ``fallback_offset``, else 10 plus the number of nodes
    in the roles declared before it."""
    before = 0
    for entry in roles(spec):
        if _same(entry.get("role"), role):
            offset = entry.get("fallback_offset")
            return offset if offset is not None else 10 + before
        before += node_count(spec, opts, entry.get("role"))
    return None


def _offset_address(cidr: object, spec: ClusterSpec, opts: dict, id: dict) -> int | None:
    """network address + offset + index, as a 32-bit integer; None when
    ``cidr`` is not a canonical IPv4 network."""
    net = ipv4_network(cidr)
    if net is None:
        return None
    return net["address"] + fallback_offset(spec, opts, id.get("role")) + id.get("index")


def fallback_ip(spec: ClusterSpec, opts: dict, id: dict) -> str:
    """The public fallback address of ``id``: ``192.0.2.0/24`` + offset +
    index."""
    return _int_to_ipv4(_offset_address(PUBLIC_FALLBACK_NETWORK, spec, opts, id))


def fallback_vpc_ip(spec: ClusterSpec, opts: dict, id: dict) -> str | None:
    """The private fallback address of ``id``: the fallback CIDR's network
    address + offset + index with 32-bit arithmetic, so a /20's nodes cross
    an octet correctly. None when the network mode is none or the CIDR does
    not parse (validation reports the latter; the node then carries no
    ``vpc_ip`` at all)."""
    a = _offset_address(fallback_cidr(spec, opts), spec, opts, id)
    return None if a is None else _int_to_ipv4(a)


def _name_suffix(spec: ClusterSpec, opts: dict, id: dict) -> str:
    role, index = id.get("role"), id.get("index")
    if role is None:
        return f"-{_s(index)}"
    count = node_count(spec, opts, role)
    if _int(count) and count == 1:
        return f"-{_s(role)}"
    return f"-{_s(role)}-{_s(index)}"


def fallback_node_name(spec: ClusterSpec, opts: dict, id: dict) -> str:
    """``<compute-name>-<index>`` (None role), ``<compute-name>-<role>`` (a
    role of count 1), ``<compute-name>-<role>-<index>``; compute's name
    supplies the base. Governs fallbacks and new packages only: a package
    whose legacy names differ overrides ``name`` on its fallback nodes in its
    own wrapper."""
    return compute.compute_name(opts) + _name_suffix(spec, opts, id)


def aliases(spec: ClusterSpec, opts: dict) -> list[str]:
    """``[profile]`` then, per node in declared order, ``<profile>-<index>``
    (None role), ``<profile>-<role>`` (count 1) or
    ``<profile>-<role>-<index>``."""
    profile = _s(opts.get("profile"))
    return [profile, *(profile + _name_suffix(spec, opts, id) for id in node_ids(spec, opts))]


def fallback_nodes(spec: ClusterSpec, opts: dict) -> list[Node]:
    """What ``build`` and ``--dry-run`` render in place of a compute output:
    one node per id — ``role index name ip user "root" sudoer "root"``, and
    ``vpc_ip`` unless the network mode is none — shaped like a real
    ``params.nodes`` entry so every later stage sees the same keys either
    way."""
    out: list[Node] = []
    for id in node_ids(spec, opts):
        vpc_ip = fallback_vpc_ip(spec, opts, id)
        node: Node = {**id, "name": fallback_node_name(spec, opts, id),
                      "ip": fallback_ip(spec, opts, id), "user": "root", "sudoer": "root"}
        if vpc_ip is not None:
            node["vpc_ip"] = vpc_ip
        out.append(node)
    return out


# --------------------------------------------------------------- params


def output_params(result: dict) -> Params | None:
    """The compute stage's ``params`` output, as compute's: untouched, the
    underscores kept."""
    return compute.output_params(result)


def _node_id(n: object) -> dict:
    return {"role": _get(n, "role"), "index": _get(n, "index")}


def _reported_nodes(params: Params) -> list:
    return list(params.get("nodes") or [])


def _nodes_by_id(params: Params) -> list[tuple[dict, Any]]:
    """Reported nodes paired with their id, the first occurrence winning."""
    out: list[tuple[dict, Any]] = []
    for n in _reported_nodes(params):
        id = _node_id(n)
        if not any(_same_id(id, seen) for seen, _ in out):
            out.append((id, n))
    return out


def _lookup(by_id: list[tuple[dict, Any]], id: dict) -> tuple[bool, Any]:
    for seen, n in by_id:
        if _same_id(seen, id):
            return True, n
    return False, None


def node_errors(spec: ClusterSpec, opts: dict, params: Params | None) -> list[str] | None:
    """None when ``params`` is None (a build); else, in this order: ids
    declared but not reported; ids reported but not declared; ids reported
    more than once; and ids whose node lacks a non-blank string for any of
    ``ip``, ``name``, ``user``, ``sudoer`` — and ``vpc_ip`` unless the
    network mode is none. Absent, null, blank and non-string values all count
    as missing. Ids are matched exactly, so a legacy ``index: null`` (or a
    string index) is an undeclared id: packages translate before ONCE sees
    the state. A present ``params`` with an empty or absent ``nodes`` reports
    every declared id missing."""
    if params is None:
        return None
    declared = node_ids(spec, opts)
    reported = [_node_id(n) for n in _reported_nodes(params)]
    by_id = _nodes_by_id(params)
    fields = (["ip", "name", "user", "sudoer"] if network_mode(spec, opts) == "none"
              else ["ip", "vpc_ip", "name", "user", "sudoer"])

    def is_declared(id: dict) -> bool:
        return any(_same_id(id, d) for d in declared)

    def is_reported(id: dict) -> bool:
        return any(_same_id(id, r) for r in reported)

    def freq(id: dict) -> int:
        return sum(1 for r in reported if _same_id(id, r))

    def complete(n: object) -> bool:
        return all(_non_blank_string(_get(n, f)) for f in fields)

    missing = [d for d in declared if not is_reported(d)]
    undeclared = _distinct_ids(r for r in reported if not is_declared(r))
    duplicated = [d for d in declared if freq(d) > 1]
    incomplete = []
    for d in declared:
        present, n = _lookup(by_id, d)
        if present and not complete(n):
            incomplete.append(d)
    errors: list[str] = []
    if missing:
        errors.append("the compute stage did not report nodes this package declares: " + _ids_str(missing))
    if undeclared:
        errors.append("the compute stage reported nodes this package does not declare: " + _ids_str(undeclared))
    if duplicated:
        errors.append("the compute stage reported " + _ids_str(duplicated) + " more than once")
    if incomplete:
        errors.append("the compute stage did not report a complete node (ip, vpc_ip, name, user, sudoer) for "
                      + _ids_str(incomplete) + "; refusing to render a partial cluster")
    return errors


def nodes(spec: ClusterSpec, opts: dict, params: Params | None) -> list:
    """The cluster's nodes in declared order. ``params`` None (a build)
    yields the fallbacks; a present ``params`` must pass ``node_errors`` —
    callers check first, this raises otherwise — and then every node comes
    from state with every field as recorded and no fallback substitution.
    Keys are spelled as ``output_params`` delivers them, ``vpc_ip`` with the
    underscore; fields ONCE does not name are preserved verbatim."""
    if params is None:
        return fallback_nodes(spec, opts)
    errors = node_errors(spec, opts, params)
    if errors:
        raise ClusterError("\n".join(errors), {"once/node-errors": errors})
    by_id = _nodes_by_id(params)
    return [_lookup(by_id, id)[1] for id in node_ids(spec, opts)]


# ----------------------------------------------------------- validation


def _host_range_errors(spec: ClusterSpec, opts: dict, subject: str, cidr: object) -> list[str]:
    """The ids whose private fallback address falls outside ``cidr``'s usable
    hosts, blamed on ``subject`` (the key or the network that owns the CIDR).
    Nothing when the CIDR does not parse or a count is invalid: both are
    reported by their own rule."""
    net = ipv4_network(cidr)
    if net is None or not _counts_valid(spec, opts):
        return []
    first, last = net["first"], net["last"]
    outside = [id for id in node_ids(spec, opts)
               if not (first <= _offset_address(cidr, spec, opts, id) <= last)]
    if not outside:
        return []
    return [f"{subject} has no usable host address for {_ids_str(outside)}"]


def _duplicate_errors(what: str, values: list[str]) -> list[str]:
    distinct = list(dict.fromkeys(values))
    return [f"the {what} {v} is generated for more than one node"
            for v in distinct if values.count(v) > 1]


def spec_errors(spec: ClusterSpec) -> list[str]:
    """Static checks over the spec alone, run in a package's spec-content
    test and at the head of ``state_errors``. Developer-facing: raises
    ``ClusterError`` on the first problem and returns ``[]`` otherwise.
    ``roles`` is non-empty; a None role is the only entry; role names match
    ``ROLE_RE``, are unique, and none equals another followed by
    ``-<digits>``; every ``count`` is a positive integer and every
    ``fallback_offset`` a non-negative one; ``entry`` names a declared role
    with a non-negative index; ``fallback_subnet``, when present, is a
    canonical IPv4 network and is permitted only when some advertised entry's
    network is discovered."""
    rs = roles(spec)
    names = [_get(r, "role") for r in rs]
    if not rs:
        _spec_error(":roles must be a non-empty vector")
    if any(r is None for r in names) and len(rs) > 1:
        _spec_error("the nil role must be the only entry in :roles")
    for r in names:
        if r is not None and not (isinstance(r, str) and ROLE_RE.fullmatch(r)):
            _spec_error(f'role "{_s(r)}" must match ^[a-z][a-z0-9]*(-[a-z0-9]+)*$')
    for r in list(dict.fromkeys(names)):
        if names.count(r) > 1:
            _spec_error(f'role "{_s(r)}" is declared more than once')
    for r in names:
        for other in names:
            if r is not None and other is not None and re.fullmatch(f"{other}-\\d+", r):
                _spec_error(f'role "{r}" reads as an alias of role "{other}"')
    for entry in rs:
        role = entry.get("role")
        label = "the nil role" if role is None else f'role "{_s(role)}"'
        if not _pos_int(entry.get("count")):
            _spec_error(f":count of {label} must be a positive integer")
        if "fallback_offset" in entry and not _nat_int(entry.get("fallback_offset")):
            _spec_error(f":fallback-offset of {label} must be a non-negative integer")
    entry = spec.get("entry")
    if entry is not None:
        if not (isinstance(entry, dict) and "role" in entry and "index" in entry):
            _spec_error(":entry must carry :role and :index")
        if not any(_same(entry.get("role"), name) for name in names):
            _spec_error(":entry :role must name a declared role")
        if not _nat_int(entry.get("index")):
            _spec_error(":entry :index must be a non-negative integer")
    if "fallback_subnet" in spec:
        if ipv4_network(spec.get("fallback_subnet")) is None:
            _spec_error(":fallback-subnet" + _CANONICAL_MESSAGE)
        if not any(_get(_get(e, "network"), "mode") == "discovered" for e in spec["registry"].values()):
            _spec_error(":fallback-subnet is permitted only when an advertised provider's network is discovered")
    return []


def network_errors(spec: ClusterSpec, opts: dict) -> list[str]:
    """Created: the key is required, must be a canonical IPv4 network (host
    bits zero, parsed as a network — not the syntactic ``cidr``), and every
    private fallback address must fall inside its usable host range.
    Discovered: nothing beyond compute's refusals of a pinned VPC. None:
    nothing. ``fallback_subnet``, when present, is held to the same canonical
    rule under its own name."""
    net = network(spec, opts)
    mode, key = net.get("mode"), net.get("key")
    value = opts.get(key)
    errors: list[str] = []
    if mode == "created":
        if placeholder(value):
            errors.append(f":{_s(key)} is required")
        elif ipv4_network(value) is None:
            errors.append(f":{_s(key)}{_CANONICAL_MESSAGE}")
        else:
            errors += _host_range_errors(spec, opts, f":{_s(key)}", value)
    if "fallback_subnet" in spec and ipv4_network(spec.get("fallback_subnet")) is None:
        errors.append(":fallback-subnet" + _CANONICAL_MESSAGE)
    return errors


def topology_errors(spec: ClusterSpec, opts: dict) -> list[str]:
    """With desired state: each present count key a positive integer — and
    nothing else until they all are, because every derivation below needs
    them; ``entry`` inside the effective count; ``fallback_subnet`` present
    when the selected network is discovered; every public fallback address
    inside ``192.0.2.0/24`` and every private one inside ``fallback_subnet``
    (a created network's range is ``network_errors``' to check); addresses,
    names and aliases unique; names and aliases at most 63 characters; and
    every generated name accepted by the selected provider's name rule — the
    spec's ``name_rules`` or compute's defaults."""
    count_errors = [f":{_s(entry.get('count_key'))} must be a positive integer"
                    for entry in roles(spec)
                    if entry.get("count_key") is not None and entry.get("count_key") in opts
                    and not _pos_int(opts.get(entry.get("count_key")))]
    if count_errors:
        return count_errors
    ids = node_ids(spec, opts)
    mode = network_mode(spec, opts)
    cidr = fallback_cidr(spec, opts)
    entry = entry_id(spec)
    public = [fallback_ip(spec, opts, id) for id in ids]
    private = [ip for ip in (fallback_vpc_ip(spec, opts, id) for id in ids) if ip is not None]
    names = [fallback_node_name(spec, opts, id) for id in ids]
    alias_names = aliases(spec, opts)
    rule = (spec.get("name_rules") or compute.default_name_rules).get(_s(opts.get("provider-compute")))
    pattern = _get(rule, "re")
    message = _get(rule, "message")
    errors: list[str] = []
    if not any(_same_id(entry, id) for id in ids):
        errors.append(f":entry names {node_id_str(entry)}, a node this topology does not declare")
    if mode == "discovered" and "fallback_subnet" not in spec:
        errors.append(":fallback-subnet is required when the selected provider's network is discovered")
    errors += _host_range_errors(spec, opts, PUBLIC_FALLBACK_NETWORK, PUBLIC_FALLBACK_NETWORK)
    errors += _duplicate_errors("public fallback address", public)
    if mode == "discovered":
        errors += _host_range_errors(spec, opts, ":fallback-subnet", cidr)
    errors += _duplicate_errors("private fallback address", private)
    errors += _duplicate_errors("fallback name", [f'"{n}"' for n in names])
    for n in names:
        if len(n) > 63 or (pattern is not None and not pattern.fullmatch(n)):
            errors.append(f'the fallback name "{n}" '
                          + (message if pattern is not None else "must be at most 63 characters"))
    errors += _duplicate_errors("alias", [f'"{a}"' for a in alias_names])
    errors += [f'the alias "{a}" must be at most 63 characters' for a in alias_names if len(a) > 63]
    return errors


# compute's two DigitalOcean refusals of a pinned VPC. They hold for a
# discovered network and are dropped for a created one, where the package
# does own a VPC; compute itself is untouched.
_DIGITALOCEAN_VPC_REFUSALS = {
    ":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime",
    ":digitalocean-vpc-cidr must be absent; this package must not create a VPC",
}


def state_errors(spec: ClusterSpec, opts: dict) -> list[str]:
    """``spec_errors`` (raised), then compute's ``state_errors`` with the two
    DigitalOcean VPC refusals filtered out when the selected entry's network
    mode is created, then — only when a provider is selected, as compute
    does — ``network_errors`` and ``topology_errors``."""
    spec_errors(spec)
    created = network_mode(spec, opts) == "created"
    errors = compute.state_errors(spec, opts)
    if created:
        errors = [e for e in errors if e not in _DIGITALOCEAN_VPC_REFUSALS]
    if compute.provider(spec, opts):
        errors += network_errors(spec, opts) + topology_errors(spec, opts)
    return errors


# ---------------------------------------------------------------- state


async def read_state(opts: dict, reader: StateReader) -> StateRead:
    """compute's, re-exported: ``{"params": m}`` or ``{"error": message}``."""
    return await compute.read_state(opts, reader)


def provider_state_errors(spec: ClusterSpec, opts: dict, params: Params | None) -> list[str]:
    """compute's, re-exported: reads ``params.provider`` alone."""
    return compute.provider_state_errors(spec, opts, params)


def provider_validator(spec: ClusterSpec, opts: dict, params: Params | None,
                       secret_errors_fn: Callable[[], list[str]]) -> list[str]:
    """compute's, re-exported: the provider mismatch pre-empts the secrets."""
    return compute.provider_validator(spec, opts, params, secret_errors_fn)


def resolved_cluster(spec: ClusterSpec, opts: dict, result: dict, fallback: dict,
                     outputs: Params | None) -> dict:
    """Refuse to hand the documentation addresses to Ansible. None outputs —
    no ``params`` from the compute stage — exit 1; outputs with any
    ``node_errors`` exit 1 with the messages; else ``result``, ``fallback``
    and ``{"once/cluster": outputs}`` merged in that order, so the whole
    recorded ``params`` — the nodes and every extension key — is what the
    cluster stages read."""
    if outputs is None:
        return {**result, "blue/exit": 1, "blue/err": NO_PARAMS_MESSAGE}
    errors = node_errors(spec, opts, outputs)
    if errors:
        return {**result, "blue/exit": 1, "blue/err": "\n".join(errors)}
    return {**result, **fallback, "once/cluster": outputs}


def adopt_state(spec: ClusterSpec, opts: dict, event: str, state: StateRead) -> dict:
    """Events that run against the existing cluster take it from state
    rather than from a fresh apply. ``{"error": e}`` fails closed with
    compute's two-line message; ``params`` with any ``node_errors`` exits 1
    with them; a readable state without ``params`` leaves ``once/cluster``
    absent and the package decides what that means for the event; else
    ``once/cluster`` holds the recorded ``params`` verbatim over
    ``ssh.with_machine_key``. Synchronous, as compute's."""
    if state.get("error") is not None:
        return compute.adopt_state(opts, event, state)
    params = state.get("params")
    if params is None:
        return {**with_machine_key(opts, True), "blue/exit": 0}
    errors = node_errors(spec, opts, params)
    if errors:
        return {**opts, "blue/exit": 1, "blue/err": "\n".join(errors)}
    return {**with_machine_key(opts, True), "once/cluster": params, "blue/exit": 0}


def ssh_config_hosts(spec: ClusterSpec, opts: dict, nodes: list) -> list[dict]:
    """The local ssh-config play's extra-vars: ``{"name": profile, "ip":
    <entry ip>}`` then one ``{"name": alias, "ip": ip}`` per node. ``nodes``
    is what ``nodes`` returns, in declared order, so aliases pair with them
    by position."""
    profile, *per_node = aliases(spec, opts)
    entry = entry_id(spec)
    position = next((i for i, id in enumerate(node_ids(spec, opts)) if _same_id(id, entry)), None)
    entry_node = nodes[position] if position is not None and position < len(nodes) else None
    return [{"name": profile, "ip": _get(entry_node, "ip")},
            *({"name": alias, "ip": _get(node, "ip")} for alias, node in zip(per_node, nodes))]
