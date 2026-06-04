# CLAUDE.md

This file describes the `once` Python codebase for AI assistants. Read it before making changes.

## Project Overview

`once` is a Python library and CLI tool that automates provisioning and configuration of cloud infrastructure using [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/). The audience is the vibe coder who wants a "one-click" deploy of a vibe-coded application via [Basecamp's ONCE](https://github.com/basecamp/once).

It depends on the Python SDK (`big-config` package, pinned to a GitHub commit in `pyproject.toml`) for the workflow engine, template renderer, step runner, and plugin system. The runtime is stdlib-only outside of `big-config`.

## Tech Stack

- **Language**: Python 3.12+
- **Package manager / dev runner**: [`uv`](https://docs.astral.sh/uv/)
- **Test runner**: [pytest](https://docs.pytest.org/)
- **Infrastructure**: OpenTofu
- **Config management**: Ansible
- **Dev environment**: Nix via `devenv` + `direnv`

The CLI shells out to external tools at runtime: `tofu`, `ansible-playbook`, `ssh`, `curl`, `skopeo`, and per-provider CLIs. The remote `deploy` ForceCommand script and the `once` Ansible module are Babashka scripts that run on the provisioned host (not part of the Python code).

## Repository Structure

```
once/
├── src/
│   ├── once/
│   │   ├── cli.py          # CLI entry point
│   │   ├── options.py      # Cloud profiles & active profile (`bb`)
│   │   ├── package.py      # High-level create/delete workflows + validate/describe wiring
│   │   ├── params.py       # Parameter extraction from OpenTofu outputs
│   │   ├── tools.py        # Tofu/Ansible tool workflows
│   │   ├── validation.py   # Profile schema, tool/credential/image/ssh-agent checks
│   │   ├── describe.py     # Post-provisioning report
│   │   ├── interop.py      # big-config alias <-> namespaced-key bridge
│   │   └── utils.py        # strip_ansi
│   └── resources/io/github/bigconfig-ai/once/tools/
│       ├── tofu/            # Multi-cloud .tf templates (DigitalOcean, hcloud, OCI, no-infra)
│       ├── tofu-backend/    # Remote state backend templates (s3, r2, local)
│       ├── tofu-smtp/       # SMTP (Resend) setup templates
│       ├── tofu-dns/        # DNS (Cloudflare) templates
│       ├── tofu-smtp-post/  # SMTP post-verification templates
│       ├── ansible/         # Remote host playbooks (incl. files/deploy bb script)
│       └── ansible-local/   # Local machine playbooks
├── tests/                   # pytest suite
├── pyproject.toml
├── uv.lock
├── devenv.nix               # Nix dev environment
└── .envrc                   # direnv config (loads devenv, sources .envrc.private)
```

Templates under `src/resources/` are not Python and are rendered verbatim (with placeholder substitution); do not treat them as code to refactor.

## Development Commands

```bash
uv sync                     # install dev dependencies
uv run pytest               # run the test suite
uv run pytest tests/test_validation.py::test_name   # run a single test
uv run once -- ...          # run the CLI from source
```

### CLI Usage

```bash
once package validate         # pre-flight checks for the active profile
once package describe         # providers + SSH reachability + deployed apps
once package build            # render everything without applying/provisioning
once package create           # provision everything (all 6 stages)
once package delete           # tear down (reverse 4 Tofu stages)
once package validate create  # validate, then create only if validation passes
once validate                 # shortcut for `once package validate`
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

## Key Architecture Concepts

### The Six-Stage Create Pipeline (`src/once/package.py`)

1. **tofu** — provision compute (DigitalOcean / hcloud / OCI / no-infra)
2. **tofu-smtp** — set up SMTP (Resend)
3. **tofu-dns** — configure DNS (Cloudflare), injecting SMTP records
4. **tofu-smtp-post** — finalize SMTP after DNS verification
5. **ansible-local** — local config: update `~/.ssh/config` so the remote host is reachable for the next stage
6. **ansible** — remote host config: install Docker, ONCE, s-nail; provision the restricted `deploy` user; deploy applications listed under `once.applications`

`delete` reverses the Tofu stages (4→3→2→1 destroy order). Compute resources render with `lifecycle { prevent_destroy = true }` by default; override with `BC_PAR_COMPUTE_PREVENT_DESTROY=false` before `once package delete`.

`validate` and `describe` are opt-in workflow steps exposed through `once package validate` / `once package describe`. They do not run automatically before `create`.

### The Workflow Engine (Python SDK)

ONCE uses the Python SDK (`big-config` package) for the workflow engine and template rendering. An `opts` dict (`dict[str, Any]`) is threaded through a series of steps; the SDK's `workflow`, `workflow_star`, and `run_steps` compose build/create/delete pipelines.

### Template Rendering (`big_config.render`)

Templates are copied from `src/resources/` into `.dist/` with placeholder substitution. The Selmer renderer is configured with custom delimiters in `tools.py` so that **variables in file content read as `<{ var }>`** — this leaves literal `{{ ... }}` untouched for downstream tools like Ansible. Provider switching is directory-level: each render step pairs a provider value (e.g., `"s3"`, `"oci"`) with the delimiters, and the renderer copies only the matching subdirectory under `tools/<step>/`.

### Parameter Flow

1. Profiles in `options.py` define base `params`.
2. `params.opts_fn` composes `read_bc_pars` (reads `BC_PAR_*` env vars) → `tofu_smtp_params` (extracts SMTP records from Tofu output) → `tofu_params` (extracts IP from Tofu output).
3. Each later stage inherits outputs from earlier stages.

Note: the `create` / `delete` pipelines pass only the global options into each tool stage — template params there come from `BC_PAR_*` env vars and Tofu outputs, not directly from `options.py`. The `options.py` profile params are used by `validate` / `describe` and the individual `once tofu ...` runners.

### `BC_PAR_*` Environment Variable Overrides

Any param can be overridden at runtime. The variable name is uppercased; hyphens/dots become underscores:

```bash
export BC_PAR_DOMAIN="example.com"
export BC_PAR_PROVIDER_BACKEND="s3"   # or "r2" / "local"
export BC_PAR_HCLOUD_TOKEN="xxx"
```

Sensitive credentials go in `.envrc.private` (gitignored).

### Plugin System

`tools.py` registers a `render-tofu-backend` step via the SDK's pluggable step registry. After each `render` step, it injects the remote-state backend config (S3, R2, or local) based on `provider-backend`.

## Code Conventions

- **Modules**: the `big_config` package is the engine; `src/once/*` is the application. Keep that separation.
- **`opts` keys**: SDK engine keys are namespaced strings (`big-config/exit`, `big-config.workflow/params`, etc.) — kept verbatim as Python dict keys, not converted to snake_case. ONCE also mirrors friendly aliases (`exit`, `err`, `params`, `profile`) at the CLI/test boundary via `interop.py`. Profile/template parameter keys are kebab-case strings matching the template variable names (`provider-compute`, `do-token`, `oci-shape`).
- **Entry points**: `*_star` functions (`once_star`, `tofu_star`, …) are the CLI-ready wrappers; `validate` / `describe` are workflow steps, while `validate_report` / `describe_report` are the pure report builders (and accept injected dependencies for testing).
- **Configuration Profiles (`options.py`)**: private sub-profile maps (`oci`, `hcloud`, `digitalocean`, `no_infra_compute`, `resend`, `cloudflare`, `r2`, `deploy`, …) compose into public application profiles (`profile_alpha`, `profile_beta`, `profile_gamma`, `profile_no_infra`) via `compose`. `profile_alpha` rides on DigitalOcean; `profile_beta` / `profile_gamma` ride on OCI; `profile_no_infra` targets an existing server. The active profile is `bb: Opts = profile_alpha` — change it to switch profiles. CamelCase aliases (`profileAlpha`, …) are exposed for cross-language parity.
- **Templates**: `.dist/` is generated output, not source — do not edit it.

## Testing

`uv run pytest` runs the suite under `tests/`. Tests cover the validation schema/credential checks, the describe report, the `strip_ansi` helper, the `deploy` ForceCommand script, and a `build` parity check against the reference `.dist/profile-alpha-<hash>/` artifact. The deploy tests require `babashka` (`bb`) on PATH and are skipped automatically when it is missing.

Functions designed for testing take their side-effecting collaborators as injectable parameters: `describe_report(opts, run_fn, once_opts_fn)`, `credential_errors(params, env, run_fn)`, `ssh_agent_errors(params, env, run_fn)`, `validate(step_fns, opts, report_fn)`, `describe(step_fns, opts, report_fn)`.

## What to Avoid

- Do not add error handling for cases that cannot happen — the SDK reports step failure via `exit` / `err`.
- Do not edit `.dist/` — it is generated output.
- Do not convert the SDK's namespaced string keys (e.g., `big-config/exit`) to snake_case — they are preserved verbatim across all three language implementations for parity.
- Credentials and tokens never go in source files; use `.envrc.private` (gitignored).

## Git

Stay on `python` (each language has its own branch in this repo). Commit only when explicitly asked. Commit messages follow Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `deps:`).
