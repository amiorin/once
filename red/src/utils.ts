import type { Opts } from "red/workflow";

// Bump on any change a launcher pinned to an older commit could not survive.
//
// 3: deploy keys are generated per application on every create instead of being
//    supplied as deploy-pubkey, and an application may name a GitHub repository
//    whose environment receives the connection details. A launcher pinned older
//    still expects deploy-pubkey in desired state and would ignore github
//    silently, publishing nothing.
export const contract = 3;

export function registrableDomain(host: unknown): string | undefined {
  const labels = String(host ?? "").split(".");
  return labels.length >= 2 ? labels.slice(-2).join(".") : undefined;
}

export function appsDomains(opts: Opts): string[] {
  const apps = (opts.once as { applications?: Array<{ host?: string }> } | undefined)?.applications ?? [];
  return [...new Set(apps.map((app) => registrableDomain(app.host)).filter(Boolean) as string[])].sort();
}
