import re

import pytest
from blue.workflow import StepError

from package_once_blue import compute_cluster as sut

# A three-provider stub registry: a created network on Vultr, a discovered
# one on DigitalOcean, and a provider with no network at all. The same stub
# drives green and red and the cluster parity driver.
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
    return {"role": role, "index": index, "ip": f"203.0.113.{10 + index}", "vpc_ip": f"10.40.0.{10 + index}",
            "name": f"n-{index}", "user": "root", "sudoer": "root", **kvs}


homog_params = {"provider": "vultr", "ssh_key_id": "77", "nodes": [node(None, 0), node(None, 1), node(None, 2)]}


def spec_message(spec):
    try:
        sut.spec_errors(spec)
        return None
    except sut.ClusterError as e:
        return str(e)


def test_spec_errors_throw_on_the_first_static_problem_and_pass_both_shapes():
    assert sut.spec_errors(homog) == []
    assert sut.spec_errors(roles) == []
    assert spec_message({**base, "roles": []}) == ":roles must be a non-empty vector"
    assert spec_message(base) == ":roles must be a non-empty vector"
    assert spec_message({**base, "roles": [{"role": None, "count": 1}, {"role": "app", "count": 1}]}) == \
        "the nil role must be the only entry in :roles"
    assert spec_message({**base, "roles": [{"role": "Foo", "count": 1}]}) == \
        'role "Foo" must match ^[a-z][a-z0-9]*(-[a-z0-9]+)*$'
    assert spec_message({**base, "roles": [{"role": "foo-", "count": 1}]}) == \
        'role "foo-" must match ^[a-z][a-z0-9]*(-[a-z0-9]+)*$'
    assert spec_message({**base, "roles": [{"role": "app", "count": 1}, {"role": "app", "count": 2}]}) == \
        'role "app" is declared more than once'
    assert spec_message({**base, "roles": [{"role": "foo", "count": 2}, {"role": "foo-0", "count": 1}]}) == \
        'role "foo-0" reads as an alias of role "foo"'
    assert spec_message({**base, "roles": [{"role": "app", "count": 0}]}) == \
        ':count of role "app" must be a positive integer'
    assert spec_message({**base, "roles": [{"role": None, "count_key": "n"}]}) == \
        ":count of the nil role must be a positive integer"
    assert spec_message({**base, "roles": [{"role": "app", "count": 1, "fallback_offset": "12"}]}) == \
        ':fallback-offset of role "app" must be a non-negative integer'
    # entry
    assert spec_message({**homog, "entry": {"index": 0}}) == ":entry must carry :role and :index"
    assert spec_message({**roles, "entry": {"role": "web", "index": 0}}) == ":entry :role must name a declared role"
    assert spec_message({**roles, "entry": {"role": "app", "index": -1}}) == ":entry :index must be a non-negative integer"
    # the index against the count is topology_errors' job
    assert sut.spec_errors({**homog, "entry": {"role": None, "index": 7}}) == []
    # fallback-subnet
    assert spec_message({**homog, "fallback_subnet": "10.110.0.1/20"}) == \
        ":fallback-subnet must be a canonical IPv4 network such as 10.40.0.0/24"
    assert spec_message({**homog, "registry": without(registry, "digitalocean")}) == \
        ":fallback-subnet is permitted only when an advertised provider's network is discovered"
    assert sut.spec_errors(without(homog, "fallback_subnet")) == []


def test_node_ids_and_counts_follow_the_roles_and_the_present_count_key():
    assert sut.node_ids(homog, vultr()) == [{"role": None, "index": 0}, {"role": None, "index": 1}, {"role": None, "index": 2}]
    assert sut.node_ids(homog, vultr(**{"node-count": 5})) == [
        {"role": None, "index": 0}, {"role": None, "index": 1}, {"role": None, "index": 2},
        {"role": None, "index": 3}, {"role": None, "index": 4}]
    assert [sut.node_id_str(i) for i in sut.node_ids(roles, vultr())] == \
        ["neon-0", "redis-0", "clickhouse-0", "clickhouse-1", "clickhouse-2", "app-0"]
    # absent key: the default
    assert sut.node_count(homog, vultr(), None) == 3
    assert sut.node_count(homog, vultr(**{"node-count": 5}), None) == 5
    # a present value is used as-is
    assert sut.node_count(homog, vultr(**{"node-count": "3"}), None) == "3"
    # a fixed role ignores opts
    assert sut.node_count(roles, vultr(), "app") == 1
    assert sut.node_id_str({"role": None, "index": None}) == "null"
    assert sut.node_id_str({"role": "app", "index": None}) == "app-null"
    assert sut.entry_id(homog) == {"role": None, "index": 0}
    assert sut.entry_id(roles) == {"role": "app", "index": 0}


def test_topology_errors_hold_counts_entry_subnet_addresses_and_names():
    assert sut.topology_errors(homog, vultr()) == []
    assert sut.topology_errors(roles, digitalocean()) == []
    # count key: zero, string, negative; nothing else is reported until it is fixed
    assert sut.topology_errors(homog, vultr(**{"node-count": 0})) == [":node-count must be a positive integer"]
    assert sut.topology_errors(homog, vultr(**{"node-count": "3"})) == [":node-count must be a positive integer"]
    assert sut.topology_errors({**roles, "entry": {"role": "app", "index": 9}}, vultr(**{"clickhouse-count": -1})) == \
        [":clickhouse-count must be a positive integer"]
    # entry outside the effective count
    assert sut.topology_errors({**homog, "entry": {"role": None, "index": 3}}, vultr()) == \
        [":entry names 3, a node this topology does not declare"]
    assert sut.topology_errors({**roles, "entry": {"role": "clickhouse", "index": 2}}, vultr(**{"clickhouse-count": 2})) == \
        [":entry names clickhouse-2, a node this topology does not declare"]
    # fallback-subnet is required by a discovered network alone
    assert sut.topology_errors(without(homog, "fallback_subnet"), digitalocean()) == \
        [":fallback-subnet is required when the selected provider's network is discovered"]
    assert sut.topology_errors(without(homog, "fallback_subnet"), vultr()) == []
    # overlapping explicit offsets collide on both address families
    overlap = {**base, "roles": [{"role": "a", "count": 2, "fallback_offset": 10},
                                 {"role": "b", "count": 2, "fallback_offset": 11}]}
    assert sut.topology_errors(overlap, vultr()) == [
        "the public fallback address 192.0.2.11 is generated for more than one node",
        "the private fallback address 10.40.0.11 is generated for more than one node"]
    # no network, no private addresses
    assert sut.topology_errors(overlap, none()) == [
        "the public fallback address 192.0.2.11 is generated for more than one node"]
    # the public range is checked here for every mode
    high = {**base, "roles": [{"role": None, "count": 3, "fallback_offset": 254}]}
    assert sut.topology_errors(high, none()) == ["192.0.2.0/24 has no usable host address for 1, 2"]
    # the created range is network_errors' job
    assert sut.topology_errors(high, vultr(**{"vultr-vpc-subnet": "10.40.0.0/20"})) == \
        ["192.0.2.0/24 has no usable host address for 1, 2"]
    # the discovered fallback subnet is checked here
    assert sut.topology_errors({**high, "fallback_subnet": "10.110.0.0/24"}, digitalocean()) == [
        "192.0.2.0/24 has no usable host address for 1, 2",
        ":fallback-subnet has no usable host address for 1, 2"]
    # names: the provider's rule and the length, aliases the length
    own = {**homog, "name_rules": {"vultr": {"re": re.compile(r"prod"), "message": "must be prod"}}}
    assert sut.topology_errors(own, vultr()) == [
        'the fallback name "prod-0" must be prod',
        'the fallback name "prod-1" must be prod',
        'the fallback name "prod-2" must be prod']
    long_profile = "a" * 62
    one = {**base, "roles": [{"role": None, "count": 1}]}
    assert sut.topology_errors(one, vultr(profile=long_profile)) == [
        f'the fallback name "{long_profile}-0" must be a safe 1-63 character name',
        f'the alias "{long_profile}-0" must be at most 63 characters']
    # no rule for the provider: the length alone
    assert sut.topology_errors(one, none(profile=long_profile)) == [
        f'the fallback name "{long_profile}-0" must be at most 63 characters',
        f'the alias "{long_profile}-0" must be at most 63 characters']
    assert sut.topology_errors(one, vultr(profile="a" * 61)) == []


def test_network_errors_hold_the_created_key_and_the_fallback_subnet():
    assert sut.network_errors(homog, vultr()) == []
    assert sut.network_errors(homog, digitalocean()) == []
    assert sut.network_errors(homog, none()) == []
    assert sut.network_errors(homog, vultr(**{"vultr-vpc-subnet": None})) == [":vultr-vpc-subnet is required"]
    assert sut.network_errors(homog, vultr(**{"vultr-vpc-subnet": "REPLACE_ME"})) == [":vultr-vpc-subnet is required"]
    canonical = ":vultr-vpc-subnet must be a canonical IPv4 network such as 10.40.0.0/24"
    assert sut.network_errors(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.1/24"})) == [canonical]
    assert sut.network_errors(homog, vultr(**{"vultr-vpc-subnet": "2001:db8::/64"})) == [canonical]
    assert sut.network_errors(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.0"})) == [canonical]
    # every fallback offset must fit the usable host range
    assert sut.network_errors(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.0/29"})) == \
        [":vultr-vpc-subnet has no usable host address for 0, 1, 2"]
    # hosts 1-14 hold offsets 10, 11, 12
    assert sut.network_errors(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.0/28"})) == []
    # offset 15 is the broadcast address
    assert sut.network_errors(homog, vultr(**{"vultr-vpc-subnet": "10.40.0.0/28", "node-count": 6})) == \
        [":vultr-vpc-subnet has no usable host address for 5"]
    assert sut.network_errors(roles, vultr(**{"vultr-vpc-subnet": "10.40.0.0/27"})) == []
    high = {**base, "roles": [{"role": None, "count": 3, "fallback_offset": 254}]}
    assert sut.network_errors(high, vultr()) == [":vultr-vpc-subnet has no usable host address for 1, 2"]
    # a /20 holds the crossing
    assert sut.network_errors(high, vultr(**{"vultr-vpc-subnet": "10.40.0.0/20"})) == []
    # an invalid count is reported by topology_errors, not here
    assert sut.network_errors(homog, vultr(**{"node-count": "3"})) == []
    # fallback-subnet under its own name
    assert sut.network_errors({**homog, "fallback_subnet": "10.110.0.1/20"}, digitalocean()) == \
        [":fallback-subnet must be a canonical IPv4 network such as 10.40.0.0/24"]


def test_fallbacks_cut_both_families_from_the_offset_with_32_bit_arithmetic():
    assert sut.fallback_nodes(homog, vultr()) == [
        {"role": None, "index": 0, "name": "prod-0", "ip": "192.0.2.10", "user": "root", "sudoer": "root", "vpc_ip": "10.40.0.10"},
        {"role": None, "index": 1, "name": "prod-1", "ip": "192.0.2.11", "user": "root", "sudoer": "root", "vpc_ip": "10.40.0.11"},
        {"role": None, "index": 2, "name": "prod-2", "ip": "192.0.2.12", "user": "root", "sudoer": "root", "vpc_ip": "10.40.0.12"}]
    assert [n["name"] for n in sut.fallback_nodes(roles, vultr())] == \
        ["prod-neon", "prod-redis", "prod-clickhouse-0", "prod-clickhouse-1", "prod-clickhouse-2", "prod-app"]
    assert [n["ip"] for n in sut.fallback_nodes(roles, vultr())] == \
        ["192.0.2.10", "192.0.2.11", "192.0.2.20", "192.0.2.21", "192.0.2.22", "192.0.2.12"]
    # discovered: the spec's fallback subnet
    assert [n["vpc_ip"] for n in sut.fallback_nodes(roles, digitalocean())] == \
        ["10.110.0.10", "10.110.0.11", "10.110.0.20", "10.110.0.21", "10.110.0.22", "10.110.0.12"]
    # compute's name supplies the base
    assert sut.fallback_nodes(roles, vultr(**{"vultr-name": "box"}))[3]["name"] == "box-clickhouse-1"
    # a role of count 1 drops the index
    assert sut.fallback_node_name(roles, vultr(**{"clickhouse-count": 1}), {"role": "clickhouse", "index": 0}) == "prod-clickhouse"
    # a /20 crosses the octet boundary
    high = {**base, "roles": [{"role": None, "count": 3, "fallback_offset": 254}]}
    assert [n["vpc_ip"] for n in sut.fallback_nodes(high, vultr(**{"vultr-vpc-subnet": "10.40.0.0/20"}))] == \
        ["10.40.0.254", "10.40.0.255", "10.40.1.0"]
    assert [n["ip"] for n in sut.fallback_nodes(high, vultr(**{"vultr-vpc-subnet": "10.40.0.0/20"}))] == \
        ["192.0.2.254", "192.0.2.255", "192.0.3.0"]
    # no network: no vpc_ip key at all
    assert sut.fallback_nodes({**base, "roles": [{"role": None, "count": 1}]}, none()) == [
        {"role": None, "index": 0, "name": "prod-0", "ip": "192.0.2.10", "user": "root", "sudoer": "root"}]
    # an unparsable created subnet leaves vpc_ip absent; validation reports it
    assert "vpc_ip" not in sut.fallback_nodes(homog, vultr(**{"vultr-vpc-subnet": "nope"}))[0]
    # ipv4_network
    assert sut.ipv4_network("10.40.0.0/24") == \
        {"cidr": "10.40.0.0/24", "address": 170393600, "prefix": 24, "first": 170393601, "last": 170393854}
    assert sut.ipv4_network("10.40.0.1/24") is None
    assert sut.ipv4_network("2001:db8::/32") is None
    assert sut.ipv4_network(None) is None
    # no usable host
    assert sut.ipv4_network("10.0.0.0/32")["first"] > sut.ipv4_network("10.0.0.0/32")["last"]


complete_message = "the compute stage did not report a complete node (ip, vpc_ip, name, user, sudoer) for "


def test_node_errors_report_the_four_classes_in_order():
    assert sut.node_errors(homog, vultr(), None) is None
    assert sut.node_errors(homog, vultr(), homog_params) == []
    # an empty or absent nodes list reports every declared id missing
    assert sut.node_errors(homog, vultr(), {"provider": "vultr", "nodes": []}) == \
        ["the compute stage did not report nodes this package declares: 0, 1, 2"]
    assert sut.node_errors(homog, vultr(), {"provider": "vultr"}) == \
        ["the compute stage did not report nodes this package declares: 0, 1, 2"]
    assert sut.node_errors(homog, vultr(), {"nodes": [node(None, 0), node(None, 2)]}) == \
        ["the compute stage did not report nodes this package declares: 1"]
    assert sut.node_errors(homog, vultr(), {"nodes": [node(None, 0), node(None, 1), node(None, 2), node(None, 3)]}) == \
        ["the compute stage reported nodes this package does not declare: 3"]
    assert sut.node_errors(homog, vultr(), {"nodes": [node(None, 0), node(None, 1), node(None, 2), node(None, 1)]}) == \
        ["the compute stage reported 1 more than once"]
    assert sut.node_errors(homog, vultr(), {"nodes": [node(None, 0), without(node(None, 1), "name"), node(None, 2)]}) == \
        [complete_message + "1; refusing to render a partial cluster"]
    # blank, null, whitespace and non-strings count as missing
    assert sut.node_errors(homog, vultr(), {"nodes": [node(None, 0, ip=""), node(None, 1, name=None), node(None, 2, user=7)]}) == \
        [complete_message + "0, 1, 2; refusing to render a partial cluster"]
    assert sut.node_errors(homog, vultr(), {"nodes": [node(None, 0, sudoer="  "), node(None, 1), node(None, 2)]}) == \
        [complete_message + "0; refusing to render a partial cluster"]
    # vpc_ip is required unless the network mode is none
    without_vpc = [without(n, "vpc_ip") for n in homog_params["nodes"]]
    assert sut.node_errors(homog, vultr(), {"nodes": without_vpc}) == \
        [complete_message + "0, 1, 2; refusing to render a partial cluster"]
    assert sut.node_errors(homog, digitalocean(), {"nodes": without_vpc}) == \
        [complete_message + "0, 1, 2; refusing to render a partial cluster"]
    assert sut.node_errors(homog, none(), {"nodes": without_vpc}) == []
    # a legacy index: null is an undeclared id
    assert sut.node_errors({**base, "roles": [{"role": None, "count": 1}]}, vultr(),
                           {"nodes": [{**node(None, 0), "index": None}]}) == [
        "the compute stage did not report nodes this package declares: 0",
        "the compute stage reported nodes this package does not declare: null"]
    # role-based ids render as role-index in declared order
    assert sut.node_errors(roles, vultr(),
                           {"nodes": [node("app", 0), node("clickhouse", 1), node("clickhouse", 0),
                                      node("redis", 0), node("web", 0)]}) == [
        "the compute stage did not report nodes this package declares: neon-0, clickhouse-2",
        "the compute stage reported nodes this package does not declare: web-0"]
    # all four classes at once, in order
    assert sut.node_errors(homog, vultr(),
                           {"nodes": [node(None, 0), node(None, 1, ip=None), node(None, 0), node(None, 9)]}) == [
        "the compute stage did not report nodes this package declares: 2",
        "the compute stage reported nodes this package does not declare: 9",
        "the compute stage reported 0 more than once",
        complete_message + "1; refusing to render a partial cluster"]


def test_nodes_come_from_state_in_declared_order_with_extras_preserved():
    assert sut.nodes(homog, vultr(), None) == sut.fallback_nodes(homog, vultr())
    params = {"provider": "digitalocean", "reserved_ip": "203.0.113.7",
              "nodes": [node("app", 0, droplet_id="3"), node("clickhouse", 2, droplet_id="2"),
                        node("clickhouse", 0), node("clickhouse", 1), node("redis", 0), node("neon", 0)]}
    out = sut.nodes(roles, digitalocean(), params)
    assert [sut.node_id_str(n) for n in out] == ["neon-0", "redis-0", "clickhouse-0", "clickhouse-1", "clickhouse-2", "app-0"]
    # verbatim, extras kept
    assert out[-1] == node("app", 0, droplet_id="3")
    assert out[4]["droplet_id"] == "2"
    # a partial state raises with the messages
    with pytest.raises(sut.ClusterError, match="did not report nodes this package declares: 2"):
        sut.nodes(homog, vultr(), {"nodes": [node(None, 0), node(None, 1)]})


async def test_output_params_and_the_re_exports_are_computes():
    assert sut.output_params({"tofu/outputs": {"params": {
        "provider": "vultr", "ssh_key_id": "77",
        "nodes": [{"ip": "1.2.3.4", "vpc_ip": "10.0.0.4", "index": 0, "role": None}]}}}) == {
        "provider": "vultr", "ssh_key_id": "77",
        "nodes": [{"ip": "1.2.3.4", "vpc_ip": "10.0.0.4", "index": 0, "role": None}]}
    assert sut.output_params({}) is None

    async def step_error(_opts):
        raise StepError("tofu output failed: boom")

    async def nothing(_opts):
        return None

    assert await sut.read_state({}, step_error) == {"error": "tofu output failed: boom"}
    assert await sut.read_state({}, nothing) == {"params": None}
    assert sut.provider_state_errors(homog, vultr(), {"provider": "digitalocean"}) == \
        ["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
    called = 0

    def thunk():
        nonlocal called
        called += 1
        return ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]

    assert sut.provider_validator(homog, vultr(), {"provider": "digitalocean"}, thunk) == \
        ["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
    # the mismatch pre-empts the secrets
    assert called == 0
    assert sut.provider_validator(homog, vultr(), homog_params, thunk) == \
        ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]
    assert called == 1


def test_resolved_cluster_refuses_nil_and_partial_outputs():
    fallback = {"once/cluster": {"nodes": sut.fallback_nodes(homog, vultr())}}
    # nil outputs
    out = sut.resolved_cluster(homog, vultr(), {"a": 1}, fallback, None)
    assert out["blue/exit"] == 1
    assert out["blue/err"] == "compute produced no params output; refusing to converge against the documentation addresses"
    assert out["a"] == 1
    # partial outputs join the messages with a newline
    out = sut.resolved_cluster(homog, vultr(), {}, fallback, {"nodes": [node(None, 0, ip=""), node(None, 9)]})
    assert out["blue/exit"] == 1
    assert out["blue/err"] == (
        "the compute stage did not report nodes this package declares: 1, 2\n"
        "the compute stage reported nodes this package does not declare: 9\n"
        + complete_message + "0; refusing to render a partial cluster")
    # complete outputs replace the fallback under once/cluster
    outputs = {**homog_params, "reserved_ip": "203.0.113.7"}
    out = sut.resolved_cluster(homog, vultr(), {"a": 1}, fallback, outputs)
    assert out == {"a": 1, "once/cluster": outputs}
    assert "blue/exit" not in out


def test_adopt_state_fails_closed_refuses_a_partial_cluster_and_adopts_params_verbatim(tmp_path, monkeypatch):
    opt_out = vultr(**{"vultr-ssh-keys": "key-uuid"})
    # error: compute's two-line message
    out = sut.adopt_state(homog, opt_out, "delete", {"error": "HTTP 403 from backend"})
    assert out["blue/exit"] == 1
    assert out["blue/err"] == (
        "could not read the infrastructure state for the delete cleanup: HTTP 403 from backend\n"
        "fix the backend credentials and retry; a delete that cannot see its state has nothing to address")
    assert sut.adopt_state(homog, opt_out, "describe", {"error": "x"})["blue/err"].startswith(
        "could not read the infrastructure state for describe: x")
    # partial params exit 1 with the node errors
    out = sut.adopt_state(homog, opt_out, "delete", {"params": {"nodes": [node(None, 0)]}})
    assert out["blue/exit"] == 1
    assert out["blue/err"] == "the compute stage did not report nodes this package declares: 1, 2"
    assert "once/cluster" not in out
    # complete params land verbatim under once/cluster; nothing is flattened into opts
    params = {**homog_params, "reserved_ip": "203.0.113.7"}
    out = sut.adopt_state(homog, {**opt_out, "ip": "9.9.9.9"}, "delete", {"params": params})
    assert out["blue/exit"] == 0
    assert out["once/cluster"] == params
    # no top-level ip is adopted; the cluster is the whole map
    assert out["ip"] == "9.9.9.9"
    assert "nodes" not in out
    # opt-out opts pass through with_machine_key untouched
    assert "ssh-keygen" not in out
    # a readable state holding nothing leaves once/cluster absent
    out = sut.adopt_state(homog, opt_out, "delete", {"params": None})
    assert out["blue/exit"] == 0
    assert "once/cluster" not in out
    # keygen mode fills the machine key through once ssh
    monkeypatch.setenv("HOME", str(tmp_path))
    out = sut.adopt_state(homog, vultr(), "delete", {"params": homog_params})
    assert out["blue/exit"] == 0
    assert out["ssh-keygen"] is True
    assert out["vultr-ssh-keys"].startswith(str(tmp_path))


def test_aliases_and_ssh_config_hosts_follow_the_shape_and_the_entry():
    assert sut.aliases(homog, vultr()) == ["prod", "prod-0", "prod-1", "prod-2"]
    assert sut.aliases(homog, vultr(**{"node-count": 1})) == ["prod", "prod-0"]
    assert sut.aliases(roles, vultr()) == \
        ["prod", "prod-neon", "prod-redis", "prod-clickhouse-0", "prod-clickhouse-1", "prod-clickhouse-2", "prod-app"]
    assert sut.aliases(roles, vultr(**{"clickhouse-count": 1})) == ["prod", "prod-neon", "prod-redis", "prod-clickhouse", "prod-app"]
    # aliases follow the profile, never the compute name
    assert sut.aliases(homog, vultr(**{"vultr-name": "box"})) == ["prod", "prod-0", "prod-1", "prod-2"]
    # hosts from the fallbacks: the bare profile points at the entry node
    assert sut.ssh_config_hosts(homog, vultr(), sut.nodes(homog, vultr(), None)) == [
        {"name": "prod", "ip": "192.0.2.10"}, {"name": "prod-0", "ip": "192.0.2.10"},
        {"name": "prod-1", "ip": "192.0.2.11"}, {"name": "prod-2", "ip": "192.0.2.12"}]
    assert sut.ssh_config_hosts(roles, vultr(), sut.nodes(roles, vultr(), None)) == [
        {"name": "prod", "ip": "192.0.2.12"}, {"name": "prod-neon", "ip": "192.0.2.10"},
        {"name": "prod-redis", "ip": "192.0.2.11"}, {"name": "prod-clickhouse-0", "ip": "192.0.2.20"},
        {"name": "prod-clickhouse-1", "ip": "192.0.2.21"}, {"name": "prod-clickhouse-2", "ip": "192.0.2.22"},
        {"name": "prod-app", "ip": "192.0.2.12"}]
    # hosts from state
    assert sut.ssh_config_hosts(homog, vultr(), sut.nodes(homog, vultr(), homog_params)) == [
        {"name": "prod", "ip": "203.0.113.10"}, {"name": "prod-0", "ip": "203.0.113.10"},
        {"name": "prod-1", "ip": "203.0.113.11"}, {"name": "prod-2", "ip": "203.0.113.12"}]


def test_state_errors_throw_on_the_spec_then_compose_compute_network_and_topology():
    with pytest.raises(sut.ClusterError, match=r"^:roles must be a non-empty vector$"):
        sut.state_errors(base, vultr())
    assert sut.state_errors(homog, vultr()) == []
    assert sut.state_errors(roles, digitalocean()) == []
    # nothing selected: compute's selection error alone
    assert sut.state_errors(homog, {"provider-compute": "hetzner"}) == \
        [":provider-compute must be one of digitalocean, none, vultr"]
    # order: compute's, then network, then topology
    assert sut.state_errors(homog, vultr(**{"vultr-ssh-sources": ["nope"], "vultr-vpc-subnet": "10.40.0.1/24",
                                            "node-count": 0})) == [
        ':vultr-ssh-sources entry "nope" is not an IPv4 or IPv6 CIDR',
        ":vultr-vpc-subnet must be a canonical IPv4 network such as 10.40.0.0/24",
        ":node-count must be a positive integer"]
    # a discovered entry keeps compute's DigitalOcean VPC refusals
    assert sut.state_errors(homog, digitalocean(**{"digitalocean-vpc-uuid": "vpc-123",
                                                   "digitalocean-vpc-cidr": "10.50.0.0/24"})) == [
        ":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime",
        ":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]
    # a created DigitalOcean entry drops them and checks its own key
    created = without({**homog, "registry": {**registry, "digitalocean": {
        **registry["digitalocean"], "network": {"mode": "created", "key": "digitalocean-vpc-cidr"}}}},
        "fallback_subnet")
    assert sut.state_errors(created, digitalocean(**{"digitalocean-vpc-cidr": "10.50.0.0/24"})) == []
    assert sut.state_errors(created, digitalocean(**{"digitalocean-vpc-cidr": "10.50.0.1/24"})) == \
        [":digitalocean-vpc-cidr must be a canonical IPv4 network such as 10.40.0.0/24"]
    # both refusals are dropped for a created entry; the key is then required
    assert sut.state_errors(created, digitalocean(**{"digitalocean-vpc-uuid": "vpc-123"})) == \
        [":digitalocean-vpc-cidr is required"]
