/**
 * Describe the active profile after provisioning: configured providers, SSH
 * reachability, and deployed ONCE applications discovered from the remote host.
 */
import { spawnSync } from "node:child_process";
import { okAlias, paramsOf, profileOf, status, syncAliases } from "./interop.js";
import { onceOpts } from "./params.js";
const RUN_TIMEOUT_MS = 30000;
const SSH_PROBE_TIMEOUT_MS = 10000;
const REGISTRY_TIMEOUT_MS = 30000;
// -------------------------------------------------------------- command helpers
function run(args, opts = {}) {
    const timeout = opts.timeoutMs ?? RUN_TIMEOUT_MS;
    try {
        const res = spawnSync(args[0], args.slice(1), {
            input: "",
            encoding: "utf8",
            timeout,
            env: opts.extraEnv
                ? { ...process.env, ...opts.extraEnv }
                : process.env,
        });
        if (res.error) {
            const code = res.error.code;
            if (code === "ETIMEDOUT") {
                return {
                    ok: false,
                    exit: -1,
                    out: "",
                    err: `command timed out after ${timeout}ms`,
                };
            }
            return { ok: false, exit: -1, out: "", err: res.error.message };
        }
        const exit = res.status ?? -1;
        return {
            ok: exit === 0,
            exit,
            out: res.stdout ?? "",
            err: res.stderr ?? "",
        };
    }
    catch (e) {
        return { ok: false, exit: -1, out: "", err: e?.message ?? String(e) };
    }
}
function trimSnippet(s) {
    const t = (s ?? "").trim();
    if (t === "")
        return null;
    return t.length > 200 ? `${t.slice(0, 200)}…` : t;
}
function resultDetail(label, r) {
    const snippet = trimSnippet(r.err) ?? trimSnippet(r.out);
    return `${label} failed (exit ${r.exit ?? -1})${snippet ? ` — ${snippet}` : ""}`;
}
function onceCommandNotFound(r) {
    const text = `${r.err ?? ""}\n${r.out ?? ""}`.toLowerCase();
    return (r.exit === 127 ||
        text.includes("once: command not found") ||
        text.includes("once: not found") ||
        text.includes("command not found: once"));
}
function sshBaseArgs(compute) {
    return [
        "ssh",
        "-o",
        "BatchMode=yes",
        "-o",
        "ConnectTimeout=5",
        "-o",
        "StrictHostKeyChecking=accept-new",
        `${compute.user}@${compute.ip}`,
    ];
}
function sshRun(runFn, compute, remoteArgs, timeoutMs = RUN_TIMEOUT_MS) {
    return runFn([...sshBaseArgs(compute), ...remoteArgs], { timeoutMs });
}
/** The shell snippet probing for the remote `once` command. */
export const onceCommandCheckArgs = [
    "command",
    "-v",
    "once",
    ">/dev/null",
    "2>&1",
    "||",
    "test",
    "-x",
    "/usr/local/bin/once",
    "||",
    "{",
    "echo",
    "once:",
    "command",
    "not",
    "found",
    ">&2",
    ";",
    "exit",
    "127",
    ";",
    "}",
];
// -------------------------------------------------------------- providers + compute
/** Return configured provider names from merged params. */
export function providerSummary(params) {
    return {
        compute: params["provider-compute"],
        backend: params["provider-backend"],
        smtp: params["provider-smtp"],
        dns: params["provider-dns"],
    };
}
function blank(s) {
    return s == null || String(s).trim() === "";
}
function nonEmpty(s) {
    return typeof s === "string" && s !== "" ? s : undefined;
}
/** Resolve the IP and SSH user to probe. */
export function computeTarget(params) {
    const pc = params["provider-compute"];
    const ip = params.ip;
    const niIp = params["no-infra-compute-ip"];
    const resolvedIp = pc === "no-infra" &&
        (blank(ip) || ip === "192.168.0.1") &&
        !blank(niIp)
        ? niIp
        : ip;
    return {
        ip: resolvedIp,
        user: nonEmpty(params.user) ??
            nonEmpty(params["no-infra-compute-user"]) ??
            nonEmpty(params.sudoer) ??
            nonEmpty(params["no-infra-compute-sudoer"]) ??
            "root",
    };
}
function computeStatus(runFn, params) {
    const target = computeTarget(params);
    if (blank(target.ip)) {
        return { ...target, running: false, detail: "missing IP address" };
    }
    const r = sshRun(runFn, target, ["true"], SSH_PROBE_TIMEOUT_MS);
    return {
        ...target,
        running: Boolean(r.ok),
        detail: r.ok
            ? "ssh ok"
            : resultDetail("ssh", r) +
                (target.ip === "192.168.0.1"
                    ? "; no Tofu output found or host is down"
                    : ""),
    };
}
// -------------------------------------------------------------- once list parsing
/** Strip ANSI escape sequences and OSC 8 hyperlinks. */
export function stripAnsi(s) {
    return (s ?? "")
        .replace(/\x1b\]8;[^\x07]*\x07/g, "")
        .replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, "");
}
const hostStatusRx = /([A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?\.[A-Za-z]{2,})(?:\s+\(([^)]*)\))?/;
/** Parse the `once list` output into hosts and statuses. */
export function parseOnceList(output) {
    const result = [];
    for (const line of stripAnsi(output).split("\n")) {
        const m = line.match(hostStatusRx);
        if (m) {
            const app = { host: m[1] };
            if (m[2] && m[2].trim() !== "")
                app.status = m[2];
            result.push(app);
        }
    }
    return result;
}
/** Parse a Docker image reference into repository, tag and normalized image. */
export function imageToRepositoryTag(image) {
    const img = (image ?? "").toString().trim();
    if (img === "" || /^sha256:[A-Fa-f0-9]+$/.test(img))
        return undefined;
    const withoutDigest = img.split("@")[0];
    const lastSlash = withoutDigest.lastIndexOf("/");
    const lastColon = withoutDigest.lastIndexOf(":");
    const hasTag = lastColon > lastSlash;
    const repository = hasTag
        ? withoutDigest.slice(0, lastColon)
        : withoutDigest;
    const tag = hasTag ? withoutDigest.slice(lastColon + 1) : "latest";
    return { repository, tag, image: `${repository}:${tag}` };
}
/** Return the digest from `repoDigests` for `repository`. */
export function matchingRepoDigest(repository, repoDigests) {
    for (const repoDigest of repoDigests ?? []) {
        const at = String(repoDigest).indexOf("@");
        const repo = at >= 0 ? String(repoDigest).slice(0, at) : String(repoDigest);
        const digest = at >= 0 ? String(repoDigest).slice(at + 1) : undefined;
        if (repo === repository)
            return digest;
    }
    return undefined;
}
export function updateAvailable(runningDigest, registryDigest) {
    if (runningDigest &&
        runningDigest.trim() !== "" &&
        registryDigest &&
        registryDigest.trim() !== "") {
        return runningDigest !== registryDigest;
    }
    return undefined;
}
function registryDigest(runFn, image, os, arch) {
    const args = ["skopeo", "inspect", "--no-tags"];
    if (!blank(os))
        args.push("--override-os", os);
    if (!blank(arch))
        args.push("--override-arch", arch);
    args.push(`docker://${image}`);
    const r = runFn(args, { timeoutMs: REGISTRY_TIMEOUT_MS });
    if (r.ok) {
        try {
            return { digest: JSON.parse(r.out).Digest ?? null };
        }
        catch (e) {
            return {
                digest: null,
                detail: `registry response was not valid JSON: ${e?.message}`,
            };
        }
    }
    return { digest: null, detail: resultDetail("skopeo inspect", r) };
}
// -------------------------------------------------------------- docker parsing
function parseJsonVector(s) {
    try {
        const v = JSON.parse(s && s.trim() !== "" ? s : "[]");
        return Array.isArray(v) ? v : [];
    }
    catch {
        return [];
    }
}
function stringLeaves(x) {
    if (x == null)
        return [];
    if (typeof x === "string")
        return [x];
    if (Array.isArray(x))
        return x.flatMap(stringLeaves);
    if (typeof x === "object") {
        return Object.entries(x).flatMap(([k, v]) => [
            ...stringLeaves(k),
            ...stringLeaves(v),
        ]);
    }
    return [String(x)];
}
function hostVariants(host) {
    const h = host.toLowerCase();
    return [...new Set([h, h.replace(/\./g, "-"), h.replace(/\./g, "_")])];
}
function containerSearchText(container) {
    const picked = {};
    for (const k of ["Name", "Config", "NetworkSettings"]) {
        if (k in container)
            picked[k] = container[k];
    }
    return stringLeaves(picked).join("\n").toLowerCase();
}
function containerForHost(host, container) {
    const text = containerSearchText(container);
    return hostVariants(host).some((v) => text.includes(v));
}
/** Find the container whose metadata mentions `host`. */
export function findContainerForHost(containers, host) {
    return containers.find((c) => containerForHost(host, c));
}
function imageIdentifiers(containers) {
    const ids = containers.flatMap((c) => [c.Image, c?.Config?.Image]);
    return [...new Set(ids.filter((x) => x != null && x !== ""))];
}
function imageInfoForContainer(imageInfos, container, parsedImage) {
    const imageId = container.Image;
    const imageRef = container?.Config?.Image;
    const normalized = parsedImage?.image;
    const repository = parsedImage?.repository;
    return (imageInfos.find((i) => i.Id === imageId) ??
        imageInfos.find((i) => (i.RepoTags ?? []).includes(imageRef)) ??
        (normalized
            ? imageInfos.find((i) => (i.RepoTags ?? []).includes(normalized))
            : undefined) ??
        (repository
            ? imageInfos.find((i) => matchingRepoDigest(repository, i.RepoDigests))
            : undefined));
}
function applicationReport(runFn, containers, imageInfos, app) {
    const container = findContainerForHost(containers, app.host);
    const imageRef = container?.Config?.Image;
    const parsedImage = imageToRepositoryTag(imageRef);
    const imageInfo = container
        ? imageInfoForContainer(imageInfos, container, parsedImage)
        : undefined;
    const os = imageInfo?.Os ?? "linux";
    const arch = imageInfo?.Architecture ?? "amd64";
    const runningDigest = parsedImage
        ? matchingRepoDigest(parsedImage.repository, imageInfo?.RepoDigests)
        : undefined;
    const fallbackDigest = imageInfo?.Id ?? container?.Image;
    const registry = parsedImage
        ? registryDigest(runFn, parsedImage.image, os, arch)
        : undefined;
    const regDigest = registry?.digest ?? undefined;
    const report = {
        host: app.host,
        status: container?.State?.Status ?? app.status ?? "unknown",
        image: parsedImage?.image ?? imageRef,
        version: parsedImage?.tag,
        digest: runningDigest ?? fallbackDigest,
        digestSource: runningDigest
            ? "repo-digest"
            : fallbackDigest
                ? "image-id"
                : undefined,
        registryDigest: regDigest,
        newVersion: updateAvailable(runningDigest, regDigest),
    };
    if (registry?.detail)
        report.registryDetail = registry.detail;
    return report;
}
function remoteApplications(runFn, compute) {
    const onceCheck = sshRun(runFn, compute, onceCommandCheckArgs);
    if (!onceCheck.ok) {
        return {
            ok: false,
            fatal: true,
            detail: resultDetail("once command check", onceCheck),
        };
    }
    const onceResult = sshRun(runFn, compute, ["sudo", "-n", "once", "list"]);
    if (!onceResult.ok) {
        return {
            ok: false,
            detail: resultDetail("once list", onceResult),
            ...(onceCommandNotFound(onceResult) ? { fatal: true } : {}),
        };
    }
    const onceApps = parseOnceList(onceResult.out);
    if (onceApps.length === 0)
        return { ok: true, applications: [] };
    const psResult = sshRun(runFn, compute, ["sudo", "-n", "docker", "ps", "-q"]);
    if (!psResult.ok) {
        return { ok: false, detail: resultDetail("docker ps", psResult) };
    }
    const ids = (psResult.out ?? "")
        .split("\n")
        .map((s) => s.trim())
        .filter((s) => s !== "");
    if (ids.length === 0) {
        return {
            ok: true,
            applications: onceApps.map((a) => ({
                ...a,
                status: a.status ?? "unknown",
                image: null,
                version: null,
                digest: null,
                registryDigest: null,
                newVersion: null,
            })),
        };
    }
    const containerResult = sshRun(runFn, compute, [
        "sudo",
        "-n",
        "docker",
        "inspect",
        "--type",
        "container",
        ...ids,
    ]);
    if (!containerResult.ok) {
        return { ok: false, detail: resultDetail("docker inspect", containerResult) };
    }
    const containers = parseJsonVector(containerResult.out);
    const imageIds = imageIdentifiers(containers);
    if (imageIds.length === 0) {
        return {
            ok: true,
            applications: onceApps.map((a) => applicationReport(runFn, containers, [], a)),
        };
    }
    const imageResult = sshRun(runFn, compute, [
        "sudo",
        "-n",
        "docker",
        "image",
        "inspect",
        ...imageIds,
    ]);
    if (!imageResult.ok) {
        return {
            ok: false,
            detail: resultDetail("docker image inspect", imageResult),
        };
    }
    const imageInfos = parseJsonVector(imageResult.out);
    return {
        ok: true,
        applications: onceApps.map((a) => applicationReport(runFn, containers, imageInfos, a)),
    };
}
// -------------------------------------------------------------- top-level
function resolveOnceOpts(opts, onceOptsFn) {
    try {
        return { opts: onceOptsFn(opts) };
    }
    catch (e) {
        return {
            opts,
            detail: `could not resolve OpenTofu parameters: ${e?.message}`,
        };
    }
}
/** Build a describe report from `opts`. */
export function describeReport(opts, runFn = run, onceOptsFn = onceOpts) {
    const resolved = resolveOnceOpts(opts, onceOptsFn);
    const resolvedOpts = syncAliases(resolved.opts);
    const params = paramsOf(resolvedOpts);
    const providers = providerSummary(params);
    let compute = computeStatus(runFn, params);
    if (resolved.detail) {
        compute = { ...compute, detail: `${compute.detail}; ${resolved.detail}` };
    }
    const appResult = compute.running
        ? remoteApplications(runFn, compute)
        : { ok: false, detail: "not checked because compute is not reachable" };
    return {
        profile: profileOf(resolvedOpts),
        providers,
        compute,
        applications: appResult.applications ?? [],
        applicationsError: appResult.ok ? undefined : appResult.detail,
        fatalError: Boolean(appResult.fatal),
    };
}
// -------------------------------------------------------------- reporting
function present(x) {
    return blank(x) ? "unknown" : String(x);
}
function updateLabel(x) {
    if (x === true)
        return "yes";
    if (x === false)
        return "no";
    return "unknown";
}
function printReport(result) {
    const { profile, providers, compute, applications, applicationsError } = result;
    console.log(`Profile: ${present(profile)}`);
    console.log("");
    console.log("Providers:");
    console.log(`  Compute: ${present(providers.compute)}`);
    console.log(`  Backend: ${present(providers.backend)}`);
    console.log(`  SMTP: ${present(providers.smtp)}`);
    console.log(`  DNS: ${present(providers.dns)}`);
    console.log("");
    console.log("Compute:");
    console.log(`  IP: ${present(compute.ip)}`);
    console.log(`  SSH user: ${present(compute.user)}`);
    console.log(`  Status: ${compute.running ? "running" : "not reachable"}${compute.detail ? ` (${compute.detail})` : ""}`);
    console.log("");
    if (applicationsError) {
        console.log(`Applications: ${applicationsError}.`);
    }
    else if (applications.length === 0) {
        console.log("Applications: none found.");
    }
    else {
        console.log("Applications:");
        for (const app of applications) {
            console.log(`  - ${present(app.host)}`);
            console.log(`    status: ${present(app.status)}`);
            console.log(`    image: ${present(app.image)}`);
            console.log(`    version: ${present(app.version)}`);
            console.log(`    digest: ${present(app.digest)}${app.digestSource === "image-id"
                ? " (image id; digest comparison unknown)"
                : ""}`);
            console.log(`    registry digest: ${present(app.registryDigest)}`);
            console.log(`    update available: ${updateLabel(app.newVersion)}`);
            if (app.registryDetail) {
                console.log(`    registry check: ${app.registryDetail}`);
            }
        }
    }
}
/** The `describe` workflow step. */
export function describe(_stepFns, opts, reportFn = describeReport) {
    const result = reportFn(opts);
    printReport(result);
    const base = { ...syncAliases(opts), "describe/result": result };
    return result.fatalError
        ? status(base, 1, result.applicationsError ?? "describe failed")
        : okAlias(base);
}
//# sourceMappingURL=describe.js.map