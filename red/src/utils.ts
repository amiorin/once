import type { Opts } from "red/workflow";

export const contract = 2;

export function registrableDomain(host: unknown): string | undefined {
  const labels = String(host ?? "").split(".");
  return labels.length >= 2 ? labels.slice(-2).join(".") : undefined;
}

export function appsDomains(opts: Opts): string[] {
  const apps = (opts.once as { applications?: Array<{ host?: string }> } | undefined)?.applications ?? [];
  return [...new Set(apps.map((app) => registrableDomain(app.host)).filter(Boolean) as string[])].sort();
}
