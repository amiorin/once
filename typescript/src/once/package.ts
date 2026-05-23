/** High-level create / delete workflows and the `once` entry point. */
import { type Opts, type StepFn, toWorkflow } from "../bc/core.js";
import { exitStepFn, printErrorStepFn } from "../bc/step-fns.js";
import {
  type PipelineEntry,
  mergeParams,
  parseArgs,
  printStepFn,
  runSteps,
  toCompWorkflow,
} from "../bc/workflow.js";
import { describe } from "./describe.js";
import { optsFn } from "./params.js";
import * as tools from "./tools.js";
import { validate } from "./validation.js";

const START = "io.github.amiorin.once.package/start";
const END = "io.github.amiorin.once.package/end";
const PIPELINE_START = "io.github.amiorin.once.package/start-create-or-delete";
const PIPELINE_END = "io.github.amiorin.once.package/end-create-or-delete";

export const stepFns: StepFn[] = [
  printStepFn,
  exitStepFn(END),
  printErrorStepFn(END),
];

const TOFU_APPLY = "render tofu:init tofu:apply:-auto-approve";
const TOFU_DESTROY = "render tofu:init tofu:destroy:-auto-approve";
const ANSIBLE_RUN = "render ansible-playbook:main.yml";

/** Provision everything: 4 Tofu stages, then local and remote Ansible. */
export const create = toCompWorkflow({
  firstStep: PIPELINE_START,
  lastStep: PIPELINE_END,
  pipeline: [
    { name: "tools/tofu", fn: tools.tofu, args: TOFU_APPLY, optsFn },
    { name: "tools/tofu-smtp", fn: tools.tofuSmtp, args: TOFU_APPLY, optsFn },
    { name: "tools/tofu-dns", fn: tools.tofuDns, args: TOFU_APPLY, optsFn },
    {
      name: "tools/tofu-smtp-post",
      fn: tools.tofuSmtpPost,
      args: TOFU_APPLY,
      optsFn,
    },
    {
      name: "tools/ansible-local",
      fn: tools.ansibleLocal,
      args: ANSIBLE_RUN,
      optsFn,
    },
    { name: "tools/ansible", fn: tools.ansible, args: ANSIBLE_RUN, optsFn },
  ] satisfies PipelineEntry[],
});

/** Tear down: reverse the 4 Tofu stages (destroy order 4 -> 3 -> 2 -> 1). */
export const deleteWorkflow = toCompWorkflow({
  firstStep: PIPELINE_START,
  lastStep: PIPELINE_END,
  pipeline: [
    {
      name: "tools/tofu-smtp-post",
      fn: tools.tofuSmtpPost,
      args: TOFU_DESTROY,
      optsFn,
    },
    { name: "tools/tofu-dns", fn: tools.tofuDns, args: TOFU_DESTROY, optsFn },
    { name: "tools/tofu-smtp", fn: tools.tofuSmtp, args: TOFU_DESTROY, optsFn },
    { name: "tools/tofu", fn: tools.tofu, args: TOFU_DESTROY, optsFn },
  ] satisfies PipelineEntry[],
});

const TOOL_OPTS_KEYS = [
  "tools/tofu-opts",
  "tools/tofu-smtp-opts",
  "tools/tofu-dns-opts",
  "tools/tofu-smtp-post-opts",
  "tools/ansible-opts",
];

/** Run a `validate` / `describe` / `create` / `delete` workflow. */
export function once(sfns: StepFn[], opts: Opts): Opts {
  const withFns: Opts = {
    createFn: create,
    deleteFn: deleteWorkflow,
    validateFn: validate,
    describeFn: describe,
    ...opts,
  };
  const merged = mergeParams(TOOL_OPTS_KEYS, opts.params ?? {}, withFns);
  const wf = toWorkflow({
    firstStep: START,
    wireFn: (step, resolvedStepFns) =>
      step === START
        ? [(o) => runSteps(resolvedStepFns, o), END]
        : [(o) => o, null],
  });
  return wf(sfns, merged);
}

/** CLI-ready entry point: `once* ["create"]`, `once* ["validate"]`, etc. */
export function onceStar(args: string | string[], opts: Opts = {}): Opts {
  const { steps, cmds } = parseArgs(args);
  return once(stepFns, { steps, cmds, env: "shell", ...opts });
}
