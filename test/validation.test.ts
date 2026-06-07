import { chmodSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it, vi } from "vitest";
import type { Opts } from "big-config";
import { main } from "../src/cli.js";
import {
  bb,
  profileAlpha,
  profileBeta,
  profileGamma,
  profileNoInfra,
} from "../src/once/options.js";
import {
  type RunResult,
  type Runner,
  credentialErrors,
  providerTools,
  schemaErrors,
  sshAgentErrors,
  toolErrors,
  validate,
  validateReport,
} from "../src/once/validation.js";

const testComputePubkey =
  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 test@example.com";
const testDeployPubkey =
  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII1Lbgxiv2OnDKwc8Wx25SQlGyI+iY1drUii/IMZ3YSh deploy@example.com";

const profileAlphaDomain = String(profileAlpha.params.domain);

const creds: Record<string, any> = {
  "compute-pubkey": testComputePubkey,
  "deploy-pubkey": testDeployPubkey,
  "resend-api-key": "stub",
  "resend-password": "stub",
  "cloudflare-api-token": "stub",
  "hcloud-token": "stub",
  "hcloud-ssh-keys": "stub-key",
  "do-token": "stub",
  "digitalocean-vpc-uuid": "stub-vpc",
  "digitalocean-ssh-keys": "stub-key",
  "oci-config-file-profile": "DEFAULT",
  "oci-subnet-id": "stub-subnet",
  "oci-compartment-id": "stub-compartment",
  "oci-availability-domain": "stub-ad",
  "oci-ssh-authorized-keys": "~/.ssh/id_ed25519.pub",
  "no-infra-compute-ip": "192.0.2.10",
  "no-infra-compute-user": "ubuntu",
  "no-infra-compute-sudoer": "ubuntu",
  "no-infra-compute-uid": "1000",
  "no-infra-compute-name": "once",
  "no-infra-smtp-password": "stub",
  "r2-bucket": "stub-bucket",
  "r2-endpoint": "https://stub.r2.cloudflarestorage.com",
  "r2-access-key-id": "stub",
  "r2-secret-access-key": "stub",
  "s3-bucket": "stub-bucket",
  "s3-region": "eu-west-1",
};

/** Replace REPLACE_ME placeholders with schema-valid test values. */
function withCreds(profile: Opts): Opts {
  return { ...profile, params: { ...profile.params, ...creds } };
}

function withParams(profile: Opts, overrides: Record<string, any>): Opts {
  return { ...profile, params: { ...profile.params, ...overrides } };
}

describe("public profiles pass schema with stub creds", () => {
  const cases: [string, Opts][] = [
    ["profile-alpha", withCreds(profileAlpha)],
    ["profile-beta", withCreds(profileBeta)],
    ["profile-gamma", withCreds(profileGamma)],
    ["profile-no-infra", withCreds(profileNoInfra)],
  ];
  for (const [name, profile] of cases) {
    it(name, () => {
      expect(schemaErrors(profile)).toBeUndefined();
    });
  }
});

it("validateReport honors BC_PAR env overrides", () => {
  const binDir = mkdtempSync(join(tmpdir(), "once-validation-bin-"));
  const oldPath = process.env.PATH;
  try {
    for (const cmd of ["tofu", "ansible-playbook", "ssh", "curl", "skopeo"]) {
      const file = join(binDir, cmd);
      writeFileSync(file, "#!/bin/sh\nexit 0\n");
      chmodSync(file, 0o755);
    }
    process.env.PATH = `${binDir}:${oldPath ?? ""}`;
    const result = validateReport(profileNoInfra, {
      BC_PAR_COMPUTE_PUBKEY: testComputePubkey,
      BC_PAR_DEPLOY_PUBKEY: testDeployPubkey,
      BC_PAR_NO_INFRA_COMPUTE_IP: "192.0.2.10",
      BC_PAR_NO_INFRA_COMPUTE_USER: "ubuntu",
      BC_PAR_NO_INFRA_COMPUTE_SUDOER: "ubuntu",
      BC_PAR_NO_INFRA_COMPUTE_UID: "1000",
      BC_PAR_NO_INFRA_COMPUTE_NAME: "once",
      BC_PAR_NO_INFRA_SMTP_PASSWORD: "stub",
    });
    expect(result).toEqual({ ok: true, errors: [] });
  } finally {
    if (oldPath === undefined) delete process.env.PATH;
    else process.env.PATH = oldPath;
    rmSync(binDir, { recursive: true, force: true });
  }
});

describe("schema validation", () => {
  it("placeholder credential is reported", () => {
    const errors = schemaErrors(profileAlpha);
    expect(errors).toBeDefined();
    expect(
      errors!.some(
        (e) =>
          e.detail.includes("resend-api-key") &&
          e.detail.includes("REPLACE_ME"),
      ),
    ).toBe(true);
  });

  it("missing compute-pubkey is reported", () => {
    const p = withCreds(profileAlpha);
    const params = { ...p.params };
    delete params["compute-pubkey"];
    const errors = schemaErrors({ ...p, params });
    expect(errors).toBeDefined();
    expect(errors!.some((e) => e.detail.includes("compute-pubkey"))).toBe(true);
  });

  it("bad domain format is reported", () => {
    const bad = withParams(withCreds(profileAlpha), {
      domain: "not_a_domain",
      once: { applications: [] },
    });
    const errors = schemaErrors(bad);
    expect(errors).toBeDefined();
    expect(
      errors!.some(
        (e) =>
          e.detail.includes("domain") && e.detail.includes("valid domain"),
      ),
    ).toBe(true);
  });

  it("cross-field mismatched host is reported", () => {
    const bad = withParams(withCreds(profileAlpha), {
      once: {
        applications: [
          { host: "alien.example.com", image: "ghcr.io/foo/bar:latest" },
        ],
      },
    });
    const errors = schemaErrors(bad);
    expect(errors).toBeDefined();
    expect(errors!.some((e) => e.detail.includes("subdomain"))).toBe(true);
  });

  it("cross-field apex and subdomain pass", () => {
    const okProfile = withParams(withCreds(profileAlpha), {
      once: {
        applications: [
          { host: profileAlphaDomain, image: "ghcr.io/foo/bar:latest" },
          { host: `www.${profileAlphaDomain}`, image: "ghcr.io/foo/bar:latest" },
        ],
      },
    });
    expect(schemaErrors(okProfile)).toBeUndefined();
  });
});

describe("cli", () => {
  it("exposes package and tool workflow verbs and rejects bare validate", () => {
    const exit = vi.spyOn(process, "exit").mockImplementation(((code?: string | number | null) => { throw new Error(`exit:${code}`); }) as never);
    const err = vi.spyOn(console, "error").mockImplementation(() => undefined);
    expect(() => main(["validate"], bb)).toThrow("exit:1");
    const output = err.mock.calls.flat().join("\n");
    for (const command of ["validate", "describe", "build", "create", "delete", "lock", "git-check", "git-push", "unlock-any"]) {
      expect(output).toContain(command);
    }
    expect(output).toContain("once package validate");
    expect(output).toContain("once package describe");
    expect(output).toContain("git-check lock render");
    expect(output).not.toContain("once validate");
    exit.mockRestore();
    err.mockRestore();
  });
});

describe("validate workflow step sets exit status", () => {
  it("valid report succeeds", () => {
    const result = validate([], {}, () => ({ ok: true, errors: [] }));
    expect(result.exit).toBe(0);
    expect(result["validation/result"]).toEqual({ ok: true, errors: [] });
  });

  it("invalid report fails", () => {
    const result = validate([], {}, () => ({
      ok: false,
      errors: [{ check: "schema", detail: "bad" }],
    }));
    expect(result.exit).toBe(1);
    expect(result.err).toBe("validation failed");
  });
});

describe("provider-tools picks the right CLIs", () => {
  const cmds = (params: Record<string, any>) =>
    new Set(providerTools(params).map((t) => t.cmd));

  it("hcloud + s3", () => {
    expect(
      cmds({ "provider-compute": "hcloud", "provider-backend": "s3" }),
    ).toEqual(new Set(["hcloud", "aws"]));
  });
  it("oci + s3", () => {
    expect(
      cmds({ "provider-compute": "oci", "provider-backend": "s3" }),
    ).toEqual(new Set(["oci", "aws"]));
  });
  it("digitalocean + s3", () => {
    expect(
      cmds({ "provider-compute": "digitalocean", "provider-backend": "s3" }),
    ).toEqual(new Set(["doctl", "aws"]));
  });
  it("hcloud + r2", () => {
    expect(
      cmds({ "provider-compute": "hcloud", "provider-backend": "r2" }),
    ).toEqual(new Set(["hcloud", "aws"]));
  });
  it("oci + r2", () => {
    expect(
      cmds({ "provider-compute": "oci", "provider-backend": "r2" }),
    ).toEqual(new Set(["oci", "aws"]));
  });
  it("no-infra + local", () => {
    expect(
      cmds({ "provider-compute": "no-infra", "provider-backend": "local" }),
    ).toEqual(new Set());
  });
  it("hcloud + local", () => {
    expect(
      cmds({ "provider-compute": "hcloud", "provider-backend": "local" }),
    ).toEqual(new Set(["hcloud"]));
  });
});

it("tool-errors honors the injected which-fn", () => {
  const params = withCreds(profileAlpha).params;
  const errors = toolErrors(params, (cmd) => cmd !== "tofu");
  expect(errors.length).toBe(1);
  expect(errors[0].detail).toContain("OpenTofu");
});

describe("ssh-agent checks cloud compute pubkey", () => {
  const params = {
    "provider-compute": "hcloud",
    "compute-pubkey": testComputePubkey,
  };
  const keyIdLine = testComputePubkey.split(/\s+/).slice(0, 2).join(" ");

  it("missing SSH_AUTH_SOCK is reported for cloud compute", () => {
    const errors = sshAgentErrors(params, {});
    expect(errors.length).toBe(1);
    expect(errors[0]).toContain("SSH_AUTH_SOCK");
  });

  it("no-infra skips the ssh-agent check", () => {
    expect(
      sshAgentErrors({ ...params, "provider-compute": "no-infra" }, {}),
    ).toEqual([]);
  });

  it("loaded key is matched by type and body, ignoring comments", () => {
    const runFn: Runner = (args, extraEnv) => {
      expect(args).toEqual(["ssh-add", "-L"]);
      expect(extraEnv).toEqual({ SSH_AUTH_SOCK: "/tmp/agent.sock" });
      return {
        ok: true,
        exit: 0,
        out: `${keyIdLine} other-comment\n`,
        err: "",
      };
    };
    expect(
      sshAgentErrors(params, { SSH_AUTH_SOCK: "/tmp/agent.sock" }, runFn),
    ).toEqual([]);
  });

  it("missing loaded key is reported", () => {
    const runFn: Runner = () => ({
      ok: true,
      exit: 0,
      out: "ssh-ed25519 AAAAother comment\n",
      err: "",
    });
    const errors = sshAgentErrors(
      params,
      { SSH_AUTH_SOCK: "/tmp/agent.sock" },
      runFn,
    );
    expect(errors.length).toBe(1);
    expect(errors[0]).toContain("not loaded");
  });

  it("dead SSH_AUTH_SOCK is reported", () => {
    const runFn: Runner = () => ({
      ok: false,
      exit: 2,
      out: "",
      err: "Error connecting to agent: No such file or directory",
    });
    const errors = sshAgentErrors(
      params,
      { SSH_AUTH_SOCK: "/tmp/dead.sock" },
      runFn,
    );
    expect(errors.length).toBe(1);
    expect(errors[0]).toContain("ssh-add -L failed");
  });
});

describe("cloudflare zone checks the configured domain", () => {
  const cfParams = () => {
    const p = withCreds(profileAlpha).params;
    return {
      "provider-dns": p["provider-dns"],
      domain: p.domain,
      "cloudflare-api-token": p["cloudflare-api-token"],
    };
  };

  it("configured zone exists", () => {
    const runFn: Runner = (args) => {
      expect(args.some((a) => a.includes(`name=${profileAlphaDomain}`))).toBe(true);
      return {
        ok: true,
        exit: 0,
        out: '{"success":true,"result":[{"id":"zone-id"}]}',
        err: "",
      };
    };
    expect(credentialErrors(cfParams(), {}, runFn)).toEqual([]);
  });

  it("configured zone is missing", () => {
    const runFn: Runner = () => ({
      ok: true,
      exit: 0,
      out: '{"success":true,"result":[]}',
      err: "",
    });
    const errors = credentialErrors(cfParams(), {}, runFn);
    expect(errors.length).toBe(1);
    expect(errors[0].detail).toContain("Cloudflare zone");
    expect(errors[0].detail).toContain(profileAlphaDomain);
  });
});

describe("domain regex table", () => {
  const paramsOf = (domain: string) =>
    withParams(withCreds(profileAlpha), { domain, once: { applications: [] } });

  it("valid", () => {
    for (const d of ["example.com", "foo.bar.example.com", "a.b", "ex-ample.co"]) {
      expect(schemaErrors(paramsOf(d)), `${d} should be valid`).toBeUndefined();
    }
  });

  it("invalid", () => {
    for (const d of ["not_a_domain", "", "no-dot", "UPPER.case", ".leading"]) {
      expect(schemaErrors(paramsOf(d)), `${d} should be invalid`).toBeDefined();
    }
  });
});

describe("image regex table", () => {
  const paramsOf = (image: string) =>
    withParams(withCreds(profileAlpha), {
      once: { applications: [{ host: `www.${profileAlphaDomain}`, image }] },
    });

  it("valid", () => {
    for (const i of [
      "ghcr.io/foo/bar",
      "ghcr.io/foo/bar:latest",
      "ghcr.io/org/path/sub:tag-1.2",
      "registry.example.com/foo/bar:v1",
    ]) {
      expect(schemaErrors(paramsOf(i)), `${i} should be valid`).toBeUndefined();
    }
  });

  it("invalid", () => {
    for (const i of [
      "nginx",
      "",
      "/no-registry",
      "Foo/Bar",
      "ghcr.io/foo/bar:bad tag",
    ]) {
      expect(schemaErrors(paramsOf(i)), `${i} should be invalid`).toBeDefined();
    }
  });
});
