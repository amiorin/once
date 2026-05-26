import { describe, expect, it } from "vitest";
import { computeTarget, describe as describeStep, describeReport, findContainerForHost, imageToRepositoryTag, matchingRepoDigest, onceCommandCheckArgs, parseOnceList, providerSummary, updateAvailable, } from "../src/once/describe.js";
const baseOpts = {
    profile: "test",
    params: {
        "provider-compute": "digitalocean",
        "provider-backend": "r2",
        "provider-smtp": "resend",
        "provider-dns": "cloudflare",
        ip: "203.0.113.10",
        user: "root",
    },
};
const ok = (out = "") => ({ ok: true, exit: 0, out, err: "" });
const fail = (err) => ({
    ok: false,
    exit: 255,
    out: "",
    err,
});
function remoteCommand(args) {
    const idx = args.findIndex((a) => a.includes("@"));
    return args.slice(idx + 1);
}
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const identity = (opts) => opts;
it("provider-summary extracts provider names", () => {
    expect(providerSummary(baseOpts.params)).toEqual({
        compute: "digitalocean",
        backend: "r2",
        smtp: "resend",
        dns: "cloudflare",
    });
});
it("no-infra compute target uses configured ip and user when state is missing", () => {
    expect(computeTarget({
        "provider-compute": "no-infra",
        ip: "192.168.0.1",
        "no-infra-compute-ip": "10.0.0.5",
        "no-infra-compute-user": "ubuntu",
    })).toEqual({ ip: "10.0.0.5", user: "ubuntu" });
});
it("image ref parsing handles tags, defaults and registry ports", () => {
    expect(imageToRepositoryTag("ghcr.io/org/app:1.2.3")).toEqual({
        repository: "ghcr.io/org/app",
        tag: "1.2.3",
        image: "ghcr.io/org/app:1.2.3",
    });
    expect(imageToRepositoryTag("ghcr.io/org/app")).toEqual({
        repository: "ghcr.io/org/app",
        tag: "latest",
        image: "ghcr.io/org/app:latest",
    });
    expect(imageToRepositoryTag("localhost:5000/org/app")).toEqual({
        repository: "localhost:5000/org/app",
        tag: "latest",
        image: "localhost:5000/org/app:latest",
    });
    expect(imageToRepositoryTag("localhost:5000/org/app:dev")).toEqual({
        repository: "localhost:5000/org/app",
        tag: "dev",
        image: "localhost:5000/org/app:dev",
    });
});
it("once list parsing strips ANSI and reads status", () => {
    const output = "[32mwww.example.com[0m (running)\n" +
        "forms.example.com (stopped)\n";
    expect(parseOnceList(output)).toEqual([
        { host: "www.example.com", status: "running" },
        { host: "forms.example.com", status: "stopped" },
    ]);
});
it("docker container matching uses labels and names", () => {
    const containers = [
        {
            Name: "/once-www-example-com",
            Config: {
                Image: "ghcr.io/org/app:latest",
                Labels: {
                    "traefik.http.routers.app.rule": "Host(`www.example.com`)",
                },
            },
        },
        { Name: "/other", Config: { Image: "ghcr.io/org/other:latest" } },
    ];
    expect(findContainerForHost(containers, "www.example.com")?.Config?.Image).toBe("ghcr.io/org/app:latest");
});
it("digest selection and comparison", () => {
    expect(matchingRepoDigest("ghcr.io/org/app", [
        "ghcr.io/org/app@sha256:aaa",
        "ghcr.io/org/other@sha256:bbb",
    ])).toBe("sha256:aaa");
    expect(updateAvailable("sha256:aaa", "sha256:aaa")).toBe(false);
    expect(updateAvailable("sha256:aaa", "sha256:bbb")).toBe(true);
    expect(updateAvailable(undefined, "sha256:bbb")).toBeUndefined();
});
it("describe ssh failure soft-fails and skips remote apps", () => {
    const calls = [];
    const runFn = (args) => {
        calls.push(args);
        if (args.includes("once")) {
            throw new Error("remote apps should not be checked");
        }
        return fail("Permission denied");
    };
    const result = describeReport(baseOpts, runFn, identity);
    expect(result.compute.running).toBe(false);
    expect(result.applications).toEqual([]);
    expect(result.applicationsError).toContain("not checked");
    expect(calls.length).toBe(1);
});
it("describe remote command failure keeps compute running", () => {
    const runFn = (args) => {
        const cmd = remoteCommand(args);
        if (eq(cmd, ["true"]))
            return ok();
        if (eq(cmd, onceCommandCheckArgs))
            return ok();
        if (eq(cmd, ["sudo", "-n", "once", "list"]))
            return fail("once missing");
        throw new Error(`unexpected command: ${JSON.stringify(args)}`);
    };
    const result = describeReport(baseOpts, runFn, identity);
    expect(result.compute.running).toBe(true);
    expect(result.applications).toEqual([]);
    expect(result.fatalError).toBe(false);
    expect(result.applicationsError).toContain("once list failed");
});
it("describe missing remote once command is fatal", () => {
    const runFn = (args) => {
        const cmd = remoteCommand(args);
        if (eq(cmd, ["true"]))
            return ok();
        if (eq(cmd, onceCommandCheckArgs)) {
            return { ok: false, exit: 127, out: "", err: "once: command not found" };
        }
        throw new Error(`unexpected command: ${JSON.stringify(args)}`);
    };
    const result = describeReport(baseOpts, runFn, identity);
    expect(result.compute.running).toBe(true);
    expect(result.applications).toEqual([]);
    expect(result.fatalError).toBe(true);
    expect(result.applicationsError).toContain("once command check failed");
});
describe("describe workflow step sets exit status", () => {
    it("soft report succeeds", () => {
        const result = describeStep([], baseOpts, () => ({
            profile: "test",
            providers: {},
            compute: {},
            applications: [],
            fatalError: false,
        }));
        expect(result.exit).toBe(0);
        expect(result["describe/result"].fatalError).toBe(false);
    });
    it("fatal report fails", () => {
        const result = describeStep([], baseOpts, () => ({
            profile: "test",
            providers: {},
            compute: {},
            applications: [],
            fatalError: true,
        }));
        expect(result.exit).toBe(1);
        expect(result.err).toBe("describe failed");
    });
});
it("describe success reports image digests and update status", () => {
    const container = {
        Id: "container-1",
        Name: "/once-www-example-com",
        Image: "sha256:local-image",
        Config: {
            Image: "ghcr.io/org/app:latest",
            Labels: { "traefik.http.routers.app.rule": "Host(`www.example.com`)" },
        },
        State: { Status: "running" },
    };
    const image = {
        Id: "sha256:local-image",
        RepoTags: ["ghcr.io/org/app:latest"],
        RepoDigests: ["ghcr.io/org/app@sha256:old"],
        Architecture: "amd64",
        Os: "linux",
    };
    const runFn = (args) => {
        if (args[0] === "skopeo") {
            expect(args).toContain("--override-os");
            expect(args).toContain("--override-arch");
            expect(args).toContain("docker://ghcr.io/org/app:latest");
            return ok(JSON.stringify({ Digest: "sha256:new" }));
        }
        const cmd = remoteCommand(args);
        if (eq(cmd, ["true"]))
            return ok();
        if (eq(cmd, onceCommandCheckArgs))
            return ok();
        if (eq(cmd, ["sudo", "-n", "once", "list"])) {
            return ok("www.example.com (running)\n");
        }
        if (eq(cmd, ["sudo", "-n", "docker", "ps", "-q"])) {
            return ok("container-1\n");
        }
        if (eq(cmd, [
            "sudo",
            "-n",
            "docker",
            "inspect",
            "--type",
            "container",
            "container-1",
        ])) {
            return ok(JSON.stringify([container]));
        }
        if (eq(cmd, [
            "sudo",
            "-n",
            "docker",
            "image",
            "inspect",
            "sha256:local-image",
            "ghcr.io/org/app:latest",
        ])) {
            return ok(JSON.stringify([image]));
        }
        throw new Error(`unexpected command: ${JSON.stringify(args)}`);
    };
    const result = describeReport(baseOpts, runFn, identity);
    const app = result.applications[0];
    expect(result.applicationsError).toBeUndefined();
    expect(app.host).toBe("www.example.com");
    expect(app.image).toBe("ghcr.io/org/app:latest");
    expect(app.version).toBe("latest");
    expect(app.digest).toBe("sha256:old");
    expect(app.registryDigest).toBe("sha256:new");
    expect(app.newVersion).toBe(true);
});
//# sourceMappingURL=describe.test.js.map