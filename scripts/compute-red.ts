// Drive the Compute Provider Standard's operations — selection, the network
// contract, the name rules, the §4 switch and legacy refusals, the state
// read, adoption and the missing-ip refusal — through red's `compute` module
// with a two-provider stub spec, printing one normalized
// `case <name> exit=<n> errors=<messages joined by " | ">` line per scenario
// (value-bearing scenarios append ` value=<fields>`). Green and blue print the
// same shape, so parity.sh can diff them: none of this logic reaches a build
// artifact, and the messages are contract for every package that delegates to
// ONCE. Exit is the real `red/exit` where a scenario returns opts and 2 (the
// CLI's validation exit) where it returns messages.
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Opts } from "red/workflow";
import * as compute from "../red/src/compute.ts";

// The SDK's typed failure, resolved from red/ the way red/src/compute.ts
// resolves it — this script's own directory has no node_modules.
const { StepError } = await import(Bun.resolveSync("red/workflow", join(import.meta.dir, "..", "red")));

const registry: compute.Registry = {
  vultr: {
    required: ["vultr-region", "vultr-plan", "vultr-os-id", "vultr-ssh-sources", "vultr-http-sources"],
    secrets: ["vultr-api-key"],
    tofuEnv: { "vultr-api-key": "VULTR_API_KEY" },
  },
  digitalocean: {
    required: ["digitalocean-region", "digitalocean-size", "digitalocean-image",
               "digitalocean-ssh-sources", "digitalocean-http-sources"],
    secrets: ["do-token"],
    tofuEnv: { "do-token": "DIGITALOCEAN_TOKEN" },
  },
};

const spec: compute.ComputeSpec = {
  registry,
  default: "vultr",
  sources: { nonEmpty: ["ssh-sources"], mayBeEmpty: ["http-sources"] },
};
const three: compute.ComputeSpec = { ...spec, sources: { nonEmpty: ["ssh-sources"], mayBeEmpty: ["http-sources", "stun-sources"] } };
const own: compute.ComputeSpec = { ...spec, nameRules: { vultr: { re: /^x$/, message: "must be x" } } };

const vultr = (kvs: Record<string, unknown> = {}): Opts => ({ profile: "prod", "provider-compute": "vultr", ...kvs });
const digitalocean = (kvs: Record<string, unknown> = {}): Opts => ({ profile: "prod", "provider-compute": "digitalocean", ...kvs });

function line(caseName: string, exit: number, errors: string[], value?: string): void {
  console.log(`case ${caseName} exit=${exit} errors=${errors.map((e) => e.replaceAll("\n", "\\n")).join(" | ")}${value === undefined ? "" : ` value=${value}`}`);
}
const errs = (caseName: string, errors: string[]) => line(caseName, errors.length === 0 ? 0 : 2, errors);
const out = (caseName: string, opts: Opts, value?: string) =>
  line(caseName, Number(opts["red/exit"] ?? 0), opts["red/err"] === undefined ? [] : [String(opts["red/err"])], value);
const b = (x: unknown) => String(Boolean(x));

function tmpDir(): string {
  const dir = mkdtempSync(join(tmpdir(), "once-compute-parity"));
  process.env.HOME = dir;
  return dir;
}

// --- selection
errs("selection-unknown", compute.selectionErrors(spec, { "provider-compute": "hetzner" }));
errs("selection-unselected-skips-checks",
  compute.stateErrors(spec, { "provider-compute": "hetzner", "hetzner-ssh-sources": ["nope"], "hetzner-name": "BAD NAME" }));
errs("selection-ignores-other-provider",
  compute.stateErrors(spec, vultr({ "vultr-ssh-sources": ["10.0.0.0/8"], "vultr-os-id": 2284,
    "digitalocean-ssh-sources": ["nope"], "digitalocean-vpc-uuid": "vpc-123", "digitalocean-name": "BAD NAME" })));
line("required-keys", 0, [], [
  compute.requiredKeys(spec, vultr()).join(","),
  compute.requiredKeys(spec, digitalocean()).join(","),
  compute.requiredKeys(spec, {}).length,
].join(";"));
line("secrets-and-tofu-env", 0, [], [
  compute.secrets(spec, vultr()).join(","),
  compute.secrets(spec, digitalocean()).join(","),
  compute.secrets(spec, {}).length,
  Object.entries(compute.tofuEnv(spec, vultr())).map(([k, v]) => `${k}=${v}`).join(","),
  Object.entries(compute.tofuEnv(spec, digitalocean())).map(([k, v]) => `${k}=${v}`).join(","),
  Object.keys(compute.tofuEnv(spec, {})).length,
].join(";"));
line("compute-key-and-name", 0, [], [
  compute.computeKey(vultr(), "ssh-sources"),
  compute.computeKey(digitalocean(), "name"),
  compute.computeName(vultr()),
  compute.computeName(vultr({ "vultr-name": " box " })),
  compute.computeName(vultr({ "vultr-name": "REPLACE_ME" })),
  compute.computeName(vultr({ "vultr-name": "" })),
  compute.computeName(vultr({ "digitalocean-name": "other" })),
].join(";"));

// --- sources
errs("source-empty-non-empty", compute.sourceErrors(spec, vultr({ "vultr-ssh-sources": [] })));
errs("source-empty-may-be-empty", compute.sourceErrors(spec, vultr({ "vultr-ssh-sources": ["10.0.0.0/8"], "vultr-http-sources": [] })));
errs("source-malformed-per-key",
  compute.sourceErrors(spec, vultr({ "vultr-ssh-sources": ["10.0.0.0/8", "nope"], "vultr-http-sources": ["::1/129", "1.2.3.4/32"] })));
errs("source-overlay-string", compute.sourceErrors(spec, vultr({ "vultr-ssh-sources": "10.0.0.0/8, 192.0.2.0/24 bad" })));
errs("source-absent-skipped", compute.sourceErrors(spec, vultr()));
errs("source-blank-skipped", compute.sourceErrors(spec, vultr({ "vultr-ssh-sources": "  " })));
errs("source-v4-grammar", compute.sourceErrors(spec, vultr({ "vultr-ssh-sources":
  ["10.0.0.0/8", "0.0.0.0/0", "203.0.113.7/32", "10.0.0.0/33", "256.0.0.1/8", "example.com/32", "10.0.0.0", "10.0.0.0/", "10.0.0.0/8/8"] })));
errs("source-v6-grammar", compute.sourceErrors(spec, vultr({ "vultr-ssh-sources":
  ["2001:db8::/32", "::/0", "::1/128", "1:2:3:4:5:6:7:8/128", "2001:db8:::1/64", "1:2:3:4:5:6:7:8:9/64", "2001:db8::/129", "2001:db8::g/64"] })));
errs("source-v4-tail", compute.sourceErrors(spec, vultr({ "vultr-ssh-sources":
  ["::ffff:203.0.113.7/128", "64:ff9b::192.0.2.33/96", "::ffff:300.0.0.1/128", "192.0.2.1::/96"] })));
errs("source-stun-spec", compute.sourceErrors(three, vultr({ "vultr-ssh-sources": ["10.0.0.0/8"], "vultr-stun-sources": ["x"] })));
errs("source-stun-outside-spec", compute.sourceErrors(spec, vultr({ "vultr-stun-sources": ["x"] })));

// --- provider
errs("name-vultr-override-bad", compute.providerErrors(spec, vultr({ "vultr-name": "bad name!" })));
errs("name-vultr-profile-bad", compute.providerErrors(spec, vultr({ profile: "bad name!" })));
errs("name-do-override-bad", compute.providerErrors(spec, digitalocean({ "digitalocean-name": "Upper" })));
errs("name-do-profile-bad", compute.providerErrors(spec, digitalocean({ profile: "under_score" })));
errs("name-do-placeholder-falls-through", compute.providerErrors(spec, digitalocean({ profile: "Bad", "digitalocean-name": "REPLACE_ME" })));
errs("name-do-too-long", compute.providerErrors(spec, digitalocean({ "digitalocean-name": "a".repeat(64) })));
errs("name-ok", [
  ...compute.providerErrors(spec, digitalocean({ "digitalocean-name": "a".repeat(63) })),
  ...compute.providerErrors(spec, digitalocean({ "digitalocean-name": "prod-1.example" })),
  ...compute.providerErrors(spec, vultr({ "vultr-name": " Prod_1 " })),
  ...compute.providerErrors(spec, digitalocean({ profile: "" })),
]);
errs("name-spec-rules-win", [
  ...compute.providerErrors(own, vultr({ "vultr-name": "prod" })),
  ...compute.providerErrors(own, digitalocean({ "digitalocean-name": "Upper" })),
]);
errs("vultr-os-id-string", compute.providerErrors(spec, vultr({ "vultr-os-id": "2284" })));
errs("vultr-os-id-int", compute.providerErrors(spec, vultr({ "vultr-os-id": 2284 })));
errs("do-vpc-bans", compute.providerErrors(spec, digitalocean({ "digitalocean-vpc-uuid": "vpc-123", "digitalocean-vpc-cidr": "10.0.0.0/16" })));
errs("provider-other-selected", [
  ...compute.providerErrors(spec, vultr({ "digitalocean-vpc-uuid": "vpc-123", "digitalocean-name": "BAD NAME" })),
  ...compute.providerErrors(spec, digitalocean({ "vultr-os-id": "2284", "vultr-name": "bad name!" })),
]);
errs("state-errors-order", compute.stateErrors(spec, digitalocean({ "digitalocean-ssh-sources": ["nope"], "digitalocean-name": "Upper" })));

// --- provider-state
errs("pse-nil", compute.providerStateErrors(spec, vultr(), undefined));
errs("pse-match", compute.providerStateErrors(spec, vultr(), { provider: "vultr", ip: "1.2.3.4" }));
errs("pse-mismatch-do-on-vultr", compute.providerStateErrors(spec, vultr(), { provider: "digitalocean" }));
errs("pse-mismatch-vultr-on-do", compute.providerStateErrors(spec, digitalocean(), { provider: "vultr" }));
errs("pse-legacy-default", compute.providerStateErrors(spec, vultr(), { ip: "1.2.3.4" }));
errs("pse-legacy-non-default", compute.providerStateErrors(spec, digitalocean(), { ip: "1.2.3.4" }));
errs("pse-legacy-empty-recorded", compute.providerStateErrors(spec, digitalocean(), { provider: "" }));

// --- params
{
  const fb = compute.fallbackParams(vultr({ "vultr-name": "box" }));
  line("fallback-params", 0, [], [fb.provider, fb.ip, fb.user, fb.sudoer, fb.name].join(";"));
}
line("lifecycle-event", 0, [], [
  { event: "create", real: true }, { event: "delete", real: true },
  { event: "create", real: false }, { event: "build", real: true },
].map((ctx) => b(compute.lifecycleEvent(ctx))).join(";"));
out("resolved-missing-ip", compute.resolvedCompute({}, compute.fallbackParams(vultr()), undefined));
out("resolved-no-ip-key", compute.resolvedCompute({}, compute.fallbackParams(vultr()), { name: "prod" }));
{
  const o = compute.resolvedCompute({}, compute.fallbackParams(vultr()), { ip: "1.2.3.4", name: "box" });
  out("resolved-present-ip", o, [o.provider, o.ip, o.user, o.sudoer, o.name].join(";"));
}
{
  const p = compute.outputParams({ "tofu/outputs": { params: { ip: "1.2.3.4", ssh_key_id: "77" } } })!;
  line("output-params", 0, [], [p.ip, p.ssh_key_id, b(compute.outputParams({}) === undefined)].join(";"));
}

// --- read-state: each SDK's typed failure is constructed here, since no
// tofu runs. Red's is the StepError red/tofu throws.
async function rs(caseName: string, reader: compute.StateReader): Promise<void> {
  let r: Record<string, unknown>;
  try {
    r = await compute.readState(vultr(), reader);
  } catch (error) {
    r = { propagated: error instanceof Error ? error.message : String(error) };
  }
  const value = "propagated" in r
    ? `propagated:${r.propagated}`
    : "params" in r
      ? `params:${r.params ? `${(r.params as any).ip},${(r.params as any).seen}` : "none"}`
      : "error";
  line(caseName, r.error ? 1 : 0, r.error ? [String(r.error)] : [], value);
}
await rs("read-state-step-error", async () => { throw new StepError("tofu output failed: boom"); });
await rs("read-state-no-message", async () => { throw new StepError(""); });
await rs("read-state-empty-message", async () => { throw new StepError(""); });
await rs("read-state-nil", async () => undefined);
await rs("read-state-params", async (o) => ({ ip: "1.2.3.4", seen: o.profile }));
await rs("read-state-other-propagates", async () => { throw new Error("defect"); });
await rs("read-state-untyped-propagates", async () => { throw new TypeError("defect"); });

// --- provider-validator
{
  let called = 0;
  const thunk = () => { called += 1; return ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]; };
  const v = (caseName: string, params: compute.Params | undefined) => {
    const e = compute.providerValidator(spec, vultr(), params, thunk);
    line(caseName, e.length === 0 ? 0 : 2, e, `thunk-calls:${called}`);
  };
  v("validator-mismatch", { provider: "digitalocean" });
  v("validator-match", { provider: "vultr" });
  v("validator-no-state", undefined);
}

// --- adopt-state
{
  const optOut = vultr({ "vultr-ssh-keys": "key-uuid" });
  out("adopt-delete-error", compute.adoptState(optOut, "delete", { error: "HTTP 403 from backend" }));
  out("adopt-rehearse-error", compute.adoptState(optOut, "rehearse", { error: "HTTP 403 from backend" }));
  out("adopt-describe-error", compute.adoptState(optOut, "describe", { error: "HTTP 403 from backend" }));
  const o = compute.adoptState({ ...optOut, ip: "9.9.9.9" }, "delete", { params: { ip: "1.2.3.4", ssh_key_id: "77", provider: "vultr" } });
  out("adopt-params", o, `ip:${o.ip};ssh_key_id:${o.ssh_key_id};keygen:${b("ssh-keygen" in o)}`);
  const empty = compute.adoptState(optOut, "delete", { params: undefined });
  out("adopt-nil-params", empty, `ip:${b("ip" in empty)}`);
  const dir = tmpDir();
  const k = compute.adoptState(vultr(), "delete", { params: { ip: "1.2.3.4" } });
  out("adopt-keygen", k, `ip:${k.ip};keygen:${b(k["ssh-keygen"])};key-under-home:${b(String(k["vultr-ssh-keys"]).startsWith(dir))}`);
}
