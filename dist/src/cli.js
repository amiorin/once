#!/usr/bin/env node
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
  * When launched through \`run\`, the active profile comes from that script;
    otherwise it defaults to \`bb\` in src/once/options.ts.
  * Any param can be overridden with BC_PAR_* environment variables.

See: https://www.bigconfig.ai/manual`;
const PACKAGE_COMMANDS = new Set(["describe", "build", "create", "delete"]);
const PROFILE_ENV = "ONCE_PROFILE_JSON";
function profileFromEnv() {
    const raw = process.env[PROFILE_ENV];
    if (!raw)
        return undefined;
    try {
        const profile = JSON.parse(raw);
        if (profile && typeof profile === "object" && !Array.isArray(profile)) {
            return profile;
        }
    }
    catch (err) {
        console.error(`Invalid ${PROFILE_ENV}: ${err instanceof Error ? err.message : String(err)}`);
        process.exit(1);
    }
    console.error(`Invalid ${PROFILE_ENV}: expected a JSON object`);
    process.exit(1);
}
function main(argv, opts = profileFromEnv() ?? bb) {
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
main(process.argv.slice(2));
//# sourceMappingURL=cli.js.map