import { parName } from "red/cli";
import type { Opts } from "red/workflow";

export interface ProviderEntry {
  required: string[];
  secrets: string[];
  tofuEnv: Record<string, string>;
}

export const providers: Record<string, Record<string, ProviderEntry>> = {
  "provider-compute": {
    digitalocean: {
      required: ["digitalocean-name", "digitalocean-region", "digitalocean-size", "digitalocean-image", "digitalocean-ssh-keys"],
      secrets: ["do-token"], tofuEnv: { "do-token": "DIGITALOCEAN_TOKEN" },
    },
    hcloud: {
      required: ["hcloud-name", "hcloud-image", "hcloud-server-type", "hcloud-location", "hcloud-ssh-keys"],
      secrets: ["hcloud-token"], tofuEnv: { "hcloud-token": "HCLOUD_TOKEN" },
    },
    yandex: {
      required: ["yandex-cloud-id", "yandex-folder-id", "yandex-zone", "yandex-image-family", "yandex-name", "yandex-subnet-cidr", "yandex-platform-id", "yandex-cores", "yandex-memory-gb", "yandex-core-fraction", "yandex-disk-size-gb", "compute-pubkey"],
      secrets: ["yandex-token"], tofuEnv: { "yandex-token": "YC_TOKEN" },
    },
    oci: {
      required: ["oci-config-file-profile", "oci-subnet-id", "oci-compartment-id", "oci-availability-domain", "oci-display-name", "oci-shape", "oci-ocpus", "oci-memory-in-gbs", "oci-boot-volume-size-in-gbs", "oci-boot-volume-vpus-per-gb", "oci-ssh-authorized-keys"],
      secrets: [], tofuEnv: {},
    },
    "no-infra": {
      required: ["no-infra-compute-ip", "no-infra-compute-user", "no-infra-compute-sudoer", "no-infra-compute-uid"],
      secrets: [], tofuEnv: {},
    },
  },
  "provider-smtp": {
    resend: { required: [], secrets: ["resend-api-key", "resend-password"], tofuEnv: { "resend-api-key": "RESEND_API_KEY" } },
    "no-infra": {
      required: ["no-infra-smtp-server", "no-infra-smtp-port", "no-infra-smtp-username"],
      secrets: ["no-infra-smtp-password"], tofuEnv: {},
    },
  },
  "provider-dns": {
    cloudflare: { required: [], secrets: ["cloudflare-api-token"], tofuEnv: { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" } },
    "no-infra": { required: [], secrets: [], tofuEnv: {} },
  },
  "provider-backend": {
    local: { required: [], secrets: [], tofuEnv: {} },
    s3: { required: ["s3-bucket", "s3-region"], secrets: [], tofuEnv: {} },
    r2: {
      required: ["r2-bucket", "r2-endpoint"],
      secrets: ["r2-access-key-id", "r2-secret-access-key"],
      tofuEnv: { "r2-access-key-id": "AWS_ACCESS_KEY_ID", "r2-secret-access-key": "AWS_SECRET_ACCESS_KEY" },
    },
  },
};

const slots = ["provider-compute", "provider-smtp", "provider-dns", "provider-backend"];

function entry(opts: Opts, slot: string): ProviderEntry | undefined {
  return providers[slot]?.[String(opts[slot])];
}

export function tofuEnv(opts: Opts, slot: string): Record<string, string> {
  return entry(opts, slot)?.tofuEnv ?? {};
}

function slotKeys(opts: Opts, field: "required" | "secrets"): string[] {
  return slots.flatMap((slot) => entry(opts, slot)?.[field] ?? []);
}

export function placeholder(value: unknown): boolean {
  return value === null || value === undefined ||
    (typeof value === "string" && (value.trim() === "" || value.toUpperCase() === "REPLACE_ME"));
}

const domainRe = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;
const envNameRe = /^[A-Z_][A-Z0-9_]*$/;

function applications(opts: Opts): any[] | undefined {
  const value = (opts.once as any)?.applications;
  return Array.isArray(value) ? value : undefined;
}

function appSecretKeys(apps: any[]): string[] {
  return apps.flatMap((app) => app.env && !Array.isArray(app.env) && typeof app.env === "object"
    ? Object.values(app.env).filter((value) => !placeholder(value)).map(String)
    : []);
}

export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const key of ["profile", "workdir", "deploy-pubkey", ...slotKeys(opts, "required")]) {
    if (placeholder(opts[key])) errors.push(`${key} is required`);
  }
  for (const slot of slots) {
    const provider = String(opts[slot]);
    if (!providers[slot]?.[provider]) errors.push(`unsupported ${slot} ${JSON.stringify(opts[slot])}`);
  }
  const apps = applications(opts);
  if (!apps?.length) errors.push("once applications must be a non-empty sequence");
  for (const [index, app] of (apps ?? []).entries()) {
    if (placeholder(app.host) || !domainRe.test(String(app.host))) errors.push(`once applications[${index}] has an invalid host`);
    if (placeholder(app.image)) errors.push(`once applications[${index}] requires image`);
    if (app.env !== undefined && !Array.isArray(app.env) && (app.env === null || typeof app.env !== "object")) {
      errors.push(`once applications[${index}] env must map container variable names to red.yml keys`);
    }
    if (app.env && !Array.isArray(app.env) && typeof app.env === "object") {
      for (const [name, key] of Object.entries(app.env)) {
        if (!envNameRe.test(name)) errors.push(`once applications[${index}] has an invalid container variable name ${name}`);
        if (placeholder(key)) errors.push(`once applications[${index}] env ${name} needs a red.yml key`);
      }
    }
  }
  if (typeof opts["compute-prevent-destroy"] !== "boolean") errors.push("compute-prevent-destroy must be true or false");
  if (!String(opts["deploy-pubkey"] ?? "").startsWith("ssh-")) errors.push("deploy-pubkey must be an SSH public key");
  const computeKey = opts["compute-pubkey"];
  if (!placeholder(computeKey) && !String(computeKey).startsWith("ssh-")) errors.push("compute-pubkey must be an SSH public key");
  return errors;
}

export function secretErrors(opts: Opts): string[] {
  const apps = applications(opts) ?? [];
  const keys = [...slotKeys(opts, "secrets"), ...(opts["red/event"] === "create" ? appSecretKeys(apps) : [])];
  return [...new Set(keys)]
    .filter((key) => placeholder(opts[key]))
    .map((key) => `required credential is not set: ${parName(key)}`);
}
