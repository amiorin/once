/** OpenTofu/Terraform construct helpers. Ported from big-tofu.core. */

/** Sanitize a `ns/name` keyword string into a Terraform-safe identifier. */
export function fqnToName(fqn: string, sep = "_"): string {
  const sanitize = (s: string) => s.replace(/[-.]/g, sep);
  const slash = fqn.indexOf("/");
  const ns = slash >= 0 ? sanitize(fqn.slice(0, slash)) : null;
  const name = sanitize(slash >= 0 ? fqn.slice(slash + 1) : fqn);
  return (ns ?? "") + (ns ? sep : "") + name;
}

/** Append a suffix to the `name` part of a `ns/name` keyword string. */
export function addSuffix(fqn: string, suffix: string): string {
  return fqn + suffix;
}

export interface Construct {
  group: string;
  type: string;
  fqn: string;
  block: Record<string, any>;
}

/** Build a Construct (Terraform resource/data descriptor). */
export function makeConstruct(
  group: string,
  type: string,
  fqn: string,
  block: Record<string, any>,
): Construct {
  return { group, type, fqn, block };
}

/** Render a Construct into its nested `{group: {type: {name: block}}}` map. */
export function construct(c: Construct): Record<string, any> {
  return { [c.group]: { [c.type]: { [fqnToName(c.fqn)]: c.block } } };
}
