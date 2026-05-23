/**
 * Workflow engine: threads an `opts` map through a series of steps.
 * Ported from big-config.core.
 */

export type Opts = Record<string, any>;

/** A step function: `(opts) => opts`. */
export type WfStep = (opts: Opts) => Opts;

/** Middleware wrapping a step: `(next, step, opts) => opts`. */
export type StepFn = (
  next: (step: string, opts: Opts) => Opts,
  step: string,
  opts: Opts,
) => Opts;

/** Return opts with a successful exit status. */
export function ok(opts?: Opts): Opts {
  return opts ? { ...opts, exit: 0, err: null } : { exit: 0, err: null };
}

/** Pick the next step based on the exit status. Used inside a `nextFn`. */
export function choice({
  onSuccess,
  onFailure,
  opts,
}: {
  onSuccess: string | null;
  onFailure: string | null;
  opts: Opts;
}): [string | null, Opts] {
  return opts.exit === 0 ? [onSuccess, opts] : [onFailure, opts];
}

type SideFn = (step: string, opts: Opts) => void;

/**
 * Build a step-fn from `beforeF` / `afterF` side-effect functions.
 * `afterF: "same"` reuses `beforeF`.
 */
export function toStepFn({
  beforeF,
  afterF,
}: {
  beforeF?: SideFn;
  afterF?: SideFn | "same";
}): StepFn {
  if (!beforeF && !afterF) {
    throw new Error("At least one f needs to be provided");
  }
  return (next, step, opts) => {
    if (beforeF) beforeF(step, opts);
    const result = next(step, opts);
    const after = afterF === "same" ? beforeF : afterF;
    if (after) after(step, result);
    return result;
  };
}

function namespaceOf(kw: string): string {
  const i = kw.indexOf("/");
  return i >= 0 ? kw.slice(0, i) : "";
}

function deriveLastStep(firstStep: string): string {
  const ns = namespaceOf(firstStep);
  return ns ? `${ns}/end` : "end";
}

function compose(
  stepFns: StepFn[],
  f: WfStep,
): (step: string, opts: Opts) => Opts {
  const innermost = (_step: string, opts: Opts) => f(opts);
  return stepFns.reduce<(step: string, opts: Opts) => Opts>(
    (acc, mw) => (step, opts) => mw(acc, step, opts),
    innermost,
  );
}

function tryF(
  f: (step: string, opts: Opts) => Opts,
  step: string,
  opts: Opts,
): Opts {
  try {
    return f(step, opts);
  } catch (e: any) {
    return {
      ...opts,
      ...(e && typeof e === "object" && e.data ? e.data : {}),
      err: e instanceof Error ? e.message : String(e),
      exit: 1,
      stackTrace: e instanceof Error ? (e.stack ?? "") : "",
    };
  }
}

export interface WorkflowSpec {
  firstStep: string;
  lastStep?: string;
  wireFn: (step: string, stepFns: StepFn[]) => [WfStep, string | null];
  nextFn?: (
    step: string,
    nextStep: string | null,
    opts: Opts,
  ) => [string | null, Opts];
}

function resolveNextFn(
  nextFn: WorkflowSpec["nextFn"],
  lastStep: string,
): NonNullable<WorkflowSpec["nextFn"]> {
  if (nextFn) return nextFn;
  return (_step, nextStep, opts) =>
    nextStep
      ? choice({ onSuccess: nextStep, onFailure: lastStep, opts })
      : [null, opts];
}

/**
 * Create a workflow function `(stepFns, opts) => opts`.
 * Each step is resolved via `wireFn`, wrapped in the step-fn middleware,
 * and transitions are driven by `nextFn` (or a default success/failure choice).
 */
export function toWorkflow(
  spec: WorkflowSpec,
): (stepFns: StepFn[], opts: Opts) => Opts {
  const lastStep = spec.lastStep ?? deriveLastStep(spec.firstStep);
  return (stepFns, opts) => {
    if (opts == null) throw new Error("opts should never be nil");
    const resolved = stepFns.slice().reverse();
    let step: string | null = spec.firstStep;
    let cur = opts;
    while (step !== null) {
      const [f, nextStep] = spec.wireFn(step, resolved);
      cur = tryF(compose(resolved, f), step, cur);
      if (cur == null) throw new Error(`opts must never be nil (step ${step})`);
      const exit = cur.exit;
      if (typeof exit !== "number" || !Number.isInteger(exit) || exit < 0) {
        throw new Error("exit must be a natural number");
      }
      const [ns, nopts] = resolveNextFn(spec.nextFn, lastStep)(
        step,
        nextStep,
        cur,
      );
      if (ns === null) return nopts;
      step = ns;
      cur = nopts;
    }
    return cur;
  };
}
