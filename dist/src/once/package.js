/** High-level create / build / delete workflows and the `once package` entry point. */
import { ENV, WF_BUILD_FN, WF_CREATE_FN, WF_DELETE_FN, WF_DESCRIBE_FN, WF_PARAMS, WF_VALIDATE_FN, } from "big-config";
import { createWorkflow } from "big-config/core";
import { createExitStepFn, createPrintErrorStepFn } from "big-config/step-fns";
import { createWorkflowStar, mergeParams, parseArgs, printStepFn, registerWorkflow, runSteps, } from "big-config/workflow";
import { describe } from "./describe.js";
import { syncAliases, toBcOpts } from "./interop.js";
import { optsFn } from "./params.js";
import * as tools from "./tools.js";
import { validate } from "./validation.js";
const START = "io.github.bigconig-ai.once.package/start";
const END = "io.github.bigconig-ai.once.package/end";
const PIPELINE_START = "io.github.bigconig-ai.once.package/start-create-or-delete";
const PIPELINE_END = "io.github.bigconig-ai.once.package/end-create-or-delete";
export const stepFns = [
    printStepFn,
    createExitStepFn(END),
    createPrintErrorStepFn(END),
];
const TOFU_APPLY = "render tofu:init tofu:apply:-auto-approve";
const TOFU_DESTROY = "render tofu:init tofu:destroy:-auto-approve";
const ANSIBLE_RUN = "render ansible-playbook:main.yml";
for (const [step, fn] of [
    [tools.TOFU, tools.tofu],
    [tools.TOFU_SMTP, tools.tofuSmtp],
    [tools.TOFU_DNS, tools.tofuDns],
    [tools.TOFU_SMTP_POST, tools.tofuSmtpPost],
    [tools.ANSIBLE_LOCAL, tools.ansibleLocal],
    [tools.ANSIBLE, tools.ansible],
]) {
    registerWorkflow(step, fn);
}
/** Provision everything: 4 Tofu stages, then local and remote Ansible. */
export const create = createWorkflowStar({
    firstStep: PIPELINE_START,
    lastStep: PIPELINE_END,
    pipeline: [
        tools.TOFU, [TOFU_APPLY, optsFn],
        tools.TOFU_SMTP, [TOFU_APPLY, optsFn],
        tools.TOFU_DNS, [TOFU_APPLY, optsFn],
        tools.TOFU_SMTP_POST, [TOFU_APPLY, optsFn],
        tools.ANSIBLE_LOCAL, [ANSIBLE_RUN, optsFn],
        tools.ANSIBLE, [ANSIBLE_RUN, optsFn],
    ],
});
/** Render everything without applying/provisioning. */
export const build = createWorkflowStar({
    firstStep: PIPELINE_START,
    lastStep: PIPELINE_END,
    pipeline: [
        tools.TOFU, ["render", optsFn],
        tools.TOFU_SMTP, ["render", optsFn],
        tools.TOFU_DNS, ["render", optsFn],
        tools.TOFU_SMTP_POST, ["render", optsFn],
        tools.ANSIBLE_LOCAL, ["render", optsFn],
        tools.ANSIBLE, ["render", optsFn],
    ],
});
/** Tear down: reverse the 4 Tofu stages (destroy order 4 -> 3 -> 2 -> 1). */
export const deleteWorkflow = createWorkflowStar({
    firstStep: PIPELINE_START,
    lastStep: PIPELINE_END,
    pipeline: [
        tools.TOFU_SMTP_POST, [TOFU_DESTROY, optsFn],
        tools.TOFU_DNS, [TOFU_DESTROY, optsFn],
        tools.TOFU_SMTP, [TOFU_DESTROY, optsFn],
        tools.TOFU, [TOFU_DESTROY, optsFn],
    ],
});
const TOOL_OPTS_KEYS = [
    `${tools.TOFU}-opts`,
    `${tools.TOFU_SMTP}-opts`,
    `${tools.TOFU_DNS}-opts`,
    `${tools.TOFU_SMTP_POST}-opts`,
    `${tools.ANSIBLE_LOCAL}-opts`,
    `${tools.ANSIBLE}-opts`,
];
/** Run a `validate` / `describe` / `build` / `create` / `delete` workflow. */
export function once(sfns, opts0) {
    const opts = toBcOpts(opts0);
    const withFns = {
        [WF_CREATE_FN]: create,
        [WF_BUILD_FN]: build,
        [WF_DELETE_FN]: deleteWorkflow,
        [WF_VALIDATE_FN]: validate,
        [WF_DESCRIBE_FN]: describe,
        ...opts,
    };
    const merged = mergeParams(TOOL_OPTS_KEYS, opts[WF_PARAMS] ?? {}, withFns);
    const wf = createWorkflow({
        firstStep: START,
        wireFn: (step, resolvedStepFns) => step === START
            ? [(o) => runSteps(resolvedStepFns, o), END]
            : [(o) => o, undefined],
    });
    return syncAliases(wf(sfns, merged));
}
/** CLI-ready package entry point. */
export function onceStar(args, opts = {}) {
    return once(stepFns, { ...parseArgs(args), [ENV]: "shell", ...toBcOpts(opts) });
}
//# sourceMappingURL=package.js.map