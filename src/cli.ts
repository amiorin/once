#!/usr/bin/env node
/** Command-line entry point. */
import { realpathSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { Opts } from "big-config";
import { bb } from "./once/options.js";
import { onceOpts } from "./once/params.js";
import { onceStar } from "./once/package.js";
import {
  ansibleLocalStar,
  ansibleStar,
  tofuDnsStar,
  tofuSmtpPostStar,
  tofuSmtpStar,
  tofuStar,
} from "./once/tools.js";

const HELP = `Usage: once <command> [args...]

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
  * When launched through \`run\`, the active profile comes from that script;
    otherwise it defaults to \`bb\` in src/once/options.ts.
  * Any param can be overridden with BC_PAR_* environment variables.

See: https://www.bigconfig.ai/manual`;

const PACKAGE_COMMANDS = new Set(["validate", "describe", "build", "create", "delete", "lock", "git-check", "git-push", "unlock-any"]);

function die(...lines: string[]): never {
  for (const line of lines) console.error(line);
  process.exit(1);
}

export function main(argv: string[], opts: Opts = bb): void {
  const [command, ...rest] = argv;
  if (command === "validate") die("Use `once package validate`.", "", HELP);
  if (command && PACKAGE_COMMANDS.has(command)) {
    onceStar(argv, opts);
    return;
  }
  switch (command) {
    case undefined:
    case "help":
    case "--help":
    case "-h":
      console.log(HELP);
      return;
    case "package":
    case "once": // Backwards-compatible alias for the old nested form.
      if (["help", "--help", "-h"].includes(rest[0] ?? "")) {
        console.log(HELP);
        return;
      }
      onceStar(rest, opts);
      return;
    case "tofu":
      tofuStar(rest, onceOpts(opts));
      return;
    case "tofu-smtp":
      tofuSmtpStar(rest, onceOpts(opts));
      return;
    case "tofu-dns":
      tofuDnsStar(rest, onceOpts(opts));
      return;
    case "tofu-smtp-post":
      tofuSmtpPostStar(rest, onceOpts(opts));
      return;
    case "ansible":
      ansibleStar(rest, onceOpts(opts));
      return;
    case "ansible-local":
      ansibleLocalStar(rest, onceOpts(opts));
      return;
    default:
      console.error(`Unknown command: ${command}\n`);
      console.error(HELP);
      process.exit(1);
  }
}

function isMainModule(): boolean {
  const entry = process.argv[1];
  if (!entry) return false;
  const modulePath = fileURLToPath(import.meta.url);
  try {
    return realpathSync(entry) === realpathSync(modulePath);
  } catch {
    return resolve(entry) === modulePath;
  }
}

if (isMainModule()) {
  main(process.argv.slice(2));
}
