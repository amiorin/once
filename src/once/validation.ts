/**
 * Validate the active profile before running `once create`.
 *
 * Four phases run in a single pass and their errors are collected into a flat
 * list: schema (required keys, placeholders, formats, cross-field), tools
 * (required CLIs on PATH), credentials (tokens authenticate, ssh-agent), and
 * images (every referenced image resolves on its registry).
 */
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { spawnSync } from "node:child_process";
import type { Opts } from "big-config";
import { readBcPars } from "big-config/workflow";
import { okAlias, paramsOf, profileOf, status, syncAliases, toBcOpts } from "./interop.js";

export interface CheckError {
  check: "schema" | "tool" | "credential" | "image";
  detail: string;
}

export interface ValidateResult {
  ok: boolean;
  errors: CheckError[];
}

export interface RunResult {
  ok: boolean;
  exit: number;
  out: string;
  err: string;
}

// -------------------------------------------------------------- regexes

const domainRx =
  /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;
const hostnameRx = domainRx;
const imageRx =
  /^[a-z0-9.-]+\/[a-z0-9._-]+(\/[a-z0-9._-]+)*(:[a-zA-Z0-9._-]+)?$/;
const sshPubkeyRx = /^ssh-(ed25519|rsa|dss|ecdsa) [A-Za-z0-9+/=]+( .*)?$/;

const PLACEHOLDER = "REPLACE_ME";

function isPlaceholder(v: unknown): boolean {
  return typeof v === "string" && v.includes(PLACEHOLDER);
}

function blankOrPlaceholder(v: unknown): boolean {
  return (
    v == null ||
    (typeof v === "string" && (v.trim() === "" || isPlaceholder(v)))
  );
}

function realValue(v: unknown): boolean {
  return !blankOrPlaceholder(v);
}

// -------------------------------------------------------------- schema

type FieldCheck = (v: unknown) => string | null;

const PLACEHOLDER_MSG = "must replace REPLACE_ME with a real value";

const stringValue: FieldCheck = (v) => {
  if (typeof v !== "string") return "should be a string";
  if (isPlaceholder(v)) return PLACEHOLDER_MSG;
  return null;
};

const intValue: FieldCheck = (v) => {
  if (isPlaceholder(v)) return PLACEHOLDER_MSG;
  if (typeof v !== "number" || !Number.isInteger(v)) {
    return "should be an integer";
  }
  return null;
};

const nonEmptyString: FieldCheck = (v) => {
  if (typeof v !== "string") return "should be a string";
  if (isPlaceholder(v)) return PLACEHOLDER_MSG;
  if (v.length === 0) return "must be a non-empty string";
  return null;
};

function reCheck(rx: RegExp, msg: string): FieldCheck {
  return (v) => {
    if (typeof v !== "string") return "should be a string";
    if (isPlaceholder(v)) return PLACEHOLDER_MSG;
    if (!rx.test(v)) return msg;
    return null;
  };
}

type Emit = (path: string, msg: string) => void;

function required(
  obj: Record<string, any>,
  key: string,
  check: FieldCheck,
  emit: Emit,
  prefix = "workflow/params",
): void {
  if (!(key in obj)) {
    emit(`${prefix} → ${key}`, "missing required key");
    return;
  }
  const msg = check(obj[key]);
  if (msg) emit(`${prefix} → ${key}`, msg);
}

function checkBaseParams(params: Record<string, any>, emit: Emit): void {
  required(params, "domain", reCheck(domainRx, "must be a valid domain"), emit);
  required(params, "package", nonEmptyString, emit);
  required(
    params,
    "compute-pubkey",
    reCheck(sshPubkeyRx, "must look like an SSH public key"),
    emit,
  );
  required(
    params,
    "deploy-pubkey",
    reCheck(sshPubkeyRx, "must look like an SSH public key"),
    emit,
  );

  const once = params.once;
  if (once === undefined) {
    emit("workflow/params → once", "missing required key");
    return;
  }
  if (typeof once !== "object" || once === null || Array.isArray(once)) {
    emit("workflow/params → once", "should be a map");
    return;
  }
  const apps = once.applications;
  if (apps === undefined) {
    emit("workflow/params → once → applications", "missing required key");
    return;
  }
  if (!Array.isArray(apps)) {
    emit("workflow/params → once → applications", "should be a vector");
    return;
  }
  apps.forEach((app: any, i: number) => {
    const prefix = `workflow/params → once → applications → ${i}`;
    required(app, "host", reCheck(hostnameRx, "must be a valid hostname"), emit, prefix);
    required(
      app,
      "image",
      reCheck(imageRx, "must be a valid image ref (e.g. ghcr.io/org/name:tag)"),
      emit,
      prefix,
    );
    if (app.env !== undefined) {
      if (!Array.isArray(app.env)) {
        emit(`${prefix} → env`, "should be a vector");
      } else {
        app.env.forEach((e: unknown, j: number) => {
          const msg = stringValue(e);
          if (msg) emit(`${prefix} → env → ${j}`, msg);
        });
      }
    }
  });
}

function checkSmtp(params: Record<string, any>, emit: Emit): void {
  switch (params["provider-smtp"]) {
    case "resend":
      required(params, "resend-server", stringValue, emit);
      required(params, "resend-port", intValue, emit);
      required(params, "resend-username", stringValue, emit);
      required(params, "resend-api-key", stringValue, emit);
      required(params, "resend-password", stringValue, emit);
      break;
    case "no-infra":
      required(params, "no-infra-smtp-server", stringValue, emit);
      required(params, "no-infra-smtp-port", intValue, emit);
      required(params, "no-infra-smtp-username", stringValue, emit);
      required(params, "no-infra-smtp-password", stringValue, emit);
      break;
    default:
      emit("workflow/params → provider-smtp", "invalid dispatch value");
  }
}

function checkDns(params: Record<string, any>, emit: Emit): void {
  switch (params["provider-dns"]) {
    case "cloudflare":
      required(params, "cloudflare-api-token", stringValue, emit);
      break;
    case "no-infra":
      break;
    default:
      emit("workflow/params → provider-dns", "invalid dispatch value");
  }
}

function checkBackend(params: Record<string, any>, emit: Emit): void {
  switch (params["provider-backend"]) {
    case "s3":
      required(params, "s3-bucket", stringValue, emit);
      required(params, "s3-region", stringValue, emit);
      break;
    case "r2":
      required(params, "r2-bucket", nonEmptyString, emit);
      required(params, "r2-endpoint", nonEmptyString, emit);
      required(params, "r2-access-key-id", nonEmptyString, emit);
      required(params, "r2-secret-access-key", nonEmptyString, emit);
      break;
    case "local":
      break;
    default:
      emit("workflow/params → provider-backend", "invalid dispatch value");
  }
}

function checkCompute(params: Record<string, any>, emit: Emit): void {
  switch (params["provider-compute"]) {
    case "oci":
      for (const k of [
        "oci-config-file-profile",
        "oci-subnet-id",
        "oci-compartment-id",
        "oci-availability-domain",
        "oci-display-name",
        "oci-shape",
        "oci-ssh-authorized-keys",
      ]) {
        required(params, k, stringValue, emit);
      }
      for (const k of [
        "oci-ocpus",
        "oci-memory-in-gbs",
        "oci-boot-volume-size-in-gbs",
        "oci-boot-volume-vpus-per-gb",
      ]) {
        required(params, k, intValue, emit);
      }
      break;
    case "hcloud":
      for (const k of [
        "hcloud-name",
        "hcloud-image",
        "hcloud-server-type",
        "hcloud-location",
        "hcloud-ssh-keys",
        "hcloud-token",
      ]) {
        required(params, k, stringValue, emit);
      }
      break;
    case "digitalocean":
      for (const k of [
        "digitalocean-name",
        "digitalocean-region",
        "digitalocean-size",
        "digitalocean-image",
        "digitalocean-vpc-uuid",
        "digitalocean-ssh-keys",
        "do-token",
      ]) {
        required(params, k, stringValue, emit);
      }
      break;
    case "no-infra":
      for (const k of [
        "no-infra-compute-ip",
        "no-infra-compute-user",
        "no-infra-compute-sudoer",
        "no-infra-compute-uid",
        "no-infra-compute-name",
      ]) {
        required(params, k, stringValue, emit);
      }
      break;
    default:
      emit("workflow/params → provider-compute", "invalid dispatch value");
  }
}

function hostsMatchDomain(params: Record<string, any>): boolean {
  const domain = params.domain;
  const apps: any[] = params.once?.applications ?? [];
  return apps.every(
    (a) =>
      a.host && (a.host === domain || a.host.endsWith(`.${domain}`)),
  );
}

/** Validate the merged profile against the schema. */
export function schemaErrors(opts: Opts): CheckError[] | undefined {
  const errors: CheckError[] = [];
  const emit: Emit = (path, msg) =>
    errors.push({ check: "schema", detail: `${path}: ${msg}` });

  const profileMsg = stringValue(profileOf(opts));
  if (profileMsg) emit("render/profile", profileMsg);

  const params = paramsOf(opts);
  if (params == null || typeof params !== "object") {
    emit("workflow/params", "missing required key");
  } else {
    checkBaseParams(params, emit);
    checkSmtp(params, emit);
    checkDns(params, emit);
    checkBackend(params, emit);
    checkCompute(params, emit);
    if (!hostsMatchDomain(params)) {
      emit(
        "workflow/params",
        "every :once :applications :host must equal or be a subdomain of :domain",
      );
    }
  }
  return errors.length ? errors : undefined;
}

// -------------------------------------------------------------- tools

export interface ToolSpec {
  cmd: string;
  name: string;
  hint: string;
}

const AWS_HINT =
  "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html";

const baseTools: ToolSpec[] = [
  { cmd: "tofu", name: "OpenTofu", hint: "https://opentofu.org/docs/intro/install/" },
  { cmd: "ansible-playbook", name: "Ansible", hint: "pipx install ansible" },
  { cmd: "ssh", name: "OpenSSH", hint: "your distro's openssh-client package" },
  { cmd: "curl", name: "curl", hint: "your distro's curl package" },
  {
    cmd: "skopeo",
    name: "skopeo",
    hint: "https://github.com/containers/skopeo/blob/main/install.md",
  },
];

/** Required per-provider CLIs for the given params. */
export function providerTools(params: Record<string, any>): ToolSpec[] {
  const tools: ToolSpec[] = [];
  const compute = params["provider-compute"];
  const backend = params["provider-backend"];
  if (compute === "oci") {
    tools.push({ cmd: "oci", name: "OCI CLI", hint: "pip install oci-cli" });
  }
  if (compute === "hcloud") {
    tools.push({
      cmd: "hcloud",
      name: "hcloud",
      hint: "https://github.com/hetznercloud/cli",
    });
  }
  if (compute === "digitalocean") {
    tools.push({
      cmd: "doctl",
      name: "doctl",
      hint: "https://docs.digitalocean.com/reference/doctl/how-to/install/",
    });
  }
  if (backend === "s3") {
    tools.push({ cmd: "aws", name: "AWS CLI", hint: AWS_HINT });
  }
  if (backend === "r2") {
    tools.push({ cmd: "aws", name: "AWS CLI", hint: AWS_HINT });
  }
  return tools;
}

function which(cmd: string): boolean {
  try {
    return (
      spawnSync("which", [cmd], { encoding: "utf8" }).status === 0
    );
  } catch {
    return false;
  }
}

/** Report required CLIs not found on PATH. */
export function toolErrors(
  params: Record<string, any>,
  whichFn: (cmd: string) => boolean = which,
): CheckError[] {
  return [...baseTools, ...providerTools(params)]
    .filter((t) => !whichFn(t.cmd))
    .map((t) => ({
      check: "tool" as const,
      detail: `${t.name} not found on PATH. Install: ${t.hint}`,
    }));
}

// -------------------------------------------------------------- credentials

const RUN_TIMEOUT_MS = 30000;

/** Run a command with empty stdin and a timeout. */
export function run(
  args: string[],
  extraEnv?: Record<string, string>,
): RunResult {
  try {
    const res = spawnSync(args[0], args.slice(1), {
      input: "",
      encoding: "utf8",
      timeout: RUN_TIMEOUT_MS,
      env: extraEnv ? { ...process.env, ...extraEnv } : process.env,
    });
    if (res.error) {
      const code = (res.error as NodeJS.ErrnoException).code;
      if (code === "ETIMEDOUT") {
        return {
          ok: false,
          exit: -1,
          out: "",
          err: `command timed out after ${RUN_TIMEOUT_MS}ms`,
        };
      }
      return { ok: false, exit: -1, out: "", err: res.error.message };
    }
    const exit = res.status ?? -1;
    return {
      ok: exit === 0,
      exit,
      out: res.stdout ?? "",
      err: res.stderr ?? "",
    };
  } catch (e: any) {
    return { ok: false, exit: -1, out: "", err: e?.message ?? String(e) };
  }
}

export type Runner = (
  args: string[],
  extraEnv?: Record<string, string>,
) => RunResult;

function trimSnippet(s: string | null | undefined): string | null {
  const t = (s ?? "").trim();
  if (t === "") return null;
  return t.length > 200 ? `${t.slice(0, 200)}…` : t;
}

function bearerCheck(
  label: string,
  url: string,
  token: string,
  runFn: Runner,
): string | null {
  const r = runFn([
    "curl",
    "-sf",
    "-o",
    "/dev/null",
    "-H",
    `Authorization: Bearer ${token}`,
    url,
  ]);
  if (r.ok) return null;
  const snippet = trimSnippet(r.err);
  return `${label}: token rejected (curl exit ${r.exit})${
    snippet ? ` — ${snippet}` : ""
  }`;
}

function cloudflareZoneCheck(
  domain: string,
  token: string,
  runFn: Runner,
): string | null {
  const url = `https://api.cloudflare.com/client/v4/zones?name=${encodeURIComponent(
    domain,
  )}&status=active&per_page=1`;
  const r = runFn(["curl", "-sf", "-H", `Authorization: Bearer ${token}`, url]);
  if (!r.ok) {
    const snippet = trimSnippet(r.err);
    return `Cloudflare API: token rejected (curl exit ${r.exit})${
      snippet ? ` — ${snippet}` : ""
    }`;
  }
  try {
    const parsed = JSON.parse(r.out);
    if (parsed.success === false) {
      const snippet = trimSnippet(JSON.stringify(parsed.errors));
      return `Cloudflare API: zone lookup failed${
        snippet ? ` — ${snippet}` : ""
      }`;
    }
    if (!parsed.result || parsed.result.length === 0) {
      return `Cloudflare zone: ${domain} not found or not active`;
    }
    return null;
  } catch (e: any) {
    return `Cloudflare API: invalid zone lookup response — ${e?.message}`;
  }
}

function cliCheck(
  label: string,
  args: string[],
  extraEnv: Record<string, string> | undefined,
  runFn: Runner,
): string | null {
  const r = runFn(args, extraEnv);
  if (r.ok) return null;
  return `${label}: ${trimSnippet(r.err) ?? "command failed"}`;
}

function ociConfigPath(): string {
  return (
    process.env.OCI_CLI_CONFIG_FILE ||
    process.env.OCI_CONFIG_FILE ||
    `${homedir()}/.oci/config`
  );
}

function ociConfigError(): string | null {
  const path = ociConfigPath();
  if (!existsSync(path)) {
    return `OCI: config file not found at ${path} — run 'oci setup config' to create one`;
  }
  return null;
}

function classifyHeadBucketError(
  err: string,
): "missing-bucket" | "bad-credentials" | "unknown" {
  const s = (err ?? "").toLowerCase();
  if (
    s.includes("(404)") ||
    s.includes("not found") ||
    s.includes("nosuchbucket")
  ) {
    return "missing-bucket";
  }
  if (
    s.includes("(401)") ||
    s.includes("(403)") ||
    s.includes("forbidden") ||
    s.includes("unauthorized") ||
    s.includes("invalidaccesskey") ||
    s.includes("signaturedoesnotmatch")
  ) {
    return "bad-credentials";
  }
  return "unknown";
}

function r2Errors(params: Record<string, any>, runFn: Runner): string[] {
  const bucket = params["r2-bucket"];
  const endpoint = params["r2-endpoint"];
  const accessKey = params["r2-access-key-id"];
  const secretKey = params["r2-secret-access-key"];
  const missing: string[] = [];
  if (blankOrPlaceholder(endpoint)) missing.push("r2-endpoint");
  if (blankOrPlaceholder(bucket)) missing.push("r2-bucket");
  if (blankOrPlaceholder(accessKey)) missing.push("r2-access-key-id");
  if (blankOrPlaceholder(secretKey)) missing.push("r2-secret-access-key");
  if (missing.length) {
    return [`R2: missing or placeholder credentials: ${missing.join(", ")}`];
  }
  if (!which("aws")) return [];
  const r = runFn(
    ["aws", "s3api", "head-bucket", "--bucket", bucket, "--endpoint-url", endpoint],
    {
      AWS_ACCESS_KEY_ID: accessKey,
      AWS_SECRET_ACCESS_KEY: secretKey,
      AWS_DEFAULT_REGION: "auto",
    },
  );
  if (r.ok) return [];
  const snippet = trimSnippet(r.err) ?? "head-bucket failed";
  switch (classifyHeadBucketError(r.err)) {
    case "missing-bucket":
      return [`R2 (bucket): ${bucket} not found at ${endpoint} — ${snippet}`];
    case "bad-credentials":
      return [`R2 (auth): credentials rejected at ${endpoint} — ${snippet}`];
    default:
      return [
        `R2: head-bucket on ${bucket} at ${endpoint} failed — ${snippet}`,
      ];
  }
}

const CLOUD_COMPUTE_PROVIDERS = new Set(["oci", "hcloud", "digitalocean"]);

function cloudCompute(params: Record<string, any>): boolean {
  return CLOUD_COMPUTE_PROVIDERS.has(params["provider-compute"]);
}

function sshPubkeyIdentity(s: string | undefined): string | null {
  const parts = (s ?? "").trim().split(/\s+/);
  const [keyType, keyBody] = parts;
  if (keyType && keyBody) return `${keyType} ${keyBody}`;
  return null;
}

/** Report ssh-agent issues for cloud compute profiles. */
export function sshAgentErrors(
  params: Record<string, any>,
  env: Record<string, string | undefined>,
  runFn: Runner = run,
): string[] {
  if (!cloudCompute(params)) return [];
  const computePubkey = params["compute-pubkey"];
  const sock = (env.SSH_AUTH_SOCK ?? "").trim();
  if (isPlaceholder(computePubkey)) {
    return ["SSH agent: :compute-pubkey still contains REPLACE_ME"];
  }
  if (sock === "") {
    return [
      "SSH agent: SSH_AUTH_SOCK is not set; start ssh-agent and run ssh-add for :compute-pubkey",
    ];
  }
  const r = runFn(["ssh-add", "-L"], { SSH_AUTH_SOCK: sock });
  const wanted = sshPubkeyIdentity(computePubkey);
  const agentMsg = `${r.err}\n${r.out}`;
  if (wanted === null) {
    return ["SSH agent: :compute-pubkey is not a parseable SSH public key"];
  }
  if (r.ok) {
    const loaded = new Set(
      (r.out ?? "")
        .split("\n")
        .map(sshPubkeyIdentity)
        .filter((x): x is string => x !== null),
    );
    return loaded.has(wanted)
      ? []
      : [
          `SSH agent: :compute-pubkey is not loaded in ssh-agent at SSH_AUTH_SOCK=${sock}`,
        ];
  }
  if (agentMsg.toLowerCase().includes("no identities")) {
    return [
      `SSH agent: :compute-pubkey is not loaded; the agent at SSH_AUTH_SOCK=${sock} has no identities`,
    ];
  }
  const snippet = trimSnippet(r.err);
  return [
    `SSH agent: ssh-add -L failed for SSH_AUTH_SOCK=${sock} (exit ${r.exit})${
      snippet ? ` — ${snippet}` : ""
    }`,
  ];
}

/** Verify tokens / cloud configs authenticate against their APIs. */
export function credentialErrors(
  params: Record<string, any>,
  env: Record<string, string | undefined> = process.env,
  runFn: Runner = run,
): CheckError[] {
  const pSmtp = params["provider-smtp"];
  const pDns = params["provider-dns"];
  const pCompute = params["provider-compute"];
  const pBackend = params["provider-backend"];
  const domain = params.domain;

  const single: (string | null)[] = [
    pSmtp === "resend" && realValue(params["resend-api-key"])
      ? bearerCheck(
          "Resend API",
          "https://api.resend.com/api-keys",
          params["resend-api-key"],
          runFn,
        )
      : null,
    pDns === "cloudflare" &&
    realValue(domain) &&
    realValue(params["cloudflare-api-token"])
      ? cloudflareZoneCheck(domain, params["cloudflare-api-token"], runFn)
      : null,
    pCompute === "hcloud" && realValue(params["hcloud-token"])
      ? bearerCheck(
          "Hetzner Cloud API",
          "https://api.hetzner.cloud/v1/server_types",
          params["hcloud-token"],
          runFn,
        )
      : null,
    pCompute === "digitalocean" && realValue(params["do-token"])
      ? bearerCheck(
          "DigitalOcean API",
          "https://api.digitalocean.com/v2/account",
          params["do-token"],
          runFn,
        )
      : null,
    pCompute === "oci" && which("oci")
      ? ociConfigError() ??
        cliCheck(
          "OCI",
          ["oci", "iam", "region", "list", "--output", "json"],
          undefined,
          runFn,
        )
      : null,
    pBackend === "s3" && which("aws")
      ? cliCheck(
          "AWS (S3 backend)",
          ["aws", "sts", "get-caller-identity"],
          undefined,
          runFn,
        )
      : null,
  ];

  const multi: string[] = [
    ...(pBackend === "r2" ? r2Errors(params, runFn) : []),
    ...sshAgentErrors(params, env, runFn),
  ];

  return [
    ...single.filter((x): x is string => x != null),
    ...multi,
  ].map((detail) => ({ check: "credential" as const, detail }));
}

// -------------------------------------------------------------- images

function imageErrors(
  params: Record<string, any>,
  runFn: Runner = run,
): CheckError[] {
  if (!which("skopeo")) return [];
  const apps: any[] = params.once?.applications ?? [];
  const errors: CheckError[] = [];
  for (const { image } of apps) {
    if (realValue(image)) {
      const r = runFn([
        "skopeo",
        "inspect",
        "--no-tags",
        "--override-os",
        "linux",
        `docker://${image}`,
      ]);
      if (!r.ok) {
        errors.push({
          check: "image",
          detail: `${image} — ${trimSnippet(r.err) ?? "manifest unknown"}`,
        });
      }
    }
  }
  return errors;
}

// -------------------------------------------------------------- top-level

/** Validate the merged active profile. */
export function validateReport(
  opts: Opts,
  env: Record<string, string | undefined> = process.env,
): ValidateResult {
  const merged = syncAliases(readBcPars(toBcOpts(opts), env));
  const params = paramsOf(merged);
  const errors: CheckError[] = [
    ...(schemaErrors(merged) ?? []),
    ...toolErrors(params),
    ...credentialErrors(params, env),
    ...imageErrors(params),
  ];
  return { ok: errors.length === 0, errors };
}

function groupName(k: CheckError["check"]): string {
  switch (k) {
    case "schema":
      return "Schema";
    case "tool":
      return "Tools";
    case "credential":
      return "Credentials";
    case "image":
      return "Images";
  }
}

function printReport(result: ValidateResult): void {
  if (result.ok) {
    console.log("All checks passed.");
    return;
  }
  const n = result.errors.length;
  console.log(`Validation failed (${n} issue${n === 1 ? "" : "s"}):`);
  for (const k of ["schema", "tool", "credential", "image"] as const) {
    const es = result.errors.filter((e) => e.check === k);
    if (es.length === 0) continue;
    console.log("");
    console.log(`  ${groupName(k)}:`);
    for (const e of es) {
      console.log(`    - ${e.detail}`);
    }
  }
}

/** The `validate` workflow step. */
export function validate(
  _stepFns: unknown,
  opts: Opts,
  reportFn: (opts: Opts) => ValidateResult = validateReport,
): Opts {
  const result = reportFn(opts);
  printReport(result);
  const base = { ...opts, "validation/result": result };
  return result.ok ? okAlias(base) : status(base, 1, "validation failed");
}
