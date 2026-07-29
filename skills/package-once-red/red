#!/usr/bin/env bun
// Thin launcher for package-once-red. Domain logic lives in the installable
// package so the copied skill payload remains testable and replaceable.
import { existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { homedir } from "node:os";

// The commits a copied payload must resolve. Managed by `bb pin` — do not edit
// by hand, and keep exactly one occurrence of each: `pin` rewrites the first
// match and a second copy would silently go stale.
//
// These are live code rather than a comment because the launcher resolves them
// itself (see below). They stay out of a bundled package.json in this
// directory, which would halt Bun's upward resolution of `package-once-red` and
// break the development symlink at red/red.
const PINS = {
  "package-once-red": "github:bigconfig-ai/once#39f00e20044d5e1c3c674b430ae41c3acff18b9d",
  "red": "github:amiorin/red#b434e37568b91228ef14c2271f6fbeea805ae7ae",
};

// Red is the one colour whose dependencies were a separate step: green resolves
// its git deps through add-deps and blue through uv's inline metadata, so only
// here could a copied payload sit beside a manifest it never installed. It now
// resolves them the same way — into a cache on first run — so `./red` works
// from a fresh clone like `./green` and `./blue` do.
//
// A static import would fail during resolution, before any line of this file
// runs, with a bare "Cannot find package" naming no fix. The import stays
// dynamic so the failure can be answered instead of reported. The answer cannot
// live in the package for the obvious reason: the package is what is missing.

/** The nearest directory at or above `from` holding a package.json, or null. */
function manifestDir(from) {
  let dir = from;
  for (;;) {
    if (existsSync(join(dir, "package.json"))) return dir;
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

function readManifest(dir) {
  try {
    return JSON.parse(readFileSync(join(dir, "package.json"), "utf8"));
  } catch {
    return null;
  }
}

/** Dependencies to resolve, and where they came from.
 *
 * A project that names these dependencies owns their versions — resolving the
 * launcher's pins instead would silently run a different commit than the one
 * its manifest and lockfile record. The launcher's own pins are the fallback
 * for a payload dropped somewhere that declares nothing.
 */
function effectiveDeps(root) {
  const manifest = root ? readManifest(root) : null;
  const declared = manifest?.dependencies?.["package-once-red"];
  if (!declared) return { deps: PINS, source: "this launcher's pins" };
  return {
    deps: {
      "package-once-red": declared,
      red: manifest.dependencies?.red ?? PINS.red,
    },
    source: `${join(root, "package.json")}`,
  };
}

/** Install `deps` into a cache keyed by their exact specifiers.
 *
 * Keyed by content, so re-pinning lands in a new directory instead of reusing a
 * stale tree, and two projects on different pins never share one. Staged in a
 * sibling and renamed, so concurrent cold starts cannot observe a half-installed
 * tree; whichever loses the rename discards its copy and uses the winner's,
 * which is byte-identical by construction.
 */
function installToCache(deps, source) {
  const home = process.env.XDG_CACHE_HOME || join(homedir(), ".cache");
  const root = join(home, "package-once-red");
  const key = Bun.hash(JSON.stringify(deps)).toString(16);
  const target = join(root, key);
  if (existsSync(join(target, "node_modules"))) return { dir: target };

  // Announced only when something is actually fetched: every later run takes
  // the branch above, and a line claiming a first run on each of them would be
  // noise on the way to every command's real output.
  console.error(`red: resolving dependencies from ${source} (first run)`);
  mkdirSync(root, { recursive: true });
  const staging = mkdtempSync(join(root, `.${key}.`));
  writeFileSync(
    join(staging, "package.json"),
    `${JSON.stringify({ name: "package-once-red-cache", private: true, dependencies: deps }, null, 2)}\n`,
  );
  const installed = Bun.spawnSync([process.execPath, "install"], {
    cwd: staging,
    stdout: "ignore",
    stderr: "pipe",
  });
  if (installed.exitCode !== 0) {
    rmSync(staging, { recursive: true, force: true });
    return { error: installed.stderr.toString().trim() };
  }
  try {
    renameSync(staging, target);
  } catch {
    // Another cold start won the race; its tree is equivalent.
    rmSync(staging, { recursive: true, force: true });
  }
  return existsSync(join(target, "node_modules"))
    ? { dir: target }
    : { error: `nothing installed under ${target}` };
}

let once;
try {
  once = await import("package-once-red");
} catch (err) {
  // Only the launcher-as-script may exit; an importer keeps ESM semantics.
  if (err?.code !== "ERR_MODULE_NOT_FOUND" || !import.meta.main) throw err;

  // Resolution starts beside the launcher, not at the caller, so the answer is
  // the same from any subdirectory a colour was invoked from.
  const root = manifestDir(import.meta.dir);
  const manifest = root ? readManifest(root) : null;

  // Inside a checkout of the package itself, the working tree is the point:
  // resolving a pinned copy from the cache would quietly test the pinned commit
  // instead of the edits under test. Say what is missing and stop.
  const inCheckout = manifest?.name === "package-once-red";
  if (inCheckout || process.env.RED_NO_BOOTSTRAP) {
    console.error(
      `red: cannot resolve '${err.specifier}'\n` +
        (root
          ? `dependencies are not installed; run: bun install --cwd ${root}`
          : `no package.json at or above ${import.meta.dir}; write one whose\n` +
            `dependencies are the two lines quoted in this file's PINS, then\n` +
            `run: bun install`),
    );
    process.exit(2);
  }

  const { deps, source } = effectiveDeps(root);
  const { dir, error } = installToCache(deps, source);
  if (error) {
    console.error(`red: could not resolve dependencies\n${error}`);
    process.exit(2);
  }
  // Resolve by name from the cache root rather than importing its directory:
  // the package is exports-only, and "exports" applies to bare specifiers, not
  // to a path.
  once = await import(Bun.resolveSync("package-once-red", dir));
}

export const run = once.run;
if (import.meta.main) await once.exec();
