// The multi-node contract the Compute Provider Standard defers in its §1, as
// a sibling of the single-node `compute` module: the same registry, the same
// selection, the same sources and name rules — called, never copied — plus
// what a cluster adds: roles and counts, one id per node, the fallback
// addresses `build` renders with, and a refusal for every state that does
// not describe the whole cluster.
//
// A package describes itself with the compute spec plus three keys:
//
//   const spec: ClusterSpec = {
//     registry: computeProviders,   // entries as in compute, plus `network`
//     default: "vultr",
//     sources: { nonEmpty: ["ssh-sources"], mayBeEmpty: [] },
//     roles: [{ role: null, countKey: "node-count", count: 3 }],
//     entry: { role: null, index: 0 },       // optional; default the first node
//     fallbackSubnet: "10.110.0.0/20",       // optional; discovered networks only
//   };
//
// `roles` is an array in play order. `role` is a string, or null for a
// homogeneous cluster (then the only entry). `countKey` names the
// desired-state integer that sets the count and `count` is the fixed count,
// or the default when the key is absent. `fallbackOffset` is the offset of
// the role's first fallback address; default 10 plus the number of nodes in
// the roles before it. A registry entry's `network` is
// `{ mode: "created", key: <cidr key> }`, `{ mode: "discovered" }` or
// `{ mode: "none" }`; absent means none.
//
// **The one representation of compute state is `params`**: ONCE reads
// exactly `provider`, `ssh_key_id` and `nodes`, and on every node the five
// fields `ip`, `vpc_ip`, `name`, `user`, `sudoer` plus its `role` and
// `index`. Node keys are spelled as `outputParams` delivers them — the
// underscore kept: `ip vpc_ip name user sudoer role index`, never hyphenated
// — and fallback nodes use the same spelling so every later stage sees one
// shape. Anything else a package emits, on a node (`droplet_id`) or at the
// top level (`reserved_ip`, `vpc_id`, `vpc_ip_range`), is preserved verbatim
// under `once/cluster`.
//
// `specErrors` is developer-facing and throws; every other `*Errors`
// function returns an array of operator-facing messages that are contract,
// printed by `scripts/cluster-*` and diffed across colours. Green's keys are
// keywords, so every key-bearing message here carries the same leading
// colon.

import type { Opts } from "red/workflow";
import * as compute from "./compute.ts";
import type { ComputeSpec, Params, ProviderEntry, StateRead, StateReader } from "./compute.ts";
import { withMachineKey } from "./ssh.ts";
import { placeholder } from "./validate.ts";

export interface NetworkSpec {
  mode: "created" | "discovered" | "none";
  key?: string;
}

export interface ClusterProviderEntry extends ProviderEntry {
  network?: NetworkSpec;
}

export type ClusterRegistry = Record<string, ClusterProviderEntry>;

export interface RoleSpec {
  role: string | null;
  countKey?: string;
  count: number;
  fallbackOffset?: number;
}

export interface NodeId {
  role: string | null;
  index: number;
}

export interface ClusterSpec extends ComputeSpec {
  registry: ClusterRegistry;
  roles: RoleSpec[];
  entry?: NodeId;
  fallbackSubnet?: string;
}

// A node as `params.nodes` records it — the five fields and the id, plus
// whatever the package's template emits.
export interface Node {
  role: string | null;
  index: number;
  name: string;
  ip: string;
  vpc_ip?: string;
  user: string;
  sudoer: string;
  [extra: string]: unknown;
}

// The compute stage's `params` output for a cluster.
export interface ClusterParams extends Params {
  nodes?: Node[];
}

export interface Ipv4Network {
  cidr: string;
  address: number;
  prefix: number;
  first: number;
  last: number;
}

export interface SshConfigHost {
  name: string;
  ip: unknown;
}

// What a role may be called: lowercase, digits, single hyphens between
// words. Alias-safe, because `<profile>-<role>` and `<profile>-<role>-<n>`
// must not collide with `<profile>-<n>` or with another role.
export const roleRe = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

// TEST-NET-1: where `build` and `--dry-run` put every public fallback
// address, offset + index from its network address.
export const publicFallbackNetwork = "192.0.2.0/24";

const canonicalMessage = " must be a canonical IPv4 network such as 10.40.0.0/24";

// The `resolvedCluster` refusal: a real converge never falls back.
export const noParamsMessage =
  "compute produced no params output; refusing to converge against the documentation addresses";

function nonBlankString(x: unknown): boolean {
  return typeof x === "string" && x.trim() !== "";
}

function posInt(x: unknown): boolean {
  return typeof x === "number" && Number.isInteger(x) && x > 0;
}

function natInt(x: unknown): boolean {
  return typeof x === "number" && Number.isInteger(x) && x >= 0;
}

function specError(msg: string): never {
  throw new Error(msg);
}

// ------------------------------------------------------------ addresses

function ipv4ToLong(s: string): number {
  return s.split(".").reduce((acc, octet) => acc * 256 + Number(octet), 0);
}

function longToIpv4(n: number): string {
  const u = n >>> 0;
  return [24, 16, 8, 0].map((shift) => (u >>> shift) & 255).join(".");
}

// `s` parsed as a canonical IPv4 network — compute's `cidr` grammar, IPv4
// only, host bits zero — as `{cidr, address, prefix, first, last}` with the
// network address and the first and last usable host as 32-bit integers, or
// undefined. A /31 or /32 parses and has no usable host.
export function ipv4Network(s: unknown): Ipv4Network | undefined {
  if (!(compute.cidr(s) && !String(s).includes(":"))) return undefined;
  const [address, prefix] = String(s).split("/");
  const n = Number(prefix);
  const a = ipv4ToLong(address!);
  const mask = n === 0 ? 0 : 2 ** 32 - 2 ** (32 - n);
  const size = 2 ** (32 - n);
  if (a !== ((a & mask) >>> 0)) return undefined;
  return { cidr: String(s), address: a, prefix: n, first: a + 1, last: a + size - 2 };
}

// -------------------------------------------------------------- network

// The selected entry's network declaration; `{ mode: "none" }` when absent
// or when nothing is selected.
export function network(spec: ClusterSpec, opts: Opts): NetworkSpec {
  return (compute.provider(spec, opts) as ClusterProviderEntry | undefined)?.network ?? { mode: "none" };
}

export function networkMode(spec: ClusterSpec, opts: Opts): NetworkSpec["mode"] {
  return network(spec, opts).mode;
}

// The CIDR the private fallback addresses are cut from: the created
// network's key value, the spec's `fallbackSubnet` for a discovered one,
// undefined for none. On a real run the discovered CIDR is the package's
// `params.vpc_ip_range`; this exists for `build` alone.
export function fallbackCidr(spec: ClusterSpec, opts: Opts): unknown {
  const { mode, key } = network(spec, opts);
  switch (mode) {
    case "created":
      return key === undefined ? undefined : opts[key];
    case "discovered":
      return spec.fallbackSubnet;
    default:
      return undefined;
  }
}

// ---------------------------------------------------------------- roles

export function roles(spec: ClusterSpec): RoleSpec[] {
  return [...(spec.roles ?? [])];
}

function sameRole(a: unknown, b: unknown): boolean {
  return (a ?? null) === (b ?? null);
}

function roleEntry(spec: ClusterSpec, role: string | null): RoleSpec | undefined {
  return roles(spec).find((entry) => sameRole(entry.role, role));
}

// How many nodes `role` (a declared role name, null for the homogeneous
// role) has: the count key's value whenever the key is present in opts —
// whatever it is, validation refuses a present non-positive-integer before
// any derivation runs — and `count` only when the key is absent or the role
// declares none.
export function nodeCount(spec: ClusterSpec, opts: Opts, role: string | null): unknown {
  const entry = roleEntry(spec, role);
  const countKey = entry?.countKey;
  if (countKey && countKey in opts) return opts[countKey];
  return entry?.count;
}

function countsValid(spec: ClusterSpec, opts: Opts): boolean {
  return roles(spec).every((entry) => posInt(nodeCount(spec, opts, entry.role)));
}

// `[{role, index}]` over `roles` in declared order, `index` 0-based per
// role. Assumes valid counts; run `topologyErrors` first.
export function nodeIds(spec: ClusterSpec, opts: Opts): NodeId[] {
  const ids: NodeId[] = [];
  for (const { role } of roles(spec)) {
    const count = nodeCount(spec, opts, role) as number;
    for (let i = 0; i < count; i++) ids.push({ role: role ?? null, index: i });
  }
  return ids;
}

// How an id renders in a message: `<index>` for the null role,
// `<role>-<index>` otherwise. A null index (a legacy state's `index: null`)
// renders as `null` in every colour.
export function nodeIdStr({ role, index }: { role?: unknown; index?: unknown }): string {
  const i = index == null ? "null" : String(index);
  return role == null ? i : `${role}-${i}`;
}

function idsStr(ids: { role?: unknown; index?: unknown }[]): string {
  return ids.map(nodeIdStr).join(", ");
}

// The exact-match identity of an id: role and index as recorded, null and
// absent alike, a string index distinct from a number.
function idKey({ role, index }: { role?: unknown; index?: unknown }): string {
  return JSON.stringify([role ?? null, index ?? null]);
}

// The node the bare `<profile>` alias points to: the spec's `entry`, else
// the first node of the first role.
export function entryId(spec: ClusterSpec): NodeId {
  const entry = spec.entry;
  if (entry) return { role: entry.role, index: entry.index };
  return { role: roles(spec)[0]?.role ?? null, index: 0 };
}

// The offset of `role`'s first fallback address inside each fallback
// network: the role's `fallbackOffset`, else 10 plus the number of nodes in
// the roles declared before it.
export function fallbackOffset(spec: ClusterSpec, opts: Opts, role: string | null): number | undefined {
  let before = 0;
  for (const entry of roles(spec)) {
    if (sameRole(entry.role, role)) return entry.fallbackOffset ?? 10 + before;
    before += nodeCount(spec, opts, entry.role) as number;
  }
  return undefined;
}

// network address + offset + index, as a 32-bit integer; undefined when
// `cidr` is not a canonical IPv4 network.
function offsetAddress(cidr: unknown, spec: ClusterSpec, opts: Opts, { role, index }: NodeId): number | undefined {
  const net = ipv4Network(cidr);
  if (!net) return undefined;
  return net.address + (fallbackOffset(spec, opts, role) as number) + index;
}

// The public fallback address of `id`: `192.0.2.0/24` + offset + index.
export function fallbackIp(spec: ClusterSpec, opts: Opts, id: NodeId): string {
  return longToIpv4(offsetAddress(publicFallbackNetwork, spec, opts, id) as number);
}

// The private fallback address of `id`: the fallback CIDR's network address
// + offset + index with 32-bit arithmetic, so a /20's nodes cross an octet
// correctly. Undefined when the network mode is none or the CIDR does not
// parse (validation reports the latter; the node then carries no `vpc_ip`
// at all).
export function fallbackVpcIp(spec: ClusterSpec, opts: Opts, id: NodeId): string | undefined {
  const address = offsetAddress(fallbackCidr(spec, opts), spec, opts, id);
  return address === undefined ? undefined : longToIpv4(address);
}

function nameSuffix(spec: ClusterSpec, opts: Opts, { role, index }: NodeId): string {
  if (role == null) return `-${index}`;
  if (nodeCount(spec, opts, role) === 1) return `-${role}`;
  return `-${role}-${index}`;
}

// `<compute-name>-<index>` (null role), `<compute-name>-<role>` (a role of
// count 1), `<compute-name>-<role>-<index>`; compute's `computeName`
// supplies the base. Governs fallbacks and new packages only: a package
// whose legacy names differ overrides `name` on its fallback nodes in its
// own wrapper.
export function fallbackNodeName(spec: ClusterSpec, opts: Opts, id: NodeId): string {
  return compute.computeName(opts) + nameSuffix(spec, opts, id);
}

// `[profile]` then, per node in declared order, `<profile>-<index>` (null
// role), `<profile>-<role>` (count 1) or `<profile>-<role>-<index>`.
export function aliases(spec: ClusterSpec, opts: Opts): string[] {
  const profile = String(opts.profile ?? "");
  return [profile, ...nodeIds(spec, opts).map((id) => profile + nameSuffix(spec, opts, id))];
}

// What `build` and `--dry-run` render in place of a compute output: one
// node per id — `role index name ip user "root" sudoer "root"`, and `vpc_ip`
// unless the network mode is none — shaped like a real `params.nodes` entry
// so every later stage sees the same keys either way.
export function fallbackNodes(spec: ClusterSpec, opts: Opts): Node[] {
  return nodeIds(spec, opts).map((id) => {
    const vpcIp = fallbackVpcIp(spec, opts, id);
    const node: Node = {
      ...id,
      name: fallbackNodeName(spec, opts, id),
      ip: fallbackIp(spec, opts, id),
      user: "root",
      sudoer: "root",
    };
    if (vpcIp !== undefined) node.vpc_ip = vpcIp;
    return node;
  });
}

// --------------------------------------------------------------- params

// The compute stage's `params` output, as compute's: untouched, the
// underscores kept.
export function outputParams(result: Opts): ClusterParams | undefined {
  return compute.outputParams(result) as ClusterParams | undefined;
}

function nodeList(params: ClusterParams): Node[] {
  return Array.isArray(params.nodes) ? params.nodes : [];
}

// Reported nodes indexed by id, the first occurrence winning.
function nodesById(params: ClusterParams): Map<string, Node> {
  const byId = new Map<string, Node>();
  for (const n of nodeList(params)) {
    const key = idKey(n);
    if (!byId.has(key)) byId.set(key, n);
  }
  return byId;
}

// Undefined when `params` is nil (a build); else, in this order: ids
// declared but not reported; ids reported but not declared; ids reported
// more than once (declared or not, in first-reported order); and ids whose
// node lacks a non-blank string for any of
// `ip`, `name`, `user`, `sudoer` — and `vpc_ip` unless the network mode is
// none. Absent, null, blank and non-string values all count as missing. Ids
// are matched exactly, so a legacy `index: null` (or a string index) is an
// undeclared id: packages translate before ONCE sees the state. A present
// `params` with an empty or absent `nodes` reports every declared id
// missing.
export function nodeErrors(spec: ClusterSpec, opts: Opts, params: ClusterParams | undefined | null): string[] | undefined {
  if (params === undefined || params === null) return undefined;
  const declared = nodeIds(spec, opts);
  const declaredKeys = new Set(declared.map(idKey));
  const reported = nodeList(params).map((n) => ({ role: n.role, index: n.index }));
  const reportedKeys = new Set(reported.map(idKey));
  const freq = new Map<string, number>();
  for (const id of reported) {
    const key = idKey(id);
    freq.set(key, (freq.get(key) ?? 0) + 1);
  }
  const byId = nodesById(params);
  const fields = networkMode(spec, opts) === "none"
    ? ["ip", "name", "user", "sudoer"]
    : ["ip", "vpc_ip", "name", "user", "sudoer"];
  const complete = (n: Node) => fields.every((field) => nonBlankString(n[field]));
  const missing = declared.filter((id) => !reportedKeys.has(idKey(id)));
  const seen = new Set<string>();
  const undeclared = reported.filter((id) => {
    const key = idKey(id);
    if (declaredKeys.has(key) || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  const duplicatedSeen = new Set<string>();
  const duplicated = reported.filter((id) => {
    const key = idKey(id);
    if ((freq.get(key) ?? 0) < 2 || duplicatedSeen.has(key)) return false;
    duplicatedSeen.add(key);
    return true;
  });
  const incomplete = declared.filter((id) => byId.has(idKey(id)) && !complete(byId.get(idKey(id))!));
  const errors: string[] = [];
  if (missing.length > 0) {
    errors.push(`the compute stage did not report nodes this package declares: ${idsStr(missing)}`);
  }
  if (undeclared.length > 0) {
    errors.push(`the compute stage reported nodes this package does not declare: ${idsStr(undeclared)}`);
  }
  if (duplicated.length > 0) {
    errors.push(`the compute stage reported ${idsStr(duplicated)} more than once`);
  }
  if (incomplete.length > 0) {
    errors.push("the compute stage did not report a complete node (ip, vpc_ip, name, user, sudoer) for " +
      `${idsStr(incomplete)}; refusing to render a partial cluster`);
  }
  return errors;
}

// The cluster's nodes in declared order. `params` nil (a build) yields the
// fallbacks; a present `params` must pass `nodeErrors` — callers check
// first, this throws otherwise — and then every node comes from state with
// every field as recorded and no fallback substitution. Keys are spelled as
// `outputParams` delivers them, `vpc_ip` with the underscore; fields ONCE
// does not name are preserved verbatim.
export function nodes(spec: ClusterSpec, opts: Opts, params: ClusterParams | undefined | null): Node[] {
  if (params === undefined || params === null) return fallbackNodes(spec, opts);
  const errors = nodeErrors(spec, opts, params)!;
  if (errors.length > 0) throw new Error(errors.join("\n"));
  const byId = nodesById(params);
  return nodeIds(spec, opts).map((id) => byId.get(idKey(id))!);
}

// ----------------------------------------------------------- validation

// The ids whose private fallback address falls outside `cidr`'s usable
// hosts, blamed on `subject` (the key or the network that owns the CIDR).
// Nothing when the CIDR does not parse or a count is invalid: both are
// reported by their own rule.
function hostRangeErrors(spec: ClusterSpec, opts: Opts, subject: string, cidr: unknown): string[] {
  const net = ipv4Network(cidr);
  if (!(net && countsValid(spec, opts))) return [];
  const outside = nodeIds(spec, opts).filter((id) => {
    const a = offsetAddress(cidr, spec, opts, id) as number;
    return !(net.first <= a && a <= net.last);
  });
  if (outside.length === 0) return [];
  return [`${subject} has no usable host address for ${idsStr(outside)}`];
}

function duplicateErrors(what: string, values: string[]): string[] {
  const freq = new Map<string, number>();
  for (const v of values) freq.set(v, (freq.get(v) ?? 0) + 1);
  return [...freq.entries()]
    .filter(([, n]) => n > 1)
    .map(([v]) => `the ${what} ${v} is generated for more than one node`);
}

// Static checks over the spec alone, run in a package's spec-content test
// and at the head of `stateErrors`. Developer-facing: throws on the first
// problem and returns `[]` otherwise. `roles` is non-empty; a null role is
// the only entry; role names match `roleRe`, are unique, and none equals
// another followed by `-<digits>`; every `count` is a positive integer and
// every `fallbackOffset` a non-negative one; `entry` names a declared role
// with a non-negative index below that role's static `count` (the count-key
// override is `topologyErrors`' to check); `fallbackSubnet`, when present,
// is a canonical
// IPv4 network and is permitted only when some advertised entry's network
// is discovered.
export function specErrors(spec: ClusterSpec): string[] {
  const rs = roles(spec);
  const names = rs.map((r) => r.role as unknown);
  if (rs.length === 0) specError(":roles must be a non-empty vector");
  if (names.some((r) => r == null) && rs.length > 1) {
    specError("the nil role must be the only entry in :roles");
  }
  for (const r of names) {
    if (r == null) continue;
    if (!(typeof r === "string" && roleRe.test(r))) {
      specError(`role "${r}" must match ^[a-z][a-z0-9]*(-[a-z0-9]+)*$`);
    }
  }
  const freq = new Map<unknown, number>();
  for (const r of names) freq.set(r, (freq.get(r) ?? 0) + 1);
  for (const [r, n] of freq) {
    if (n > 1) specError(`role "${r ?? ""}" is declared more than once`);
  }
  for (const r of names) {
    for (const other of names) {
      if (r != null && other != null && new RegExp(`^${other}-\\d+$`).test(String(r))) {
        specError(`role "${r}" reads as an alias of role "${other}"`);
      }
    }
  }
  for (const entry of rs) {
    const label = entry.role == null ? "the nil role" : `role "${entry.role}"`;
    if (!posInt(entry.count)) specError(`:count of ${label} must be a positive integer`);
    if ("fallbackOffset" in entry && !natInt(entry.fallbackOffset)) {
      specError(`:fallback-offset of ${label} must be a non-negative integer`);
    }
  }
  const entry = spec.entry as unknown;
  if (entry) {
    if (!(typeof entry === "object" && !Array.isArray(entry) && "role" in entry && "index" in entry)) {
      specError(":entry must carry :role and :index");
    }
    const { role, index } = entry as { role: unknown; index: unknown };
    if (!names.some((r) => sameRole(r, role))) specError(":entry :role must name a declared role");
    if (!natInt(index)) specError(":entry :index must be a non-negative integer");
    const label = role == null ? "the nil role" : `role "${role}"`;
    if (!((index as number) < roleEntry(spec, (role ?? null) as string | null)!.count)) {
      specError(`:entry :index must be below :count of ${label}`);
    }
  }
  if ("fallbackSubnet" in spec) {
    if (!ipv4Network(spec.fallbackSubnet)) specError(`:fallback-subnet${canonicalMessage}`);
    if (!Object.values(spec.registry).some((e) => e.network?.mode === "discovered")) {
      specError(":fallback-subnet is permitted only when an advertised provider's network is discovered");
    }
  }
  return [];
}

// Created: the key is required, must be a canonical IPv4 network (host bits
// zero, parsed as a network — not the syntactic `cidr`), and every private
// fallback address must fall inside its usable host range. Discovered:
// nothing beyond compute's refusals of a pinned VPC. None: nothing.
// `fallbackSubnet`, when present, is held to the same canonical rule under
// its own name.
export function networkErrors(spec: ClusterSpec, opts: Opts): string[] {
  const { mode, key } = network(spec, opts);
  const value = key === undefined ? undefined : opts[key];
  const errors: string[] = [];
  if (mode === "created") {
    if (placeholder(value)) errors.push(`:${key} is required`);
    else if (!ipv4Network(value)) errors.push(`:${key}${canonicalMessage}`);
    else errors.push(...hostRangeErrors(spec, opts, `:${key}`, value));
  }
  if ("fallbackSubnet" in spec && !ipv4Network(spec.fallbackSubnet)) {
    errors.push(`:fallback-subnet${canonicalMessage}`);
  }
  return errors;
}

// With desired state: each present count key a positive integer — and
// nothing else until they all are, because every derivation below needs
// them; `entry` inside the effective count; `fallbackSubnet` present when
// the selected network is discovered; every public fallback address inside
// `192.0.2.0/24` and every private one inside `fallbackSubnet` (a created
// network's range is `networkErrors`' to check); addresses, names and
// aliases unique; names and aliases at most 63 characters; and every
// generated name accepted by the selected provider's name rule — the spec's
// `nameRules` or compute's defaults.
export function topologyErrors(spec: ClusterSpec, opts: Opts): string[] {
  const countErrors = roles(spec)
    .filter(({ countKey }) => countKey && countKey in opts && !posInt(opts[countKey]))
    .map(({ countKey }) => `:${countKey} must be a positive integer`);
  if (countErrors.length > 0) return countErrors;
  const ids = nodeIds(spec, opts);
  const mode = networkMode(spec, opts);
  const cidr = fallbackCidr(spec, opts);
  const entry = entryId(spec);
  const publicIps = ids.map((id) => fallbackIp(spec, opts, id));
  const privateIps = ids.map((id) => fallbackVpcIp(spec, opts, id)).filter((ip): ip is string => ip !== undefined);
  const names = ids.map((id) => fallbackNodeName(spec, opts, id));
  const aliasNames = aliases(spec, opts);
  const rule = (spec.nameRules ?? compute.defaultNameRules)[String(opts["provider-compute"])];
  const re = rule?.re;
  const message = rule?.message;
  const quote = (s: string) => `"${s}"`;
  const errors: string[] = [];
  if (!ids.some((id) => idKey(id) === idKey(entry))) {
    errors.push(`:entry names ${nodeIdStr(entry)}, a node this topology does not declare`);
  }
  if (mode === "discovered" && !("fallbackSubnet" in spec)) {
    errors.push(":fallback-subnet is required when the selected provider's network is discovered");
  }
  errors.push(...hostRangeErrors(spec, opts, publicFallbackNetwork, publicFallbackNetwork));
  errors.push(...duplicateErrors("public fallback address", publicIps));
  if (mode === "discovered") errors.push(...hostRangeErrors(spec, opts, ":fallback-subnet", cidr));
  errors.push(...duplicateErrors("private fallback address", privateIps));
  errors.push(...duplicateErrors("fallback name", names.map(quote)));
  for (const n of names) {
    if (n.length > 63 || (re && !re.test(n))) {
      errors.push(`the fallback name "${n}" ${re ? message : "must be at most 63 characters"}`);
    }
  }
  errors.push(...duplicateErrors("alias", aliasNames.map(quote)));
  for (const a of aliasNames) {
    if (a.length > 63) errors.push(`the alias "${a}" must be at most 63 characters`);
  }
  return errors;
}

// compute's two DigitalOcean refusals of a pinned VPC. They hold for a
// discovered network and are dropped for a created one, where the package
// does own a VPC; compute itself is untouched.
const digitaloceanVpcRefusals = new Set([
  ":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime",
  ":digitalocean-vpc-cidr must be absent; this package must not create a VPC",
]);

// `specErrors` (thrown), then compute's `stateErrors` with the two
// DigitalOcean VPC refusals filtered out when the selected entry's network
// mode is created, then — only when a provider is selected, as compute does
// — `networkErrors` and `topologyErrors`.
export function stateErrors(spec: ClusterSpec, opts: Opts): string[] {
  specErrors(spec);
  const created = networkMode(spec, opts) === "created";
  const errors = compute.stateErrors(spec, opts).filter((e) => !(created && digitaloceanVpcRefusals.has(e)));
  if (compute.provider(spec, opts)) errors.push(...networkErrors(spec, opts), ...topologyErrors(spec, opts));
  return errors;
}

// ---------------------------------------------------------------- state

// compute's, re-exported: `{params}` or `{error}`.
export async function readState(opts: Opts, reader: StateReader): Promise<StateRead> {
  return compute.readState(opts, reader);
}

// compute's, re-exported: reads `params.provider` alone.
export function providerStateErrors(spec: ClusterSpec, opts: Opts, params: Params | undefined | null): string[] {
  return compute.providerStateErrors(spec, opts, params);
}

// compute's, re-exported: the provider mismatch pre-empts the secrets.
export function providerValidator(
  spec: ClusterSpec,
  opts: Opts,
  params: Params | undefined | null,
  secretErrorsFn: () => string[],
): string[] {
  return compute.providerValidator(spec, opts, params, secretErrorsFn);
}

// Refuse to hand the documentation addresses to Ansible. Nil outputs — no
// `params` from the compute stage — exit 1; outputs with any `nodeErrors`
// exit 1 with the messages; else `result`, `fallback` and `{"once/cluster":
// outputs}` merged in that order, so the whole recorded `params` — the nodes
// and every extension key — is what the cluster stages read.
export function resolvedCluster(
  spec: ClusterSpec,
  opts: Opts,
  result: Opts,
  fallback: Opts,
  outputs: ClusterParams | undefined | null,
): Opts {
  if (outputs === undefined || outputs === null) {
    return { ...result, "red/exit": 1, "red/err": noParamsMessage };
  }
  const errors = nodeErrors(spec, opts, outputs)!;
  if (errors.length > 0) return { ...result, "red/exit": 1, "red/err": errors.join("\n") };
  return { ...result, ...fallback, "once/cluster": outputs };
}

// Events that run against the existing cluster take it from state rather
// than from a fresh apply. `{error}` fails closed with compute's two-line
// message; `params` with any `nodeErrors` exits 1 with them; a readable
// state without `params` leaves `once/cluster` absent and the package
// decides what that means for the event; else `once/cluster` holds the
// recorded `params` verbatim over `ssh.withMachineKey`. Synchronous, as
// compute's is.
export function adoptState(spec: ClusterSpec, opts: Opts, event: string, state: StateRead): Opts {
  if (state.error !== undefined) return compute.adoptState(opts, event, state);
  const params = state.params as ClusterParams | undefined | null;
  if (params === undefined || params === null) return { ...withMachineKey(opts, true), "red/exit": 0 };
  const errors = nodeErrors(spec, opts, params)!;
  if (errors.length > 0) return { ...opts, "red/exit": 1, "red/err": errors.join("\n") };
  return { ...withMachineKey(opts, true), "once/cluster": params, "red/exit": 0 };
}

// The local ssh-config play's extra-vars: `{name: profile, ip: <entry ip>}`
// then one `{name: alias, ip}` per node. `nodes` is what `nodes` returns,
// in declared order, so aliases pair with them by position.
export function sshConfigHosts(spec: ClusterSpec, opts: Opts, nodes: Node[]): SshConfigHost[] {
  const [profile, ...perNode] = aliases(spec, opts);
  const entryKey = idKey(entryId(spec));
  const position = nodeIds(spec, opts).findIndex((id) => idKey(id) === entryKey);
  const entry = position >= 0 ? nodes[position] : undefined;
  const hosts: SshConfigHost[] = [{ name: profile!, ip: entry?.ip }];
  const pairs = Math.min(perNode.length, nodes.length);
  for (let i = 0; i < pairs; i++) hosts.push({ name: perNode[i]!, ip: nodes[i]!.ip });
  return hosts;
}
