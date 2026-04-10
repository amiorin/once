# CLAUDE.md

This file describes the `once` codebase for AI assistants. Read it before making changes.

## Project Overview

`once` is a Clojure library and CLI tool that automates provisioning and configuration of cloud infrastructure using [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/). It targets "vibe coders" who want one-click deployment via [Basecamp's ONCE](https://github.com/basecamp/once).

Built on top of [big-config](https://github.com/amiorin/big-config), which provides workflow orchestration, template rendering, and step execution primitives.

## Tech Stack

- **Language**: Clojure 1.12.4 (JVM)
- **CLI runner**: Babashka (`bb.edn` tasks)
- **Infrastructure**: OpenTofu (Terraform fork)
- **Config management**: Ansible
- **Key libraries**: `big-config`, `big-tofu`, `cheshire` (JSON), `babashka/process`, `com.rpl/specter`
- **Dev environment**: Nix via `devenv` + `direnv`

## Repository Structure

```
once/
├── src/
│   ├── clj/io/github/amiorin/once/
│   │   ├── options.clj      # Cloud profiles & active profile (def bb ...)
│   │   ├── package.clj      # High-level create/delete workflow definitions
│   │   ├── params.clj       # Parameter extraction from OpenTofu outputs
│   │   └── tools.clj        # Tofu/Ansible tool implementations
│   └── resources/io/github/amiorin/once/tools/
│       ├── tofu/            # Multi-cloud .tf templates (DigitalOcean, hcloud, OCI, no-infra)
│       ├── tofu-backend/    # S3 backend config template
│       ├── tofu-smtp/       # SMTP (Resend) setup templates
│       ├── tofu-dns/        # DNS (Cloudflare) templates
│       ├── tofu-smtp-post/  # SMTP post-verification templates
│       ├── ansible/         # Remote host playbooks
│       └── ansible-local/   # Local machine playbooks
├── test/clj/io/github/amiorin/once/
│   └── tools_test.clj       # Test stub (currently empty)
├── env/dev/clj/user.clj     # REPL dev namespace
├── deps.edn                 # Clojure CLI deps and aliases
├── bb.edn                   # Babashka task definitions
├── devenv.nix               # Nix dev environment
└── .envrc                   # direnv config (loads devenv, sources .envrc.private)
```

## Development Commands

### Code Maintenance
```bash
bb tidy           # clean-ns + format via clojure-lsp
```

### Running Tests
```bash
clojure -M:test   # runs cognitect test-runner against test/clj
```

### Full Lifecycle
```bash
bb once create              # provision everything (all 6 stages)
bb once delete              # tear down (reverse 4 Tofu stages)
bb once delete create       # clean slate redeploy
```

### Individual Tools (each requires `render` first)
```bash
bb tofu render tofu:init tofu:apply:-auto-approve
bb tofu-smtp render tofu:init tofu:apply:-auto-approve
bb tofu-dns render tofu:init tofu:apply:-auto-approve
bb tofu-smtp-post render tofu:init tofu:apply:-auto-approve
bb ansible render -- ansible-playbook main.yml
bb ansible-local render -- ansible-playbook main.yml
```

### Profile-specific tasks
```bash
bb website create    # uses options/website profile
bb online create     # uses options/online profile
```

## Key Architecture Concepts

### The Six-Stage Create Pipeline (`package.clj`)
1. **tofu** — provision compute (DigitalOcean / hcloud / OCI / no-infra)
2. **tofu-smtp** — set up SMTP (Resend)
3. **tofu-dns** — configure DNS (Cloudflare), injecting SMTP records
4. **tofu-smtp-post** — finalize SMTP after DNS verification
5. **ansible** — remote host config: install Docker, ONCE, s-nail, configure `.mailrc`
6. **ansible-local** — local config: update `~/.ssh/config`

Delete reverses the Tofu stages (4→3→2→1 destroy order).

### Template Rendering
`big-config` renders templates from `src/resources/` into `.dist/` using parameters. Templates use `{{ ... }}` delimiters for provider switching and `{ ... }` for filter expressions.

### Parameter Flow
1. Options maps in `options.clj` define base params under `::workflow/params`
2. `params/opts-fn` composes three transformations: `workflow/read-bc-pars` (reads `BC_PAR_*` env vars) → `tofu-smtp-params` (extracts SMTP records from Tofu output) → `tofu-params` (extracts IP from Tofu output)
3. Each later stage inherits outputs from earlier stages

### `BC_PAR_*` Environment Variable Overrides
Any `::workflow/params` key can be overridden at runtime:
```bash
export BC_PAR_DOMAIN="example.com"
export BC_PAR_PROVIDER_BACKEND="s3"
export BC_PAR_S3_BUCKET="my-tf-state-bucket"
export BC_PAR_HCLOUD_TOKEN="xxx"
```
Variable names are uppercased; hyphens become underscores. Sensitive credentials go in `.envrc.private` (gitignored).

### Plugin System
`tools.clj` uses `pluggable/handle-step` for the S3 backend plugin (`::render-tofu-backend`). After each `render` step, the plugin injects the backend configuration — this is done via `run-steps-with-plugin`.

## Code Conventions

### Naming
- **Namespaces**: `io.github.amiorin.once.*`
- **Keywords**: Fully namespaced (`::workflow/params`, `::bc/env`, `::render/profile`)
- **Entry points**: Functions ending with `*` are CLI/REPL entry points (`tofu*`, `ansible*`, `once*`)
- **Private defs**: Use `^:private` metadata for implementation details not intended for external use

### Configuration Profiles (`options.clj`)
Profiles are plain Clojure maps composed with `merge-with merge`:
```clojure
(def online (merge-with merge resend common cloudflare s3 oci
                        {::render/profile "online"
                         ::workflow/params {:domain "bigconfig.online"}}))
```
The `bb` var sets the active profile for Babashka tasks:
```clojure
(def bb online)  ; change this to switch profiles
```

### REPL Development Pattern
All source files contain `comment` blocks with live evaluation examples:
```clojure
(comment
  (debug tap-values
    (once* "create" options/oci))
  (-> tap-values))
```
These are documentation-as-tests — use them to understand expected behavior and to test interactively. Use CIDER with `:dev` alias.

### Data Transformation Pattern
Functions follow a pipeline pattern where each function takes and returns an `opts` map:
```clojure
(def opts-fn (comp tofu-params tofu-smtp-params workflow/read-bc-pars))
```

## Supported Cloud Providers

| Profile | Provider | Key params |
|---------|----------|------------|
| `oci` | Oracle Cloud | `oci-subnet-id`, `oci-compartment-id`, `oci-availability-domain`, `oci-shape` |
| `hcloud` | Hetzner Cloud | `hcloud-name`, `hcloud-image`, `hcloud-server-type`, `hcloud-location` |
| `digitalocean` | DigitalOcean | `digitalocean-name`, `digitalocean-region`, `digitalocean-size`, `digitalocean-image` |
| `no-infra` | Existing server | `no-infra-compute-ip`, `no-infra-compute-user`, `no-infra-compute-sudoer` |

All cloud profiles combine with `resend` (SMTP) and `cloudflare` (DNS) sub-profiles.

## Dependencies

To use `big-config` from local source during development, swap in `deps.edn`:
```clojure
;; comment out:
io.github.amiorin/big-config {:git/sha "364fe1f..."}
;; uncomment:
io.github.amiorin/big-config {:local/root "../big-config/main"}
```

## Git Conventions

Commit messages use [Conventional Commits](https://www.conventionalcommits.org/):
- `feat:` new feature
- `refactor:` code restructuring
- `deps:` dependency updates
- `docs:` documentation changes

## What to Avoid

- Do not add error handling for cases that cannot happen (big-config handles step failure via `::bc/exit` and `::bc/err`)
- Do not create new namespaces unless a genuine new concern arises; the four existing namespaces map cleanly to their responsibilities
- Do not modify `.dist/` — it is generated output, not source
- Credentials and tokens never go in source files; use `.envrc.private` (already gitignored via the whitelist `.gitignore`)
