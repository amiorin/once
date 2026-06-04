# CLAUDE.md

This file describes the `once` codebase for AI assistants. Read it before making changes.

## Project Overview

`once` is a TypeScript library and CLI tool that automates provisioning and configuration of cloud infrastructure using [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/). It targets "vibe coders" who want one-click deployment via [Basecamp's ONCE](https://github.com/basecamp/once).

It depends on the TypeScript SDK (`big-config` package, `bigconfig-ai/big-config`, pinned to a GitHub commit in `package.json`) for the workflow engine, template renderer, step runner, and plugin system. To develop against a local SDK checkout instead, override the `big-config` dependency in `package.json` with `"big-config": "file:../../big-config/typescript"` and re-run `npm install`.

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
│   ├── once/
│   │   ├── options.ts       # Cloud profiles & active profile (`bb`)
│   │   ├── package.ts       # High-level create/delete workflows + validate/describe wiring
│   │   ├── params.ts        # Parameter extraction from OpenTofu outputs
│   │   ├── tools.ts         # Tofu/Ansible tool workflows
│   │   ├── validation.ts    # Profile schema, tool/credential/image/ssh-agent checks
│   │   ├── describe.ts      # Post-provisioning report
│   │   ├── interop.ts       # big-config interop helpers
│   │   └── utils.ts         # stripAnsi
│   ├── resources/io/github/bigconfig-ai/once/tools/
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
once package validate       # pre-flight checks for the active profile
once package describe       # providers + SSH reachability + deployed apps
once package build          # render everything without applying/provisioning
once package create         # provision everything (all 6 stages)
once package delete         # tear down (reverse 4 Tofu stages)
once package validate create # validate, then create only if validation passes
once validate               # shortcut for `once package validate`
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

During development, prefix with `npm run once --` (e.g. `npm run once -- package validate`).

## Key Architecture Concepts

### The Six-Stage Create Pipeline (`src/once/package.ts`)
1. **tofu** — provision compute (DigitalOcean / hcloud / OCI / no-infra)
2. **tofu-smtp** — set up SMTP (Resend)
3. **tofu-dns** — configure DNS (Cloudflare), injecting SMTP records
4. **tofu-smtp-post** — finalize SMTP after DNS verification
5. **ansible-local** — local config: update `~/.ssh/config` so the remote host is reachable for the next stage
6. **ansible** — remote host config: install Docker, ONCE, s-nail; provision the restricted `deploy` user; deploy applications listed under `once.applications`

`delete` reverses the Tofu stages (4→3→2→1 destroy order). Compute resources render with `lifecycle { prevent_destroy = true }` by default; override with `BC_PAR_COMPUTE_PREVENT_DESTROY=false` before `once package delete`.

`validate` and `describe` are opt-in workflow steps exposed through `once package validate` / `once package describe`. They do not run automatically before `create`.

### The Workflow Engine (TypeScript SDK: `../../big-config/typescript`)
ONCE uses the TypeScript SDK (`big-config` package) for the workflow engine and template rendering. An `opts` object (`Record<string, any>`) is threaded through a series of steps; the SDK's `createWorkflow`, `createWorkflowStar`, and `runSteps` composition helpers support build/create/delete pipelines.

### Template Rendering (`../../big-config/typescript/src/render.ts`)
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
`tools.ts` registers a `render-tofu-backend` step via the SDK's pluggable step registry. After each `render` step, it injects the remote-state backend config (S3, R2, or local) based on `provider-backend`.

## Code Conventions

- **Modules**: the `big-config` npm package is the engine; `src/once/*` is the application. Keep that separation.
- **`opts` keys**: SDK engine keys are namespaced strings (`big-config/exit`, `big-config.workflow/params`, etc.). ONCE also mirrors friendly aliases (`exit`, `err`, `params`, `profile`) at the CLI/test boundary. Profile/template parameter keys are kebab-case strings matching the template variable names (`provider-compute`, `do-token`, `oci-shape`).
- **Entry points**: `*Star` functions (`onceStar`, `tofuStar`, …) are the CLI-ready wrappers; `validate` / `describe` are workflow steps, while `validateReport` / `describeReport` are the pure report builders (and accept injected dependencies for testing).
- **Configuration Profiles (`options.ts`)**: private sub-profile maps (`oci`, `hcloud`, `digitalocean`, `noInfraCompute`, `resend`, `cloudflare`, `r2`, `deploy`, …) compose into public application profiles (`profileAlpha`, `profileBeta`, `profileGamma`, `profileNoInfra`) via `compose`. Each application profile pins a `domain`, `package`, and the `once.applications` list. `profileAlpha` rides on DigitalOcean; `profileBeta` / `profileGamma` ride on OCI; `profileNoInfra` targets an existing server. The active profile is `export const bb = profileAlpha;` — change it to switch profiles.
- **Templates**: `.dist/` is generated output, not source — do not edit it.

## Testing

`npm test` runs Vitest against `test/*.test.ts`. Tests cover the validation schema/credential checks, the describe report, the `stripAnsi` helper, and the `deploy` ForceCommand script. The deploy tests require `babashka` (`bb`) on PATH and are skipped automatically when it is missing.

Functions designed for testing take their side-effecting collaborators as injectable parameters: `describeReport(opts, runFn, onceOptsFn)`, `credentialErrors(params, env, runFn)`, `sshAgentErrors(params, env, runFn)`, `validate(stepFns, opts, reportFn)`, `describe(stepFns, opts, reportFn)`.

## What to Avoid

- Do not add error handling for cases that cannot happen — the SDK reports step failure via `exit` / `err`.
- Do not edit `.dist/` — it is generated output.
- Credentials and tokens never go in source files; use `.envrc.private` (gitignored).
- Keep imports using explicit `.js` extensions (required by `NodeNext` module resolution).

## Git

Stay on `typescript` (each language has its own branch in this repo). Commit only when explicitly asked. Commit messages follow Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `deps:`).
