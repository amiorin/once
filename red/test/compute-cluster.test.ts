import { expect, test } from "bun:test";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { StepError } from "red/workflow";
import type { Opts } from "red/workflow";
import * as sut from "../src/compute-cluster.ts";

// A three-provider stub registry: a created network on Vultr, a discovered
// one on DigitalOcean, and a provider with no network at all. The same stub
// drives green and blue and the cluster parity driver.
const registry: sut.ClusterRegistry = {
  vultr: {
    required: ["vultr-region", "vultr-plan", "vultr-os-id", "vultr-ssh-sources", "vultr-vpc-subnet"],
    secrets: ["vultr-api-key"],
    tofuEnv: { "vultr-api-key": "VULTR_API_KEY" },
    network: { mode: "created", key: "vultr-vpc-subnet" },
  },
  digitalocean: {
    required: ["digitalocean-region", "digitalocean-size", "digitalocean-image", "digitalocean-ssh-sources"],
    secrets: ["do-token"],
    tofuEnv: { "do-token": "DIGITALOCEAN_TOKEN" },
    network: { mode: "discovered" },
  },
  none: { required: [], secrets: [], tofuEnv: {} },
};

const base = {
  registry,
  default: "vultr",
  sources: { nonEmpty: ["ssh-sources"], mayBeEmpty: [] },
  fallbackSubnet: "10.110.0.0/20",
};
// Deliberately malformed specs are the point of `specErrors`, so the fixtures
// are shaped freely and cast at the boundary.
const spec = (s: object): sut.ClusterSpec => s as sut.ClusterSpec;
const without = <T extends object, K extends keyof T>(o: T, key: K): Omit<T, K> => {
  const { [key]: _dropped, ...rest } = o;
  return rest;
};

const homog = spec({ ...base, roles: [{ role: null, countKey: "node-count", count: 3 }] });

const roles = spec({
  ...base,
  roles: [
    { role: "neon", count: 1 },
    { role: "redis", count: 1 },
    { role: "clickhouse", countKey: "clickhouse-count", count: 3, fallbackOffset: 20 },
    { role: "app", count: 1, fallbackOffset: 12 },
  ],
  entry: { role: "app", index: 0 },
});

const vultr = (kvs: Record<string, unknown> = {}): Opts =>
  ({ profile: "prod", "provider-compute": "vultr", "vultr-ssh-sources": ["10.0.0.0/8"], "vultr-vpc-subnet": "10.40.0.0/24", ...kvs });
const digitalocean = (kvs: Record<string, unknown> = {}): Opts =>
  ({ profile: "prod", "provider-compute": "digitalocean", "digitalocean-ssh-sources": ["10.0.0.0/8"], ...kvs });
const none = (kvs: Record<string, unknown> = {}): Opts => ({ profile: "prod", "provider-compute": "none", ...kvs });

const node = (role: string | null, index: number, kvs: Record<string, unknown> = {}): sut.Node =>
  ({ role, index, ip: `203.0.113.${10 + index}`, vpc_ip: `10.40.0.${10 + index}`,
     name: `n-${index}`, user: "root", sudoer: "root", ...kvs }) as sut.Node;

const homogParams: sut.ClusterParams = { provider: "vultr", ssh_key_id: "77", nodes: [node(null, 0), node(null, 1), node(null, 2)] };

function specMessage(s: sut.ClusterSpec): string | undefined {
  try {
    sut.specErrors(s);
    return undefined;
  } catch (error) {
    return (error as Error).message;
  }
}

test("spec errors throw on the first static problem and pass both shapes", () => {
  expect(sut.specErrors(homog)).toEqual([]);
  expect(sut.specErrors(roles)).toEqual([]);
  expect(specMessage(spec({ ...base, roles: [] }))).toBe(":roles must be a non-empty vector");
  expect(specMessage(spec(base))).toBe(":roles must be a non-empty vector");
  expect(specMessage(spec({ ...base, roles: [{ role: null, count: 1 }, { role: "app", count: 1 }] })))
    .toBe("the nil role must be the only entry in :roles");
  expect(specMessage(spec({ ...base, roles: [{ role: "Foo", count: 1 }] })))
    .toBe("role \"Foo\" must match ^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
  expect(specMessage(spec({ ...base, roles: [{ role: "foo-", count: 1 }] })))
    .toBe("role \"foo-\" must match ^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
  expect(specMessage(spec({ ...base, roles: [{ role: "app", count: 1 }, { role: "app", count: 2 }] })))
    .toBe("role \"app\" is declared more than once");
  expect(specMessage(spec({ ...base, roles: [{ role: "foo", count: 2 }, { role: "foo-0", count: 1 }] })))
    .toBe("role \"foo-0\" reads as an alias of role \"foo\"");
  expect(specMessage(spec({ ...base, roles: [{ role: "app", count: 0 }] })))
    .toBe(":count of role \"app\" must be a positive integer");
  expect(specMessage(spec({ ...base, roles: [{ role: null, countKey: "n" }] })))
    .toBe(":count of the nil role must be a positive integer");
  expect(specMessage(spec({ ...base, roles: [{ role: "app", count: 1, fallbackOffset: "12" }] })))
    .toBe(":fallback-offset of role \"app\" must be a non-negative integer");
  // entry
  expect(specMessage(spec({ ...homog, entry: { index: 0 } }))).toBe(":entry must carry :role and :index");
  expect(specMessage(spec({ ...roles, entry: { role: "web", index: 0 } }))).toBe(":entry :role must name a declared role");
  expect(specMessage(spec({ ...roles, entry: { role: "app", index: -1 } }))).toBe(":entry :index must be a non-negative integer");
  // a fixed role's static count bounds the index here
  expect(specMessage(spec({ ...roles, entry: { role: "app", index: 9 } }))).toBe(":entry :index must be below :count of role \"app\"");
  expect(specMessage(spec({ ...homog, entry: { role: null, index: 3 } }))).toBe(":entry :index must be below :count of the nil role");
  // the static count admits it; the index against a count-key override is topologyErrors' job
  expect(sut.specErrors(spec({ ...homog, entry: { role: null, index: 2 } }))).toEqual([]);
  // fallback-subnet
  expect(specMessage(spec({ ...homog, fallbackSubnet: "10.110.0.1/20" })))
    .toBe(":fallback-subnet must be a canonical IPv4 network such as 10.40.0.0/24");
  expect(specMessage(spec({ ...homog, registry: without(registry, "digitalocean") })))
    .toBe(":fallback-subnet is permitted only when an advertised provider's network is discovered");
  expect(sut.specErrors(spec(without(homog, "fallbackSubnet")))).toEqual([]);
});

test("node ids and counts follow the roles and the present count key", () => {
  expect(sut.nodeIds(homog, vultr())).toEqual([{ role: null, index: 0 }, { role: null, index: 1 }, { role: null, index: 2 }]);
  expect(sut.nodeIds(homog, vultr({ "node-count": 5 })))
    .toEqual([{ role: null, index: 0 }, { role: null, index: 1 }, { role: null, index: 2 }, { role: null, index: 3 }, { role: null, index: 4 }]);
  expect(sut.nodeIds(roles, vultr()).map(sut.nodeIdStr))
    .toEqual(["neon-0", "redis-0", "clickhouse-0", "clickhouse-1", "clickhouse-2", "app-0"]);
  // absent key: the default
  expect(sut.nodeCount(homog, vultr(), null)).toBe(3);
  expect(sut.nodeCount(homog, vultr({ "node-count": 5 }), null)).toBe(5);
  // a present value is used as-is
  expect(sut.nodeCount(homog, vultr({ "node-count": "3" }), null)).toBe("3");
  // a fixed role ignores opts
  expect(sut.nodeCount(roles, vultr(), "app")).toBe(1);
  expect(sut.nodeIdStr({ role: null, index: null })).toBe("null");
  expect(sut.nodeIdStr({ role: "app", index: null })).toBe("app-null");
  expect(sut.entryId(homog)).toEqual({ role: null, index: 0 });
  expect(sut.entryId(roles)).toEqual({ role: "app", index: 0 });
});

test("topology errors hold counts entry subnet addresses and names", () => {
  expect(sut.topologyErrors(homog, vultr())).toEqual([]);
  expect(sut.topologyErrors(roles, digitalocean())).toEqual([]);
  // count key: zero, string, negative; nothing else is reported until it is fixed
  expect(sut.topologyErrors(homog, vultr({ "node-count": 0 }))).toEqual([":node-count must be a positive integer"]);
  expect(sut.topologyErrors(homog, vultr({ "node-count": "3" }))).toEqual([":node-count must be a positive integer"]);
  expect(sut.topologyErrors(spec({ ...roles, entry: { role: "app", index: 9 } }), vultr({ "clickhouse-count": -1 })))
    .toEqual([":clickhouse-count must be a positive integer"]);
  // entry outside the effective count
  expect(sut.topologyErrors(spec({ ...homog, entry: { role: null, index: 3 } }), vultr()))
    .toEqual([":entry names 3, a node this topology does not declare"]);
  expect(sut.topologyErrors(spec({ ...roles, entry: { role: "clickhouse", index: 2 } }), vultr({ "clickhouse-count": 2 })))
    .toEqual([":entry names clickhouse-2, a node this topology does not declare"]);
  // fallback-subnet is required by a discovered network alone
  expect(sut.topologyErrors(spec(without(homog, "fallbackSubnet")), digitalocean()))
    .toEqual([":fallback-subnet is required when the selected provider's network is discovered"]);
  expect(sut.topologyErrors(spec(without(homog, "fallbackSubnet")), vultr())).toEqual([]);
  // overlapping explicit offsets collide on both address families
  const overlap = spec({ ...base, roles: [{ role: "a", count: 2, fallbackOffset: 10 }, { role: "b", count: 2, fallbackOffset: 11 }] });
  expect(sut.topologyErrors(overlap, vultr())).toEqual([
    "the public fallback address 192.0.2.11 is generated for more than one node",
    "the private fallback address 10.40.0.11 is generated for more than one node",
  ]);
  // no network, no private addresses
  expect(sut.topologyErrors(overlap, none()))
    .toEqual(["the public fallback address 192.0.2.11 is generated for more than one node"]);
  // the public range is checked here for every mode
  const high = spec({ ...base, roles: [{ role: null, count: 3, fallbackOffset: 254 }] });
  expect(sut.topologyErrors(high, none())).toEqual(["192.0.2.0/24 has no usable host address for 1, 2"]);
  // the created range is networkErrors' job
  expect(sut.topologyErrors(high, vultr({ "vultr-vpc-subnet": "10.40.0.0/20" })))
    .toEqual(["192.0.2.0/24 has no usable host address for 1, 2"]);
  // the discovered fallback subnet is checked here
  expect(sut.topologyErrors(spec({ ...high, fallbackSubnet: "10.110.0.0/24" }), digitalocean())).toEqual([
    "192.0.2.0/24 has no usable host address for 1, 2",
    ":fallback-subnet has no usable host address for 1, 2",
  ]);
  // names: the provider's rule and the length, aliases the length
  const own = spec({ ...homog, nameRules: { vultr: { re: /^prod$/, message: "must be prod" } } });
  expect(sut.topologyErrors(own, vultr())).toEqual([
    "the fallback name \"prod-0\" must be prod",
    "the fallback name \"prod-1\" must be prod",
    "the fallback name \"prod-2\" must be prod",
  ]);
  const longProfile = "a".repeat(62);
  const one = spec({ ...base, roles: [{ role: null, count: 1 }] });
  expect(sut.topologyErrors(one, vultr({ profile: longProfile }))).toEqual([
    `the fallback name "${longProfile}-0" must be a safe 1-63 character name`,
    `the alias "${longProfile}-0" must be at most 63 characters`,
  ]);
  // no rule for the provider: the length alone
  expect(sut.topologyErrors(one, none({ profile: longProfile }))).toEqual([
    `the fallback name "${longProfile}-0" must be at most 63 characters`,
    `the alias "${longProfile}-0" must be at most 63 characters`,
  ]);
  expect(sut.topologyErrors(one, vultr({ profile: "a".repeat(61) }))).toEqual([]);
});

test("network errors hold the created key and the fallback subnet", () => {
  expect(sut.networkErrors(homog, vultr())).toEqual([]);
  expect(sut.networkErrors(homog, digitalocean())).toEqual([]);
  expect(sut.networkErrors(homog, none())).toEqual([]);
  expect(sut.networkErrors(homog, vultr({ "vultr-vpc-subnet": null }))).toEqual([":vultr-vpc-subnet is required"]);
  expect(sut.networkErrors(homog, vultr({ "vultr-vpc-subnet": "REPLACE_ME" }))).toEqual([":vultr-vpc-subnet is required"]);
  const canonical = ":vultr-vpc-subnet must be a canonical IPv4 network such as 10.40.0.0/24";
  expect(sut.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.40.0.1/24" }))).toEqual([canonical]);
  expect(sut.networkErrors(homog, vultr({ "vultr-vpc-subnet": "2001:db8::/64" }))).toEqual([canonical]);
  expect(sut.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.40.0.0" }))).toEqual([canonical]);
  // every fallback offset must fit the usable host range
  expect(sut.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.40.0.0/29" })))
    .toEqual([":vultr-vpc-subnet has no usable host address for 0, 1, 2"]);
  // hosts 1-14 hold offsets 10, 11, 12
  expect(sut.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.40.0.0/28" }))).toEqual([]);
  // offset 15 is the broadcast address
  expect(sut.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.40.0.0/28", "node-count": 6 })))
    .toEqual([":vultr-vpc-subnet has no usable host address for 5"]);
  expect(sut.networkErrors(roles, vultr({ "vultr-vpc-subnet": "10.40.0.0/27" }))).toEqual([]);
  const high = spec({ ...base, roles: [{ role: null, count: 3, fallbackOffset: 254 }] });
  expect(sut.networkErrors(high, vultr())).toEqual([":vultr-vpc-subnet has no usable host address for 1, 2"]);
  // a /20 holds the crossing
  expect(sut.networkErrors(high, vultr({ "vultr-vpc-subnet": "10.40.0.0/20" }))).toEqual([]);
  // an invalid count is reported by topologyErrors, not here
  expect(sut.networkErrors(homog, vultr({ "node-count": "3" }))).toEqual([]);
  // fallback-subnet under its own name
  expect(sut.networkErrors(spec({ ...homog, fallbackSubnet: "10.110.0.1/20" }), digitalocean()))
    .toEqual([":fallback-subnet must be a canonical IPv4 network such as 10.40.0.0/24"]);
});

test("fallbacks cut both families from the offset with 32 bit arithmetic", () => {
  expect(sut.fallbackNodes(homog, vultr())).toEqual([
    { role: null, index: 0, name: "prod-0", ip: "192.0.2.10", user: "root", sudoer: "root", vpc_ip: "10.40.0.10" },
    { role: null, index: 1, name: "prod-1", ip: "192.0.2.11", user: "root", sudoer: "root", vpc_ip: "10.40.0.11" },
    { role: null, index: 2, name: "prod-2", ip: "192.0.2.12", user: "root", sudoer: "root", vpc_ip: "10.40.0.12" },
  ]);
  expect(sut.fallbackNodes(roles, vultr()).map((n) => n.name))
    .toEqual(["prod-neon", "prod-redis", "prod-clickhouse-0", "prod-clickhouse-1", "prod-clickhouse-2", "prod-app"]);
  expect(sut.fallbackNodes(roles, vultr()).map((n) => n.ip))
    .toEqual(["192.0.2.10", "192.0.2.11", "192.0.2.20", "192.0.2.21", "192.0.2.22", "192.0.2.12"]);
  // discovered: the spec's fallback subnet
  expect(sut.fallbackNodes(roles, digitalocean()).map((n) => n.vpc_ip))
    .toEqual(["10.110.0.10", "10.110.0.11", "10.110.0.20", "10.110.0.21", "10.110.0.22", "10.110.0.12"]);
  // compute's name supplies the base
  expect(sut.fallbackNodes(roles, vultr({ "vultr-name": "box" }))[3]!.name).toBe("box-clickhouse-1");
  // a role of count 1 drops the index
  expect(sut.fallbackNodeName(roles, vultr({ "clickhouse-count": 1 }), { role: "clickhouse", index: 0 })).toBe("prod-clickhouse");
  // a /20 crosses the octet boundary
  const high = spec({ ...base, roles: [{ role: null, count: 3, fallbackOffset: 254 }] });
  expect(sut.fallbackNodes(high, vultr({ "vultr-vpc-subnet": "10.40.0.0/20" })).map((n) => n.vpc_ip))
    .toEqual(["10.40.0.254", "10.40.0.255", "10.40.1.0"]);
  expect(sut.fallbackNodes(high, vultr({ "vultr-vpc-subnet": "10.40.0.0/20" })).map((n) => n.ip))
    .toEqual(["192.0.2.254", "192.0.2.255", "192.0.3.0"]);
  // no network: no vpc_ip key at all
  expect(sut.fallbackNodes(spec({ ...base, roles: [{ role: null, count: 1 }] }), none()))
    .toEqual([{ role: null, index: 0, name: "prod-0", ip: "192.0.2.10", user: "root", sudoer: "root" }]);
  expect("vpc_ip" in sut.fallbackNodes(spec({ ...base, roles: [{ role: null, count: 1 }] }), none())[0]!).toBe(false);
  // an unparsable created subnet leaves vpc_ip absent; validation reports it
  expect("vpc_ip" in sut.fallbackNodes(homog, vultr({ "vultr-vpc-subnet": "nope" }))[0]!).toBe(false);
  // ipv4Network
  expect(sut.ipv4Network("10.40.0.0/24"))
    .toEqual({ cidr: "10.40.0.0/24", address: 170393600, prefix: 24, first: 170393601, last: 170393854 });
  expect(sut.ipv4Network("10.40.0.1/24")).toBeUndefined();
  expect(sut.ipv4Network("2001:db8::/32")).toBeUndefined();
  expect(sut.ipv4Network(undefined)).toBeUndefined();
  // no usable host
  expect(sut.ipv4Network("10.0.0.0/32")!.first).toBeGreaterThan(sut.ipv4Network("10.0.0.0/32")!.last);
});

const completeMessage = "the compute stage did not report a complete node (ip, vpc_ip, name, user, sudoer) for ";

test("node errors report the four classes in order", () => {
  expect(sut.nodeErrors(homog, vultr(), undefined)).toBeUndefined();
  expect(sut.nodeErrors(homog, vultr(), homogParams)).toEqual([]);
  // an empty or absent nodes list reports every declared id missing
  expect(sut.nodeErrors(homog, vultr(), { provider: "vultr", nodes: [] }))
    .toEqual(["the compute stage did not report nodes this package declares: 0, 1, 2"]);
  expect(sut.nodeErrors(homog, vultr(), { provider: "vultr" }))
    .toEqual(["the compute stage did not report nodes this package declares: 0, 1, 2"]);
  expect(sut.nodeErrors(homog, vultr(), { nodes: [node(null, 0), node(null, 2)] }))
    .toEqual(["the compute stage did not report nodes this package declares: 1"]);
  expect(sut.nodeErrors(homog, vultr(), { nodes: [node(null, 0), node(null, 1), node(null, 2), node(null, 3)] }))
    .toEqual(["the compute stage reported nodes this package does not declare: 3"]);
  expect(sut.nodeErrors(homog, vultr(), { nodes: [node(null, 0), node(null, 1), node(null, 2), node(null, 1)] }))
    .toEqual(["the compute stage reported 1 more than once"]);
  // duplicates are counted whether or not the id is declared, in first-reported order
  expect(sut.nodeErrors(homog, vultr(), { nodes: [node(null, 0), node(null, 1), node(null, 2), node(null, 9), node(null, 9)] }))
    .toEqual([
      "the compute stage reported nodes this package does not declare: 9",
      "the compute stage reported 9 more than once",
    ]);
  expect(sut.nodeErrors(homog, vultr(), { nodes: [node(null, 2), node(null, 2), node(null, 0), node(null, 0), node(null, 1)] }))
    .toEqual(["the compute stage reported 2, 0 more than once"]);
  expect(sut.nodeErrors(homog, vultr(), { nodes: [node(null, 0), without(node(null, 1), "name") as sut.Node, node(null, 2)] }))
    .toEqual([`${completeMessage}1; refusing to render a partial cluster`]);
  // blank, null, whitespace and non-strings count as missing
  expect(sut.nodeErrors(homog, vultr(), { nodes: [node(null, 0, { ip: "" }), node(null, 1, { name: null }), node(null, 2, { user: 7 })] }))
    .toEqual([`${completeMessage}0, 1, 2; refusing to render a partial cluster`]);
  expect(sut.nodeErrors(homog, vultr(), { nodes: [node(null, 0, { sudoer: "  " }), node(null, 1), node(null, 2)] }))
    .toEqual([`${completeMessage}0; refusing to render a partial cluster`]);
  // vpc_ip is required unless the network mode is none
  const withoutVpc = homogParams.nodes!.map((n) => without(n, "vpc_ip") as sut.Node);
  expect(sut.nodeErrors(homog, vultr(), { nodes: withoutVpc }))
    .toEqual([`${completeMessage}0, 1, 2; refusing to render a partial cluster`]);
  expect(sut.nodeErrors(homog, digitalocean(), { nodes: withoutVpc }))
    .toEqual([`${completeMessage}0, 1, 2; refusing to render a partial cluster`]);
  expect(sut.nodeErrors(homog, none(), { nodes: withoutVpc })).toEqual([]);
  // a legacy index: null is an undeclared id
  expect(sut.nodeErrors(spec({ ...base, roles: [{ role: null, count: 1 }] }), vultr(), { nodes: [node(null, 0, { index: null })] }))
    .toEqual([
      "the compute stage did not report nodes this package declares: 0",
      "the compute stage reported nodes this package does not declare: null",
    ]);
  // role-based ids render as role-index in declared order
  expect(sut.nodeErrors(roles, vultr(),
    { nodes: [node("app", 0), node("clickhouse", 1), node("clickhouse", 0), node("redis", 0), node("web", 0)] }))
    .toEqual([
      "the compute stage did not report nodes this package declares: neon-0, clickhouse-2",
      "the compute stage reported nodes this package does not declare: web-0",
    ]);
  // all four classes at once, in order
  expect(sut.nodeErrors(homog, vultr(), { nodes: [node(null, 0), node(null, 1, { ip: null }), node(null, 0), node(null, 9)] }))
    .toEqual([
      "the compute stage did not report nodes this package declares: 2",
      "the compute stage reported nodes this package does not declare: 9",
      "the compute stage reported 0 more than once",
      `${completeMessage}1; refusing to render a partial cluster`,
    ]);
});

test("nodes come from state in declared order with extras preserved", () => {
  expect(sut.nodes(homog, vultr(), undefined)).toEqual(sut.fallbackNodes(homog, vultr()));
  const params: sut.ClusterParams = {
    provider: "digitalocean", reserved_ip: "203.0.113.7",
    nodes: [node("app", 0, { droplet_id: "3" }), node("clickhouse", 2, { droplet_id: "2" }),
            node("clickhouse", 0), node("clickhouse", 1), node("redis", 0), node("neon", 0)],
  };
  const out = sut.nodes(roles, digitalocean(), params);
  expect(out.map(sut.nodeIdStr)).toEqual(["neon-0", "redis-0", "clickhouse-0", "clickhouse-1", "clickhouse-2", "app-0"]);
  // verbatim, extras kept
  expect(out[out.length - 1]).toEqual(node("app", 0, { droplet_id: "3" }));
  expect(out[4]!.droplet_id).toBe("2");
  // a partial state throws with the messages
  expect(() => sut.nodes(homog, vultr(), { nodes: [node(null, 0), node(null, 1)] }))
    .toThrow(/did not report nodes this package declares: 2/);
});

test("output params and the re-exports are computes", async () => {
  expect(sut.outputParams({ "tofu/outputs": { params: { provider: "vultr", ssh_key_id: "77",
    nodes: [{ ip: "1.2.3.4", vpc_ip: "10.0.0.4", index: 0, role: null }] } } }) as unknown)
    .toEqual({ provider: "vultr", ssh_key_id: "77", nodes: [{ ip: "1.2.3.4", vpc_ip: "10.0.0.4", index: 0, role: null }] });
  expect(sut.outputParams({})).toBeUndefined();
  expect(await sut.readState({}, async () => { throw new StepError("tofu output failed: boom"); }))
    .toEqual({ error: "tofu output failed: boom" });
  expect(await sut.readState({}, async () => undefined)).toEqual({ params: undefined });
  expect(sut.providerStateErrors(homog, vultr(), { provider: "digitalocean" }))
    .toEqual(["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]);
  let called = 0;
  const thunk = () => { called += 1; return ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]; };
  expect(sut.providerValidator(homog, vultr(), { provider: "digitalocean" }, thunk))
    .toEqual(["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]);
  // the mismatch pre-empts the secrets
  expect(called).toBe(0);
  expect(sut.providerValidator(homog, vultr(), homogParams, thunk))
    .toEqual(["required credential is not set: COLORS_PAR_VULTR_API_KEY"]);
  expect(called).toBe(1);
});

test("resolved cluster refuses nil and partial outputs", () => {
  const fallback: Opts = { "once/cluster": { nodes: sut.fallbackNodes(homog, vultr()) } };
  // nil outputs
  const refused = sut.resolvedCluster(homog, vultr(), { a: 1 }, fallback, undefined);
  expect(refused["red/exit"]).toBe(1);
  expect(refused["red/err"]).toBe("compute produced no params output; refusing to converge against the documentation addresses");
  expect(refused.a).toBe(1);
  // partial outputs join the messages with a newline
  const partial = sut.resolvedCluster(homog, vultr(), {}, fallback, { nodes: [node(null, 0, { ip: "" }), node(null, 9)] });
  expect(partial["red/exit"]).toBe(1);
  expect(partial["red/err"]).toBe(
    "the compute stage did not report nodes this package declares: 1, 2\n" +
    "the compute stage reported nodes this package does not declare: 9\n" +
    `${completeMessage}0; refusing to render a partial cluster`,
  );
  // complete outputs replace the fallback under once/cluster
  const outputs: sut.ClusterParams = { ...homogParams, reserved_ip: "203.0.113.7" };
  const out = sut.resolvedCluster(homog, vultr(), { a: 1 }, fallback, outputs);
  expect(out).toEqual({ a: 1, "once/cluster": outputs });
  expect("red/exit" in out).toBe(false);
});

test("adopt state fails closed refuses a partial cluster and adopts params verbatim", () => {
  const optOut = vultr({ "vultr-ssh-keys": "key-uuid" });
  // error: compute's two-line message
  const refused = sut.adoptState(homog, optOut, "delete", { error: "HTTP 403 from backend" });
  expect(refused["red/exit"]).toBe(1);
  expect(refused["red/err"]).toBe(
    "could not read the infrastructure state for the delete cleanup: HTTP 403 from backend\n" +
    "fix the backend credentials and retry; a delete that cannot see its state has nothing to address",
  );
  expect(String(sut.adoptState(homog, optOut, "describe", { error: "x" })["red/err"]))
    .toStartWith("could not read the infrastructure state for describe: x");
  // partial params exit 1 with the node errors
  const partial = sut.adoptState(homog, optOut, "delete", { params: { nodes: [node(null, 0)] } });
  expect(partial["red/exit"]).toBe(1);
  expect(partial["red/err"]).toBe("the compute stage did not report nodes this package declares: 1, 2");
  expect("once/cluster" in partial).toBe(false);
  // complete params land verbatim under once/cluster; nothing is flattened into opts
  const params: sut.ClusterParams = { ...homogParams, reserved_ip: "203.0.113.7" };
  const adopted = sut.adoptState(homog, { ...optOut, ip: "9.9.9.9" }, "delete", { params });
  expect(adopted["red/exit"]).toBe(0);
  expect(adopted["once/cluster"]).toEqual(params);
  // no top-level ip is adopted; the cluster is the whole map
  expect(adopted.ip).toBe("9.9.9.9");
  expect("nodes" in adopted).toBe(false);
  // opt-out opts pass through withMachineKey untouched
  expect("ssh-keygen" in adopted).toBe(false);
  // a readable state holding nothing leaves once/cluster absent
  const empty = sut.adoptState(homog, optOut, "delete", { params: undefined });
  expect(empty["red/exit"]).toBe(0);
  expect("once/cluster" in empty).toBe(false);
  // keygen mode fills the machine key through once ssh
  const home = process.env.HOME;
  const dir = mkdtempSync(join(tmpdir(), "once-compute-cluster-test"));
  process.env.HOME = dir;
  try {
    const keygen = sut.adoptState(homog, vultr(), "delete", { params: homogParams });
    expect(keygen["red/exit"]).toBe(0);
    expect(keygen["ssh-keygen"]).toBe(true);
    expect(String(keygen["vultr-ssh-keys"]).startsWith(dir)).toBe(true);
  } finally {
    process.env.HOME = home;
  }
});

test("aliases and ssh config hosts follow the shape and the entry", () => {
  expect(sut.aliases(homog, vultr())).toEqual(["prod", "prod-0", "prod-1", "prod-2"]);
  expect(sut.aliases(homog, vultr({ "node-count": 1 }))).toEqual(["prod", "prod-0"]);
  expect(sut.aliases(roles, vultr()))
    .toEqual(["prod", "prod-neon", "prod-redis", "prod-clickhouse-0", "prod-clickhouse-1", "prod-clickhouse-2", "prod-app"]);
  expect(sut.aliases(roles, vultr({ "clickhouse-count": 1 })))
    .toEqual(["prod", "prod-neon", "prod-redis", "prod-clickhouse", "prod-app"]);
  // aliases follow the profile, never the compute name
  expect(sut.aliases(homog, vultr({ "vultr-name": "box" }))).toEqual(["prod", "prod-0", "prod-1", "prod-2"]);
  // hosts from the fallbacks: the bare profile points at the entry node
  expect(sut.sshConfigHosts(homog, vultr(), sut.nodes(homog, vultr(), undefined))).toEqual([
    { name: "prod", ip: "192.0.2.10" }, { name: "prod-0", ip: "192.0.2.10" },
    { name: "prod-1", ip: "192.0.2.11" }, { name: "prod-2", ip: "192.0.2.12" },
  ]);
  expect(sut.sshConfigHosts(roles, vultr(), sut.nodes(roles, vultr(), undefined))).toEqual([
    { name: "prod", ip: "192.0.2.12" }, { name: "prod-neon", ip: "192.0.2.10" },
    { name: "prod-redis", ip: "192.0.2.11" }, { name: "prod-clickhouse-0", ip: "192.0.2.20" },
    { name: "prod-clickhouse-1", ip: "192.0.2.21" }, { name: "prod-clickhouse-2", ip: "192.0.2.22" },
    { name: "prod-app", ip: "192.0.2.12" },
  ]);
  // hosts from state
  expect(sut.sshConfigHosts(homog, vultr(), sut.nodes(homog, vultr(), homogParams))).toEqual([
    { name: "prod", ip: "203.0.113.10" }, { name: "prod-0", ip: "203.0.113.10" },
    { name: "prod-1", ip: "203.0.113.11" }, { name: "prod-2", ip: "203.0.113.12" },
  ]);
});

test("state errors throw on the spec then compose compute network and topology", () => {
  expect(() => sut.stateErrors(spec(base), vultr())).toThrow(/^:roles must be a non-empty vector$/);
  expect(sut.stateErrors(homog, vultr())).toEqual([]);
  expect(sut.stateErrors(roles, digitalocean())).toEqual([]);
  // nothing selected: compute's selection error alone
  expect(sut.stateErrors(homog, { "provider-compute": "hetzner" }))
    .toEqual([":provider-compute must be one of digitalocean, none, vultr"]);
  // order: compute's, then network, then topology
  expect(sut.stateErrors(homog, vultr({ "vultr-ssh-sources": ["nope"], "vultr-vpc-subnet": "10.40.0.1/24", "node-count": 0 })))
    .toEqual([
      ":vultr-ssh-sources entry \"nope\" is not an IPv4 or IPv6 CIDR",
      ":vultr-vpc-subnet must be a canonical IPv4 network such as 10.40.0.0/24",
      ":node-count must be a positive integer",
    ]);
  // a discovered entry keeps compute's DigitalOcean VPC refusals
  expect(sut.stateErrors(homog, digitalocean({ "digitalocean-vpc-uuid": "vpc-123", "digitalocean-vpc-cidr": "10.50.0.0/24" })))
    .toEqual([
      ":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime",
      ":digitalocean-vpc-cidr must be absent; this package must not create a VPC",
    ]);
  // a created DigitalOcean entry drops them and checks its own key
  const created = spec(without({
    ...homog,
    registry: { ...registry, digitalocean: { ...registry.digitalocean!, network: { mode: "created", key: "digitalocean-vpc-cidr" } } },
  }, "fallbackSubnet"));
  expect(sut.stateErrors(created, digitalocean({ "digitalocean-vpc-cidr": "10.50.0.0/24" }))).toEqual([]);
  expect(sut.stateErrors(created, digitalocean({ "digitalocean-vpc-cidr": "10.50.0.1/24" })))
    .toEqual([":digitalocean-vpc-cidr must be a canonical IPv4 network such as 10.40.0.0/24"]);
  // both refusals are dropped for a created entry; the key is then required
  expect(sut.stateErrors(created, digitalocean({ "digitalocean-vpc-uuid": "vpc-123" })))
    .toEqual([":digitalocean-vpc-cidr is required"]);
});
