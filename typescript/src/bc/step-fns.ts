/** Step-fn middleware for logging, error reporting and process exit. */
import { type StepFn, toStepFn } from "./core.js";

/** Terminate the process with the workflow exit code at the final step. */
export function exitStepFn(end: string): StepFn {
  return toStepFn({
    afterF: (step, opts) => {
      if (step === end && opts.env !== "repl") {
        process.exit(typeof opts.exit === "number" ? opts.exit : 0);
      }
    },
  });
}

/** Print the workflow error to stderr at the final step. */
export function printErrorStepFn(end: string): StepFn {
  return toStepFn({
    beforeF: (step, opts) => {
      if (
        step === end &&
        typeof opts.exit === "number" &&
        opts.exit > 0 &&
        typeof opts.err === "string" &&
        opts.err.trim() !== ""
      ) {
        console.error(opts.err);
      }
    },
  });
}
