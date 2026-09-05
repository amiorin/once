// Drive the SSH Keypair Standard's create matrix, provider preflight, and
// delete cleanup through red with injected state, keygen, and account-key
// functions, printing one normalized `case exit=<n> err=<message>` line per
// scenario. Green and blue print the same shape, so parity.sh can diff them:
// none of this logic reaches a build artifact, and the error messages are
// user-facing contract.
import { chmodSync, existsSync, mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Opts } from "red/workflow";
import * as ssh from "../red/src/ssh.ts";

// A fresh scenario directory, installed as $HOME so the keypair lands under
// it — no scenario may touch the real ~/.ssh.
function tmpDir(): string {
  const dir = mkdtempSync(join(tmpdir(), "once-ssh-parity"));
  process.env.HOME = dir;
  return dir;
}

const fakeKeygen = async (args: string[]) => {
  const path = String(args[args.length - 1]);
  mkdirSync(join(path, ".."), { recursive: true });
  writeFileSync(path, "PRIVATE");
  writeFileSync(`${path}.pub`, "ssh-ed25519 AAAATESTKEY parity managed by Colors");
  return { exit: 0 } as any;
};

function seed(dir: string): void {
  void fakeKeygen(["ssh-keygen", join(dir, ".ssh", "parity")]);
}

function base(dir: string): Opts {
  return {
    profile: "parity",
    "provider-compute": "digitalocean",
    "do-token": "tok",
    "red/state-file": join(dir, "colors.yml"),
  };
}

function line(caseName: string, dir: string, opts: Opts): void {
  console.log(`${caseName} exit=${opts["red/exit"] ?? 0} err=${String(opts["red/err"] ?? "").replaceAll(dir, "<dir>")}`);
}

const stateNone = async () => undefined;
const stateLive = async () => ({ ip: "1.2.3.4" });
const stateOwned = async () => ({ ip: "1.2.3.4", ssh_key_id: "77" });

{
  const dir = tmpDir();
  line("first-create", dir, await ssh.ensureKey(base(dir), stateNone, fakeKeygen));
}
{
  const dir = tmpDir();
  line("lost-key", dir, await ssh.ensureKey(base(dir), stateLive, fakeKeygen));
}
{
  const dir = tmpDir();
  seed(dir);
  line("leftover", dir, await ssh.ensureKey(base(dir), stateNone, fakeKeygen));
}
{
  const dir = tmpDir();
  seed(dir);
  line("converge", dir, await ssh.ensureKey(base(dir), stateLive, fakeKeygen));
}
{
  const dir = tmpDir();
  mkdirSync(join(dir, ".ssh"), { recursive: true });
  writeFileSync(join(dir, ".ssh", "parity"), "PRIVATE");
  line("half-keypair", dir, await ssh.ensureKey(base(dir), stateLive, fakeKeygen));
}

async function pre(dir: string, state: () => Promise<any>, fetch: ssh.FetchFn): Promise<Opts> {
  return ssh.preflight({ ...ssh.withMachineKey(base(dir), true), "once/ssh-state-params": await state() }, fetch);
}

{
  const dir = tmpDir();
  seed(dir);
  line("preflight-none", dir, await pre(dir, stateNone, async () => []));
}
{
  const dir = tmpDir();
  seed(dir);
  line("preflight-owned", dir, await pre(dir, stateOwned, async () => [{ id: "77", name: "parity", public: "ssh-ed25519 AAAATESTKEY" }]));
}
{
  const dir = tmpDir();
  seed(dir);
  line("preflight-ours", dir, await pre(dir, stateNone, async () => [{ id: "77", name: "parity", public: "ssh-ed25519 AAAATESTKEY" }]));
}
{
  const dir = tmpDir();
  seed(dir);
  line("preflight-foreign", dir, await pre(dir, stateNone, async () => [{ id: "88", name: "parity", public: "ssh-ed25519 AAAAOTHER" }]));
}
{
  const dir = tmpDir();
  seed(dir);
  line("preflight-api-error", dir, await pre(dir, stateNone, async () => {
    throw new Error("HTTP 500 from provider");
  }));
}

{
  const dir = tmpDir();
  seed(dir);
  const out = ssh.cleanupStep({ ...base(dir), "red/event": "delete" });
  console.log(`cleanup exit=${out["red/exit"] ?? 0} removed=${!existsSync(join(dir, ".ssh", "parity"))}`);
}

// A key that survives the removal (read-only ~/.ssh) fails the delete with
// one message in every colour.
{
  const dir = tmpDir();
  seed(dir);
  chmodSync(join(dir, ".ssh"), 0o500);
  line("cleanup-readonly", dir, ssh.cleanupStep({ ...base(dir), "red/event": "delete" }));
  chmodSync(join(dir, ".ssh"), 0o700);
}
