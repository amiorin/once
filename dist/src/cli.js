#!/usr/bin/env node
/** Command-line entry point. */
import { bb } from "./once/options.js";
import { onceOpts } from "./once/params.js";
import { onceStar } from "./once/package.js";
import { ansibleLocalStar, ansibleStar, tofuDnsStar, tofuSmtpPostStar, tofuSmtpStar, tofuStar, } from "./once/tools.js";
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
  * The active profile is selected by \`bb\` in src/once/options.ts.
  * Any param can be overridden with BC_PAR_* environment variables.

See: https://www.bigconfig.ai/manual`;
const PACKAGE_COMMANDS = new Set(["describe", "build", "create", "delete"]);
function main(argv) {
    const [command, ...rest] = argv;
    if (command && PACKAGE_COMMANDS.has(command)) {
        onceStar(argv, bb);
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
            onceStar(rest, bb);
            return;
        case "validate":
            onceStar(rest.length > 0 ? argv : ["validate"], bb);
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
//# sourceMappingURL=cli.js.map