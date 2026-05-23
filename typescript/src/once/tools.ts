/** Tofu / Ansible tool workflows. */
import type { Opts, StepFn } from "../bc/core.js";
import { addSuffix, construct, makeConstruct } from "../bc/big-tofu.js";
import { type Delimiters, renderTemplates } from "../bc/render.js";
import { registerStep } from "../bc/pluggable.js";
import { exitStepFn, printErrorStepFn } from "../bc/step-fns.js";
import { deepMerge, keywordToPath, sortNestedMap } from "../bc/utils.js";
import { parseArgs, prepare, printStepFn, runSteps } from "../bc/workflow.js";

const END = "big-config.workflow/end";

export const stepFns: StepFn[] = [
  printStepFn,
  exitStepFn(END),
  printErrorStepFn(END),
];

/** Custom delimiters for file content: `<{ var }>`. */
export const delimiters: Delimiters = {
  tagOpen: "<",
  tagClose: ">",
  filterOpen: "{",
  filterClose: "}",
};

/** The remote-state backend plugin step keyword. */
export const pluginStep = "io.github.amiorin.once.tools/render-tofu-backend";

function runStepsWithPlugin(
  plugin: string,
  sfns: StepFn[],
  opts: Opts,
): Opts {
  const steps: string[] = (opts.steps ?? []).reduce(
    (acc: string[], step: string) =>
      step === "render" ? [...acc, step, plugin] : [...acc, step],
    [],
  );
  return runSteps(sfns, { ...opts, steps });
}

registerStep(pluginStep, (_f, _step, sfns, opts) => {
  const prepareKeys = [
    "name",
    "pathFn",
    "prefix",
    "objectFn",
    "objectPrefix",
    "params",
  ];
  const overrides: Opts = {};
  for (const k of prepareKeys) {
    if (k in opts) overrides[k] = opts[k];
  }
  const prepared = prepare(
    {
      name: opts.name,
      templates: [
        {
          template: keywordToPath("io.github.amiorin.once.tools/tofu-backend"),
          overwrite: true,
          "provider-backend": "s3",
          transform: [["{{ provider-backend }}", delimiters]],
        },
      ],
    },
    overrides,
  );
  const pluginOpts = renderTemplates(sfns, prepared);
  return {
    ...opts,
    exit: pluginOpts.exit,
    err: pluginOpts.err,
    [pluginStep]: [...(opts[pluginStep] ?? []), pluginOpts],
  };
});

function ipDataFn(data: Record<string, any>): Record<string, any> {
  return { ...data, ip: data.ip ?? "192.168.0.1" };
}

/** Build the Cloudflare DNS records JSON from the SMTP records. */
export function renderFn(src: string, data: Record<string, any>): string {
  if (src !== "smtp") {
    throw new Error(`unknown render-fn source: ${src}`);
  }
  const records: any[] = data.records ?? [];
  const constructs = records.map((r) => {
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
    return construct(
      makeConstruct(
        "resource",
        "cloudflare_dns_record",
        addSuffix(
          "io.github.amiorin.once.tools/smtp-dns",
          `-${record}-${type}`,
        ),
        block,
      ),
    );
  });
  const merged = constructs.length
    ? sortNestedMap(deepMerge(...constructs))
    : {};
  return JSON.stringify(merged, null, 2);
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
  const inv = {
    all: {
      children: {
        admin: { hosts: adminsHosts },
        users: { hosts: usersHosts },
      },
    },
  };
  return JSON.stringify(inv, null, 2);
}

function yamlScalar(v: any): string {
  if (v === null || v === undefined) return "null";
  if (typeof v === "boolean") return v ? "true" : "false";
  if (typeof v === "number") return String(v);
  return JSON.stringify(String(v));
}

function yamlLines(value: any, indent: string): string[] {
  if (Array.isArray(value)) {
    const lines: string[] = [];
    for (const item of value) {
      if (item !== null && typeof item === "object") {
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
  const tasks = [
    {
      name: "Reconcile ONCE applications",
      become: true,
      once: {
        ...once,
        applications: (once?.applications ?? []).map((app: any) => ({
          ...app,
          ...smtp,
        })),
      },
    },
  ];
  return toYaml(tasks);
}

/** Multi-target render function for the Ansible inventory and ONCE task file. */
export function render(target: string, data: Record<string, any>): string {
  if (target === "inventory") return inventory(data);
  if (target === "ansible-once") return ansibleOnce(data);
  throw new Error(`unknown render target: ${target}`);
}

export function tofu(sfns: StepFn[], opts: Opts): Opts {
  const prepared = prepare(
    {
      name: "io.github.amiorin.once.tools/tofu",
      templates: [
        {
          template: keywordToPath("io.github.amiorin.once.tools/tofu"),
          overwrite: true,
          "provider-compute": "hcloud",
          "compute-prevent-destroy": true,
          transform: [["{{ provider-compute }}", delimiters]],
        },
      ],
    },
    opts,
  );
  return runStepsWithPlugin(pluginStep, sfns, prepared);
}

export function tofuSmtp(sfns: StepFn[], opts: Opts): Opts {
  const prepared = prepare(
    {
      name: "io.github.amiorin.once.tools/tofu-smtp",
      templates: [
        {
          template: keywordToPath("io.github.amiorin.once.tools/tofu-smtp"),
          overwrite: true,
          dataFn: ipDataFn,
          "provider-smtp": "resend",
          transform: [["{{ provider-smtp }}", delimiters]],
        },
      ],
    },
    opts,
  );
  return runStepsWithPlugin(pluginStep, sfns, prepared);
}

export function tofuDns(sfns: StepFn[], opts: Opts): Opts {
  const prepared = prepare(
    {
      name: "io.github.amiorin.once.tools/tofu-dns",
      templates: [
        {
          template: keywordToPath("io.github.amiorin.once.tools/tofu-dns"),
          overwrite: true,
          dataFn: ipDataFn,
          "provider-dns": "cloudflare",
          transform: [
            ["{{ provider-dns }}", delimiters],
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
  const prepared = prepare(
    {
      name: "io.github.amiorin.once.tools/tofu-smtp-post",
      templates: [
        {
          template: keywordToPath(
            "io.github.amiorin.once.tools/tofu-smtp-post",
          ),
          overwrite: true,
          "provider-smtp": "resend",
          transform: [["{{ provider-smtp }}", delimiters]],
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
      name: "io.github.amiorin.once.tools/ansible",
      templates: [
        {
          template: keywordToPath("io.github.amiorin.once.tools/ansible"),
          overwrite: true,
          dataFn: ansibleDataFn,
          transform: [
            [".", delimiters],
            [
              render,
              { inventory: "inventory.json", "ansible-once": "once.yml" },
              delimiters,
            ],
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
      name: "io.github.amiorin.once.tools/ansible-local",
      templates: [
        {
          template: keywordToPath(
            "io.github.amiorin.once.tools/ansible-local",
          ),
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
  return (args, opts = {}) => {
    const { steps, cmds } = parseArgs(args);
    return fn(stepFns, { steps, cmds, env: "shell", ...opts });
  };
}

export const tofuStar = toolStar(tofu);
export const tofuSmtpStar = toolStar(tofuSmtp);
export const tofuDnsStar = toolStar(tofuDns);
export const tofuSmtpPostStar = toolStar(tofuSmtpPost);
export const ansibleStar = toolStar(ansible);
export const ansibleLocalStar = toolStar(ansibleLocal);
