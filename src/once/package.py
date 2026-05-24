"""High-level create/delete workflows and the ``once`` entry point."""
from __future__ import annotations

from .bc.core import Opts, StepFn, to_workflow
from .bc.step_fns import exit_step_fn, print_error_step_fn
from .bc.workflow import PipelineEntry, merge_params, parse_args, print_step_fn, run_steps, to_comp_workflow
from .describe import describe
from .params import opts_fn
from .validation import validate
from . import tools

START = "io.github.amiorin.once.package/start"
END = "io.github.amiorin.once.package/end"
PIPELINE_START = "io.github.amiorin.once.package/start-create-or-delete"
PIPELINE_END = "io.github.amiorin.once.package/end-create-or-delete"

step_fns: list[StepFn] = [print_step_fn, exit_step_fn(END), print_error_step_fn(END)]

TOFU_APPLY = "render tofu:init tofu:apply:-auto-approve"
TOFU_DESTROY = "render tofu:init tofu:destroy:-auto-approve"
ANSIBLE_RUN = "render ansible-playbook:main.yml"

create = to_comp_workflow(
    first_step=PIPELINE_START,
    last_step=PIPELINE_END,
    pipeline=[
        PipelineEntry("tools/tofu", tools.tofu, TOFU_APPLY, opts_fn),
        PipelineEntry("tools/tofu-smtp", tools.tofu_smtp, TOFU_APPLY, opts_fn),
        PipelineEntry("tools/tofu-dns", tools.tofu_dns, TOFU_APPLY, opts_fn),
        PipelineEntry("tools/tofu-smtp-post", tools.tofu_smtp_post, TOFU_APPLY, opts_fn),
        PipelineEntry("tools/ansible-local", tools.ansible_local, ANSIBLE_RUN, opts_fn),
        PipelineEntry("tools/ansible", tools.ansible, ANSIBLE_RUN, opts_fn),
    ],
)

delete_workflow = to_comp_workflow(
    first_step=PIPELINE_START,
    last_step=PIPELINE_END,
    pipeline=[
        PipelineEntry("tools/tofu-smtp-post", tools.tofu_smtp_post, TOFU_DESTROY, opts_fn),
        PipelineEntry("tools/tofu-dns", tools.tofu_dns, TOFU_DESTROY, opts_fn),
        PipelineEntry("tools/tofu-smtp", tools.tofu_smtp, TOFU_DESTROY, opts_fn),
        PipelineEntry("tools/tofu", tools.tofu, TOFU_DESTROY, opts_fn),
    ],
)

TOOL_OPTS_KEYS = [
    "tools/tofu-opts",
    "tools/tofu-smtp-opts",
    "tools/tofu-dns-opts",
    "tools/tofu-smtp-post-opts",
    "tools/ansible-opts",
]


def once(sfns: list[StepFn], opts: Opts) -> Opts:
    """Run a validate / describe / create / delete workflow."""
    with_fns: Opts = {"createFn": create, "deleteFn": delete_workflow, "validateFn": validate, "describeFn": describe, **opts}
    merged = merge_params(TOOL_OPTS_KEYS, opts.get("params") or {}, with_fns)

    def wire(step: str, resolved_step_fns: list[StepFn]):
        if step == START:
            return (lambda o: run_steps(resolved_step_fns, o)), END
        return (lambda o: o), None

    wf = to_workflow(first_step=START, wire_fn=wire)
    return wf(sfns, merged)


def once_star(args: str | list[str], opts: Opts | None = None) -> Opts:
    """CLI-ready entry point."""
    parsed = parse_args(args)
    return once(step_fns, {**parsed, "env": "shell", **(opts or {})})


# TypeScript-style aliases.
deleteWorkflow = delete_workflow
onceStar = once_star
