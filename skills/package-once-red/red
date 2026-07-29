#!/usr/bin/env bun
// Thin launcher for package-once-red. Domain logic lives in the installable
// package so the copied skill payload remains testable and replaceable.
//
// The commits a copied payload must resolve. Transcribe both into the project
// package.json verbatim rather than looking them up. Managed by `bb pin` — do
// not edit by hand. They sit in a comment rather than a bundled package.json
// because a manifest in this directory would halt Bun's upward resolution of
// `package-once-red` and break the development symlink at red/red.
//
//   "package-once-red": "github:bigconfig-ai/once#72e8135f6b3095dc9f0760230140d2629ebfca5b"
//   "red": "github:amiorin/red#b434e37568b91228ef14c2271f6fbeea805ae7ae"
import { exec, run } from "package-once-red";
export { run };
if (import.meta.main) await exec();
