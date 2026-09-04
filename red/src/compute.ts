// The operations of the Compute Provider Standard (workspace
// `standards/compute-provider.md`), over a registry the calling package owns.
//
// A package describes itself with one spec value and passes it to every
// function that needs it:
//
//   const spec: ComputeSpec = {
//     registry: computeProviders,          // provider -> { required, secrets, tofuEnv }
//     default: "vultr",                    // what a legacy state without params.provider is
//     sources: { nonEmpty: ["ssh-sources"],   // suffixes; each must list a CIDR
//                mayBeEmpty: ["http-sources"] }, // suffixes; may be []
//     nameRules: defaultNameRules,         // optional; this value by default
//   };
//
// Nothing here is stateful: no factory, no closure, no global a package could
// mutate, so every stub in every package test keeps working. The registry
// data, the default provider, the templates, the fixtures and the lifecycle
// wiring stay the package's; what lives here is the logic that was copied into
// six packages in three colours and had already drifted. Template lookup
// deliberately stays package-local: red packages hold template content in
// static `with { type: "text" }` imports a root string cannot reach.
//
// The error strings are contract. They are printed by `scripts/compute-*` and
// diffed across colours by `scripts/parity.sh`, because none of this reaches a
// build artifact and a message that differs per colour is a bug no rendered
// file can show. Green's keys are keywords, so every key-bearing message here
// carries the same leading colon.

import type { PreflightContext } from "red/lifecycle";
import { StepError, type Opts } from "red/workflow";
import { withMachineKey } from "./ssh.ts";
import { placeholder } from "./validate.ts";

export interface ProviderEntry {
  required: string[];
  secrets: string[];
  tofuEnv: Record<string, string>;
}

export type Registry = Record<string, ProviderEntry>;

export interface NameRule {
  re: RegExp;
  message: string;
}

export type NameRules = Record<string, NameRule>;

export interface ComputeSpec {
  registry: Registry;
  default: string;
  // The non-empty rule is a named field, never an array position, so a
  // reorder cannot weaken SSH validation.
  sources: { nonEmpty: string[]; mayBeEmpty: string[] };
  nameRules?: NameRules;
}

export type Params = Record<string, unknown>;

// One read of the compute state: `params` may be undefined (nothing
// recorded), or `error` is set (nothing readable).
export interface StateRead { params?: Params; error?: string }

export type StateReader = (opts: Opts) => Promise<Params | undefined>;

// What each provider accepts as a machine name, checked before the apply
// rather than discovered mid-apply. DigitalOcean droplet names are
// hostname-like; Vultr labels are free-form console text, held to a safe
// subset. An immutable value: a package that needs different rules passes
// its own under `nameRules` in the spec.
export const defaultNameRules: NameRules = Object.freeze({
  digitalocean: Object.freeze({
    re: /^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$/,
    message: "must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters",
  }),
  vultr: Object.freeze({
    re: /^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$/,
    message: "must be a safe 1-63 character name",
  }),
});

function missing(value: unknown): boolean {
  return value === null || value === undefined ||
    (typeof value === "string" && value.trim() === "");
}

// ----------------------------------------------------------- selection

// The selected registry entry, or undefined when `provider-compute` names
// none.
export function provider(spec: ComputeSpec, opts: Opts): ProviderEntry | undefined {
  return spec.registry[String(opts["provider-compute"])];
}

// Desired state names compute keys after the provider, so the shared steps
// reach them through the selected provider rather than a fixed prefix:
// `<provider>-<suffix>`.
export function computeKey(opts: Opts, suffix: string): string {
  return `${opts["provider-compute"]}-${suffix}`;
}

// What this deployment calls its machine (Compute Name Standard §2): the
// selected provider's `<provider>-name` when present and not a placeholder,
// else the profile; trimmed. The one function that answers it, so every label
// derives from the same value.
export function computeName(opts: Opts): string {
  const override = opts[computeKey(opts, "name")];
  return String((placeholder(override) ? opts.profile : override) ?? "").trim();
}

// The §2 refusal: a `provider-compute` outside the registry, naming the
// advertised providers sorted.
export function selectionErrors(spec: ComputeSpec, opts: Opts): string[] {
  if (provider(spec, opts)) return [];
  return [`:provider-compute must be one of ${Object.keys(spec.registry).sort().join(", ")}`];
}

// The selected entry's non-secret keys; `[]` when nothing is selected. The
// package concatenates its own required list and reports the missing ones.
export function requiredKeys(spec: ComputeSpec, opts: Opts): string[] {
  return [...(provider(spec, opts)?.required ?? [])];
}

// The selected entry's credentials; `[]` when nothing is selected.
export function secrets(spec: ComputeSpec, opts: Opts): string[] {
  return [...(provider(spec, opts)?.secrets ?? [])];
}

// The selected entry's OpenTofu environment mapping; `{}` when nothing is
// selected.
export function tofuEnv(spec: ComputeSpec, opts: Opts): Record<string, string> {
  return provider(spec, opts)?.tofuEnv ?? {};
}

// ------------------------------------------------------------- sources

// A source list as desired state or an overlay string carries it: a YAML
// list, or one string of comma- or space-separated entries.
export function cidrs(opts: Opts, key: string): string[] {
  const value = opts[key];
  const parts = Array.isArray(value) ? value : String(value ?? "").split(/[,\s]+/);
  return parts.map((part) => String(part).trim()).filter((part) => part.length > 0);
}

// Syntactic CIDR checks, the same in every colour and deliberately not a
// resolver: an address library that accepts a hostname would let a firewall
// source depend on DNS at apply time.
const ipv4Re = /^(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;
const hexGroupRe = /^[0-9A-Fa-f]{1,4}$/;

// An IPv4-embedded address (`::ffff:192.0.2.1`, `64:ff9b::192.0.2.33`)
// carries a dotted quad in last position only. It stands for two 16-bit
// groups, so it is checked as IPv4 and folded into two zero groups before the
// group arithmetic; undefined when the tail is dotted but not an IPv4 address.
// A dotted quad anywhere else falls through to the hex-group check and fails
// there.
function foldIpv4Tail(s: string): string | undefined {
  const i = s.lastIndexOf(":");
  const tail = i >= 0 ? s.slice(i + 1) : s;
  if (!tail.includes(".")) return s;
  if (i >= 0 && ipv4Re.test(tail)) return `${s.slice(0, i + 1)}0:0`;
  return undefined;
}

function ipv6Address(raw: string): boolean {
  const s = foldIpv4Tail(raw);
  if (s === undefined) return false;
  const groups = (part: string) => (part.trim() === "" ? [] : part.split(":"));
  if (s.includes("::")) {
    const halves = s.split("::");
    if (halves.length !== 2) return false;
    const gs = halves.flatMap(groups);
    return gs.length <= 7 && gs.every((g) => hexGroupRe.test(g));
  }
  const gs = groups(s);
  return gs.length === 8 && gs.every((g) => hexGroupRe.test(g));
}

// Whether `s` is a syntactically valid IPv4 or IPv6 CIDR: an address, a
// slash, and a prefix length the address family allows.
export function cidr(s: unknown): boolean {
  const [address, prefix, ...more] = String(s).split("/");
  if (more.length > 0 || prefix === undefined || !/^\d{1,3}$/.test(prefix)) return false;
  const n = Number(prefix);
  if (ipv4Re.test(address ?? "")) return n >= 0 && n <= 32;
  if (ipv6Address(address ?? "")) return n >= 0 && n <= 128;
  return false;
}

// The §5 network contract over the spec's `sources`: every `nonEmpty` suffix
// must list at least one CIDR — a machine nobody can reach is not a
// deployment — and every entry of every listed suffix must be one. A
// `mayBeEmpty` list may be `[]` and means no public access on that port set.
// Keys absent from opts are skipped: presence is `requiredKeys`' job.
// Refusing beats defaulting: a silent default-open is worse than a validation
// error.
export function sourceErrors(spec: ComputeSpec, opts: Opts): string[] {
  const { nonEmpty, mayBeEmpty } = spec.sources;
  const present = (key: string) => !missing(opts[key]);
  const nonEmptyKeys = nonEmpty.map((suffix) => computeKey(opts, suffix));
  const allKeys = [...nonEmpty, ...mayBeEmpty].map((suffix) => computeKey(opts, suffix));
  const errors: string[] = [];
  for (const key of nonEmptyKeys) {
    if (present(key) && cidrs(opts, key).length === 0) {
      errors.push(`:${key} must list at least one CIDR`);
    }
  }
  for (const key of allKeys) {
    if (!present(key)) continue;
    for (const entry of cidrs(opts, key)) {
      if (!cidr(entry)) errors.push(`:${key} entry "${entry}" is not an IPv4 or IPv6 CIDR`);
    }
  }
  return errors;
}

// ------------------------------------------------------------ provider

// Checks that hold only for the selected provider; keys of another provider
// are ignored, never refused. The *resolved* machine name is validated
// against the provider's rules (Compute Name Standard §2): an override is
// checked as itself, and a profile that falls through as the name is checked
// too, because a profile Vultr accepts as a label can be a droplet name
// DigitalOcean refuses mid-apply. The error names the key the value came
// from. A blank resolved value is skipped, so a missing profile reports `is
// required` alone.
export function providerErrors(spec: ComputeSpec, opts: Opts): string[] {
  const selected = String(opts["provider-compute"]);
  const nameKey = computeKey(opts, "name");
  const rule = (spec.nameRules ?? defaultNameRules)[selected];
  const resolved = computeName(opts);
  const source = placeholder(opts[nameKey])
    ? `:profile (the ${selected} machine name)`
    : `:${nameKey}`;
  const errors: string[] = [];
  if (rule && resolved.trim() !== "" && (resolved.length > 63 || !rule.re.test(resolved))) {
    errors.push(`${source} ${rule.message}`);
  }
  switch (selected) {
    case "vultr": {
      const osId = opts["vultr-os-id"];
      if (!(missing(osId) || (typeof osId === "number" && Number.isInteger(osId)))) {
        errors.push(":vultr-os-id must be Vultr's numeric operating-system id");
      }
      break;
    }
    case "digitalocean":
      // No VPC is created: the region's default is discovered at plan time,
      // and a pinned UUID or a CIDR would make the package start owning one.
      if ("digitalocean-vpc-uuid" in opts) {
        errors.push(":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime");
      }
      if ("digitalocean-vpc-cidr" in opts) {
        errors.push(":digitalocean-vpc-cidr must be absent; this package must not create a VPC");
      }
      break;
    default:
      break;
  }
  return errors;
}

// Selection, then — only when a provider is selected — the source and the
// provider checks, in that order. Presence of the required keys is reported
// by the package over `requiredKeys`.
export function stateErrors(spec: ComputeSpec, opts: Opts): string[] {
  const errors = selectionErrors(spec, opts);
  if (provider(spec, opts)) errors.push(...sourceErrors(spec, opts), ...providerErrors(spec, opts));
  return errors;
}

// The §4 switch and legacy rules. Provider switching is a rebuild, never an
// apply: every provider shares one state key, so a changed provider-compute
// on a profile whose state already holds compute would plan a cross-provider
// replacement — and a delete would render and destroy the *selected*
// provider's template against the wrong lifecycle. `params` is the compute
// stage's recorded output, or undefined when the state holds none; its
// `provider` is the registry name the template that produced it belongs to.
// A recorded output without one predates the package recording it, which
// makes it the spec's `default` provider's.
export function providerStateErrors(spec: ComputeSpec, opts: Opts, params: Params | undefined | null): string[] {
  if (params === undefined || params === null) return [];
  const selected = String(opts["provider-compute"]);
  const recorded = String(params.provider ?? "");
  if (recorded.length > 0 && recorded !== selected) {
    return [`state holds a ${recorded} machine; set provider-compute back to ${recorded} and delete first`];
  }
  if (recorded.length === 0 && selected !== spec.default) {
    return ["state holds a machine with no recorded provider, created before this " +
      `package recorded one, which makes it a ${spec.default} machine; ` +
      `set provider-compute back to ${spec.default} and delete first`];
  }
  return [];
}

// --------------------------------------------------------------- params

// What `build` and `--dry-run` render in place of a compute output: the
// documentation address, shaped like the selected provider's real `params` so
// every later stage sees the same keys either way.
export function fallbackParams(opts: Opts): Params {
  return { provider: opts["provider-compute"], ip: "192.0.2.10", user: "root", sudoer: "root",
    name: computeName(opts) };
}

// The compute stage's `params` output, untouched: the SSH Keypair Standard
// reads `ssh_key_id` with the underscore from this map, and a renamed key
// reads as a key the deployment does not own.
export function outputParams(result: Opts): Params | undefined {
  const params = (result["tofu/outputs"] as Record<string, unknown> | undefined)?.params;
  return params && typeof params === "object" ? params as Params : undefined;
}

// Refuse to hand 192.0.2.10 to Ansible. That is the documentation address the
// credential-free build and dry-run paths render with; on a real converge a
// missing compute output must fail loudly rather than quietly point the whole
// playbook at TEST-NET.
export function resolvedCompute(result: Opts, fallback: Params, outputs: Params | undefined): Opts {
  if (outputs?.ip) return { ...result, ...fallback, ...outputs };
  return { ...result, "red/exit": 1,
    "red/err": "compute produced no ip output; refusing to converge against the documentation address" };
}

// ---------------------------------------------------------------- state

const noMessage = "state read failed without a message";

// The SDK's step error, by class: `red/tofu` throws the one `StepError` that
// `red/workflow` exports, and a package pins one `red`, so there is one copy.
function stepError(error: unknown): error is Error {
  return error instanceof StepError;
}

// One read of the compute state per run, shaped so a caller can tell nothing
// recorded from nothing readable: `params` may be undefined, or `error` is
// set. `reader` is the package's `stateOutput` — it keeps that function local
// so its tests keep injecting one — and it throws when the backend is
// unreadable.
//
// Only the SDK's `StepError` (exported by `red/workflow`) is caught: `red/tofu`
// imports it and throws it from the output read, which is the shape this
// function depends on, as its green and blue twins depend on
// `green.tofu/outputs` throwing an ex-info carrying `:dir` and `blue.tofu`
// throwing `StepError`. A message-less step error reads as the fixed string
// `state read failed without a message`. Any other exception propagates: a
// programmer defect in the reader must not read as "no state" and skip the
// switch guard.
export async function readState(opts: Opts, reader: StateReader): Promise<StateRead> {
  try {
    return { params: await reader(opts) };
  } catch (error) {
    if (stepError(error)) return { error: error.message || noMessage };
    throw error;
  }
}

// A real create or delete: the two events that touch a provider.
export function lifecycleEvent({ event, real }: PreflightContext): boolean {
  return Boolean(real && (event === "create" || event === "delete"));
}

// Standard §4 before the credentials. The recorded provider is compared with
// the selected one first, so a mistaken provider edit reports the actionable
// error — put it back and delete — rather than a missing token for the
// provider that was just selected; validators aggregate, which is why a
// mismatch pre-empts the secrets check rather than sitting beside it.
// `secretErrorsFn` is the package's thunk, carrying its event and its
// application secrets, so ONCE never learns about them. On a create an
// unreadable backend counts as no state (a fresh clone has none) and the
// credentials are checked as usual; on a delete `adoptState` refuses it after
// validation.
export function providerValidator(
  spec: ComputeSpec,
  opts: Opts,
  params: Params | undefined | null,
  secretErrorsFn: () => string[],
): string[] {
  const mismatch = providerStateErrors(spec, opts, params);
  return mismatch.length > 0 ? mismatch : [...secretErrorsFn()];
}

// Events that run against the existing machine take its address from state
// rather than from a fresh apply. A readable state without compute params
// leaves `ip` unset — a delete's cleanup step then skips itself — while an
// unreadable backend fails loudly: swallowing it is how a live teardown once
// ended up converging against 192.0.2.10 (§4). Delete keeps the standard's
// wording; a package's rehearse or describe reads its own event name.
//
// No address override: the recorded params win over anything already in
// opts, and nothing here reads an `ip` from desired state or the overlay. A
// package that wants one (posthog's `COLORS_PAR_IP`) wraps this function; the
// others must not gain a way to point a delete's cleanup at an arbitrary
// host. Synchronous in every colour, so `afterValidate` keeps returning opts.
export function adoptState(opts: Opts, event: string, state: StateRead): Opts {
  if (state.error !== undefined) {
    const what = event === "delete" ? "the delete cleanup" : event;
    return { ...opts, "red/exit": 1,
      "red/err": `could not read the infrastructure state for ${what}: ${state.error}\n` +
        `fix the backend credentials and retry; a ${event} that cannot see its state has nothing to address` };
  }
  return { ...withMachineKey(opts, true), ...(state.params ?? {}), "red/exit": 0 };
}
