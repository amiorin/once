import { ERR, EXIT, RENDER_PROFILE, WF_PARAMS, ok, } from "big-config";
export const PROFILE = RENDER_PROFILE;
export const PARAMS = WF_PARAMS;
export function toBcOpts(opts = {}) {
    const out = { ...opts };
    if ("profile" in out)
        out[PROFILE] = out.profile;
    else if (PROFILE in out)
        out.profile = out[PROFILE];
    if ("params" in out)
        out[PARAMS] = out.params;
    else if (PARAMS in out)
        out.params = out[PARAMS];
    return out;
}
export function syncAliases(opts = {}) {
    const out = { ...opts };
    if (PROFILE in out)
        out.profile = out[PROFILE];
    if (PARAMS in out)
        out.params = out[PARAMS];
    if (EXIT in out)
        out.exit = out[EXIT];
    if (ERR in out)
        out.err = out[ERR];
    return out;
}
export function paramsOf(opts = {}) {
    return { ...(toBcOpts(opts)[PARAMS] ?? {}) };
}
export function profileOf(opts = {}) {
    return toBcOpts(opts)[PROFILE];
}
export function okAlias(opts = {}) {
    return syncAliases(ok(opts));
}
export function status(opts = {}, exitCode, err = null) {
    return syncAliases({ ...opts, [EXIT]: exitCode, [ERR]: err });
}
//# sourceMappingURL=interop.js.map