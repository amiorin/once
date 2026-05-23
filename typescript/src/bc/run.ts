/** Command execution and the `exec` workflow. Ported from big-config.run. */
import { spawnSync } from "node:child_process";
import { type Opts, type WfStep, ok, toWorkflow } from "./core.js";

export interface ShellOpts {
  dir?: string;
  extraEnv?: Record<string, string>;
  out?: "string" | "inherit";
  err?: "string" | "inherit";
  continue?: boolean;
}

export interface Proc {
  exit: number;
  out: string;
  err: string;
}

/** A command runner: the single boundary between the engine and the OS. */
export type Runner = (shellOpts: ShellOpts, cmd: string | string[]) => Proc;

function tokenize(cmd: string): string[] {
  return cmd.trim().split(/\s+/).filter(Boolean);
}

/** Default runner: spawns a child process. */
export const defaultRunner: Runner = (shellOpts, cmd) => {
  const argv = Array.isArray(cmd) ? cmd : tokenize(cmd);
  const capture = shellOpts.out !== "inherit";
  const res = spawnSync(argv[0], argv.slice(1), {
    cwd: shellOpts.dir,
    env: { ...process.env, ...(shellOpts.extraEnv ?? {}) },
    encoding: "utf8",
    stdio: capture ? ["ignore", "pipe", "pipe"] : ["ignore", "inherit", "inherit"],
  });
  return {
    exit: res.status ?? (res.error ? -1 : 0),
    out: res.stdout ?? "",
    err: res.stderr ?? (res.error ? res.error.message : ""),
  };
};

let runner: Runner = defaultRunner;

/** Override the runner (used by tests). */
export function setRunner(r: Runner): void {
  runner = r;
}

const ANSI_COLOR = /\x1B\[[0-9;]+m/g;

function handleCmd(opts: Opts, proc: Proc): Opts {
  const res: Proc = {
    exit: proc.exit,
    out: typeof proc.out === "string" ? proc.out.replace(ANSI_COLOR, "") : proc.out,
    err: typeof proc.err === "string" ? proc.err.replace(ANSI_COLOR, "") : proc.err,
  };
  return {
    ...opts,
    procs: [...(opts.procs ?? []), res],
    exit: res.exit,
    err: res.err,
  };
}

function pushNil(opts: Opts): Opts {
  const cmds: (string | null)[] = opts.cmds ?? [];
  return ok({ ...opts, cmds: cmds.length ? [null, ...cmds] : [null] });
}

function runCmd(opts: Opts): Opts {
  const baseShell: ShellOpts = { ...(opts.shellOpts ?? {}), continue: true };
  const shellOpts: ShellOpts =
    opts.env === "lib"
      ? { out: "string", err: "string", ...baseShell }
      : { out: "inherit", err: "inherit", ...baseShell };
  const cmd = (opts.cmds ?? [])[0] as string;
  return handleCmd(opts, runner(shellOpts, cmd));
}

const runCmdsWorkflow = toWorkflow({
  firstStep: "big-config.run/start",
  wireFn: (step): [WfStep, string | null] => {
    switch (step) {
      case "big-config.run/start":
        return [pushNil, "big-config.run/run-cmd"];
      case "big-config.run/run-cmd":
        return [runCmd, "big-config.run/run-cmd"];
      default:
        return [(o) => o, null];
    }
  },
  nextFn: (step, _nextStep, opts) => {
    const cmds: (string | null)[] = opts.cmds ?? [];
    if (cmds.length > 1 && (opts.exit === 0 || opts.exit == null)) {
      return ["big-config.run/run-cmd", { ...opts, cmds: cmds.slice(1) }];
    }
    if (step === "big-config.run/end") return [null, opts];
    return ["big-config.run/end", opts];
  },
});

/** The `exec` workflow step: run a sequence of commands, stopping on failure. */
export function runCmds(stepFns: Parameters<typeof runCmdsWorkflow>[0], opts: Opts): Opts {
  return runCmdsWorkflow(stepFns, opts);
}
