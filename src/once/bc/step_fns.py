"""Step-fn middleware for process exit and error reporting."""
from __future__ import annotations

import sys

from .core import Opts, StepFn, to_step_fn


def exit_step_fn(end: str) -> StepFn:
    """Terminate the process with the workflow exit code at the final step."""

    def after(step: str, opts: Opts) -> None:
        if step == end and opts.get("env") != "repl":
            code = opts.get("exit", 0)
            raise SystemExit(code if isinstance(code, int) else 0)

    return to_step_fn(after_f=after)


def print_error_step_fn(end: str) -> StepFn:
    """Print the workflow error to stderr at the final step."""

    def before(step: str, opts: Opts) -> None:
        err = opts.get("err")
        if step == end and isinstance(opts.get("exit"), int) and opts["exit"] > 0 and isinstance(err, str) and err.strip():
            print(err, file=sys.stderr)

    return to_step_fn(before_f=before)


exitStepFn = exit_step_fn
printErrorStepFn = print_error_step_fn
