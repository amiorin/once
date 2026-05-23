#!/usr/bin/env node
/** Command-line entry point. */
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
  once <step>...    Provision or tear down infrastructure for the active profile.
                      once once validate    Run pre-flight checks.
                      once once describe    Show providers, SSH and deployed apps.
                      once once create      Run the full 6-stage create pipeline.
                      once once delete      Reverse the 4 Tofu stages.
  validate          Shortcut for \`once once validate\`.

  Individual tools (each requires \`render\` first):
  tofu <args>             e.g. once tofu render tofu:init tofu:apply:-auto-approve
  tofu-smtp <args>
  tofu-dns <args>
  tofu-smtp-post <args>
  ansible <args>          e.g. once ansible render -- ansible-playbook main.yml
  ansible-local <args>

Notes:
  * The active profile is selected by \`bb\` in src/once/options.ts.
  * Any param can be overridden with BC_PAR_* environment variables.

See: https://www.bigconfig.ai/manual`;

function main(argv: string[]): void {
  const [command, ...rest] = argv;
  switch (command) {
    case undefined:
    case "help":
    case "--help":
    case "-h":
      console.log(HELP);
      return;
    case "once":
      onceStar(rest, bb);
      return;
    case "validate":
      if (rest.length > 0) {
        console.error("Error: validate does not accept arguments.");
        console.error("Usage: once validate");
        process.exit(1);
      }
      onceStar(["validate"], bb);
      return;
    case "tofu":
      tofuStar(rest, onceOpts(bb));
      return;
    case "tofu-smtp":
      tofuSmtpStar(rest, onceOpts(bb));
      return;
    case "tofu-dns":
      tofuDnsStar(rest, onceOpts(bb));
      return;
    case "tofu-smtp-post":
      tofuSmtpPostStar(rest, onceOpts(bb));
      return;
    case "ansible":
      ansibleStar(rest, onceOpts(bb));
      return;
    case "ansible-local":
      ansibleLocalStar(rest, onceOpts(bb));
      return;
    default:
      console.error(`Unknown command: ${command}\n`);
      console.error(HELP);
      process.exit(1);
  }
}

main(process.argv.slice(2));
