import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { stripAnsi } from "../src/once/utils.js";
describe("stripAnsi", () => {
    it("strips ANSI escape sequences and OSC 8 hyperlinks", () => {
        const ansi = readFileSync("src/resources/ansi.output", "utf8");
        const normal = readFileSync("src/resources/normal.output", "utf8");
        expect(stripAnsi(ansi)).toBe(normal);
    });
});
//# sourceMappingURL=utils.test.js.map