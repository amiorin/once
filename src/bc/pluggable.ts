/**
 * Pluggable workflow: extend or override step behavior via a registry.
 * Ported from big-config.pluggable.
 */
import {
  type Opts,
  type StepFn,
  type WfStep,
  type WorkflowSpec,
  toWorkflow,
} from "./core.js";

export type HandleStepFn = (
  f: WfStep,
  step: string,
  stepFns: StepFn[],
  opts: Opts,
) => Opts;

const handlers = new Map<string, HandleStepFn>();

/** Register a custom handler for a step keyword. */
export function registerStep(step: string, fn: HandleStepFn): void {
  handlers.set(step, fn);
}

/** Dispatch a step: a registered handler, or the default `f(opts)`. */
export function handleStep(
  f: WfStep,
  step: string,
  stepFns: StepFn[],
  opts: Opts,
): Opts {
  const handler = handlers.get(step);
  return handler ? handler(f, step, stepFns, opts) : f(opts);
}

/** Like `toWorkflow`, but every step is routed through `handleStep`. */
export function toWorkflowStar(
  spec: WorkflowSpec,
): (stepFns: StepFn[], opts: Opts) => Opts {
  return toWorkflow({
    ...spec,
    wireFn: (step, stepFns) => {
      const [f, nextStep] = spec.wireFn(step, stepFns);
      return [(opts: Opts) => handleStep(f, step, stepFns, opts), nextStep];
    },
  });
}
