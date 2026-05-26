/** Strip ANSI escape sequences and OSC 8 hyperlinks from a string. */
export function stripAnsi(s) {
    return s
        .replace(/\x1b\]8;[^\x07]*\x07/g, "")
        .replace(/\x1b\[[0-9;]*m/g, "");
}
//# sourceMappingURL=utils.js.map