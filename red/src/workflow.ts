import { parName } from "red/cli";
import * as dryRun from "red/dry-run";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, workflow, type Opts, type StepFn } from "red/workflow";
import { readPars } from "red/cli";
import { dirname } from "node:path";
import * as github from "./github.ts";
import * as tools from "./tools.ts";
import { secretErrors, stateErrors } from "./validate.ts";

async function stateOutput(opts: Opts, tool: string): Promise<Record<string, unknown> | undefined> {
  try {
    const result = await tofu.outputs(tools.toolDir(opts, tool), tools.backendCredentialEnv(opts));
    return result.params as Record<string, unknown> | undefined;
  } catch {
    return undefined;
  }
}

async function adoptExistingState(opts: Opts): Promise<Opts> {
  const [compute, smtp] = await Promise.all([stateOutput(opts, "tofu-compute"), stateOutput(opts, "tofu-smtp")]);
  return {
    ...opts,
    ...(compute ?? {}), ...(smtp ?? {}),
    ...(compute ? { "once/compute-params": compute } : {}),
    ...(smtp ? { "once/smtp-params": smtp } : {}),
  };
}

// Attach the keys ansible-remote installs and the github step publishes.
//
// Generating them is a create-time side effect, so a build or a dry-run takes
// fixed placeholders instead: a fresh key rendered into the artifact would make
// the build nondeterministic and break byte parity between the colours.
async function withDeployKeys(opts: Opts, real: boolean): Promise<Opts> {
  if (real && opts["red/event"] === "create") {
    const [keys, err] = await github.generateKeys(opts);
    if (err) return { ...opts, "red/exit": 1, "red/err": err };
    return {
      ...opts,
      "red/exit": 0,
      "once/deploy-keys": keys,
      ...(keys.length ? { "once/key-dir": dirname(String(keys[0]!.privateFile)) } : {}),
    };
  }
  return { ...opts, "red/exit": 0, "once/deploy-keys": github.placeholderKeys(opts) };
}

export async function startStep(
  original: Opts,
  env: Record<string, string | undefined> = process.env,
): Promise<Opts> {
  const opts = readPars({ "compute-prevent-destroy": true, ...original }, env);
  const event = opts["red/event"];
  const real = !opts["red/dry-run"];
  const lifecycle = event === "create" || event === "delete";
  const errors = [
    ...stateErrors(opts),
    ...(real && lifecycle ? secretErrors(opts) : []),
    ...(real && event === "delete" && opts["compute-prevent-destroy"]
      ? [`compute destruction is protected; set ${parName("compute-prevent-destroy")}=false to delete`]
      : []),
  ];
  if (errors.length) return { ...opts, "red/exit": 2, "red/err": errors.join("\n") };
  if (real && event === "delete") return { ...(await adoptExistingState(opts)), "red/exit": 0 };
  return withDeployKeys(opts, real);
}

export async function ansibleCleanupStep(opts: Opts): Promise<Opts> {
  return tools.ansibleRemoteStep(await tools.ansibleLocalStep(opts));
}

export const tofuSteps = ["once/tofu-compute", "once/tofu-smtp", "once/tofu-dns", "once/tofu-smtp-post"];
export const sideEffectingSteps = [...tofuSteps, "once/ansible-local", "once/ansible-remote", "once/ansible-cleanup", "once/github"];

export function wireFn(step: string, runOpts: Opts) {
  if (runOpts["red/event"] === "delete") {
    switch (step) {
      // Revoking runs before anything is destroyed: a withdrawn credential
      // against a live host is a loud, recoverable broken deploy, while a live
      // credential against a destroyed host is silent. It needs no key
      // material, so it also works when the box is already gone.
      case "once/start": return [startStep, "once/github"] as const;
      case "once/github": return [github.githubStep, "once/ansible-cleanup"] as const;
      case "once/ansible-cleanup": return [ansibleCleanupStep, "once/tofu-smtp-post"] as const;
      case "once/tofu-smtp-post": return [tools.tofuSmtpPostStep, "once/tofu-dns"] as const;
      case "once/tofu-dns": return [tools.tofuDnsStep, "once/tofu-smtp", "once/tofu-compute"] as const;
      case "once/tofu-smtp": return [tools.tofuSmtpStep] as const;
      case "once/tofu-compute": return [tools.tofuComputeStep] as const;
    }
  } else {
    switch (step) {
      case "once/start": return [startStep, "once/tofu-compute", "once/tofu-smtp"] as const;
      case "once/tofu-compute": return [tools.tofuComputeStep, "once/tofu-dns"] as const;
      case "once/tofu-smtp": return [tools.tofuSmtpStep, "once/tofu-dns"] as const;
      case "once/tofu-dns": return [tools.tofuDnsStep, "once/tofu-smtp-post"] as const;
      case "once/tofu-smtp-post": return [tools.tofuSmtpPostStep, "once/ansible-local", "once/ansible-remote"] as const;
      case "once/ansible-local": return [tools.ansibleLocalStep] as const;
      // Publishing follows the remote stage, not the local one: the credentials
      // describe a configured host, and a workstation-side failure should not
      // gate them.
      case "once/ansible-remote": return [tools.ansibleRemoteStep, "once/github"] as const;
      case "once/github": return [github.githubStep] as const;
    }
  }
}

export function backendAdvice(tool: string) {
  const dirFn = (opts: Opts) => tools.toolDir(opts, tool);
  const stateKey = (opts: Opts) => `${opts.profile ?? "default"}/${tool}.tfstate`;
  return tofu.backends(
    (opts) => String(opts["provider-backend"] ?? "local"),
    {
      local: tofu.localBackendAdvice(dirFn),
      s3: tofu.s3BackendAdvice(dirFn, (opts) => ({ bucket: opts["s3-bucket"], key: stateKey(opts), region: opts["s3-region"] })),
      r2: tofu.r2BackendAdvice(dirFn, (opts) => ({ bucket: opts["r2-bucket"], key: stateKey(opts), endpoint: opts["r2-endpoint"] })),
    },
  );
}

function createWorkflow() {
  let result = workflow({ start: "once/start", wireFn });
  for (const tool of tofuSteps.map((step) => step.slice("once/".length))) {
    result = adviceAdd(result, `once/${tool}`, "before", "once.workflow/backend", backendAdvice(tool));
  }
  result = progress.advise(result);
  return dryRun.advise(result, sideEffectingSteps);
}

export const onceWorkflow = createWorkflow();
