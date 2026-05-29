import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, readFileSync, readdirSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";
const root = resolve("../..");
const clojureDir = join(root, "once/clojure");
const typeScriptDir = join(root, "once/typescript");
const profileDir = ".dist/default-378cc184";
const hasBb = spawnSync("bb", ["--version"], { encoding: "utf8" }).status === 0;
function run(cmd, args, cwd) {
    const res = spawnSync(cmd, args, { cwd, encoding: "utf8", env: process.env });
    expect(res.status, `${cmd} ${args.join(" ")} failed\nstdout:\n${res.stdout}\nstderr:\n${res.stderr}`).toBe(0);
}
function clean(project) {
    rmSync(join(project, ".dist"), { recursive: true, force: true });
}
function files(base) {
    const out = [];
    function walk(dir) {
        for (const entry of readdirSync(dir)) {
            const p = join(dir, entry);
            const st = statSync(p);
            if (st.isDirectory())
                walk(p);
            else if (st.isFile())
                out.push(relative(base, p));
        }
    }
    if (existsSync(base))
        walk(base);
    return out.sort();
}
describe("built CLI", () => {
    it("renders package templates outside the package root", () => {
        const tmp = mkdtempSync(join(tmpdir(), "once-typescript-"));
        try {
            run("node", [join(typeScriptDir, "dist/src/cli.js"), "package", "build"], tmp);
            expect(existsSync(join(tmp, ".dist"))).toBe(true);
        }
        finally {
            rmSync(tmp, { recursive: true, force: true });
        }
    });
});
describe.skipIf(!hasBb)("build parity", () => {
    it("TypeScript build matches Clojure byte-for-byte", () => {
        clean(clojureDir);
        clean(typeScriptDir);
        run("bb", ["run", "once", "package", "build"], clojureDir);
        run("node", ["run", "package", "build"], typeScriptDir);
        const cljOut = join(clojureDir, profileDir);
        const tsOut = join(typeScriptDir, profileDir);
        expect(files(tsOut)).toEqual(files(cljOut));
        for (const rel of files(cljOut)) {
            expect(readFileSync(join(tsOut, rel)), rel).toEqual(readFileSync(join(cljOut, rel)));
        }
    });
});
//# sourceMappingURL=build-parity.test.js.map