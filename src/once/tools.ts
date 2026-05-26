/** Tofu / Ansible tool workflows. */
import {
  ERR,
  EXIT,
  RENDER_TEMPLATES,
  RUN_SHELL_OPTS,
  WF_NAME,
  WF_OBJECT_FN,
  WF_OBJECT_PREFIX,
  WF_PARAMS,
  WF_PATH_FN,
  WF_PREFIX,
  WF_STEPS,
  type Opts,
  type StepFn,
} from "big-config";
import { Construct, addSuffix, construct } from "big-config/big-tofu/core";
import { registerHandleStep } from "big-config/pluggable";
import { templates as renderTemplates } from "big-config/render";
import { createExitStepFn, createPrintErrorStepFn } from "big-config/step-fns";
import { deepMerge, keywordToPath, sortNestedMap } from "big-config/utils";
import { parseArgs, prepare, printStepFn, runSteps } from "big-config/workflow";

const END = "big-config.workflow/end";

export const stepFns: StepFn[] = [
  printStepFn,
  createExitStepFn(END),
  createPrintErrorStepFn(END),
];

export const delimiters = {
  "tag-open": "<",
  "tag-close": ">",
  "filter-open": "{",
  "filter-close": "}",
};

export const TOFU = "io.github.bigconig-ai.once.tools/tofu";
export const TOFU_SMTP = "io.github.bigconig-ai.once.tools/tofu-smtp";
export const TOFU_DNS = "io.github.bigconig-ai.once.tools/tofu-dns";
export const TOFU_SMTP_POST = "io.github.bigconig-ai.once.tools/tofu-smtp-post";
export const ANSIBLE_LOCAL = "io.github.bigconig-ai.once.tools/ansible-local";
export const ANSIBLE = "io.github.bigconig-ai.once.tools/ansible";

export const pluginStep = "io.github.bigconig-ai.once.tools/render-tofu-backend";

function runStepsWithPlugin(plugin: string, sfns: StepFn[], opts: Opts): Opts {
  const steps: string[] = [];
  for (const step of opts[WF_STEPS] ?? []) {
    if (step === "render") steps.push(step, plugin);
    else steps.push(step);
  }
  return runSteps(sfns, { ...opts, [WF_STEPS]: steps });
}

function providerParam(opts: Opts, key: string, defaultValue: any): any {
  return (opts[WF_PARAMS] ?? {})[key] ?? defaultValue;
}

registerHandleStep(pluginStep, (_f, _step, sfns, opts) => {
  const providerBackend = providerParam(opts, "provider-backend", "s3");
  const prepareKeys = [WF_NAME, WF_PATH_FN, WF_PREFIX, WF_OBJECT_FN, WF_OBJECT_PREFIX, WF_PARAMS];
  const overrides: Opts = {};
  for (const k of prepareKeys) if (k in opts) overrides[k] = opts[k];
  const prepared = prepare(
    {
      [WF_NAME]: opts[WF_NAME],
      [RENDER_TEMPLATES]: [
        {
          template: keywordToPath("io.github.bigconig-ai.once.tools/tofu-backend"),
          overwrite: true,
          "provider-backend": providerBackend,
          transform: [[providerBackend, delimiters]],
        },
      ],
    },
    overrides,
  );
  const pluginOpts = renderTemplates(sfns, prepared);
  return {
    ...opts,
    [EXIT]: pluginOpts[EXIT],
    [ERR]: pluginOpts[ERR],
    [pluginStep]: [...(opts[pluginStep] ?? []), pluginOpts],
  };
});

function ipDataFn(data: Record<string, any>): Record<string, any> {
  return { ...data, ip: data.ip ?? "192.168.0.1" };
}

function cljJson(value: any, indent = 0): string {
  const sp = " ".repeat(indent);
  const child = " ".repeat(indent + 2);
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]";
    return [
      "[",
      ...value.map((v, i) => `${child}${cljJson(v, indent + 2)}${i < value.length - 1 ? "," : ""}`),
      `${sp}]`,
    ].join("\n");
  }
  if (value && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return "{ }";
    return [
      "{",
      ...entries.map(([k, v], i) => `${child}${JSON.stringify(k)} : ${cljJson(v, indent + 2)}${i < entries.length - 1 ? "," : ""}`),
      `${sp}}`,
    ].join("\n");
  }
  if (typeof value === "string") return JSON.stringify(value);
  if (value === true) return "true";
  if (value === false) return "false";
  if (value == null) return "null";
  return String(value);
}

/** Build the Cloudflare DNS records JSON from the SMTP records. */
export function renderFn(src: string, data: Record<string, any>): string {
  if (src !== "smtp") throw new Error(`unknown render-fn source: ${src}`);
  const constructs = (data.records ?? []).map((r: any) => {
    const { name, priority, record, type, value } = r;
    let block: Record<string, any> = {
      zone_id: "${data.cloudflare_zone.domain.id}",
      name,
      ttl: "1",
      type,
      proxied: false,
    };
    if (type === "TXT") block = { ...block, content: `"${value}"` };
    if (type === "MX") block = { ...block, priority, content: value };
    return construct(new Construct(
      "resource",
      "cloudflare_dns_record",
      addSuffix("io.github.bigconig-ai.once.tools/smtp-dns", `-${record}-${type}`),
      block,
    ));
  });
  const merged = constructs.length ? sortNestedMap(deepMerge(...constructs)) : {};
  return cljJson(merged);
}

function ansibleDataFn(data: Record<string, any>): Record<string, any> {
  const sudoer = data.sudoer ?? "root";
  const hosts = [data.ip ?? "64.227.72.100"];
  return { ...data, sudoer, hosts, users: [] };
}

function inventory(data: Record<string, any>): string {
  const { sudoer, hosts, users } = data;
  const liveUsers: any[] = (users ?? [])
    .filter((u: any) => !u.remove)
    .flatMap((u: any) => hosts.map((host: string) => ({ ...u, host })));
  const admins: any[] = [{ ansible_user: sudoer }].flatMap((a) =>
    hosts.map((host: string) => ({ ...a, host, name: sudoer })),
  );
  const usersHosts: Record<string, any> = {};
  for (const u of liveUsers) {
    usersHosts[`${u.name}@${u.host}`] = {
      ansible_host: u.host,
      ansible_user: u.name,
      uid: u.uid,
    };
  }
  const adminsHosts: Record<string, any> = {};
  for (const a of admins) {
    adminsHosts[`root@${a.host}`] = {
      ansible_host: a.host,
      ansible_user: a.name,
    };
  }
  return cljJson({ all: { children: { admin: { hosts: adminsHosts }, users: { hosts: usersHosts } } } });
}

function yamlScalar(v: any): string {
  if (v === null || v === undefined) return "null";
  if (typeof v === "boolean") return v ? "true" : "false";
  return String(v);
}

function yamlLines(value: any, indent: string): string[] {
  if (Array.isArray(value)) {
    const lines: string[] = [];
    for (const item of value) {
      if (item !== null && typeof item === "object" && !Array.isArray(item)) {
        const sub = yamlLines(item, `${indent}  `);
        if (sub.length === 0) {
          lines.push(`${indent}- {}`);
          continue;
        }
        lines.push(`${indent}- ${sub[0].slice(indent.length + 2)}`);
        for (let i = 1; i < sub.length; i++) lines.push(sub[i]);
      } else {
        lines.push(`${indent}- ${yamlScalar(item)}`);
      }
    }
    return lines;
  }
  if (value !== null && typeof value === "object") {
    const lines: string[] = [];
    for (const [k, v] of Object.entries(value)) {
      if (Array.isArray(v)) {
        lines.push(`${indent}${k}:`);
        lines.push(...yamlLines(v, indent));
      } else if (v !== null && typeof v === "object") {
        lines.push(`${indent}${k}:`);
        lines.push(...yamlLines(v, `${indent}  `));
      } else {
        lines.push(`${indent}${k}: ${yamlScalar(v)}`);
      }
    }
    return lines;
  }
  return [`${indent}${yamlScalar(value)}`];
}

function toYaml(value: any): string {
  return `${yamlLines(value, "").join("\n")}\n`;
}

function ansibleOnce(data: Record<string, any>): string {
  const { once, domain } = data;
  const smtp: Record<string, any> = {};
  for (const k of ["smtp_server", "smtp_port", "smtp_username", "smtp_password"]) {
    if (k in data) smtp[k] = data[k];
  }
  smtp.smtp_from = `Info <info@notifications.${domain}>`;
  return toYaml([
    {
      name: "Reconcile ONCE applications",
      become: true,
      once: {
        ...once,
        applications: (once?.applications ?? []).map((app: any) => ({ ...app, ...smtp })),
      },
    },
  ]);
}

/** Multi-target render function for the Ansible inventory and ONCE task file. */
export function render(target: string, data: Record<string, any>): string {
  if (target === "inventory") return inventory(data);
  if (target === "ansible-once") return ansibleOnce(data);
  throw new Error(`unknown render target: ${target}`);
}

export function tofu(sfns: StepFn[], opts: Opts): Opts {
  const providerCompute = providerParam(opts, "provider-compute", "hcloud");
  const prepared = prepare(
    {
      [WF_NAME]: TOFU,
      [RENDER_TEMPLATES]: [
        {
          template: keywordToPath(TOFU),
          overwrite: true,
          "provider-compute": providerCompute,
          "compute-prevent-destroy": true,
          transform: [[providerCompute, delimiters]],
        },
      ],
    },
    opts,
  );
  return runStepsWithPlugin(pluginStep, sfns, prepared);
}

export function tofuSmtp(sfns: StepFn[], opts: Opts): Opts {
  const providerSmtp = providerParam(opts, "provider-smtp", "resend");
  const prepared = prepare(
    {
      [WF_NAME]: TOFU_SMTP,
      [RENDER_TEMPLATES]: [
        {
          template: keywordToPath(TOFU_SMTP),
          overwrite: true,
          "data-fn": ipDataFn,
          "provider-smtp": providerSmtp,
          transform: [[providerSmtp, delimiters]],
        },
      ],
    },
    opts,
  );
  return runStepsWithPlugin(pluginStep, sfns, prepared);
}

export function tofuDns(sfns: StepFn[], opts: Opts): Opts {
  const providerDns = providerParam(opts, "provider-dns", "cloudflare");
  const prepared = prepare(
    {
      [WF_NAME]: TOFU_DNS,
      [RENDER_TEMPLATES]: [
        {
          template: keywordToPath(TOFU_DNS),
          overwrite: true,
          "data-fn": ipDataFn,
          "provider-dns": providerDns,
          transform: [
            [providerDns, delimiters],
            [renderFn, { smtp: "smtp.tf.json" }, delimiters],
          ],
        },
      ],
    },
    opts,
  );
  return runStepsWithPlugin(pluginStep, sfns, prepared);
}

export function tofuSmtpPost(sfns: StepFn[], opts: Opts): Opts {
  const providerSmtp = providerParam(opts, "provider-smtp", "resend");
  const prepared = prepare(
    {
      [WF_NAME]: TOFU_SMTP_POST,
      [RENDER_TEMPLATES]: [
        {
          template: keywordToPath(TOFU_SMTP_POST),
          overwrite: true,
          "provider-smtp": providerSmtp,
          transform: [[providerSmtp, delimiters]],
        },
      ],
    },
    opts,
  );
  return runStepsWithPlugin(pluginStep, sfns, prepared);
}

export function ansible(sfns: StepFn[], opts: Opts): Opts {
  const prepared = prepare(
    {
      [WF_NAME]: ANSIBLE,
      [RENDER_TEMPLATES]: [
        {
          template: keywordToPath(ANSIBLE),
          overwrite: true,
          "data-fn": ansibleDataFn,
          transform: [
            [".", delimiters],
            [render, { inventory: "inventory.json", "ansible-once": "once.yml" }, delimiters],
          ],
        },
      ],
    },
    opts,
  );
  return runSteps(sfns, prepared);
}

export function ansibleLocal(sfns: StepFn[], opts: Opts): Opts {
  const prepared = prepare(
    {
      [WF_NAME]: ANSIBLE_LOCAL,
      [RENDER_TEMPLATES]: [
        {
          template: keywordToPath(ANSIBLE_LOCAL),
          overwrite: true,
          transform: [["."]],
        },
      ],
    },
    opts,
  );
  return runSteps(sfns, prepared);
}

function toolStar(
  fn: (sfns: StepFn[], opts: Opts) => Opts,
): (args: string | string[], opts?: Opts) => Opts {
  return (args, opts = {}) => fn(stepFns, { ...parseArgs(args), "big-config/env": "shell", ...opts });
}

export const tofuStar = toolStar(tofu);
export const tofuSmtpStar = toolStar(tofuSmtp);
export const tofuDnsStar = toolStar(tofuDns);
export const tofuSmtpPostStar = toolStar(tofuSmtpPost);
export const ansibleStar = toolStar(ansible);
export const ansibleLocalStar = toolStar(ansibleLocal);

export { runStepsWithPlugin };
