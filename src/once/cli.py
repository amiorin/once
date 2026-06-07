"""Command-line entry point."""
from __future__ import annotations

import sys

from big_config.core import Opts

from .options import bb
from .package import once_star
from .params import once_opts
from .tools import ansible_local_star, ansible_star, tofu_dns_star, tofu_smtp_post_star, tofu_smtp_star, tofu_star

HELP = """Usage: once <command> [args...]

Commands:
  package <step>...  Run package workflow steps for the active profile.
                       once package validate
                       once package describe
                       once package build
                       once package create
                       once package delete
                       once package git-check lock build unlock-any

  Package steps:
    validate              Pre-flight profile, tool, credential, image, and SSH-agent checks.
    describe              Post-provisioning providers, SSH reachability, apps, and updates report.
    build                 Render all stages without applying/provisioning.
    create                Provision and configure the full ONCE stack.
    delete                Tear down the Tofu stages in reverse order.
    lock                  Acquire the BigConfig Git-tag lock.
    git-check             Verify the Git working tree/upstream state is clean.
    git-push              Run git push through the BigConfig workflow.
    unlock-any            Force-release the computed BigConfig lock tag.

  Individual tools (accept SDK workflow steps and exec commands):
  tofu <args>             e.g. once tofu render tofu:init tofu:apply:-auto-approve
                          e.g. once tofu git-check lock render tofu:init tofu:plan unlock-any
  tofu-smtp <args>
  tofu-dns <args>
  tofu-smtp-post <args>
  ansible <args>          e.g. once ansible render -- ansible-playbook main.yml
  ansible-local <args>

Notes:
  * When launched through `run`, the active profile comes from that script;
    otherwise it defaults to `bb` in once/options.py.
  * Any param can be overridden with BC_PAR_* environment variables.

See: https://www.bigconfig.ai/manual"""

PACKAGE_COMMANDS = {"validate", "describe", "build", "create", "delete", "lock", "git-check", "git-push", "unlock-any"}


def die(*lines: str) -> None:
    for line in lines:
        print(line, file=sys.stderr)
    raise SystemExit(1)


def main(argv: list[str] | None = None, opts: Opts | None = None) -> None:
    argv = list(sys.argv[1:] if argv is None else argv)
    active_profile = opts if opts is not None else bb
    command = argv[0] if argv else None
    rest = argv[1:] if argv else []

    if command in {None, "help", "--help", "-h"}:
        print(HELP)
        return
    if command in {"package", "once"}:  # "once" kept as a backwards-compatible alias.
        if rest and rest[0] in {"help", "--help", "-h"}:
            print(HELP)
            return
        once_star(rest, active_profile)
        return
    if command == "validate":
        die("Use `once package validate`.", "", HELP)
    if command in PACKAGE_COMMANDS:
        once_star(argv, active_profile)
        return
    if command == "tofu":
        tofu_star(rest, once_opts(active_profile))
        return
    if command == "tofu-smtp":
        tofu_smtp_star(rest, once_opts(active_profile))
        return
    if command == "tofu-dns":
        tofu_dns_star(rest, once_opts(active_profile))
        return
    if command == "tofu-smtp-post":
        tofu_smtp_post_star(rest, once_opts(active_profile))
        return
    if command == "ansible":
        ansible_star(rest, once_opts(active_profile))
        return
    if command == "ansible-local":
        ansible_local_star(rest, once_opts(active_profile))
        return

    print(f"Unknown command: {command}\n", file=sys.stderr)
    print(HELP, file=sys.stderr)
    raise SystemExit(1)


if __name__ == "__main__":
    main()
