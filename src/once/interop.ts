import {
  ERR,
  EXIT,
  RENDER_PROFILE,
  WF_PARAMS,
  ok,
  type Opts,
} from "big-config";

export const PROFILE = RENDER_PROFILE;
export const PARAMS = WF_PARAMS;

export function toBcOpts(opts: Opts = {}): Opts {
  const out: Opts = { ...opts };
  if ("profile" in out) out[PROFILE] = out.profile;
  else if (PROFILE in out) out.profile = out[PROFILE];
  if ("params" in out) out[PARAMS] = out.params;
  else if (PARAMS in out) out.params = out[PARAMS];
  return out;
}

export function syncAliases(opts: Opts = {}): Opts {
  const out: Opts = { ...opts };
  if (PROFILE in out) out.profile = out[PROFILE];
  if (PARAMS in out) out.params = out[PARAMS];
  if (EXIT in out) out.exit = out[EXIT];
  if (ERR in out) out.err = out[ERR];
  return out;
}

export function paramsOf(opts: Opts = {}): Record<string, any> {
  return { ...(toBcOpts(opts)[PARAMS] ?? {}) };
}

export function profileOf(opts: Opts = {}): any {
  return toBcOpts(opts)[PROFILE];
}

export function okAlias(opts: Opts = {}): Opts {
  return syncAliases(ok(opts));
}

export function status(opts: Opts = {}, exitCode: number, err: any = null): Opts {
  return syncAliases({ ...opts, [EXIT]: exitCode, [ERR]: err });
}
