"""Pluggable workflow step dispatch."""
from __future__ import annotations

from typing import Callable

from .core import Opts, StepFn, WfStep, to_workflow

HandleStepFn = Callable[[WfStep, str, list[StepFn], Opts], Opts]

_handlers: dict[str, HandleStepFn] = {}


def register_step(step: str, fn: HandleStepFn) -> None:
    """Register a custom handler for a step keyword."""
    _handlers[step] = fn


def handle_step(f: WfStep, step: str, step_fns: list[StepFn], opts: Opts) -> Opts:
    """Dispatch a step through a registered handler, or run ``f``."""
    handler = _handlers.get(step)
    return handler(f, step, step_fns, opts) if handler else f(opts)


def to_workflow_star(
    *,
    first_step: str,
    wire_fn,
    last_step: str | None = None,
    next_fn=None,
):
    """Like ``to_workflow``, but route every step through ``handle_step``."""

    def wrapped_wire(step: str, step_fns: list[StepFn]):
        f, next_step = wire_fn(step, step_fns)

        def routed(opts: Opts) -> Opts:
            return handle_step(f, step, step_fns, opts)

        return routed, next_step

    return to_workflow(first_step=first_step, last_step=last_step, wire_fn=wrapped_wire, next_fn=next_fn)


registerStep = register_step
toWorkflowStar = to_workflow_star
handleStep = handle_step
