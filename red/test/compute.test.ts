import { expect, test } from "bun:test";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { StepError } from "red/workflow";
import * as sut from "../src/compute.ts";

// A two-provider stub registry shaped like clickstack's: the package-owned
// data ONCE takes as a spec value. The same stub drives green and blue.
const registry: sut.Registry = {
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

const spec: sut.ComputeSpec = {
  registry,
  default: "vultr",
  sources: { nonEmpty: ["ssh-sources"], mayBeEmpty: ["http-sources"] },
};

const vultr = (kvs: Record<string, unknown> = {}) => ({ profile: "prod", "provider-compute": "vultr", ...kvs });
const digitalocean = (kvs: Record<string, unknown> = {}) => ({ profile: "prod", "provider-compute": "digitalocean", ...kvs });

const selectionMessage = ":provider-compute must be one of digitalocean, vultr";

test("selection refuses an unadvertised provider with the sorted list", () => {
  expect(sut.provider(spec, { "provider-compute": "hetzner" })).toBeUndefined();
  expect(sut.selectionErrors(spec, { "provider-compute": "hetzner" })).toEqual([selectionMessage]);
  expect(sut.selectionErrors(spec, {})).toEqual([selectionMessage]);
  expect(sut.selectionErrors(spec, vultr())).toEqual([]);
  // An unselected provider reports the selection alone; its keys are not checked.
  expect(sut.stateErrors(spec, { "provider-compute": "hetzner", "hetzner-ssh-sources": ["nope"], "hetzner-name": "BAD NAME" }))
    .toEqual([selectionMessage]);
});

test("selection ignores keys of the unselected provider", () => {
  expect(sut.stateErrors(spec, vultr({
    "vultr-ssh-sources": ["10.0.0.0/8"], "vultr-os-id": 2284,
    "digitalocean-ssh-sources": ["nope"], "digitalocean-vpc-uuid": "vpc-123", "digitalocean-name": "BAD NAME",
  }))).toEqual([]);
});

test("required keys, secrets and tofu env follow the selected entry", () => {
  expect(sut.requiredKeys(spec, vultr())).toEqual(registry.vultr!.required);
  expect(sut.requiredKeys(spec, digitalocean())).toEqual(registry.digitalocean!.required);
  expect(sut.requiredKeys(spec, { "provider-compute": "hetzner" })).toEqual([]);
  expect(sut.secrets(spec, vultr())).toEqual(["vultr-api-key"]);
  expect(sut.secrets(spec, digitalocean())).toEqual(["do-token"]);
  expect(sut.secrets(spec, {})).toEqual([]);
  expect(sut.tofuEnv(spec, vultr())).toEqual({ "vultr-api-key": "VULTR_API_KEY" });
  expect(sut.tofuEnv(spec, digitalocean())).toEqual({ "do-token": "DIGITALOCEAN_TOKEN" });
  expect(sut.tofuEnv(spec, {})).toEqual({});
});

test("compute key and name follow the selected provider", () => {
  expect(sut.computeKey(vultr(), "ssh-sources")).toBe("vultr-ssh-sources");
  expect(sut.computeKey(digitalocean(), "name")).toBe("digitalocean-name");
  expect(sut.computeName(vultr())).toBe("prod");
  expect(sut.computeName(vultr({ "vultr-name": "box" }))).toBe("box");
  expect(sut.computeName(vultr({ "vultr-name": " box " }))).toBe("box");
  expect(sut.computeName(vultr({ "vultr-name": "REPLACE_ME" }))).toBe("prod");
  expect(sut.computeName(vultr({ "vultr-name": "" }))).toBe("prod");
  expect(sut.computeName(vultr({ "digitalocean-name": "other" }))).toBe("prod");
});

test("cidr grammar", () => {
  // v4
  expect(sut.cidr("10.0.0.0/8")).toBe(true);
  expect(sut.cidr("203.0.113.7/32")).toBe(true);
  expect(sut.cidr("0.0.0.0/0")).toBe(true);
  // v6
  expect(sut.cidr("2001:db8::/32")).toBe(true);
  expect(sut.cidr("::1/128")).toBe(true);
  expect(sut.cidr("1:2:3:4:5:6:7:8/128")).toBe(true);
  // ::
  expect(sut.cidr("::/0")).toBe(true);
  // v4-tail
  expect(sut.cidr("::ffff:203.0.113.7/128")).toBe(true);
  expect(sut.cidr("64:ff9b::192.0.2.33/96")).toBe(true);
  expect(sut.cidr("::ffff:300.0.0.1/128")).toBe(false);
  expect(sut.cidr("192.0.2.1::/96")).toBe(false);
  // bad prefix
  expect(sut.cidr("10.0.0.0/33")).toBe(false);
  expect(sut.cidr("2001:db8::/129")).toBe(false);
  expect(sut.cidr("10.0.0.0/")).toBe(false);
  expect(sut.cidr("10.0.0.0")).toBe(false);
  expect(sut.cidr("10.0.0.0/8/8")).toBe(false);
  // bad octet and bad groups
  expect(sut.cidr("256.0.0.1/8")).toBe(false);
  expect(sut.cidr("2001:db8:::1/64")).toBe(false);
  expect(sut.cidr("1:2:3:4:5:6:7:8:9/64")).toBe(false);
  expect(sut.cidr("2001:db8::g/64")).toBe(false);
  // hostname
  expect(sut.cidr("example.com/32")).toBe(false);
  // blank
  expect(sut.cidr("")).toBe(false);
  expect(sut.cidr(undefined)).toBe(false);
});

test("source errors follow the spec", () => {
  // an empty nonEmpty key is refused
  expect(sut.sourceErrors(spec, vultr({ "vultr-ssh-sources": [] })))
    .toEqual([":vultr-ssh-sources must list at least one CIDR"]);
  // an empty mayBeEmpty key is allowed
  expect(sut.sourceErrors(spec, vultr({ "vultr-ssh-sources": ["10.0.0.0/8"], "vultr-http-sources": [] }))).toEqual([]);
  // malformed entries are counted per key
  expect(sut.sourceErrors(spec, vultr({ "vultr-ssh-sources": ["10.0.0.0/8", "nope"], "vultr-http-sources": ["::1/129", "1.2.3.4/32"] })))
    .toEqual([
      ':vultr-ssh-sources entry "nope" is not an IPv4 or IPv6 CIDR',
      ':vultr-http-sources entry "::1/129" is not an IPv4 or IPv6 CIDR',
    ]);
  // an overlay string parses
  expect(sut.cidrs(vultr({ "vultr-ssh-sources": "10.0.0.0/8, 192.0.2.0/24" }), "vultr-ssh-sources")).toEqual(["10.0.0.0/8", "192.0.2.0/24"]);
  expect(sut.sourceErrors(spec, vultr({ "vultr-ssh-sources": "10.0.0.0/8, 192.0.2.0/24" }))).toEqual([]);
  expect(sut.sourceErrors(spec, vultr({ "vultr-ssh-sources": "10.0.0.0/8 bad" })))
    .toEqual([':vultr-ssh-sources entry "bad" is not an IPv4 or IPv6 CIDR']);
  // an absent key is skipped — presence is requiredKeys' job
  expect(sut.sourceErrors(spec, vultr())).toEqual([]);
  expect(sut.sourceErrors(spec, vultr({ "vultr-ssh-sources": "  " }))).toEqual([]);
  // the spec decides which suffixes exist
  const three: sut.ComputeSpec = { ...spec, sources: { nonEmpty: ["ssh-sources"], mayBeEmpty: ["http-sources", "stun-sources"] } };
  expect(sut.sourceErrors(three, vultr({ "vultr-stun-sources": ["x"] })))
    .toEqual([':vultr-stun-sources entry "x" is not an IPv4 or IPv6 CIDR']);
  expect(sut.sourceErrors(spec, vultr({ "vultr-stun-sources": ["x"] }))).toEqual([]);
});

const doNameMessage = ":digitalocean-name must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters";

test("provider errors check the resolved name and the provider rules", () => {
  // default name rules on the raw override, blamed on the override key
  expect(sut.providerErrors(spec, vultr({ "vultr-name": "bad name!" }))).toEqual([":vultr-name must be a safe 1-63 character name"]);
  expect(sut.providerErrors(spec, digitalocean({ "digitalocean-name": "Upper" }))).toEqual([doNameMessage]);
  // default name rules on the resolved profile, blamed on the profile
  expect(sut.providerErrors(spec, vultr({ profile: "bad name!" })))
    .toEqual([":profile (the vultr machine name) must be a safe 1-63 character name"]);
  expect(sut.providerErrors(spec, digitalocean({ profile: "under_score" })))
    .toEqual([":profile (the digitalocean machine name) must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"]);
  expect(sut.providerErrors(spec, digitalocean({ profile: "Bad", "digitalocean-name": "REPLACE_ME" })))
    .toEqual([":profile (the digitalocean machine name) must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"]);
  // length and the valid shapes
  expect(sut.providerErrors(spec, digitalocean({ "digitalocean-name": "a".repeat(64) }))).toEqual([doNameMessage]);
  expect(sut.providerErrors(spec, digitalocean({ "digitalocean-name": "a".repeat(63) }))).toEqual([]);
  expect(sut.providerErrors(spec, digitalocean({ "digitalocean-name": "prod-1.example" }))).toEqual([]);
  expect(sut.providerErrors(spec, vultr({ "vultr-name": "Prod_1" }))).toEqual([]);
  expect(sut.providerErrors(spec, vultr({ "vultr-name": " Prod_1 " }))).toEqual([]);
  expect(sut.providerErrors(spec, digitalocean({ profile: "" }))).toEqual([]);
  // a spec-supplied rule set wins
  const own: sut.ComputeSpec = { ...spec, nameRules: { vultr: { re: /^x$/, message: "must be x" } } };
  expect(sut.providerErrors(own, vultr({ "vultr-name": "prod" }))).toEqual([":vultr-name must be x"]);
  expect(sut.providerErrors(own, digitalocean({ "digitalocean-name": "Upper" }))).toEqual([]);
  // Vultr os-id
  expect(sut.providerErrors(spec, vultr({ "vultr-os-id": "2284" }))).toEqual([":vultr-os-id must be Vultr's numeric operating-system id"]);
  expect(sut.providerErrors(spec, vultr({ "vultr-os-id": 2284 }))).toEqual([]);
  expect(sut.providerErrors(spec, vultr())).toEqual([]);
  // DigitalOcean vpc bans
  expect(sut.providerErrors(spec, digitalocean({ "digitalocean-vpc-uuid": "vpc-123" })))
    .toEqual([":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"]);
  expect(sut.providerErrors(spec, digitalocean({ "digitalocean-vpc-cidr": "10.0.0.0/16" })))
    .toEqual([":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]);
  expect(sut.providerErrors(spec, digitalocean({ "digitalocean-vpc-uuid": "vpc-123", "digitalocean-vpc-cidr": "10.0.0.0/16" })))
    .toEqual([
      ":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime",
      ":digitalocean-vpc-cidr must be absent; this package must not create a VPC",
    ]);
  // nothing when the other provider is selected
  expect(sut.providerErrors(spec, vultr({ "digitalocean-vpc-uuid": "vpc-123", "digitalocean-name": "BAD NAME" }))).toEqual([]);
  expect(sut.providerErrors(spec, digitalocean({ "vultr-os-id": "2284", "vultr-name": "bad name!" }))).toEqual([]);
});

test("state errors order: selection, then source, then provider", () => {
  expect(sut.stateErrors(spec, digitalocean({ "digitalocean-ssh-sources": ["nope"], "digitalocean-name": "Upper" })))
    .toEqual([':digitalocean-ssh-sources entry "nope" is not an IPv4 or IPv6 CIDR', doNameMessage]);
});

const legacyMessage = "state holds a machine with no recorded provider, created before this package recorded one, which makes it a vultr machine; set provider-compute back to vultr and delete first";

test("provider state errors implement the switch and legacy rules", () => {
  // nil params
  expect(sut.providerStateErrors(spec, vultr(), undefined)).toEqual([]);
  expect(sut.providerStateErrors(spec, vultr(), null)).toEqual([]);
  // match
  expect(sut.providerStateErrors(spec, vultr(), { provider: "vultr", ip: "1.2.3.4" })).toEqual([]);
  expect(sut.providerStateErrors(spec, digitalocean(), { provider: "digitalocean" })).toEqual([]);
  // mismatch both ways
  expect(sut.providerStateErrors(spec, vultr(), { provider: "digitalocean" }))
    .toEqual(["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]);
  expect(sut.providerStateErrors(spec, digitalocean(), { provider: "vultr" }))
    .toEqual(["state holds a vultr machine; set provider-compute back to vultr and delete first"]);
  // legacy on the default
  expect(sut.providerStateErrors(spec, vultr(), { ip: "1.2.3.4" })).toEqual([]);
  // legacy on a non-default
  expect(sut.providerStateErrors(spec, digitalocean(), { ip: "1.2.3.4" })).toEqual([legacyMessage]);
  expect(sut.providerStateErrors(spec, digitalocean(), { provider: "" })).toEqual([legacyMessage]);
});

test("resolved compute refuses a missing ip", () => {
  const fallback = sut.fallbackParams(vultr());
  // missing ip refuses
  const refused = sut.resolvedCompute({ a: 1 }, fallback, undefined);
  expect(refused["red/exit"]).toBe(1);
  expect(refused["red/err"]).toBe("compute produced no ip output; refusing to converge against the documentation address");
  expect(sut.resolvedCompute({}, fallback, { name: "prod" })["red/exit"]).toBe(1);
  // present ip merges outputs over the fallback
  expect(sut.resolvedCompute({ a: 1 }, fallback, { ip: "1.2.3.4", name: "box" }))
    .toEqual({ a: 1, provider: "vultr", ip: "1.2.3.4", user: "root", sudoer: "root", name: "box" });
  // outputParams leaves the map alone
  expect(sut.outputParams({ "tofu/outputs": { params: { ip: "1.2.3.4", ssh_key_id: "77" } } })).toEqual({ ip: "1.2.3.4", ssh_key_id: "77" });
  expect(sut.outputParams({ "tofu/outputs": {} })).toBeUndefined();
  expect(sut.outputParams({})).toBeUndefined();
});

test("read state catches only the step error", async () => {
  // the reader's step error becomes error
  expect(await sut.readState({}, async () => { throw new StepError("tofu output failed: boom"); }))
    .toEqual({ error: "tofu output failed: boom" });
  // a step error without a message reads as the fixed fallback string
  expect(await sut.readState({}, async () => { throw new StepError(""); }))
    .toEqual({ error: "state read failed without a message" });
  // a StepError from another copy of the SDK is recognised by name
  const foreign = new Error("tofu output failed: other copy");
  foreign.name = "StepError";
  expect(await sut.readState({}, async () => { throw foreign; }))
    .toEqual({ error: "tofu output failed: other copy" });
  // undefined from the reader is a readable state holding nothing
  // A launch failure (no stage directory yet) reaches red as a failed exit and so as a StepError.
  expect(await sut.readState({}, async () => { throw new StepError("tofu output failed: spawn tofu ENOENT"); }))
    .toEqual({ error: "tofu output failed: spawn tofu ENOENT" });
  expect(await sut.readState({}, async () => undefined)).toEqual({ params: undefined });
  // params pass through, and the reader sees opts
  expect(await sut.readState({ profile: "prod" }, async (o) => ({ ip: "1.2.3.4", seen: o.profile })))
    .toEqual({ params: { ip: "1.2.3.4", seen: "prod" } });
  // any other exception propagates
  await expect(sut.readState({}, async () => { throw new Error("defect"); })).rejects.toThrow("defect");
  await expect(sut.readState({}, async () => { throw new TypeError("not a step error"); })).rejects.toThrow("not a step error");
});

test("provider validator pre-empts the thunk on a mismatch", () => {
  let called = 0;
  const thunk = () => { called += 1; return ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]; };
  // mismatch pre-empts the thunk
  expect(sut.providerValidator(spec, vultr(), { provider: "digitalocean" }, thunk))
    .toEqual(["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]);
  expect(called).toBe(0);
  // match calls it
  expect(sut.providerValidator(spec, vultr(), { provider: "vultr" }, thunk))
    .toEqual(["required credential is not set: COLORS_PAR_VULTR_API_KEY"]);
  expect(called).toBe(1);
  // no state calls it
  expect(sut.providerValidator(spec, vultr(), undefined, thunk))
    .toEqual(["required credential is not set: COLORS_PAR_VULTR_API_KEY"]);
  expect(called).toBe(2);
});

test("adopt state fails closed and adopts the recorded address", () => {
  const optOut = vultr({ "vultr-ssh-keys": "key-uuid" });
  // error exits 1 with the delete wording and the reason
  const refused = sut.adoptState(optOut, "delete", { error: "HTTP 403 from backend" });
  expect(refused["red/exit"]).toBe(1);
  expect(refused["red/err"]).toBe(
    "could not read the infrastructure state for the delete cleanup: HTTP 403 from backend\n" +
    "fix the backend credentials and retry; a delete that cannot see its state has nothing to address",
  );
  // rehearse wording
  const rehearse = sut.adoptState(optOut, "rehearse", { error: "HTTP 403 from backend" });
  expect(rehearse["red/exit"]).toBe(1);
  expect(rehearse["red/err"]).toBe(
    "could not read the infrastructure state for rehearse: HTTP 403 from backend\n" +
    "fix the backend credentials and retry; a rehearse that cannot see its state has nothing to address",
  );
  // params merged; an ip already in opts does not override the recorded
  // address; ssh_key_id kept as written
  const adopted = sut.adoptState({ ...optOut, ip: "9.9.9.9" }, "delete", { params: { ip: "1.2.3.4", ssh_key_id: "77", provider: "vultr" } });
  expect(adopted["red/exit"]).toBe(0);
  expect(adopted.ip).toBe("1.2.3.4");
  expect(adopted.ssh_key_id).toBe("77");
  expect("ssh-keygen" in adopted).toBe(false);
  // a readable state holding nothing leaves ip unset
  const empty = sut.adoptState(optOut, "delete", { params: undefined });
  expect(empty["red/exit"]).toBe(0);
  expect("ip" in empty).toBe(false);
  // keygen mode fills the machine key through once ssh, never touching the
  // real ~/.ssh
  const home = process.env.HOME;
  const dir = mkdtempSync(join(tmpdir(), "once-compute-test"));
  process.env.HOME = dir;
  try {
    const keygen = sut.adoptState(vultr(), "delete", { params: { ip: "1.2.3.4" } });
    expect(keygen["red/exit"]).toBe(0);
    expect(keygen["ssh-keygen"]).toBe(true);
    expect(keygen["vultr-ssh-keys"]).toBe(join(dir, ".ssh", "prod.pub"));
    expect(String(keygen["ssh-private-key-path"]).startsWith(dir)).toBe(true);
  } finally {
    process.env.HOME = home;
  }
});

test("fallback params carry the provider and lifecycle event covers the four combinations", () => {
  expect(sut.fallbackParams(vultr())).toEqual({ provider: "vultr", ip: "192.0.2.10", user: "root", sudoer: "root", name: "prod" });
  expect(sut.fallbackParams(digitalocean({ "digitalocean-name": "box" })))
    .toEqual({ provider: "digitalocean", ip: "192.0.2.10", user: "root", sudoer: "root", name: "box" });
  expect(sut.lifecycleEvent({ event: "create", real: true })).toBe(true);
  expect(sut.lifecycleEvent({ event: "delete", real: true })).toBe(true);
  expect(sut.lifecycleEvent({ event: "create", real: false })).toBe(false);
  expect(sut.lifecycleEvent({ event: "build", real: true })).toBe(false);
});
