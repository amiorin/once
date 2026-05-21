# CLAUDE.md

This file describes the `once` codebase for AI assistants. Read it before making changes.

## Project Overview

`once` is a TypeScript library and CLI tool that automates provisioning and configuration of cloud infrastructure using [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/). It targets "vibe coders" who want one-click deployment via [Basecamp's ONCE](https://github.com/basecamp/once).

It was originally a Clojure project built on the `big-config` library. The workflow engine, template renderer, step runner and plugin system that `big-config` provided have been ported to TypeScript and live under `src/bc/` — there is no longer any JVM/Clojure dependency.

## Tech Stack

- **Language**: TypeScript (ES2022, ESM, `NodeNext` module resolution)
- **Runtime**: Node.js 20+
- **Test runner**: [Vitest](https://vitest.dev/)
- **Infrastructure**: OpenTofu (Terraform fork)
- **Config management**: Ansible
- **Dev environment**: Nix via `devenv` + `direnv`

The CLI shells out to external tools at runtime: `tofu`, `ansible-playbook`, `ssh`, `curl`, `skopeo`, and per-provider CLIs. The remote `deploy` ForceCommand script and the `once` Ansible module are Babashka scripts that run on the provisioned host (not part of the TypeScript code).

## Repository Structure

```
once/
├── src/
│   ├── bc/                  # Ported "big-config" engine (no external deps)
│   │   ├── core.ts          # Workflow engine: toWorkflow, ok, choice, toStepFn
│   │   ├── pluggable.ts     # handleStep registry + toWorkflowStar
│   │   ├── workflow.ts      # runSteps, toCompWorkflow, parseArgs, prepare, params helpers
│   │   ├── render.ts        # Template engine (selmer-subset renderer)
│   │   ├── run.ts           # Command execution + the `exec` workflow
│   │   ├── step-fns.ts      # exitStepFn / printErrorStepFn middleware
│   │   ├── big-tofu.ts      # Terraform construct helpers
│   │   └── utils.ts         # deepMerge, sortNestedMap, keyword/path helpers
│   ├── once/
│   │   ├── options.ts       # Cloud profiles & active profile (`bb`)
│   │   ├── package.ts       # High-level create/delete workflows + validate/describe wiring
│   │   ├── params.ts        # Parameter extraction from OpenTofu outputs
│   │   ├── tools.ts         # Tofu/Ansible tool workflows
│   │   ├── validation.ts    # Profile schema, tool/credential/image/ssh-agent checks
│   │   ├── describe.ts      # Post-provisioning report
│   │   └── utils.ts         # stripAnsi
│   ├── resources/io/github/amiorin/once/tools/
│   │   ├── tofu/            # Multi-cloud .tf templates (DigitalOcean, hcloud, OCI, no-infra)
│   │   ├── tofu-backend/    # Remote state backend templates (s3, r2, local)
│   │   ├── tofu-smtp/       # SMTP (Resend) setup templates
│   │   ├── tofu-dns/        # DNS (Cloudflare) templates
│   │   ├── tofu-smtp-post/  # SMTP post-verification templates
│   │   ├── ansible/         # Remote host playbooks (incl. files/deploy bb script)
│   │   └── ansible-local/   # Local machine playbooks
│   └── cli.ts               # CLI entry point
├── test/                    # Vitest tests (*.test.ts)
├── package.json
├── tsconfig.json
├── vitest.config.ts
├── devenv.nix               # Nix dev environment
└── .envrc                   # direnv config (loads devenv, sources .envrc.private)
```

Templates under `src/resources/` are not TypeScript and are rendered verbatim (with placeholder substitution); do not treat them as code to refactor.

## Development Commands

```bash
npm install          # install dev dependencies
npm run build        # tsc -> dist/
npm run typecheck    # tsc --noEmit
npm test             # vitest run
npm run once -- ...  # run the CLI from source via tsx
```

### CLI Usage

```bash
once once validate          # pre-flight checks for the active profile
once once describe          # providers + SSH reachability + deployed apps
once once create            # provision everything (all 6 stages)
once once delete            # tear down (reverse 4 Tofu stages)
once once validate create   # validate, then create only if validation passes
once validate               # shortcut for `once once validate` (accepts no args)
```

Individual tools (each requires `render` first):

```bash
once tofu render tofu:init tofu:apply:-auto-approve
once tofu-smtp render tofu:init tofu:apply:-auto-approve
once tofu-dns render tofu:init tofu:apply:-auto-approve
once tofu-smtp-post render tofu:init tofu:apply:-auto-approve
once ansible render -- ansible-playbook main.yml
once ansible-local render -- ansible-playbook main.yml
```

During development, prefix with `npm run once --` (e.g. `npm run once -- once validate`).

## Key Architecture Concepts

### The Six-Stage Create Pipeline (`src/once/package.ts`)
1. **tofu** — provision compute (DigitalOcean / hcloud / OCI / no-infra)
2. **tofu-smtp** — set up SMTP (Resend)
3. **tofu-dns** — configure DNS (Cloudflare), injecting SMTP records
4. **tofu-smtp-post** — finalize SMTP after DNS verification
5. **ansible-local** — local config: update `~/.ssh/config` so the remote host is reachable for the next stage
6. **ansible** — remote host config: install Docker, ONCE, s-nail; provision the restricted `deploy` user; deploy applications listed under `once.applications`

`delete` reverses the Tofu stages (4→3→2→1 destroy order). Compute resources render with `lifecycle { prevent_destroy = true }` by default; override with `BC_PAR_COMPUTE_PREVENT_DESTROY=false` before `once once delete`.

`validate` and `describe` are opt-in workflow steps exposed through `once once validate` / `once once describe`. They do not run automatically before `create`.

### The Workflow Engine (`src/bc/`)
An `opts` object (`Record<string, any>`) is threaded through a series of steps. `toWorkflow` builds a step machine; `toWorkflowStar` adds the `handleStep` plugin layer; `runSteps` is the dynamic "workflow of workflows" that runs the steps named under `opts.steps`; `toCompWorkflow` builds composite pipelines like `create` / `delete`. Step functions return a new `opts` with `exit` (a non-negative integer) and `err`.

### Template Rendering (`src/bc/render.ts`)
Templates are copied from `src/resources/` into `.dist/` with placeholder substitution. File content uses `<{ var }>` delimiters; directory selection (e.g. picking `tofu/oci` vs `tofu/hcloud`) uses `{{ var }}`. The render data is the merged `params` plus `target-object` / `module` / `profile`.

### Parameter Flow
1. Profiles in `options.ts` define base `params`.
2. `params.optsFn` composes `readBcPars` (reads `BC_PAR_*` env vars) → `tofuSmtpParams` (extracts SMTP records from Tofu output) → `tofuParams` (extracts IP from Tofu output).
3. Each later stage inherits outputs from earlier stages.

Note: the `create` / `delete` pipelines pass only the global options into each tool stage — template params there come from `BC_PAR_*` env vars and Tofu outputs, not directly from `options.ts`. The `options.ts` profile params are used by `validate` / `describe` and the individual `once tofu ...` runners.

### `BC_PAR_*` Environment Variable Overrides
Any param can be overridden at runtime. The variable name is uppercased; hyphens/dots become underscores:
```bash
export BC_PAR_DOMAIN="example.com"
export BC_PAR_PROVIDER_BACKEND="s3"   # or "r2" / "local"
export BC_PAR_HCLOUD_TOKEN="xxx"
```
Sensitive credentials go in `.envrc.private` (gitignored).

### Plugin System
`tools.ts` registers a `render-tofu-backend` step via `pluggable.registerStep`. After each `render` step, it injects the remote-state backend config (S3, R2, or local) based on `provider-backend`.

## Code Conventions

- **Modules**: `src/bc/*` is the engine; `src/once/*` is the application. Keep that separation.
- **`opts` keys**: camelCase for engine/structural keys (`exit`, `err`, `params`, `steps`, `profile`, `prefix`). Profile/template parameter keys are kebab-case strings matching the template variable names (`provider-compute`, `do-token`, `oci-shape`).
- **Entry points**: `*Star` functions (`onceStar`, `tofuStar`, …) are the CLI-ready wrappers; `validate` / `describe` are workflow steps, while `validateReport` / `describeReport` are the pure report builders (and accept injected dependencies for testing).
- **Configuration Profiles (`options.ts`)**: private sub-profile maps (`oci`, `hcloud`, `digitalocean`, `noInfraCompute`, `resend`, `cloudflare`, `r2`, `deploy`, …) compose into public application profiles (`profileAlpha`, `profileBeta`, `profileGamma`, `profileNoInfra`) via `compose`. Each application profile pins a `domain`, `package`, and the `once.applications` list. `profileAlpha` rides on DigitalOcean; `profileBeta` / `profileGamma` ride on OCI; `profileNoInfra` targets an existing server. The active profile is `export const bb = profileAlpha;` — change it to switch profiles.
- **Templates**: `.dist/` is generated output, not source — do not edit it.

## Testing

`npm test` runs Vitest against `test/*.test.ts`. Tests cover the validation schema/credential checks, the describe report, the `stripAnsi` helper, and the `deploy` ForceCommand script. The deploy tests require `babashka` (`bb`) on PATH and are skipped automatically when it is missing.

Functions designed for testing take their side-effecting collaborators as injectable parameters: `describeReport(opts, runFn, onceOptsFn)`, `credentialErrors(params, env, runFn)`, `sshAgentErrors(params, env, runFn)`, `validate(stepFns, opts, reportFn)`, `describe(stepFns, opts, reportFn)`.

## What to Avoid

- Do not add error handling for cases that cannot happen — the workflow engine reports step failure via `exit` / `err`.
- Do not edit `.dist/` — it is generated output.
- Credentials and tokens never go in source files; use `.envrc.private` (gitignored).
- Keep imports using explicit `.js` extensions (required by `NodeNext` module resolution).
