import { readPars } from "red/cli";
import type { Opts } from "red/workflow";

export const contract = 1;

export function readOncePars(
  opts: Opts,
  env: Record<string, string | undefined> = process.env,
): Opts {
  const portable = Object.fromEntries(Object.entries(env).flatMap(([name, value]) =>
    name.startsWith("ONCE_PAR_") ? [[`RED_PAR_${name.slice("ONCE_PAR_".length)}`, value]] : []));
  return readPars(readPars(opts, env), portable);
}

export function registrableDomain(host: unknown): string | undefined {
  const labels = String(host ?? "").split(".");
  return labels.length >= 2 ? labels.slice(-2).join(".") : undefined;
}

export function appsDomains(opts: Opts): string[] {
  const apps = (opts.once as { applications?: Array<{ host?: string }> } | undefined)?.applications ?? [];
  return [...new Set(apps.map((app) => registrableDomain(app.host)).filter(Boolean) as string[])].sort();
}
