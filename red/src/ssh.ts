// The machine-access SSH keypair a deployment owns.
//
// Implements the SSH Keypair Standard (workspace `standards/ssh-keypair.md`):
// when the selected compute provider's machine-key configuration key is absent
// from desired state, the package generates and manages an ed25519 keypair
// named after the profile, in `.ssh/` next to colors.yml. When the key is
// present, everything here steps aside and the value is used exactly as
// before the standard — presence is the only switch.
//
// Key material is like state: losing it loses access to the machine. So the
// keypair lives outside the regenerable workdir, an existing key without
// state is an error rather than something to overwrite, a provider-side key
// named after the profile but absent from our state is an error rather than
// something to import, and delete removes the local key only after the
// compute destroy succeeded.
//
// Generation shells `ssh-keygen` like `github`: three languages agreeing on
// OpenSSH private-key encoding is a parity problem, one subprocess is not.
// The private key never enters the opts map — templates receive only paths.

import { chmodSync, existsSync, mkdirSync, readFileSync, readdirSync, rmdirSync, unlinkSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { runtime } from "red/runtime";
import type { Opts } from "red/workflow";
import type { Runner } from "./github.ts";
import { placeholder } from "./validate.ts";

const runTimeoutMs = 30000;
const httpTimeoutMs = 30000;

const defaultRunner: Runner = (cmd, opts = {}) => runtime.exec(cmd, { ...opts, timeoutMs: opts.timeoutMs ?? runTimeoutMs });

// The public key a build or dry-run renders where content (not a path) is
// interpolated. Fixed, so the artifact stays deterministic and byte-identical
// across colours whether or not `.ssh/` exists.
export const placeholderPublic = "ssh-ed25519 PLACEHOLDER managed-by-colors";

// Compute provider -> the desired-state key that carries the machine key.
// Absent or placeholder value = keygen mode. `no-infra` provisions no machine,
// so it has no entry and never generates.
export const machineKeyKeys: Record<string, string> = {
  azure: "azure-ssh-authorized-keys",
  aws: "aws-ssh-authorized-keys",
  google: "google-ssh-authorized-keys",
  digitalocean: "digitalocean-ssh-keys",
  hcloud: "hcloud-ssh-keys",
  vultr: "vultr-ssh-keys",
  yandex: "compute-pubkey",
  oci: "oci-ssh-authorized-keys",
};

// Registered-key providers with a token-bearing REST API the create preflight
// can list account keys through. AWS is exempt by design: `aws_key_pair` names
// are unique per region and the instance depends on the key pair, so a
// duplicate name fails the apply before any instance exists.
export const preflightProviders = new Set(["digitalocean", "hcloud", "vultr"]);

// Whether this deployment is in keygen mode: the selected compute provider
// takes a machine key and desired state does not supply one. Once
// `withMachineKey` has filled the provider key with the generated path the
// desired-state test alone would flip to opt-out, so the `ssh-keygen` flag it
// stamps keeps the answer sticky for the rest of the run.
export function keygen(opts: Opts): boolean {
  const key = machineKeyKeys[String(opts["provider-compute"])];
  return Boolean(opts["ssh-keygen"] || (key && placeholder(opts[key])));
}

export function profile(opts: Opts): string {
  return String(opts.profile ?? "default");
}

// The directory holding colors.yml — where `.ssh/` lives. `.ssh/` sits
// outside the workdir on purpose: the workdir is regenerable output and the
// key is not.
function projectDir(opts: Opts): string {
  const stateFile = opts["red/state-file"];
  return stateFile ? dirname(String(stateFile)) : ".";
}

export function sshDir(opts: Opts): string {
  return join(projectDir(opts), ".ssh");
}

export function privateKeyPath(opts: Opts): string {
  return join(sshDir(opts), profile(opts));
}

export function publicKeyPath(opts: Opts): string {
  return `${privateKeyPath(opts)}.pub`;
}

function fail(opts: Opts, message: string): Opts {
  return { ...opts, "red/exit": 1, "red/err": message };
}

// Fill the template values keygen mode owns, for every event, and leave
// opt-out opts untouched. Path providers get the absolute public-key path
// (OpenTofu resolves relative paths against the stage directory, and the
// workdir is relocatable while `.ssh/` is not); the content provider gets the
// key content on real events and the fixed placeholder otherwise, so builds
// never read `.ssh/`.
export function withMachineKey(opts: Opts, real: boolean): Opts {
  if (!keygen(opts)) return opts;
  const key = machineKeyKeys[String(opts["provider-compute"])]!;
  const prv = resolve(privateKeyPath(opts));
  const pub = resolve(publicKeyPath(opts));
  const content = real && existsSync(pub) ? readFileSync(pub, "utf8").trim() : placeholderPublic;
  return {
    ...opts,
    "ssh-keygen": true,
    "ssh-private-key-path": prv,
    "ssh-public-key-path": pub,
    [key]: key === "compute-pubkey" ? content : pub,
  };
}

// ssh arguments selecting the deployment's key, empty in opt-out mode. Every
// ssh the package runs against the machine (host-key capture, describe)
// threads these, because in keygen mode nothing guarantees an agent holds the
// key.
export function identityArgs(opts: Opts): string[] {
  return opts["ssh-keygen"]
    ? ["-o", "IdentitiesOnly=yes", "-i", String(opts["ssh-private-key-path"])]
    : [];
}

// ------------------------------------------------------------- permissions

// 700 on `.ssh/`, 600 on the private key — on every real run, not only at
// generation, so a checkout restored with wrong permissions fails early.
function enforcePerms(opts: Opts): string | undefined {
  try {
    chmodSync(sshDir(opts), 0o700);
    if (existsSync(privateKeyPath(opts))) chmodSync(privateKeyPath(opts), 0o600);
    return undefined;
  } catch (error) {
    return `cannot enforce permissions on ${sshDir(opts)}: ${error instanceof Error ? error.message : String(error)}`;
  }
}

// ------------------------------------------------- the create-time matrix

function keygenArgs(opts: Opts, path: string): string[] {
  return ["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", `${profile(opts)} managed by Colors`, "-f", path];
}

export type StateFn = (opts: Opts) => Promise<Record<string, unknown> | undefined>;

// The standard's create matrix, generation, and permission enforcement, on a
// real create in keygen mode. `stateFn` reads the compute stage's applied
// `params` output best-effort (undefined when no state is readable): state and
// key agreeing means converge, disagreeing means a human has to act, and
// neither existing means first create. An existing key without state is never
// overwritten — it may be the only credential to a host that is still alive.
//
// Threads the state params through `once/ssh-state-params` so the provider
// preflight does not read state twice.
export async function ensureKey(opts: Opts, stateFn: StateFn, runFn: Runner = defaultRunner): Promise<Opts> {
  if (!keygen(opts)) return opts;
  const prv = privateKeyPath(opts);
  const pub = publicKeyPath(opts);
  const hasPrv = existsSync(prv);
  const hasPub = existsSync(pub);
  const state = await stateFn(opts);
  const threaded: Opts = { ...opts, "once/ssh-state-params": state };

  if (state && !hasPrv && !hasPub) {
    return fail(threaded, `compute state exists but ${prv} is missing: this checkout has lost the machine key. Restore .ssh/ from where the deployment was created, or rebuild; a regenerated key cannot reach the existing host.`);
  }
  if ((hasPrv || hasPub) && !(hasPrv && hasPub)) {
    return fail(threaded, `.ssh/ holds half a keypair for ${profile(opts)} (private ${hasPrv ? "present" : "missing"}, public ${hasPub ? "present" : "missing"}): restore the missing half, or — after verifying no host for ${profile(opts)} survives — remove both and retry.`);
  }
  if (!state && hasPrv) {
    return fail(threaded, `${prv} exists but no compute state is readable: the previous delete may be incomplete, or a first create was interrupted. Verify at the provider that no host for ${profile(opts)} survives; if it is confirmed gone (or the interrupted create never made one), remove ${prv} and ${pub} and retry.`);
  }
  if (hasPrv) {
    const error = enforcePerms(threaded);
    return error ? fail(threaded, error) : threaded;
  }
  mkdirSync(dirname(prv), { recursive: true });
  const result = await runFn(keygenArgs(opts, prv), {});
  if (result.exit !== 0) {
    return fail(threaded, `ssh-keygen failed for ${profile(opts)}: ${String(result.err ?? "").trim()}`);
  }
  const error = enforcePerms(threaded);
  return error ? fail(threaded, error) : threaded;
}

// ------------------------------------------- the provider-side preflight

async function httpGetJson(url: string, headers: Record<string, string>): Promise<any> {
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(httpTimeoutMs) });
  if (!response.ok) throw new Error(`HTTP ${response.status} from ${url}`);
  return response.json();
}

// The comparable part of an OpenSSH public key: type and material, comment
// dropped.
function normalizeKey(value: unknown): string {
  return String(value ?? "").trim().split(/\s+/).slice(0, 2).join(" ");
}

export interface AccountKey {
  id: string;
  name: string;
  public: string;
}

export type FetchFn = (provider: string, token: string) => Promise<AccountKey[]>;

// Every SSH key registered in the provider account, as id/name/public,
// following pagination. A listing failure throws: the preflight answers or
// the create does not proceed.
export async function fetchAccountKeys(provider: string, token: string): Promise<AccountKey[]> {
  const headers = { Authorization: `Bearer ${token}` };
  const keys: AccountKey[] = [];
  if (provider === "digitalocean") {
    let url: string | undefined = "https://api.digitalocean.com/v2/account/keys?per_page=200";
    while (url) {
      const body: any = await httpGetJson(url, headers);
      for (const key of body.ssh_keys ?? []) {
        keys.push({ id: String(key.id), name: String(key.name), public: normalizeKey(key.public_key) });
      }
      url = body.links?.pages?.next;
    }
    return keys;
  }
  if (provider === "hcloud") {
    let page: number | undefined = 1;
    while (page) {
      const body: any = await httpGetJson(`https://api.hetzner.cloud/v1/ssh_keys?per_page=50&page=${page}`, headers);
      for (const key of body.ssh_keys ?? []) {
        keys.push({ id: String(key.id), name: String(key.name), public: normalizeKey(key.public_key) });
      }
      page = body.meta?.pagination?.next_page ?? undefined;
    }
    return keys;
  }
  let cursor: string | undefined;
  while (true) {
    const url = `https://api.vultr.com/v2/ssh-keys?per_page=100${cursor ? `&cursor=${cursor}` : ""}`;
    const body: any = await httpGetJson(url, headers);
    for (const key of body.ssh_keys ?? []) {
      keys.push({ id: String(key.id), name: String(key.name), public: normalizeKey(key.ssh_key) });
    }
    const next = body.meta?.links?.next;
    if (!next) return keys;
    cursor = next;
  }
}

const preflightTokens: Record<string, string> = {
  digitalocean: "do-token",
  hcloud: "hcloud-token",
  vultr: "vultr-api-key",
};

// Refuse a real create when the provider account holds a key named after the
// profile that this deployment's state does not own. Ownership is the
// resource id recorded in state (surfaced through the compute stage's
// `ssh_key_id` output param) — names are conventions anyone can copy. A found
// key is never adopted: if state was lost, the instance is likely orphaned
// too, and importing the key would let create build a second machine next to
// the first. The local public key decides the message: matching material is
// our leftover, anything else is foreign and must not be deleted.
export async function preflight(opts: Opts, fetchFn: FetchFn = fetchAccountKeys): Promise<Opts> {
  const provider = String(opts["provider-compute"]);
  if (!keygen(opts) || !preflightProviders.has(provider)) return opts;
  const token = String(opts[preflightTokens[provider]!] ?? "");
  const ownedId = (opts["once/ssh-state-params"] as any)?.ssh_key_id;
  let accountKeys: AccountKey[];
  try {
    accountKeys = await fetchFn(provider, token);
  } catch (error) {
    return fail(opts, `cannot list ${provider} SSH keys for the create preflight: ${error instanceof Error ? error.message : String(error)}`);
  }
  const found = accountKeys.find((key) => key.name === profile(opts));
  if (!found) return opts;
  if (ownedId !== undefined && String(ownedId) === found.id) return opts;
  const pubPath = publicKeyPath(opts);
  const localPub = existsSync(pubPath) ? normalizeKey(readFileSync(pubPath, "utf8")) : undefined;
  if (localPub && localPub === found.public) {
    return fail(opts, `${provider} already has an SSH key named ${profile(opts)} (id ${found.id}) that is not in this deployment's state and matches ${pubPath}: a previous delete left it behind. Verify no host for ${profile(opts)} survives, delete that key at the provider, and retry.`);
  }
  return fail(opts, `${provider} already has an SSH key named ${profile(opts)} (id ${found.id}) that is not in this deployment's state and does not match ${pubPath}. Do not delete it: it belongs to something else. Investigate, or change profile.`);
}

// ----------------------------------------------------------------- delete

// Remove the generated keypair — the delete DAG wires this after the compute
// destroy, so reaching it means the destroy succeeded and the invariant `key
// present ⇔ deployment exists` holds. A failed or interrupted delete leaves
// the key, correctly: it is still needed. Removes `.ssh/` itself only when
// nothing else lives there.
export function cleanupStep(opts: Opts): Opts {
  if (opts["red/event"] !== "delete" || !keygen(opts)) return { ...opts, "red/exit": 0 };
  for (const path of [privateKeyPath(opts), publicKeyPath(opts)]) {
    if (existsSync(path)) unlinkSync(path);
  }
  const dir = sshDir(opts);
  if (existsSync(dir) && readdirSync(dir).length === 0) rmdirSync(dir);
  return { ...opts, "red/exit": 0 };
}
