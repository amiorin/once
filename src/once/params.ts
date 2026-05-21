/** Parameter extraction from OpenTofu outputs. */
import { spawnSync } from "node:child_process";
import type { Opts } from "../bc/core.js";
import { newPrefix, path, readBcPars } from "../bc/workflow.js";

const START_STEP = "io.github.amiorin.once.package/start-create-or-delete";

function tofuOutput(dir: string): any {
  const res = spawnSync("tofu", ["output", "--json"], {
    cwd: dir,
    encoding: "utf8",
  });
  if (res.error || res.status !== 0) {
    throw new Error(res.stderr || "tofu output failed");
  }
  return JSON.parse(res.stdout);
}

/** Merge the IP (and other compute outputs) from the `tofu` stage. */
export function tofuParams(opts: Opts): Opts {
  const dir = path(opts, "io.github.amiorin.once.tools/tofu");
  let value: Record<string, any>;
  try {
    value = tofuOutput(dir)?.params?.value ?? { ip: "192.168.0.1" };
  } catch {
    value = { ip: "192.168.0.1" };
  }
  return { ...opts, params: { ...(opts.params ?? {}), ...value } };
}

/** Merge the SMTP records / domain id from the `tofu-smtp` stage. */
export function tofuSmtpParams(opts: Opts): Opts {
  const dir = path(opts, "io.github.amiorin.once.tools/tofu-smtp");
  let value: Record<string, any>;
  try {
    value =
      tofuOutput(dir)?.params?.value ?? {
        id: "domain-id-not-defined",
        records: [],
      };
  } catch {
    value = { id: "domain-id-not-defined", records: [] };
  }
  return { ...opts, params: { ...(opts.params ?? {}), ...value } };
}

/** Compose env overrides with the SMTP and compute Tofu outputs. */
export function optsFn(opts: Opts): Opts {
  return tofuParams(tofuSmtpParams(readBcPars(opts)));
}

/** `optsFn` after stamping the deterministic prefix for the create/delete workflow. */
export function onceOpts(opts: Opts): Opts {
  return optsFn(newPrefix(opts, START_STEP));
}
