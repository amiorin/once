// Drive the multi-node contract — roles and counts, the fallback addresses,
// the node-id refusals, the created and discovered network rules, the
// cluster read and adoption — through red's `compute-cluster` module with a
// three-provider stub spec, printing one normalized
// `case <name> exit=<n> errors=<messages joined by " | ">` line per scenario
// (value-bearing scenarios append ` value=<fields>`). Green and blue print
// the same shape, so parity.sh can diff them: none of this logic reaches a
// build artifact, and the messages are contract for every package that
// delegates to ONCE. Exit is the real `red/exit` where a scenario returns
// opts, 2 (the CLI's validation exit) where it returns messages, and 2 with
// the exception message where a developer-facing check throws.
import { join } from "node:path";
import type { Opts } from "red/workflow";
import * as cluster from "../red/src/compute-cluster.ts";

// The SDK's typed failure, resolved from red/ the way red/src/compute.ts
// resolves it — this script's own directory has no node_modules.
const { StepError } = await import(Bun.resolveSync("red/workflow", join(import.meta.dir, "..", "red")));

const registry: cluster.ClusterRegistry = {
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
const spec = (s: object): cluster.ClusterSpec => s as cluster.ClusterSpec;
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
// three nodes from offset 254: the private addresses cross an octet inside a
// /20 and the public ones run off the end of 192.0.2.0/24
const high = spec({ ...base, roles: [{ role: null, count: 3, fallbackOffset: 254 }] });
const overlap = spec({ ...base, roles: [{ role: "a", count: 2, fallbackOffset: 10 }, { role: "b", count: 2, fallbackOffset: 11 }] });
const one = spec({ ...base, roles: [{ role: null, count: 1 }] });
const own = spec({ ...homog, nameRules: { vultr: { re: /^prod$/, message: "must be prod" } } });
const doCreated = spec(without({
  ...homog,
  registry: { ...registry, digitalocean: { ...registry.digitalocean!, network: { mode: "created", key: "digitalocean-vpc-cidr" } } },
}, "fallbackSubnet"));

const vultr = (kvs: Record<string, unknown> = {}): Opts =>
  ({ profile: "prod", "provider-compute": "vultr", "vultr-ssh-sources": ["10.0.0.0/8"], "vultr-vpc-subnet": "10.40.0.0/24", ...kvs });
const digitalocean = (kvs: Record<string, unknown> = {}): Opts =>
  ({ profile: "prod", "provider-compute": "digitalocean", "digitalocean-ssh-sources": ["10.0.0.0/8"], ...kvs });
const none = (kvs: Record<string, unknown> = {}): Opts => ({ profile: "prod", "provider-compute": "none", ...kvs });

const node = (role: string | null, index: number, kvs: Record<string, unknown> = {}): cluster.Node =>
  ({ role, index, ip: `203.0.113.${10 + index}`, vpc_ip: `10.40.0.${10 + index}`,
     name: `n-${index}`, user: "root", sudoer: "root", ...kvs }) as cluster.Node;
const homogParams: cluster.ClusterParams = { provider: "vultr", ssh_key_id: "77", nodes: [node(null, 0), node(null, 1), node(null, 2)] };

function line(caseName: string, exit: number, errors: string[], value?: string): void {
  console.log(`case ${caseName} exit=${exit} errors=${errors.map((e) => e.replaceAll("\n", "\\n")).join(" | ")}${value === undefined ? "" : ` value=${value}`}`);
}
const errs = (caseName: string, errors: string[]) => line(caseName, errors.length === 0 ? 0 : 2, errors);
const out = (caseName: string, opts: Opts, value?: string) =>
  line(caseName, Number(opts["red/exit"] ?? 0), opts["red/err"] === undefined ? [] : [String(opts["red/err"])], value);
// A developer-facing check throws; print its message as the one error.
function thrown(caseName: string, f: () => string[] | cluster.Node[], value?: (r: unknown) => string): void {
  try {
    const r = f();
    line(caseName, r.length === 0 ? 0 : 2, r as string[], value ? value(r) : undefined);
  } catch (error) {
    line(caseName, 2, [error instanceof Error ? error.message : String(error)]);
  }
}
const b = (x: unknown) => String(Boolean(x));

const idStr = (id: { role?: unknown; index?: unknown }) => cluster.nodeIdStr(id);
const nodeStr = (n: cluster.Node) =>
  `${idStr(n)}=${n.name}|${n.ip}|${"vpc_ip" in n ? n.vpc_ip : "-"}|${n.user}|${n.sudoer}`;
const nodesStr = (nodes: cluster.Node[]) => nodes.map(nodeStr).join(",");
const hostsStr = (hosts: cluster.SshConfigHost[]) => hosts.map((h) => `${h.name}=${h.ip}`).join(",");

// --- spec-errors
thrown("spec-homog-ok", () => cluster.specErrors(homog));
thrown("spec-roles-ok", () => cluster.specErrors(roles));
thrown("spec-roles-empty", () => cluster.specErrors(spec({ ...base, roles: [] })));
thrown("spec-roles-absent", () => cluster.specErrors(spec(base)));
thrown("spec-nil-role-not-alone",
  () => cluster.specErrors(spec({ ...base, roles: [{ role: null, count: 1 }, { role: "app", count: 1 }] })));
thrown("spec-role-bad-name", () => cluster.specErrors(spec({ ...base, roles: [{ role: "Foo", count: 1 }] })));
thrown("spec-role-duplicate",
  () => cluster.specErrors(spec({ ...base, roles: [{ role: "app", count: 1 }, { role: "app", count: 2 }] })));
thrown("spec-role-alias-collision",
  () => cluster.specErrors(spec({ ...base, roles: [{ role: "foo", count: 2 }, { role: "foo-0", count: 1 }] })));
thrown("spec-count-not-positive", () => cluster.specErrors(spec({ ...base, roles: [{ role: "app", count: 0 }] })));
thrown("spec-count-absent-nil-role", () => cluster.specErrors(spec({ ...base, roles: [{ role: null, countKey: "n" }] })));
thrown("spec-offset-not-integer",
  () => cluster.specErrors(spec({ ...base, roles: [{ role: "app", count: 1, fallbackOffset: "12" }] })));
thrown("spec-entry-incomplete", () => cluster.specErrors(spec({ ...homog, entry: { index: 0 } })));
thrown("spec-entry-unresolved", () => cluster.specErrors(spec({ ...roles, entry: { role: "web", index: 0 } })));
thrown("spec-entry-bad-index", () => cluster.specErrors(spec({ ...roles, entry: { role: "app", index: -1 } })));
thrown("spec-entry-index-beyond-static-count", () => cluster.specErrors(spec({ ...roles, entry: { role: "app", index: 9 } })));
// the static count (3) admits index 2; the count-key override (2) does not:
// specErrors passes and the refusal is topologyErrors'
thrown("spec-entry-index-beyond-count-is-topology", () => {
  const s = spec({ ...homog, entry: { role: null, index: 2 } });
  cluster.specErrors(s);
  return cluster.topologyErrors(s, vultr({ "node-count": 2 }));
});
thrown("spec-fallback-subnet-non-canonical", () => cluster.specErrors(spec({ ...homog, fallbackSubnet: "10.110.0.1/20" })));
thrown("spec-fallback-subnet-not-permitted",
  () => cluster.specErrors(spec({ ...homog, registry: without(registry, "digitalocean") })));

// --- ids and counts
line("node-ids-homog", 0, [], cluster.nodeIds(homog, vultr()).map(idStr).join(","));
line("node-ids-roles", 0, [], cluster.nodeIds(roles, vultr()).map(idStr).join(","));
line("node-count-present-valid", 0, [], String(cluster.nodeCount(homog, vultr({ "node-count": 5 }), null)));
line("node-count-absent-default", 0, [], String(cluster.nodeCount(homog, vultr(), null)));
line("node-count-present-string-as-is", 0, [], String(cluster.nodeCount(homog, vultr({ "node-count": "3" }), null)));
line("node-count-fixed-role", 0, [], String(cluster.nodeCount(roles, vultr({ "clickhouse-count": 5 }), "app")));
line("node-id-str", 0, [],
  [{ role: null, index: 0 }, { role: "app", index: 2 }, { role: null, index: null }, { role: "app", index: null }]
    .map(idStr).join(","));
line("entry-id", 0, [], `${idStr(cluster.entryId(homog))};${idStr(cluster.entryId(roles))}`);

// --- topology-errors
errs("topology-homog-ok", cluster.topologyErrors(homog, vultr()));
errs("topology-roles-ok", cluster.topologyErrors(roles, digitalocean()));
errs("topology-count-zero", cluster.topologyErrors(homog, vultr({ "node-count": 0 })));
errs("topology-count-string", cluster.topologyErrors(homog, vultr({ "node-count": "3" })));
errs("topology-count-negative-pre-empts",
  cluster.topologyErrors(spec({ ...roles, entry: { role: "app", index: 9 } }), vultr({ "clickhouse-count": -1 })));
errs("topology-entry-outside-homog",
  cluster.topologyErrors(spec({ ...homog, entry: { role: null, index: 3 } }), vultr()));
errs("topology-entry-outside-roles",
  cluster.topologyErrors(spec({ ...roles, entry: { role: "clickhouse", index: 2 } }), vultr({ "clickhouse-count": 2 })));
errs("topology-fallback-subnet-required", cluster.topologyErrors(spec(without(homog, "fallbackSubnet")), digitalocean()));
errs("topology-fallback-subnet-not-required-created",
  cluster.topologyErrors(spec(without(homog, "fallbackSubnet")), vultr()));
errs("topology-offsets-overlap", cluster.topologyErrors(overlap, vultr()));
errs("topology-offsets-overlap-no-network", cluster.topologyErrors(overlap, none()));
errs("topology-public-outside-none", cluster.topologyErrors(high, none()));
errs("topology-public-outside-created-slash-20",
  cluster.topologyErrors(high, vultr({ "vultr-vpc-subnet": "10.40.0.0/20" })));
errs("topology-public-and-private-outside-discovered",
  cluster.topologyErrors(spec({ ...high, fallbackSubnet: "10.110.0.0/24" }), digitalocean()));
errs("topology-name-rule-rejects", cluster.topologyErrors(own, vultr()));
{
  const longProfile = "a".repeat(62);
  errs("topology-name-too-long-vultr", cluster.topologyErrors(one, vultr({ profile: longProfile })));
  errs("topology-name-too-long-no-rule", cluster.topologyErrors(one, none({ profile: longProfile })));
  errs("topology-name-63-ok", cluster.topologyErrors(one, vultr({ profile: "a".repeat(61) })));
}

// --- network-errors
errs("network-created-ok", cluster.networkErrors(homog, vultr()));
errs("network-discovered-ok", cluster.networkErrors(homog, digitalocean()));
errs("network-none-ok", cluster.networkErrors(homog, none()));
errs("network-created-key-missing", cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": null })));
errs("network-created-key-placeholder", cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": "REPLACE_ME" })));
errs("network-created-non-canonical", cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.40.0.1/24" })));
errs("network-created-ipv6", cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": "2001:db8::/64" })));
errs("network-created-no-prefix", cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.40.0.0" })));
errs("network-created-offset-outside", cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.40.0.0/29" })));
errs("network-created-slash-28-holds-three", cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.40.0.0/28" })));
errs("network-created-slash-28-broadcast",
  cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.40.0.0/28", "node-count": 6 })));
errs("network-created-slash-24-crossing-refused", cluster.networkErrors(high, vultr()));
errs("network-created-slash-20-crossing-holds", cluster.networkErrors(high, vultr({ "vultr-vpc-subnet": "10.40.0.0/20" })));
errs("network-created-invalid-count-skipped", cluster.networkErrors(homog, vultr({ "node-count": "3" })));
errs("network-created-slash-0", cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": "0.0.0.0/0" })));
errs("network-created-slash-31", cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.0.0.0/31" })));
errs("network-created-slash-32", cluster.networkErrors(homog, vultr({ "vultr-vpc-subnet": "10.0.0.1/32" })));
errs("network-fallback-subnet-non-canonical",
  cluster.networkErrors(spec({ ...homog, fallbackSubnet: "10.110.0.1/20" }), digitalocean()));

// --- fallbacks
line("fallback-homog-vultr-24", 0, [], nodesStr(cluster.fallbackNodes(homog, vultr())));
line("fallback-homog-vultr-20", 0, [], nodesStr(cluster.fallbackNodes(homog, vultr({ "vultr-vpc-subnet": "10.40.0.0/20" }))));
line("fallback-homog-do-20", 0, [], nodesStr(cluster.fallbackNodes(homog, digitalocean())));
line("fallback-homog-do-24", 0, [],
  nodesStr(cluster.fallbackNodes(spec({ ...homog, fallbackSubnet: "10.110.0.0/24" }), digitalocean())));
line("fallback-roles-vultr-24", 0, [], nodesStr(cluster.fallbackNodes(roles, vultr())));
line("fallback-roles-vultr-20", 0, [], nodesStr(cluster.fallbackNodes(roles, vultr({ "vultr-vpc-subnet": "10.40.0.0/20" }))));
line("fallback-roles-do-20", 0, [], nodesStr(cluster.fallbackNodes(roles, digitalocean())));
line("fallback-roles-do-24", 0, [],
  nodesStr(cluster.fallbackNodes(spec({ ...roles, fallbackSubnet: "10.110.0.0/24" }), digitalocean())));
line("fallback-roles-none", 0, [], nodesStr(cluster.fallbackNodes(roles, none())));
line("fallback-roles-count-one-drops-index", 0, [], nodesStr(cluster.fallbackNodes(roles, vultr({ "clickhouse-count": 1 }))));
line("fallback-compute-name-base", 0, [], nodesStr(cluster.fallbackNodes(homog, vultr({ "vultr-name": " box " }))));
line("fallback-slash-20-crosses-octet", 0, [], nodesStr(cluster.fallbackNodes(high, vultr({ "vultr-vpc-subnet": "10.40.0.0/20" }))));
line("fallback-unparsable-subnet-omits-vpc-ip", 0, [], nodesStr(cluster.fallbackNodes(homog, vultr({ "vultr-vpc-subnet": "nope" }))));
line("fallback-node-name-and-offset", 0, [], [
  cluster.fallbackNodeName(roles, vultr(), { role: "clickhouse", index: 1 }),
  cluster.fallbackNodeName(roles, vultr({ "clickhouse-count": 1 }), { role: "clickhouse", index: 0 }),
  cluster.fallbackOffset(roles, vultr(), "app"),
  cluster.fallbackOffset(roles, vultr(), "redis"),
  cluster.fallbackOffset(roles, vultr({ "clickhouse-count": 5 }), "app"),
].join(";"));

// --- node-errors
function ne(caseName: string, s: cluster.ClusterSpec, opts: Opts, params: cluster.ClusterParams | undefined): void {
  const e = cluster.nodeErrors(s, opts, params);
  line(caseName, e === undefined || e.length === 0 ? 0 : 2, e ?? [], e === undefined ? "nil" : undefined);
}
ne("node-errors-params-nil", homog, vultr(), undefined);
ne("node-errors-complete", homog, vultr(), homogParams);
ne("node-errors-empty-nodes", homog, vultr(), { provider: "vultr", nodes: [] });
ne("node-errors-nodes-absent", homog, vultr(), { provider: "vultr" });
ne("node-errors-missing-id", homog, vultr(), { nodes: [node(null, 0), node(null, 2)] });
ne("node-errors-extra-id", homog, vultr(), { nodes: [node(null, 0), node(null, 1), node(null, 2), node(null, 3)] });
ne("node-errors-duplicate-id", homog, vultr(), { nodes: [node(null, 0), node(null, 1), node(null, 2), node(null, 1)] });
ne("node-errors-undeclared-duplicate", homog, vultr(),
  { nodes: [node(null, 0), node(null, 1), node(null, 2), node(null, 9), node(null, 9)] });
ne("node-errors-without-name", homog, vultr(), { nodes: [node(null, 0), without(node(null, 1), "name") as cluster.Node, node(null, 2)] });
{
  const withoutVpc = homogParams.nodes!.map((n) => without(n, "vpc_ip") as cluster.Node);
  ne("node-errors-without-vpc-ip-none", homog, none(), { nodes: withoutVpc });
  ne("node-errors-without-vpc-ip-created", homog, vultr(), { nodes: withoutVpc });
  ne("node-errors-without-vpc-ip-discovered", homog, digitalocean(), { nodes: withoutVpc });
}
ne("node-errors-blank-ip", homog, vultr(), { nodes: [node(null, 0, { ip: "" }), node(null, 1), node(null, 2)] });
ne("node-errors-null-name", homog, vultr(), { nodes: [node(null, 0), node(null, 1, { name: null }), node(null, 2)] });
ne("node-errors-whitespace-and-non-string", homog, vultr(),
  { nodes: [node(null, 0, { sudoer: "  " }), node(null, 1, { user: 7 }), node(null, 2)] });
ne("node-errors-legacy-null-index", one, vultr(), { nodes: [node(null, 0, { index: null })] });
ne("node-errors-string-index-undeclared", one, vultr(), { nodes: [node(null, 0, { index: "0" })] });
ne("node-errors-roles-ok", roles, vultr(),
  { nodes: [node("app", 0), node("clickhouse", 2), node("clickhouse", 0), node("clickhouse", 1), node("redis", 0), node("neon", 0)] });
ne("node-errors-roles-missing-and-extra", roles, vultr(),
  { nodes: [node("app", 0), node("clickhouse", 1), node("clickhouse", 0), node("redis", 0), node("web", 0)] });
ne("node-errors-all-classes-in-order", homog, vultr(),
  { nodes: [node(null, 0), node(null, 1, { ip: null }), node(null, 0), node(null, 9)] });

// --- nodes
line("nodes-fallback-when-nil", 0, [], nodesStr(cluster.nodes(homog, vultr(), undefined)));
{
  const params: cluster.ClusterParams = {
    provider: "digitalocean", reserved_ip: "203.0.113.7",
    nodes: [node("app", 0, { droplet_id: "3" }), node("clickhouse", 2, { droplet_id: "2" }),
            node("clickhouse", 0), node("clickhouse", 1), node("redis", 0), node("neon", 0)],
  };
  const ns = cluster.nodes(roles, digitalocean(), params);
  line("nodes-from-state-preserve-extras", 0, [],
    `${nodesStr(ns)};droplet_ids=${ns.map((n) => ("droplet_id" in n ? n.droplet_id : "-")).join(",")}`);
}
thrown("nodes-throws-on-partial", () => cluster.nodes(homog, vultr(), { nodes: [node(null, 0), node(null, 1)] }));

// --- output-params and the re-exports
{
  const p = cluster.outputParams({ "tofu/outputs": { params: { provider: "vultr", ssh_key_id: "77", reserved_ip: "203.0.113.7",
    nodes: [{ ip: "1.2.3.4", vpc_ip: "10.0.0.4", index: 0, role: null, droplet_id: "9" }] } } })!;
  const first = p.nodes![0]!;
  line("output-params", 0, [],
    [p.provider, p.ssh_key_id, p.reserved_ip, first.ip, first.vpc_ip, first.droplet_id,
     b(cluster.outputParams({}) === undefined)].join(";"));
}
{
  const r = await cluster.readState(vultr(), async () => { throw new StepError("tofu output failed: boom"); });
  line("read-state-error", 1, [String(r.error)]);
}
{
  const r = await cluster.readState(vultr(), async () => undefined);
  line("read-state-nil", 0, [], `params:${"params" in r ? "none" : "absent"}`);
}
errs("provider-state-mismatch", cluster.providerStateErrors(homog, vultr(), { provider: "digitalocean" }));
errs("provider-state-match", cluster.providerStateErrors(homog, vultr(), homogParams));
{
  let called = 0;
  const thunk = () => { called += 1; return ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]; };
  const v = (caseName: string, params: cluster.ClusterParams | undefined) => {
    const e = cluster.providerValidator(homog, vultr(), params, thunk);
    line(caseName, e.length === 0 ? 0 : 2, e, `thunk-calls:${called}`);
  };
  v("validator-mismatch-before-secrets", { provider: "digitalocean" });
  v("validator-match", homogParams);
  v("validator-no-state", undefined);
}

// --- resolved-cluster
{
  const fallback: Opts = { "once/cluster": { nodes: cluster.fallbackNodes(homog, vultr()) } };
  out("resolved-nil-outputs", cluster.resolvedCluster(homog, vultr(), { a: 1 }, fallback, undefined));
  out("resolved-partial", cluster.resolvedCluster(homog, vultr(), {}, fallback, { nodes: [node(null, 0, { ip: "" }), node(null, 9)] }));
  const outputs: cluster.ClusterParams = { ...homogParams, reserved_ip: "203.0.113.7" };
  const o = cluster.resolvedCluster(homog, vultr(), { a: 1 }, fallback, outputs);
  out("resolved-ok", o, `a:${o.a};reserved_ip:${o["once/cluster"].reserved_ip}` +
    `;nodes:${nodesStr(o["once/cluster"].nodes)}` +
    `;fallback-replaced:${b(!Bun.deepEquals(fallback["once/cluster"], o["once/cluster"]))}`);
}

// --- adopt-state (opt-out opts: with-machine-key leaves them untouched)
{
  const optOut = vultr({ "vultr-ssh-keys": "key-uuid" });
  out("adopt-delete-error", cluster.adoptState(homog, optOut, "delete", { error: "HTTP 403 from backend" }));
  out("adopt-describe-error", cluster.adoptState(homog, optOut, "describe", { error: "HTTP 403 from backend" }));
  out("adopt-partial", cluster.adoptState(homog, optOut, "delete", { params: { nodes: [node(null, 0)] } }));
  const params: cluster.ClusterParams = { ...homogParams, reserved_ip: "203.0.113.7",
    nodes: [node(null, 0, { droplet_id: "1" }), node(null, 1, { droplet_id: "2" }), node(null, 2, { droplet_id: "3" })] };
  const o = cluster.adoptState(homog, { ...optOut, ip: "9.9.9.9" }, "delete", { params });
  out("adopt-success-extras", o,
    `cluster-equals-params:${b(Bun.deepEquals(params, o["once/cluster"]))}` +
    `;reserved_ip:${o["once/cluster"].reserved_ip}` +
    `;droplet_ids:${(o["once/cluster"].nodes as cluster.Node[]).map((n) => n.droplet_id).join(",")}` +
    `;ip:${o.ip};nodes-flattened:${b("nodes" in o)};keygen:${b("ssh-keygen" in o)}`);
  const empty = cluster.adoptState(homog, optOut, "delete", { params: undefined });
  out("adopt-no-params", empty, `cluster:${b("once/cluster" in empty)}`);
}

// --- aliases and ssh-config-hosts
line("aliases-homog", 0, [], cluster.aliases(homog, vultr()).join(","));
line("aliases-homog-count-one", 0, [], cluster.aliases(homog, vultr({ "node-count": 1 })).join(","));
line("aliases-roles", 0, [], cluster.aliases(roles, vultr()).join(","));
line("aliases-roles-count-one", 0, [], cluster.aliases(roles, vultr({ "clickhouse-count": 1 })).join(","));
line("aliases-follow-profile-not-name", 0, [], cluster.aliases(homog, vultr({ "vultr-name": "box" })).join(","));
line("ssh-config-hosts-homog", 0, [], hostsStr(cluster.sshConfigHosts(homog, vultr(), cluster.nodes(homog, vultr(), undefined))));
line("ssh-config-hosts-roles", 0, [], hostsStr(cluster.sshConfigHosts(roles, vultr(), cluster.nodes(roles, vultr(), undefined))));
line("ssh-config-hosts-from-state", 0, [],
  hostsStr(cluster.sshConfigHosts(homog, vultr(), cluster.nodes(homog, vultr(), homogParams))));

// --- state-errors
thrown("state-errors-spec-throws", () => cluster.stateErrors(spec(base), vultr()));
thrown("state-errors-homog-ok", () => cluster.stateErrors(homog, vultr()));
thrown("state-errors-roles-ok", () => cluster.stateErrors(roles, digitalocean()));
thrown("state-errors-unselected", () => cluster.stateErrors(homog, { "provider-compute": "hetzner" }));
thrown("state-errors-order",
  () => cluster.stateErrors(homog, vultr({ "vultr-ssh-sources": ["nope"], "vultr-vpc-subnet": "10.40.0.1/24", "node-count": 0 })));
thrown("state-errors-discovered-keeps-do-vpc",
  () => cluster.stateErrors(homog, digitalocean({ "digitalocean-vpc-uuid": "vpc-123", "digitalocean-vpc-cidr": "10.50.0.0/24" })));
thrown("state-errors-created-filters-do-vpc",
  () => cluster.stateErrors(doCreated, digitalocean({ "digitalocean-vpc-cidr": "10.50.0.0/24" })));
thrown("state-errors-created-checks-own-key",
  () => cluster.stateErrors(doCreated, digitalocean({ "digitalocean-vpc-cidr": "10.50.0.1/24" })));
thrown("state-errors-created-key-required",
  () => cluster.stateErrors(doCreated, digitalocean({ "digitalocean-vpc-uuid": "vpc-123" })));
