# Drive the multi-node contract — roles and counts, the fallback addresses,
# the node-id refusals, the created and discovered network rules, the
# cluster read and adoption — through blue's `compute_cluster` module with
# a three-provider stub spec, printing one normalized
# `case <name> exit=<n> errors=<messages joined by " | ">` line per scenario
# (value-bearing scenarios append ` value=<fields>`). Green and red print the
# same shape, so parity.sh can diff them: none of this logic reaches a build
# artifact, and the messages are contract for every package that delegates
# to ONCE. Exit is the real `blue/exit` where a scenario returns opts, 2
# (the CLI's validation exit) where it returns messages, and 2 with the
# exception message where a developer-facing check raises.
import asyncio
import re

from blue.workflow import StepError
from package_once_blue import compute_cluster as cluster

registry = {
    "vultr": {
        "required": ["vultr-region", "vultr-plan", "vultr-os-id", "vultr-ssh-sources", "vultr-vpc-subnet"],
        "secrets": ["vultr-api-key"],
        "tofu-env": {"vultr-api-key": "VULTR_API_KEY"},
        "network": {"mode": "created", "key": "vultr-vpc-subnet"},
    },
    "digitalocean": {
        "required": ["digitalocean-region", "digitalocean-size", "digitalocean-image",
                     "digitalocean-ssh-sources"],
        "secrets": ["do-token"],
        "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"},
        "network": {"mode": "discovered"},
    },
    "none": {"required": [], "secrets": [], "tofu-env": {}},
}

base = {
    "registry": registry,
    "default": "vultr",
    "sources": {"non_empty": ["ssh-sources"], "may_be_empty": []},
    "fallback_subnet": "10.110.0.0/20",
}

homog = {**base, "roles": [{"role": None, "count_key": "node-count", "count": 3}]}
roles = {**base,
         "roles": [{"role": "neon", "count": 1},
                   {"role": "redis", "count": 1},
                   {"role": "clickhouse", "count_key": "clickhouse-count", "count": 3, "fallback_offset": 20},
                   {"role": "app", "count": 1, "fallback_offset": 12}],
         "entry": {"role": "app", "index": 0}}
# three nodes from offset 254: the private addresses cross an octet inside a
# /20 and the public ones run off the end of 192.0.2.0/24
high = {**base, "roles": [{"role": None, "count": 3, "fallback_offset": 254}]}
overlap = {**base, "roles": [{"role": "a", "count": 2, "fallback_offset": 10},
                             {"role": "b", "count": 2, "fallback_offset": 11}]}
one = {**base, "roles": [{"role": None, "count": 1}]}
own = {**homog, "name_rules": {"vultr": {"re": re.compile(r"prod"), "message": "must be prod"}}}
do_created = {**homog,
              "registry": {**registry,
                           "digitalocean": {**registry["digitalocean"],
                                            "network": {"mode": "created", "key": "digitalocean-vpc-cidr"}}}}
del do_created["fallback_subnet"]


def without(m: dict, *keys: str) -> dict:
    return {k: v for k, v in m.items() if k not in keys}


def vultr(**kvs):
    return {"profile": "prod", "provider-compute": "vultr",
            "vultr-ssh-sources": ["10.0.0.0/8"], "vultr-vpc-subnet": "10.40.0.0/24", **kvs}


def digitalocean(**kvs):
    return {"profile": "prod", "provider-compute": "digitalocean", "digitalocean-ssh-sources": ["10.0.0.0/8"], **kvs}


def none(**kvs):
    return {"profile": "prod", "provider-compute": "none", **kvs}


def node(role, index, **kvs):
    return {"role": role, "index": index,
            "ip": f"203.0.113.{10 + index}", "vpc_ip": f"10.40.0.{10 + index}",
            "name": f"n-{index}", "user": "root", "sudoer": "root", **kvs}


homog_params = {"provider": "vultr", "ssh_key_id": "77", "nodes": [node(None, 0), node(None, 1), node(None, 2)]}


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


# A developer-facing check raises; print its message as the one error.
def thrown(case_name: str, f, value=None) -> None:
    try:
        r = f()
        line(case_name, 0 if not r else 2, r, value(r) if value else None)
    except cluster.ClusterError as e:
        line(case_name, 2, [str(e)])


def id_str(id) -> str:
    return cluster.node_id_str(id)


def node_str(n) -> str:
    return (f"{id_str(n)}={n.get('name')}|{n.get('ip')}|{n['vpc_ip'] if 'vpc_ip' in n else '-'}"
            f"|{n.get('user')}|{n.get('sudoer')}")


def nodes_str(nodes) -> str:
    return ",".join(node_str(n) for n in nodes)


def hosts_str(hosts) -> str:
    return ",".join(f"{h['name']}={h['ip']}" for h in hosts)


# --- spec-errors
thrown("spec-homog-ok", lambda: cluster.spec_errors(homog))
thrown("spec-roles-ok", lambda: cluster.spec_errors(roles))
thrown("spec-roles-empty", lambda: cluster.spec_errors({**base, "roles": []}))
thrown("spec-roles-absent", lambda: cluster.spec_errors(base))
thrown("spec-nil-role-not-alone",
       lambda: cluster.spec_errors({**base, "roles": [{"role": None, "count": 1}, {"role": "app", "count": 1}]}))
thrown("spec-role-bad-name", lambda: cluster.spec_errors({**base, "roles": [{"role": "Foo", "count": 1}]}))
thrown("spec-role-duplicate",
       lambda: cluster.spec_errors({**base, "roles": [{"role": "app", "count": 1}, {"role": "app", "count": 2}]}))
thrown("spec-role-alias-collision",
       lambda: cluster.spec_errors({**base, "roles": [{"role": "foo", "count": 2}, {"role": "foo-0", "count": 1}]}))
thrown("spec-count-not-positive", lambda: cluster.spec_errors({**base, "roles": [{"role": "app", "count": 0}]}))
thrown("spec-count-absent-nil-role", lambda: cluster.spec_errors({**base, "roles": [{"role": None, "count_key": "n"}]}))
thrown("spec-offset-not-integer",
       lambda: cluster.spec_errors({**base, "roles": [{"role": "app", "count": 1, "fallback_offset": "12"}]}))
thrown("spec-entry-incomplete", lambda: cluster.spec_errors({**homog, "entry": {"index": 0}}))
thrown("spec-entry-unresolved", lambda: cluster.spec_errors({**roles, "entry": {"role": "web", "index": 0}}))
thrown("spec-entry-bad-index", lambda: cluster.spec_errors({**roles, "entry": {"role": "app", "index": -1}}))
thrown("spec-entry-index-beyond-count-is-topology", lambda: cluster.spec_errors({**homog, "entry": {"role": None, "index": 7}}))
thrown("spec-fallback-subnet-non-canonical", lambda: cluster.spec_errors({**homog, "fallback_subnet": "10.110.0.1/20"}))
thrown("spec-fallback-subnet-not-permitted",
       lambda: cluster.spec_errors({**homog, "registry": without(registry, "digitalocean")}))

# --- ids and counts
line("node-ids-homog", 0, [], ",".join(id_str(i) for i in cluster.node_ids(homog, vultr())))
line("node-ids-roles", 0, [], ",".join(id_str(i) for i in cluster.node_ids(roles, vultr())))
line("node-count-present-valid", 0, [], str(cluster.node_count(homog, vultr(**{"node-count": 5}), None)))
line("node-count-absent-default", 0, [], str(cluster.node_count(homog, vultr(), None)))
line("node-count-present-string-as-is", 0, [], str(cluster.node_count(homog, vultr(**{"node-count": "3"}), None)))
line("node-count-fixed-role", 0, [], str(cluster.node_count(roles, vultr(**{"clickhouse-count": 5}), "app")))
line("node-id-str", 0, [],
     ",".join(id_str(i) for i in [{"role": None, "index": 0}, {"role": "app", "index": 2},
                                  {"role": None, "index": None}, {"role": "app", "index": None}]))
line("entry-id", 0, [], f"{id_str(cluster.entry_id(homog))};{id_str(cluster.entry_id(roles))}")

# --- topology-errors
errs("topology-homog-ok", cluster.topology_errors(homog, vultr()))
errs("topology-roles-ok", cluster.topology_errors(roles, digitalocean()))
errs("topology-count-zero", cluster.topology_errors(homog, vultr(**{"node-count": 0})))
errs("topology-count-string", cluster.topology_errors(homog, vultr(**{"node-count": "3"})))
errs("topology-count-negative-pre-empts",
     cluster.topology_errors({**roles, "entry": {"role": "app", "index": 9}}, vultr(**{"clickhouse-count": -1})))
errs("topology-entry-outside-homog",
     cluster.topology_errors({**homog, "entry": {"role": None, "index": 3}}, vultr()))
errs("topology-entry-outside-roles",
     cluster.topology_errors({**roles, "entry": {"role": "clickhouse", "index": 2}}, vultr(**{"clickhouse-count": 2})))
errs("topology-fallback-subnet-required", cluster.topology_errors(without(homog, "fallback_subnet"), digitalocean()))
errs("topology-fallback-subnet-not-required-created",
     cluster.topology_errors(without(homog, "fallback_subnet"), vultr()))
errs("topology-offsets-overlap", cluster.topology_errors(overlap, vultr()))
errs("topology-offsets-overlap-no-network", cluster.topology_errors(overlap, none()))
errs("topology-public-outside-none", cluster.topology_errors(high, none()))
errs("topology-public-outside-created-slash-20",
     cluster.topology_errors(high, vultr(**{"vultr-vpc-subnet": "10.40.0.0/20"})))
errs("topology-public-and-private-outside-discovered",
     cluster.topology_errors({**high, "fallback_subnet": "10.110.0.0/24"}, digitalocean()))
errs("topology-name-rule-rejects", cluster.topology_errors(own, vultr()))
long_profile = "a" * 62
errs("topology-name-too-long-vultr", cluster.topology_errors(one, vultr(profile=long_profile)))
errs("topology-name-too-long-no-rule", cluster.topology_errors(one, none(profile=long_profile)))
errs("topology-name-63-ok", cluster.topology_errors(one, vultr(profile="a" * 61)))

# --- network-errors
errs("network-created-ok", cluster.network_errors(homog, vultr()))
errs("network-discovered-ok", cluster.network_errors(homog, digitalocean()))
errs("network-none-ok", cluster.network_errors(homog, none()))
errs("network-created-key-missing", cluster.network_errors(homog, vultr(**{"vultr-vpc-subnet": None})))
errs("network-created-key-placeholder", cluster.network_errors(homog, vultr(**{"vultr-vpc-subnet": "REPLACE_ME"})))
errs("network-created-non-canonical", cluster.network_errors(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.1/24"})))
errs("network-created-ipv6", cluster.network_errors(homog, vultr(**{"vultr-vpc-subnet": "2001:db8::/64"})))
errs("network-created-no-prefix", cluster.network_errors(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.0"})))
errs("network-created-offset-outside", cluster.network_errors(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.0/29"})))
errs("network-created-slash-28-holds-three", cluster.network_errors(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.0/28"})))
errs("network-created-slash-28-broadcast",
     cluster.network_errors(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.0/28", "node-count": 6})))
errs("network-created-slash-24-crossing-refused", cluster.network_errors(high, vultr()))
errs("network-created-slash-20-crossing-holds", cluster.network_errors(high, vultr(**{"vultr-vpc-subnet": "10.40.0.0/20"})))
errs("network-created-invalid-count-skipped", cluster.network_errors(homog, vultr(**{"node-count": "3"})))
errs("network-fallback-subnet-non-canonical",
     cluster.network_errors({**homog, "fallback_subnet": "10.110.0.1/20"}, digitalocean()))

# --- fallbacks
line("fallback-homog-vultr-24", 0, [], nodes_str(cluster.fallback_nodes(homog, vultr())))
line("fallback-homog-vultr-20", 0, [], nodes_str(cluster.fallback_nodes(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.0/20"}))))
line("fallback-homog-do-20", 0, [], nodes_str(cluster.fallback_nodes(homog, digitalocean())))
line("fallback-homog-do-24", 0, [],
     nodes_str(cluster.fallback_nodes({**homog, "fallback_subnet": "10.110.0.0/24"}, digitalocean())))
line("fallback-roles-vultr-24", 0, [], nodes_str(cluster.fallback_nodes(roles, vultr())))
line("fallback-roles-vultr-20", 0, [], nodes_str(cluster.fallback_nodes(roles, vultr(**{"vultr-vpc-subnet": "10.40.0.0/20"}))))
line("fallback-roles-do-20", 0, [], nodes_str(cluster.fallback_nodes(roles, digitalocean())))
line("fallback-roles-do-24", 0, [],
     nodes_str(cluster.fallback_nodes({**roles, "fallback_subnet": "10.110.0.0/24"}, digitalocean())))
line("fallback-roles-none", 0, [], nodes_str(cluster.fallback_nodes(roles, none())))
line("fallback-roles-count-one-drops-index", 0, [], nodes_str(cluster.fallback_nodes(roles, vultr(**{"clickhouse-count": 1}))))
line("fallback-compute-name-base", 0, [], nodes_str(cluster.fallback_nodes(homog, vultr(**{"vultr-name": " box "}))))
line("fallback-slash-20-crosses-octet", 0, [], nodes_str(cluster.fallback_nodes(high, vultr(**{"vultr-vpc-subnet": "10.40.0.0/20"}))))
line("fallback-unparsable-subnet-omits-vpc-ip", 0, [], nodes_str(cluster.fallback_nodes(homog, vultr(**{"vultr-vpc-subnet": "nope"}))))
line("fallback-node-name-and-offset", 0, [],
     ";".join(str(x) for x in [cluster.fallback_node_name(roles, vultr(), {"role": "clickhouse", "index": 1}),
                               cluster.fallback_node_name(roles, vultr(**{"clickhouse-count": 1}), {"role": "clickhouse", "index": 0}),
                               cluster.fallback_offset(roles, vultr(), "app"),
                               cluster.fallback_offset(roles, vultr(), "redis"),
                               cluster.fallback_offset(roles, vultr(**{"clickhouse-count": 5}), "app")]))


# --- node-errors
def ne(case_name: str, spec, opts, params) -> None:
    e = cluster.node_errors(spec, opts, params)
    line(case_name, 0 if not e else 2, e or [], "nil" if e is None else None)


ne("node-errors-params-nil", homog, vultr(), None)
ne("node-errors-complete", homog, vultr(), homog_params)
ne("node-errors-empty-nodes", homog, vultr(), {"provider": "vultr", "nodes": []})
ne("node-errors-nodes-absent", homog, vultr(), {"provider": "vultr"})
ne("node-errors-missing-id", homog, vultr(), {"nodes": [node(None, 0), node(None, 2)]})
ne("node-errors-extra-id", homog, vultr(), {"nodes": [node(None, 0), node(None, 1), node(None, 2), node(None, 3)]})
ne("node-errors-duplicate-id", homog, vultr(), {"nodes": [node(None, 0), node(None, 1), node(None, 2), node(None, 1)]})
ne("node-errors-without-name", homog, vultr(), {"nodes": [node(None, 0), without(node(None, 1), "name"), node(None, 2)]})
without_vpc = [without(n, "vpc_ip") for n in homog_params["nodes"]]
ne("node-errors-without-vpc-ip-none", homog, none(), {"nodes": without_vpc})
ne("node-errors-without-vpc-ip-created", homog, vultr(), {"nodes": without_vpc})
ne("node-errors-without-vpc-ip-discovered", homog, digitalocean(), {"nodes": without_vpc})
ne("node-errors-blank-ip", homog, vultr(), {"nodes": [node(None, 0, ip=""), node(None, 1), node(None, 2)]})
ne("node-errors-null-name", homog, vultr(), {"nodes": [node(None, 0), node(None, 1, name=None), node(None, 2)]})
ne("node-errors-whitespace-and-non-string", homog, vultr(),
   {"nodes": [node(None, 0, sudoer="  "), node(None, 1, user=7), node(None, 2)]})
ne("node-errors-legacy-null-index", one, vultr(), {"nodes": [{**node(None, 0), "index": None}]})
ne("node-errors-string-index-undeclared", one, vultr(), {"nodes": [{**node(None, 0), "index": "0"}]})
ne("node-errors-roles-ok", roles, vultr(),
   {"nodes": [node("app", 0), node("clickhouse", 2), node("clickhouse", 0), node("clickhouse", 1),
              node("redis", 0), node("neon", 0)]})
ne("node-errors-roles-missing-and-extra", roles, vultr(),
   {"nodes": [node("app", 0), node("clickhouse", 1), node("clickhouse", 0), node("redis", 0), node("web", 0)]})
ne("node-errors-all-classes-in-order", homog, vultr(),
   {"nodes": [node(None, 0), node(None, 1, ip=None), node(None, 0), node(None, 9)]})

# --- nodes
line("nodes-fallback-when-nil", 0, [], nodes_str(cluster.nodes(homog, vultr(), None)))
state_params = {"provider": "digitalocean", "reserved_ip": "203.0.113.7",
                "nodes": [node("app", 0, droplet_id="3"), node("clickhouse", 2, droplet_id="2"),
                          node("clickhouse", 0), node("clickhouse", 1), node("redis", 0), node("neon", 0)]}
ns = cluster.nodes(roles, digitalocean(), state_params)
line("nodes-from-state-preserve-extras", 0, [],
     nodes_str(ns) + ";droplet_ids=" + ",".join(n["droplet_id"] if "droplet_id" in n else "-" for n in ns))
thrown("nodes-throws-on-partial", lambda: cluster.nodes(homog, vultr(), {"nodes": [node(None, 0), node(None, 1)]}))

# --- output-params and the re-exports
p = cluster.output_params({"tofu/outputs": {"params": {
    "provider": "vultr", "ssh_key_id": "77", "reserved_ip": "203.0.113.7",
    "nodes": [{"ip": "1.2.3.4", "vpc_ip": "10.0.0.4", "index": 0, "role": None, "droplet_id": "9"}]}}})
line("output-params", 0, [],
     ";".join([p["provider"], p["ssh_key_id"], p["reserved_ip"],
               p["nodes"][0]["ip"], p["nodes"][0]["vpc_ip"], p["nodes"][0]["droplet_id"],
               b(cluster.output_params({}) is None)]))


async def step_error(_opts):
    raise StepError("tofu output failed: boom")


async def nothing(_opts):
    return None


r = asyncio.run(cluster.read_state(vultr(), step_error))
line("read-state-error", 1, [r["error"]])
r = asyncio.run(cluster.read_state(vultr(), nothing))
line("read-state-nil", 0, [], "params:" + ("none" if "params" in r else "absent"))
errs("provider-state-mismatch", cluster.provider_state_errors(homog, vultr(), {"provider": "digitalocean"}))
errs("provider-state-match", cluster.provider_state_errors(homog, vultr(), homog_params))
called = 0


def thunk():
    global called
    called += 1
    return ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]


def v(case_name: str, params) -> None:
    e = cluster.provider_validator(homog, vultr(), params, thunk)
    line(case_name, 0 if not e else 2, e, f"thunk-calls:{called}")


v("validator-mismatch-before-secrets", {"provider": "digitalocean"})
v("validator-match", homog_params)
v("validator-no-state", None)

# --- resolved-cluster
fallback = {"once/cluster": {"nodes": cluster.fallback_nodes(homog, vultr())}}
out("resolved-nil-outputs", cluster.resolved_cluster(homog, vultr(), {"a": 1}, fallback, None))
out("resolved-partial", cluster.resolved_cluster(homog, vultr(), {}, fallback, {"nodes": [node(None, 0, ip=""), node(None, 9)]}))
outputs = {**homog_params, "reserved_ip": "203.0.113.7"}
o = cluster.resolved_cluster(homog, vultr(), {"a": 1}, fallback, outputs)
out("resolved-ok", o, f"a:{o['a']};reserved_ip:{o['once/cluster']['reserved_ip']}"
                      f";nodes:{nodes_str(o['once/cluster']['nodes'])}"
                      f";fallback-replaced:{b(fallback['once/cluster'] != o['once/cluster'])}")

# --- adopt-state (opt-out opts: with_machine_key leaves them untouched)
opt_out = vultr(**{"vultr-ssh-keys": "key-uuid"})
out("adopt-delete-error", cluster.adopt_state(homog, opt_out, "delete", {"error": "HTTP 403 from backend"}))
out("adopt-describe-error", cluster.adopt_state(homog, opt_out, "describe", {"error": "HTTP 403 from backend"}))
out("adopt-partial", cluster.adopt_state(homog, opt_out, "delete", {"params": {"nodes": [node(None, 0)]}}))
params = {**homog_params, "reserved_ip": "203.0.113.7",
          "nodes": [node(None, 0, droplet_id="1"), node(None, 1, droplet_id="2"), node(None, 2, droplet_id="3")]}
o = cluster.adopt_state(homog, {**opt_out, "ip": "9.9.9.9"}, "delete", {"params": params})
out("adopt-success-extras", o,
    f"cluster-equals-params:{b(params == o['once/cluster'])}"
    f";reserved_ip:{o['once/cluster']['reserved_ip']}"
    f";droplet_ids:{','.join(n['droplet_id'] for n in o['once/cluster']['nodes'])}"
    f";ip:{o['ip']};nodes-flattened:{b('nodes' in o)};keygen:{b('ssh-keygen' in o)}")
o = cluster.adopt_state(homog, opt_out, "delete", {"params": None})
out("adopt-no-params", o, f"cluster:{b('once/cluster' in o)}")

# --- aliases and ssh-config-hosts
line("aliases-homog", 0, [], ",".join(cluster.aliases(homog, vultr())))
line("aliases-homog-count-one", 0, [], ",".join(cluster.aliases(homog, vultr(**{"node-count": 1}))))
line("aliases-roles", 0, [], ",".join(cluster.aliases(roles, vultr())))
line("aliases-roles-count-one", 0, [], ",".join(cluster.aliases(roles, vultr(**{"clickhouse-count": 1}))))
line("aliases-follow-profile-not-name", 0, [], ",".join(cluster.aliases(homog, vultr(**{"vultr-name": "box"}))))
line("ssh-config-hosts-homog", 0, [], hosts_str(cluster.ssh_config_hosts(homog, vultr(), cluster.nodes(homog, vultr(), None))))
line("ssh-config-hosts-roles", 0, [], hosts_str(cluster.ssh_config_hosts(roles, vultr(), cluster.nodes(roles, vultr(), None))))
line("ssh-config-hosts-from-state", 0, [],
     hosts_str(cluster.ssh_config_hosts(homog, vultr(), cluster.nodes(homog, vultr(), homog_params))))

# --- state-errors
thrown("state-errors-spec-throws", lambda: cluster.state_errors(base, vultr()))
thrown("state-errors-homog-ok", lambda: cluster.state_errors(homog, vultr()))
thrown("state-errors-roles-ok", lambda: cluster.state_errors(roles, digitalocean()))
thrown("state-errors-unselected", lambda: cluster.state_errors(homog, {"provider-compute": "hetzner"}))
thrown("state-errors-order",
       lambda: cluster.state_errors(homog, vultr(**{"vultr-ssh-sources": ["nope"], "vultr-vpc-subnet": "10.40.0.1/24",
                                                    "node-count": 0})))
thrown("state-errors-discovered-keeps-do-vpc",
       lambda: cluster.state_errors(homog, digitalocean(**{"digitalocean-vpc-uuid": "vpc-123",
                                                           "digitalocean-vpc-cidr": "10.50.0.0/24"})))
thrown("state-errors-created-filters-do-vpc",
       lambda: cluster.state_errors(do_created, digitalocean(**{"digitalocean-vpc-cidr": "10.50.0.0/24"})))
thrown("state-errors-created-checks-own-key",
       lambda: cluster.state_errors(do_created, digitalocean(**{"digitalocean-vpc-cidr": "10.50.0.1/24"})))
thrown("state-errors-created-key-required",
       lambda: cluster.state_errors(do_created, digitalocean(**{"digitalocean-vpc-uuid": "vpc-123"})))
