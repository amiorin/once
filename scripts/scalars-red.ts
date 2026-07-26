// Print how red's YAML reader typed every scalar in the parity corpus, one
// `key=type:value` line per entry. Green and blue print the same shape, so
// parity.sh can diff them directly.
import { readFileSync } from "node:fs";

function describe(value: unknown): string {
  if (value === null || value === undefined) return "null:";
  if (typeof value === "boolean") return `bool:${value}`;
  if (typeof value === "number") return Number.isInteger(value) ? `int:${value}` : `float:${value}`;
  if (typeof value === "string") return `string:${value}`;
  return `other:${JSON.stringify(value)}`;
}

const path = Bun.argv[2]!;
const state = (Bun.YAML.parse(readFileSync(path, "utf8")) ?? {}) as Record<string, unknown>;
for (const key of Object.keys(state).sort()) {
  console.log(`${key}=${describe(state[key])}`);
}
