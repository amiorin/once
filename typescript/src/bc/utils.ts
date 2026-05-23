/** Shared utilities. Ported from big-config.utils. */

function isPlainObject(x: unknown): x is Record<string, any> {
  return typeof x === "object" && x !== null && !Array.isArray(x);
}

/** Recursively merge plain objects. Later values win; arrays are replaced. */
export function deepMerge(...maps: Record<string, any>[]): Record<string, any> {
  const merge = (a: any, b: any): any => {
    if (isPlainObject(a) && isPlainObject(b)) {
      const out: Record<string, any> = { ...a };
      for (const [k, v] of Object.entries(b)) {
        out[k] = k in out ? merge(out[k], v) : v;
      }
      return out;
    }
    return b;
  };
  return maps.reduce((acc, m) => merge(acc, m), {});
}

/** Recursively sort map keys for deterministic serialization. */
export function sortNestedMap(m: any): any {
  if (isPlainObject(m)) {
    const out: Record<string, any> = {};
    for (const k of Object.keys(m).sort()) {
      out[k] = sortNestedMap(m[k]);
    }
    return out;
  }
  if (Array.isArray(m)) return m.map(sortNestedMap);
  return m;
}

/**
 * Convert a `ns/name` keyword string into a file path.
 * Example: `big-config.core/foo` -> `big-config/core/foo`.
 */
export function keywordToPath(kw: string): string {
  return kw.replace(/\./g, "/");
}

/**
 * Convert a `ns/name` keyword string into a file name.
 * Example: `big-config.core/foo` -> `big-config-core-foo`.
 */
export function keywordToName(kw: string): string {
  return kw.replace(/[/.]/g, "-");
}
