import { spawnSync } from "node:child_process";
import { chmodSync, mkdtempSync, readFileSync, writeFileSync, } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { describe, expect, it } from "vitest";
const deployScript = resolve("src/resources/io/github/bigconfig-ai/once/tools/ansible/files/deploy");
const hasBb = spawnSync("bb", ["--version"], { encoding: "utf8" }).status === 0;
/**
 * Create a temp dir with `sudo` and `once` shims. The `once` shim logs every
 * invocation, returns `listOutput` for `once list`, and exits 0 for
 * `once update`.
 */
function makeShim(listOutput) {
    const dir = mkdtempSync(join(tmpdir(), "deploy-test-"));
    const log = join(dir, "calls.log");
    const listFile = join(dir, "list.output");
    writeFileSync(listFile, listOutput);
    const sudo = join(dir, "sudo");
    writeFileSync(sudo, '#!/bin/sh\nexec "$@"\n');
    chmodSync(sudo, 0o755);
    const once = join(dir, "once");
    writeFileSync(once, `#!/bin/sh\necho "$@" >> ${log}\ncase "$1" in\n` +
        `  list)   cat ${listFile} ;;\n` +
        `  update) exit 0 ;;\n` +
        `  *)      exit 2 ;;\n` +
        "esac\n");
    chmodSync(once, 0o755);
    return { dir, log };
}
function runDeploy(sshOriginalCommand, shim) {
    const env = {
        ...process.env,
        SSH_ORIGINAL_COMMAND: sshOriginalCommand ?? "",
    };
    if (shim)
        env.PATH = `${shim.dir}:${process.env.PATH ?? ""}`;
    const res = spawnSync("bb", [deployScript], { encoding: "utf8", env });
    return { exit: res.status ?? -1, err: res.stderr ?? "" };
}
describe.skipIf(!hasBb)("deploy ForceCommand script", () => {
    it("rejects empty ssh command", () => {
        const { exit, err } = runDeploy("");
        expect(exit).toBe(1);
        expect(err).toContain("interactive sessions");
    });
    it("rejects unrelated command", () => {
        const { exit, err } = runDeploy("rm -rf /");
        expect(exit).toBe(1);
        expect(err).toContain("command not allowed");
    });
    it("rejects wrong once subcommand", () => {
        const { exit, err } = runDeploy("sudo once list");
        expect(exit).toBe(1);
        expect(err).toContain("command not allowed");
    });
    it("rejects too many tokens", () => {
        const { exit, err } = runDeploy("sudo once update foo bar");
        expect(exit).toBe(1);
        expect(err).toContain("command not allowed");
    });
    it("rejects chained command", () => {
        const { exit, err } = runDeploy("sudo once update foo.com; rm -rf /");
        expect(exit).toBe(1);
        expect(err).toContain("command not allowed");
    });
    it("rejects shell metacharacters in host", () => {
        const { exit, err } = runDeploy("sudo once update foo;bar");
        expect(exit).toBe(1);
        expect(err).toContain("invalid host");
    });
    it("rejects host not in once list", () => {
        const shim = makeShim("bigconfig.website (running)\n");
        const { exit, err } = runDeploy("sudo once update bogus.example.com", shim);
        expect(exit).toBe(1);
        expect(err).toContain("host not allowed");
    });
    it("runs update for an allowed host", () => {
        const shim = makeShim("bigconfig.website (running)\nforms.bigconfig.website (running)\n");
        const { exit } = runDeploy("sudo once update bigconfig.website", shim);
        expect(exit).toBe(0);
        expect(readFileSync(shim.log, "utf8")).toContain("update bigconfig.website");
    });
    it("parses the host list with ANSI escapes", () => {
        const shim = makeShim(readFileSync("src/resources/ansi.output", "utf8"));
        const { exit } = runDeploy("sudo once update foo.bigconfig.space", shim);
        expect(exit).toBe(0);
        expect(readFileSync(shim.log, "utf8")).toContain("update foo.bigconfig.space");
    });
});
//# sourceMappingURL=deploy.test.js.map