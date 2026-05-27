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
  package <step>...  Build, provision, or tear down infrastructure for the active profile.
                       once package validate
                       once package describe
                       once package build
                       once package create
                       once package delete
  validate           Shortcut for \`once package validate\`.

  Individual tools (each requires \`render\` first):
  tofu <args>             e.g. once tofu render tofu:init tofu:apply:-auto-approve
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

const PACKAGE_COMMANDS = new Set(["describe", "build", "create", "delete"]);

export function main(argv: string[], opts: Opts = bb): void {
  const [command, ...rest] = argv;
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
      onceStar(rest, opts);
      return;
    case "validate":
      onceStar(rest.length > 0 ? argv : ["validate"], opts);
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
