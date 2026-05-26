/** Parameter extraction from OpenTofu outputs. */
import { spawnSync } from "node:child_process";
import * as bcWorkflow from "big-config/workflow";
import { PARAMS, syncAliases, toBcOpts } from "./interop.js";
const START_STEP = "io.github.bigconig-ai.once.package/start-create-or-delete";
const TOFU = "io.github.bigconig-ai.once.tools/tofu";
const TOFU_SMTP = "io.github.bigconig-ai.once.tools/tofu-smtp";
function tofuOutput(dir) {
    try {
        const res = spawnSync("tofu", ["output", "--json"], {
            cwd: dir,
            encoding: "utf8",
        });
        if (res.error || res.status !== 0)
            return undefined;
        const value = JSON.parse(res.stdout || "{}")?.params?.value;
        return value && typeof value === "object" ? value : undefined;
    }
    catch {
        return undefined;
    }
}
function fallbackComputeParams(params) {
    const name = params.package ?? "once";
    switch (params["provider-compute"]) {
        case "oci":
            return { ip: "192.168.0.1", sudoer: "ubuntu", uid: "1001", name, user: "ubuntu" };
        case "no-infra": {
            const out = {
                ip: params["no-infra-compute-ip"] ?? "192.168.0.1",
                sudoer: params["no-infra-compute-sudoer"] ?? "root",
                name,
                user: params["no-infra-compute-user"] ?? "root",
            };
            if (params["no-infra-compute-uid"] != null)
                out.uid = params["no-infra-compute-uid"];
            return out;
        }
        default:
            return { ip: "192.168.0.1", sudoer: "root", name, user: "root" };
    }
}
function fallbackSmtpParams(params) {
    const out = { id: "domain-id-not-defined", records: [] };
    switch (params["provider-smtp"]) {
        case "no-infra":
            return {
                ...out,
                smtp_username: params["no-infra-smtp-username"],
                smtp_password: params["no-infra-smtp-password"],
                smtp_server: params["no-infra-smtp-server"],
                smtp_port: params["no-infra-smtp-port"],
                smtp_use_starttls: true,
            };
        case "resend":
            return {
                ...out,
                smtp_username: params["resend-username"],
                smtp_password: params["resend-password"],
                smtp_server: params["resend-server"],
                smtp_port: params["resend-port"],
                smtp_use_starttls: true,
            };
        default:
            return out;
    }
}
function mergeParams(opts, newParams) {
    return { ...opts, [PARAMS]: { ...(opts[PARAMS] ?? {}), ...newParams } };
}
/** Merge the IP (and other compute outputs) from the `tofu` stage. */
export function tofuParams(opts0) {
    const opts = toBcOpts(opts0);
    const params = opts[PARAMS] ?? {};
    const dir = bcWorkflow.path(opts, TOFU);
    return syncAliases(mergeParams(opts, { ...fallbackComputeParams(params), ...(tofuOutput(dir) ?? {}) }));
}
/** Merge the SMTP records / domain id from the `tofu-smtp` stage. */
export function tofuSmtpParams(opts0) {
    const opts = toBcOpts(opts0);
    const params = opts[PARAMS] ?? {};
    const dir = bcWorkflow.path(opts, TOFU_SMTP);
    return syncAliases(mergeParams(opts, { ...fallbackSmtpParams(params), ...(tofuOutput(dir) ?? {}) }));
}
/** Compose env overrides with the SMTP and compute Tofu outputs. */
export function optsFn(opts) {
    const withEnv = syncAliases(bcWorkflow.readBcPars(toBcOpts(opts)));
    return syncAliases(tofuParams(tofuSmtpParams(withEnv)));
}
/** `optsFn` after stamping the deterministic prefix for the create/delete workflow. */
export function onceOpts(opts) {
    return optsFn(bcWorkflow.newPrefix(toBcOpts(opts), START_STEP));
}
//# sourceMappingURL=params.js.map