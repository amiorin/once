/**
 * Template engine: copies resource templates into a target directory,
 * substituting `{{ var }}` (or custom-delimited) placeholders.
 * Ported from big-config.render.
 */
import {
  chmodSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { type Opts, type WfStep, ok, toWorkflow } from "./core.js";

export interface Delimiters {
  tagOpen: string;
  tagClose: string;
  filterOpen: string;
  filterClose: string;
}

const DEFAULT_DELIMITERS: Delimiters = {
  tagOpen: "{",
  tagClose: "}",
  filterOpen: "{",
  filterClose: "}",
};

function isDelimiters(x: unknown): x is Delimiters {
  return typeof x === "object" && x !== null && "tagOpen" in x;
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Render `{{ var }}`-style placeholders against `data`. */
export function selmer(
  content: string,
  data: Record<string, any>,
  delimiters: Delimiters = DEFAULT_DELIMITERS,
): string {
  const open = escapeRegExp(delimiters.tagOpen + delimiters.filterOpen);
  const close = escapeRegExp(delimiters.filterClose + delimiters.tagClose);
  const re = new RegExp(`${open}\\s*([A-Za-z0-9_.-]+)\\s*${close}`, "g");
  return content.replace(re, (_match, key: string) => {
    const v = data[key];
    return v === undefined || v === null ? "" : String(v);
  });
}

const NON_REPLACED_EXTS = new Set(["jpg", "jpeg", "png", "gif", "bmp", "bin"]);

function extensionOf(path: string): string {
  const base = path.slice(path.lastIndexOf("/") + 1);
  const dot = base.lastIndexOf(".");
  return dot > 0 ? base.slice(dot + 1).toLowerCase() : "";
}

function* walkFiles(dir: string): Generator<string> {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      yield* walkFiles(full);
    } else if (entry.isFile()) {
      yield full;
    }
  }
}

function copyDir({
  srcDir,
  targetDir,
  data,
  delimiters,
}: {
  srcDir: string;
  targetDir: string;
  data?: Record<string, any>;
  delimiters?: Delimiters;
}): void {
  for (const file of walkFiles(srcDir)) {
    const targetFile = join(targetDir, relative(srcDir, file));
    mkdirSync(dirname(targetFile), { recursive: true });
    let content = readFileSync(file, "utf8");
    if (data && !NON_REPLACED_EXTS.has(extensionOf(file))) {
      content = selmer(content, data, delimiters);
    }
    writeFileSync(targetFile, content);
    chmodSync(targetFile, statSync(file).mode);
  }
}

/** A template `src`: a directory string or a content-producing function. */
export type TransformSrc =
  | string
  | ((key: string, data: Record<string, any>) => string);

function copyTemplateDir({
  templateDir,
  targetDir,
  data,
  src,
  target,
  files,
  delimiters,
  raw,
}: {
  templateDir: string;
  targetDir: string;
  data: Record<string, any>;
  src: TransformSrc;
  target?: string;
  files?: Record<string, string>;
  delimiters?: Delimiters;
  raw: boolean;
}): void {
  if (typeof src === "function") {
    if (!files) {
      throw new Error("files is required when src is a function");
    }
    for (const [key, to] of Object.entries(files)) {
      let content = src(key, data);
      if (!raw) content = selmer(content, data, delimiters);
      const targetFile = join(
        targetDir + (target ? selmer(target, data) : ""),
        selmer(to, data),
      );
      mkdirSync(dirname(targetFile), { recursive: true });
      writeFileSync(targetFile, content);
    }
    return;
  }
  const srcDir = join(templateDir, selmer(src, data));
  copyDir({
    srcDir,
    targetDir: targetDir + (target ? `/${selmer(target, data)}` : ""),
    data: raw ? undefined : data,
    delimiters,
  });
}

let resourcesRoot: string | null = null;

function resourcesDir(): string {
  if (resourcesRoot) return resourcesRoot;
  let dir = dirname(fileURLToPath(import.meta.url));
  while (!existsSync(join(dir, "package.json"))) {
    const parent = dirname(dir);
    if (parent === dir) throw new Error("could not locate project root");
    dir = parent;
  }
  resourcesRoot = join(dir, "src", "resources");
  return resourcesRoot;
}

/** A parsed transform tuple. */
export type Transform = [TransformSrc, ...unknown[]];

const TEMPLATE_KEYS = [
  "template",
  "targetDir",
  "overwrite",
  "dataFn",
  "templateFn",
  "postProcessFn",
  "transform",
];

export interface TemplateEdn {
  template: string;
  targetDir: string;
  overwrite?: boolean | "delete";
  dataFn?: (data: Record<string, any>, opts: Opts) => Record<string, any>;
  transform: Transform[];
  [key: string]: any;
}

/** The functional template engine: render every template in `opts.templates`. */
export function render(opts: Opts): Opts {
  const templates: TemplateEdn[] | undefined = opts.templates;
  if (templates == null) {
    throw new Error("templates should never be nil");
  }
  for (const edn of templates) {
    const dataFn = edn.dataFn ?? ((d: Record<string, any>) => d);
    const data: Record<string, any> = {};
    for (const [k, v] of Object.entries(edn)) {
      if (!TEMPLATE_KEYS.includes(k)) data[k] = v;
    }
    data.module = opts.module ?? null;
    data.profile = opts.profile ?? null;
    const finalData = dataFn(data, opts);

    const templateDir = join(resourcesDir(), edn.template);
    if (existsSync(edn.targetDir)) {
      if (edn.overwrite) {
        if (edn.overwrite === "delete") {
          rmSync(edn.targetDir, { recursive: true, force: true });
        }
      } else {
        throw new Error(
          `${edn.targetDir} already exists (and overwrite was not true).`,
        );
      }
    }
    for (const transform of edn.transform) {
      const [src, ...rest] = transform;
      let target: string | undefined;
      let files: Record<string, string> | undefined;
      let delimiters: Delimiters | undefined;
      let raw = false;
      for (const item of rest) {
        if (item === "raw") raw = true;
        else if (item === "only") continue;
        else if (typeof item === "string") target = item;
        else if (isDelimiters(item)) delimiters = item;
        else if (item && typeof item === "object") {
          files = item as Record<string, string>;
        }
      }
      copyTemplateDir({
        templateDir,
        targetDir: edn.targetDir,
        data: finalData,
        src,
        target,
        files,
        delimiters,
        raw,
      });
    }
  }
  return ok(opts);
}

/** The `render` workflow step. */
export const renderTemplates = toWorkflow({
  firstStep: "big-config.render/start",
  wireFn: (step): [WfStep, string | null] =>
    step === "big-config.render/start"
      ? [render, "big-config.render/end"]
      : [(o) => o, null],
});
