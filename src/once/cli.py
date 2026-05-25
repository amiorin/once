"""Command-line entry point."""
from __future__ import annotations

import sys

from .options import bb
from .package import once_star
from .params import once_opts
from .tools import ansible_local_star, ansible_star, tofu_dns_star, tofu_smtp_post_star, tofu_smtp_star, tofu_star

HELP = """Usage: once <command> [args...]

Commands:
  package <step>...  Build, provision, or tear down infrastructure for the active profile.
                       once package validate
                       once package describe
                       once package build
                       once package create
                       once package delete
  validate           Shortcut for `once package validate`.

  Individual tools (each requires `render` first):
  tofu <args>             e.g. once tofu render tofu:init tofu:apply:-auto-approve
  tofu-smtp <args>
  tofu-dns <args>
  tofu-smtp-post <args>
  ansible <args>          e.g. once ansible render -- ansible-playbook main.yml
  ansible-local <args>

Notes:
  * The active profile is selected by `bb` in once/options.py.
  * Any param can be overridden with BC_PAR_* environment variables.

See: https://www.bigconfig.ai/manual"""

PACKAGE_COMMANDS = {"validate", "describe", "build", "create", "delete"}


def main(argv: list[str] | None = None) -> None:
    argv = list(sys.argv[1:] if argv is None else argv)
    command = argv[0] if argv else None
    rest = argv[1:] if argv else []

    if command in {None, "help", "--help", "-h"}:
        print(HELP)
        return
    if command in {"package", "once"}:  # "once" kept as a backwards-compatible alias.
        once_star(rest, bb)
        return
    if command == "validate":
        once_star(argv if rest else ["validate"], bb)
        return
    if command in PACKAGE_COMMANDS:
        once_star(argv, bb)
        return
    if command == "tofu":
        tofu_star(rest, once_opts(bb))
        return
    if command == "tofu-smtp":
        tofu_smtp_star(rest, once_opts(bb))
        return
    if command == "tofu-dns":
        tofu_dns_star(rest, once_opts(bb))
        return
    if command == "tofu-smtp-post":
        tofu_smtp_post_star(rest, once_opts(bb))
        return
    if command == "ansible":
        ansible_star(rest, once_opts(bb))
        return
    if command == "ansible-local":
        ansible_local_star(rest, once_opts(bb))
        return

    print(f"Unknown command: {command}\n", file=sys.stderr)
    print(HELP, file=sys.stderr)
    raise SystemExit(1)


if __name__ == "__main__":
    main()
