/**
 * Workflow composition: the dynamic `run-steps` engine, composite pipelines,
 * argument parsing and parameter helpers. Ported from big-config.workflow.
 */
import {
  type Opts,
  type StepFn,
  type WfStep,
  ok,
  toStepFn,
} from "./core.js";
import { toWorkflowStar } from "./pluggable.js";
import { renderTemplates } from "./render.js";
import { runCmds } from "./run.js";
import { keywordToName, keywordToPath } from "./utils.js";

/** Logs the command about to run, to stderr. */
export const printStepFn: StepFn = toStepFn({
  beforeF: (step, opts) => {
    if (step === "big-config.run/run-cmd" && opts.cmds?.[0]) {
      console.error(`> ${opts.cmds[0]}`);
    }
  },
});

const DEFAULT_GLOBALS = [
  "env",
  "shellOpts",
  "module",
  "profile",
  "prefix",
  "objectPrefix",
  "globals",
];

/** Copy the global options across workflows. */
export function selectGlobals(opts: Opts): Opts {
  const keys: string[] = opts.globals ?? DEFAULT_GLOBALS;
  const out: Opts = {};
  for (const k of keys) {
    if (k in opts) out[k] = opts[k];
  }
  return out;
}

function resolveFn(opts: Opts, key: string, dflt?: unknown): any {
  const f = opts[key];
  if (f == null) {
    if (dflt === undefined) throw new Error(`\`${key}\` not defined`);
    return dflt;
  }
  return f;
}

/**
 * The dynamic "workflow of workflows": runs the steps listed under `opts.steps`.
 * `create` / `delete` are entire subworkflows; the rest are ordinary steps.
 */
export function runSteps(stepFns: StepFn[], opts: Opts): Opts {
  const globalsOpts = selectGlobals(opts);
  const createOpts = { ...(opts.createOpts ?? {}), ...globalsOpts };
  const deleteOpts = { ...(opts.deleteOpts ?? {}), ...globalsOpts };
  let cur: Opts = { ...opts };
  const queue: string[] = (opts.steps ?? []).map((s: string) =>
    s.includes("/") ? s : `big-config.workflow/${s}`,
  );
  const twoArgOk = (_sf: StepFn[], o: Opts) => ok(o);
  const wf = toWorkflowStar({
    firstStep: "big-config.workflow/start",
    lastStep: "big-config.workflow/end",
    wireFn: (step, sfns): [WfStep, string | null] => {
      switch (step) {
        case "big-config.workflow/start":
          return [ok, null];
        case "big-config.workflow/render":
          return [(o) => renderTemplates(sfns, o), null];
        case "big-config.workflow/exec":
          return [(o) => runCmds(sfns, o), null];
        case "big-config.workflow/create":
          return [(o) => resolveFn(opts, "createFn")(sfns, o), null];
        case "big-config.workflow/delete":
          return [(o) => resolveFn(opts, "deleteFn")(sfns, o), null];
        case "big-config.workflow/validate":
          return [(o) => resolveFn(opts, "validateFn", twoArgOk)(sfns, o), null];
        case "big-config.workflow/describe":
          return [(o) => resolveFn(opts, "describeFn", twoArgOk)(sfns, o), null];
        default:
          return [(o) => o, null];
      }
    },
    nextFn: (step, _nextStep, o) => {
      if (
        step === "big-config.workflow/create" ||
        step === "big-config.workflow/delete"
      ) {
        cur = { ...cur, exit: o.exit, err: o.err, [step]: [...(cur[step] ?? []), o] };
      } else {
        cur = o;
      }
      if (step === "big-config.workflow/end") return [null, cur];
      if (typeof o.exit === "number" && o.exit > 0) {
        return ["big-config.workflow/end", cur];
      }
      const next = queue.shift();
      if (next) {
        const stepOpts =
          next === "big-config.workflow/create"
            ? createOpts
            : next === "big-config.workflow/delete"
              ? deleteOpts
              : cur;
        return [next, stepOpts];
      }
      return ["big-config.workflow/end", cur];
    },
  });
  return wf(stepFns, cur);
}

const PARSE_ARGS_STEPS = new Set([
  "lock",
  "git-check",
  "render",
  "create",
  "delete",
  "validate",
  "describe",
  "exec",
  "git-push",
  "unlock-any",
]);

/** Normalize a string or array of CLI args into `{ steps, cmds }`. */
export function parseArgs(input: string | string[]): {
  steps: string[];
  cmds: string[];
} {
  const xs: string[] =
    typeof input === "string"
      ? input.trim().split(/\s+/).filter(Boolean)
      : [...input];
  const steps: string[] = [];
  const cmds: string[] = [];
  let i = 0;
  while (i < xs.length) {
    const token = xs[i];
    if (PARSE_ARGS_STEPS.has(token)) {
      steps.push(token);
      i++;
      continue;
    }
    if (token === "--") {
      const rest = xs.slice(i + 1);
      if (rest.length === 0) {
        throw new Error("-- cannot be without a command");
      }
      if (!steps.includes("exec")) steps.push("exec");
      cmds.push(rest.join(" "));
      break;
    }
    if (!steps.includes("exec")) steps.push("exec");
    cmds.push(token.replace(/:/g, " "));
    i++;
  }
  return { steps, cmds };
}

function hashHex(s: string): string {
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  }
  return (h >>> 0).toString(16);
}

/**
 * Derive a deterministic `prefix` / `objectPrefix` from `firstStep` and the
 * profile, so a workflow always resolves to the same directory.
 */
export function newPrefix(opts: Opts, firstStep: string): Opts {
  const prefix: string = opts.prefix ?? ".dist";
  const objectPrefix: string = opts.objectPrefix ?? "tofu";
  const profile: string = opts.profile ?? "default";
  const dirs = prefix.split("/");
  const objectDirs = objectPrefix.split("/");
  const last = dirs[dirs.length - 1];
  const profileFound = last.startsWith(profile);
  const prevHash = profileFound ? (last.split("-").pop() ?? "") : "";
  const baseDirs = profileFound ? dirs.slice(0, -1) : dirs;
  const baseObjectDirs = profileFound ? objectDirs.slice(0, -1) : objectDirs;
  const suffix = hashHex(firstStep + prevHash);
  return {
    ...opts,
    prefix: [...baseDirs, `${profile}-${suffix}`].join("/"),
    objectPrefix: [...baseObjectDirs, `${profile}-${suffix}`].join("/"),
  };
}

/** Resolve the directory of a previous workflow, to extract its outputs. */
export function path(opts: Opts, name: string): string {
  return `${opts.prefix ?? ".dist"}/${keywordToPath(name)}`;
}

/**
 * Prepare `opts`: compute target directory / object, merge params into every
 * template, and set the shell working directory.
 */
export function prepare(base: Opts, overrides: Opts): Opts {
  const name = base.name;
  if (name == null) throw new Error("Argument name is nil");
  if (overrides == null) throw new Error("Argument overrides is nil");
  const merged = { ...base, ...overrides };
  const prefix: string | undefined = merged.prefix;
  const objectPrefix: string | undefined = merged.objectPrefix;
  const pathFn: (o: Opts) => string =
    merged.pathFn ?? ((o) => `${prefix ?? ".dist"}/${keywordToPath(o.name)}`);
  const objectFn: (o: Opts) => string =
    merged.objectFn ??
    ((o) => `${objectPrefix ?? "tofu"}/${keywordToName(o.name)}`);
  const dir = pathFn(merged);
  const object = objectFn(merged);
  const params: Record<string, any> = merged.params ?? {};
  const templates = (merged.templates ?? []).map((t: any) => ({
    ...t,
    ...params,
    targetDir: dir,
    "target-object": object,
  }));
  return {
    ...merged,
    templates,
    shellOpts: { ...(merged.shellOpts ?? {}), dir },
  };
}

/** Merge package params into per-tool create/delete opts. */
export function mergeParams(
  tools: string[],
  params: Record<string, any>,
  opts: Opts,
): Opts {
  let result: Opts = { ...opts };
  for (const tool of tools) {
    for (const slot of ["createOpts", "deleteOpts"]) {
      const toolOpts = result[slot]?.[tool];
      if (toolOpts?.params) {
        result = {
          ...result,
          [slot]: {
            ...result[slot],
            [tool]: { ...toolOpts, params: { ...params, ...toolOpts.params } },
          },
        };
      }
    }
  }
  return result;
}

const BC_PAR_PREFIX = "BC_PAR_";

/** Override params from `BC_PAR_*` environment variables. */
export function readBcPars(
  opts: Opts,
  env: NodeJS.ProcessEnv = process.env,
): Opts {
  const fromEnv: Record<string, string> = {};
  for (const [k, v] of Object.entries(env)) {
    if (k.startsWith(BC_PAR_PREFIX) && v !== undefined) {
      const key = k
        .slice(BC_PAR_PREFIX.length)
        .toLowerCase()
        .replace(/_/g, "-")
        .replace(/\./g, "-");
      fromEnv[key] = v;
    }
  }
  return { ...opts, params: { ...(opts.params ?? {}), ...fromEnv } };
}

export interface PipelineEntry {
  /** Step keyword, e.g. `tools/tofu`. Used to store per-step opts/results. */
  name: string;
  /** The tool workflow function. */
  fn: (stepFns: StepFn[], opts: Opts) => Opts;
  /** CLI-style arguments, e.g. `render tofu:init tofu:apply:-auto-approve`. */
  args: string;
  /** Adapts outputs from previous steps into this step's params. */
  optsFn?: (opts: Opts) => Opts;
}

/** Create a composite workflow from a pipeline of tool workflows. */
export function toCompWorkflow({
  firstStep,
  lastStep,
  pipeline,
}: {
  firstStep: string;
  lastStep?: string;
  pipeline: PipelineEntry[];
}): (stepFns: StepFn[], opts: Opts) => Opts {
  return (stepFns, opts) => {
    const last = lastStep ?? `${firstStep.split("/")[0]}/end`;
    const globalsOpts = newPrefix(selectGlobals(opts), firstStep);
    const stepInfo = new Map<
      string,
      { stepOpts: Opts; optsFn: (o: Opts) => Opts }
    >();
    for (const entry of pipeline) {
      const { steps, cmds } = parseArgs(entry.args);
      const override = opts[`${entry.name}-opts`] ?? {};
      stepInfo.set(entry.name, {
        stepOpts: { steps, cmds, ...globalsOpts, ...override },
        optsFn: entry.optsFn ?? ((o) => o),
      });
    }
    const order = [firstStep, ...pipeline.map((e) => e.name), last];
    const nextByStep = new Map<string, string>();
    for (let i = 0; i < order.length - 1; i++) {
      nextByStep.set(order[i], order[i + 1]);
    }
    const fnByStep = new Map<string, WfStep>();
    fnByStep.set(firstStep, ok);
    fnByStep.set(last, (o) => o);
    for (const e of pipeline) {
      fnByStep.set(e.name, (o) => e.fn(stepFns, o));
    }
    const stepsSet = new Set(pipeline.map((e) => e.name));
    let cur: Opts = { ...opts };
    const wf = toWorkflowStar({
      firstStep,
      lastStep: last,
      wireFn: (step): [WfStep, string | null] => [
        fnByStep.get(step) ?? ((o) => o),
        nextByStep.get(step) ?? null,
      ],
      nextFn: (step, nextStep, o) => {
        if (stepsSet.has(step)) {
          cur = { ...cur, exit: o.exit, err: o.err, [step]: o };
        } else {
          cur = o;
        }
        if (step === last) return [null, cur];
        if (typeof o.exit === "number" && o.exit > 0) return [last, cur];
        const info = nextStep ? stepInfo.get(nextStep) : undefined;
        if (info) return [nextStep, info.optsFn(info.stepOpts)];
        return [nextStep, cur];
      },
    });
    return wf(stepFns, opts);
  };
}
