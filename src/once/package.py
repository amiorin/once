"""High-level create/build/delete workflows and the ``once package`` entry point."""
from __future__ import annotations

from big_config import ENV
from big_config import workflow as bc_workflow
from big_config.core import Opts, StepFn, ok, workflow
from big_config.step_fns import exit_step_fn, print_error_step_fn

from . import tools
from .describe import describe
from .interop import PARAMS, sync_aliases, to_bc_opts
from .params import opts_fn
from .validation import validate

START = "io.github.bigconfig-ai.once.package/start"
END = "io.github.bigconfig-ai.once.package/end"
PIPELINE_START = "io.github.bigconfig-ai.once.package/start-create-or-delete"
PIPELINE_END = "io.github.bigconfig-ai.once.package/end-create-or-delete"

step_fns: list[StepFn] = [bc_workflow.print_step_fn, exit_step_fn(END), print_error_step_fn(END)]

TOFU_APPLY = "render tofu:init tofu:apply:-auto-approve"
TOFU_DESTROY = "render tofu:init tofu:destroy:-auto-approve"
ANSIBLE_RUN = "render ansible-playbook:main.yml"

PIPELINE_TOOLS = [
    (tools.TOFU, tools.tofu),
    (tools.TOFU_SMTP, tools.tofu_smtp),
    (tools.TOFU_DNS, tools.tofu_dns),
    (tools.TOFU_SMTP_POST, tools.tofu_smtp_post),
    (tools.ANSIBLE_LOCAL, tools.ansible_local),
    (tools.ANSIBLE, tools.ansible),
]

for step, fn in PIPELINE_TOOLS:
    bc_workflow.register_workflow_step(step, fn)

create = bc_workflow.workflow_star(
    {
        "first_step": PIPELINE_START,
        "last_step": PIPELINE_END,
        "pipeline": [
            tools.TOFU,
            [TOFU_APPLY, opts_fn],
            tools.TOFU_SMTP,
            [TOFU_APPLY, opts_fn],
            tools.TOFU_DNS,
            [TOFU_APPLY, opts_fn],
            tools.TOFU_SMTP_POST,
            [TOFU_APPLY, opts_fn],
            tools.ANSIBLE_LOCAL,
            [ANSIBLE_RUN, opts_fn],
            tools.ANSIBLE,
            [ANSIBLE_RUN, opts_fn],
        ],
    }
)

build = bc_workflow.workflow_star(
    {
        "first_step": PIPELINE_START,
        "last_step": PIPELINE_END,
        "pipeline": [
            tools.TOFU,
            ["render", opts_fn],
            tools.TOFU_SMTP,
            ["render", opts_fn],
            tools.TOFU_DNS,
            ["render", opts_fn],
            tools.TOFU_SMTP_POST,
            ["render", opts_fn],
            tools.ANSIBLE_LOCAL,
            ["render", opts_fn],
            tools.ANSIBLE,
            ["render", opts_fn],
        ],
    }
)

delete_workflow = bc_workflow.workflow_star(
    {
        "first_step": PIPELINE_START,
        "last_step": PIPELINE_END,
        "pipeline": [
            tools.TOFU_SMTP_POST,
            [TOFU_DESTROY, opts_fn],
            tools.TOFU_DNS,
            [TOFU_DESTROY, opts_fn],
            tools.TOFU_SMTP,
            [TOFU_DESTROY, opts_fn],
            tools.TOFU,
            [TOFU_DESTROY, opts_fn],
        ],
    }
)

TOOL_OPTS_KEYS = [
    f"{tools.TOFU}-opts",
    f"{tools.TOFU_SMTP}-opts",
    f"{tools.TOFU_DNS}-opts",
    f"{tools.TOFU_SMTP_POST}-opts",
    f"{tools.ANSIBLE_LOCAL}-opts",
    f"{tools.ANSIBLE}-opts",
]


def once(sfns: list[StepFn], opts: Opts) -> Opts:
    """Run a validate / describe / build / create / delete workflow."""
    opts = to_bc_opts(opts)
    with_fns: Opts = {
        bc_workflow.CREATE_FN: create,
        bc_workflow.BUILD_FN: build,
        bc_workflow.DELETE_FN: delete_workflow,
        bc_workflow.VALIDATE_FN: validate,
        bc_workflow.DESCRIBE_FN: describe,
        **opts,
    }
    merged = bc_workflow.merge_params(TOOL_OPTS_KEYS, opts.get(PARAMS) or {}, with_fns)

    def wire(step: str, resolved_step_fns: list[StepFn]):
        if step == START:
            return (lambda o: bc_workflow.run_steps(resolved_step_fns, o)), END
        return (lambda o: o), None

    wf = workflow({"first_step": START, "wire_fn": wire})
    return sync_aliases(wf(sfns, merged))


def once_star(args: str | list[str], opts: Opts | None = None) -> Opts:
    """CLI-ready package entry point."""
    parsed = bc_workflow.parse_args(args)
    return once(step_fns, {**parsed, ENV: "shell", **to_bc_opts(opts or {})})


# TypeScript-style aliases.
deleteWorkflow = delete_workflow
onceStar = once_star
