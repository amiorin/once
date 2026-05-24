"""Workflow engine: threads an ``opts`` dict through a series of steps."""
from __future__ import annotations

import traceback
from typing import Any, Callable, Optional

Opts = dict[str, Any]
WfStep = Callable[[Opts], Opts]
StepFn = Callable[[Callable[[str, Opts], Opts], str, Opts], Opts]
SideFn = Callable[[str, Opts], None]


def ok(opts: Opts | None = None) -> Opts:
    """Return opts with a successful exit status."""
    if opts is None:
        return {"exit": 0, "err": None}
    return {**opts, "exit": 0, "err": None}


def choice(on_success: str | None, on_failure: str | None, opts: Opts) -> tuple[str | None, Opts]:
    """Pick the next step based on ``opts['exit']``."""
    return (on_success, opts) if opts.get("exit") == 0 else (on_failure, opts)


def to_step_fn(*, before_f: SideFn | None = None, after_f: SideFn | str | None = None) -> StepFn:
    """Build middleware from before/after side-effect functions."""
    if before_f is None and after_f is None:
        raise ValueError("At least one f needs to be provided")

    def step_fn(next_fn: Callable[[str, Opts], Opts], step: str, opts: Opts) -> Opts:
        if before_f is not None:
            before_f(step, opts)
        result = next_fn(step, opts)
        after = before_f if after_f == "same" else after_f
        if callable(after):
            after(step, result)
        return result

    return step_fn


def _namespace_of(kw: str) -> str:
    i = kw.find("/")
    return kw[:i] if i >= 0 else ""


def _derive_last_step(first_step: str) -> str:
    ns = _namespace_of(first_step)
    return f"{ns}/end" if ns else "end"


def _compose(step_fns: list[StepFn], f: WfStep) -> Callable[[str, Opts], Opts]:
    def innermost(_step: str, opts: Opts) -> Opts:
        return f(opts)

    acc: Callable[[str, Opts], Opts] = innermost
    for mw in step_fns:
        prev = acc

        def wrapped(step: str, opts: Opts, mw: StepFn = mw, prev: Callable[[str, Opts], Opts] = prev) -> Opts:
            return mw(prev, step, opts)

        acc = wrapped
    return acc


def _try_f(f: Callable[[str, Opts], Opts], step: str, opts: Opts) -> Opts:
    try:
        return f(step, opts)
    except Exception as exc:  # noqa: BLE001 - workflow boundary
        data = getattr(exc, "data", {})
        if not isinstance(data, dict):
            data = {}
        return {
            **opts,
            **data,
            "err": str(exc),
            "exit": 1,
            "stackTrace": "".join(traceback.format_exception(type(exc), exc, exc.__traceback__)),
        }


WireFn = Callable[[str, list[StepFn]], tuple[WfStep, str | None]]
NextFn = Callable[[str, str | None, Opts], tuple[str | None, Opts]]


def _resolve_next_fn(next_fn: NextFn | None, last_step: str) -> NextFn:
    if next_fn is not None:
        return next_fn

    def default(_step: str, next_step: str | None, opts: Opts) -> tuple[str | None, Opts]:
        if next_step is None:
            return None, opts
        return choice(next_step, last_step, opts)

    return default


def to_workflow(
    *,
    first_step: str,
    wire_fn: WireFn,
    last_step: str | None = None,
    next_fn: NextFn | None = None,
) -> Callable[[list[StepFn], Opts], Opts]:
    """Create a workflow function ``(step_fns, opts) -> opts``."""
    last = last_step or _derive_last_step(first_step)

    def workflow(step_fns: list[StepFn], opts: Opts) -> Opts:
        if opts is None:
            raise ValueError("opts should never be nil")
        resolved = list(reversed(step_fns))
        step: str | None = first_step
        cur = opts
        while step is not None:
            f, next_step = wire_fn(step, resolved)
            cur = _try_f(_compose(resolved, f), step, cur)
            if cur is None:
                raise ValueError(f"opts must never be nil (step {step})")
            exit_code = cur.get("exit")
            if not isinstance(exit_code, int) or exit_code < 0:
                raise ValueError("exit must be a natural number")
            step, cur = _resolve_next_fn(next_fn, last)(step, next_step, cur)
            if step is None:
                return cur
        return cur

    return workflow


# Compatibility aliases for callers ported mechanically from TypeScript names.
toWorkflow = to_workflow
toStepFn = to_step_fn
