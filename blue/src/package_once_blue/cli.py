from __future__ import annotations

import asyncio
import sys
from pathlib import Path

from blue.cli import run_cli

from .describe import describe_file
from .workflow import once_workflow

USAGE = "Usage: blue <build|create|delete|describe> [-f|--file colors.yml] [--dry-run]"


def _find_up(name: str, start: Path | None = None) -> str:
    """Walking up means a colour can be run from any subdirectory of a project
    and still find the one desired state every colour shares."""
    directory = (start or Path.cwd()).resolve()
    for candidate in [directory, *directory.parents]:
        if (candidate / name).exists():
            return str(candidate / name)
    return name


def default_args(args: list[str]) -> list[str]:
    if any(arg in ("-f", "--file") or arg.startswith("--file=") for arg in args):
        return args
    return [*args, "-f", _find_up("colors.yml")]


def _file(args: list[str]) -> str:
    for index, arg in enumerate(args):
        if arg.startswith("--file="):
            return arg.split("=", 1)[1]
        if arg in ("-f", "--file"):
            return args[index + 1] if index + 1 < len(args) else _find_up("colors.yml")
    return _find_up("colors.yml")


async def run(*input: str) -> dict:
    args = default_args(list(input))
    command = args[0] if args else None
    if command in ("help", "--help", "-h"):
        return {"blue/exit": 0, "blue/err": USAGE}
    if command == "describe":
        return await describe_file(_file(args))
    if command in ("build", "create", "delete"):
        return await run_cli(once_workflow, args)
    return {"blue/exit": 2, "blue/err": USAGE}


def exec(args: list[str] | None = None) -> None:
    result = asyncio.run(run(*(sys.argv[1:] if args is None else args)))
    if result.get("blue/err"):
        print(result["blue/err"], file=sys.stdout if (result.get("blue/exit") or 0) == 0 else sys.stderr)
        if result.get("blue/trace"):
            print(result["blue/trace"], file=sys.stderr)
    raise SystemExit(result.get("blue/exit") or 0)
