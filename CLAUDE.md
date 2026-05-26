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
│   ├── clj/io/github/bigconig_ai/once/
│   │   ├── options.clj      # Cloud profiles & active profile (def bb ...)
│   │   ├── package.clj      # High-level create/delete workflows + validate/describe step wiring
│   │   ├── params.clj       # Parameter extraction from OpenTofu outputs
│   │   ├── tools.clj        # Tofu/Ansible tool implementations
│   │   ├── validation.clj   # Profile schema (malli), tool/credential/image/ssh-agent checks
│   │   └── describe.clj     # Post-provisioning report (providers, SSH, deployed apps)
│   └── resources/io/github/bigconig-ai/once/tools/
│       ├── tofu/            # Multi-cloud .tf templates (DigitalOcean, hcloud, OCI, no-infra)
│       ├── tofu-backend/    # Remote state backend templates (s3, r2, local)
│       ├── tofu-smtp/       # SMTP (Resend) setup templates
│       ├── tofu-dns/        # DNS (Cloudflare) templates
│       ├── tofu-smtp-post/  # SMTP post-verification templates
│       ├── ansible/         # Remote host playbooks (incl. files/deploy bb script)
│       └── ansible-local/   # Local machine playbooks
├── test/clj/io/github/bigconig_ai/once/
│   ├── deploy_test.clj      # Tests for the deploy ForceCommand script
│   ├── describe_test.clj    # Tests for the describe report (parsing + assembly)
│   ├── utils_test.clj       # Utility tests
│   └── validation_test.clj  # Tests for the malli profile schema and tool selection
├── env/dev/clj/user.clj     # REPL dev namespace
├── deps.edn                 # Clojure CLI deps and aliases
├── bb.edn                   # Babashka task definitions
├── devenv.nix               # Nix dev environment
└── .envrc                   # direnv config (loads devenv, sources .envrc.private)
```

## Development Commands

### Code Maintenance
```bash
bb -tidy          # clean-ns + format via clojure-lsp
```

### Running Tests
```bash
clojure -M:test   # runs cognitect test-runner against test/clj
```

### Pre-flight Validation
```bash
bb validate       # shortcut for `bb once package validate`; accepts no extra args
                  # checks active profile schema, required CLIs, credentials, image refs,
                  # and that :compute-pubkey is loaded in ssh-agent (cloud providers only)
```

### Post-provisioning Report
```bash
bb once package describe  # configured providers + SSH reachability + deployed ONCE applications
                  # (image, tag, running digest, registry digest, update-available?)
```

### Full Lifecycle
```bash
bb validate                         # strict shortcut for `bb once package validate`
bb once package validate            # same validation via workflow step; can be chained
bb once package describe            # opt-in post-provisioning report
bb once package build               # render everything without applying/provisioning
bb once package create              # provision everything (all 6 stages)
bb once package delete              # tear down (reverse 4 Tofu stages)
bb once package validate create     # validate, then create only if validation passes
bb once package delete create       # clean slate redeploy
```

### Individual Tools (each requires `render` first)
```bash
bb -tofu render tofu:init tofu:apply:-auto-approve
bb -tofu-smtp render tofu:init tofu:apply:-auto-approve
bb -tofu-dns render tofu:init tofu:apply:-auto-approve
bb -tofu-smtp-post render tofu:init tofu:apply:-auto-approve
bb -ansible render -- ansible-playbook main.yml
bb -ansible-local render -- ansible-playbook main.yml
```

The active profile is selected by editing `(def bb ...)` in `options.clj` (see Configuration Profiles below).

## Key Architecture Concepts

### The Six-Stage Create Pipeline (`package.clj`)
1. **tofu** — provision compute (DigitalOcean / hcloud / OCI / no-infra)
2. **tofu-smtp** — set up SMTP (Resend)
3. **tofu-dns** — configure DNS (Cloudflare), injecting SMTP records
4. **tofu-smtp-post** — finalize SMTP after DNS verification
5. **ansible-local** — local config: update `~/.ssh/config` so the remote host is reachable as `Host once` for the next stage
6. **ansible** — remote host config: install Docker, ONCE, s-nail, configure `.mailrc`, provision the restricted `deploy` user (NOPASSWD sudo for `/usr/local/bin/once *` + `ForceCommand` Babashka script at `/usr/local/bin/deploy` authorized by `:deploy-pubkey`), deploy applications listed under `:once {:applications [...]}`

Delete reverses the Tofu stages (4→3→2→1 destroy order). Compute resources are rendered with `lifecycle { prevent_destroy = true }` by default; override with `BC_PAR_COMPUTE_PREVENT_DESTROY=false` before `bb once package delete`.

`validate` and `describe` are opt-in `big-config.workflow/run-steps` steps exposed through `bb once package validate` and `bb once package describe`. Validation is also exposed as a top-level `bb validate` shortcut, which accepts no extra args and exits non-zero when `*command-line-args*` is non-empty. These steps do not run automatically before `create`.

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
export BC_PAR_PROVIDER_BACKEND="s3"   # or "r2" / "local"
export BC_PAR_S3_BUCKET="my-tf-state-bucket"
export BC_PAR_HCLOUD_TOKEN="xxx"
```
Variable names are uppercased; hyphens become underscores. Sensitive credentials go in `.envrc.private` (gitignored).

### Plugin System
`tools.clj` uses `pluggable/handle-step` for the remote-state backend plugin (`::render-tofu-backend`). After each `render` step, the plugin injects the backend configuration (S3, R2, or local) based on `:provider-backend` — this is done via `run-steps-with-plugin`.

## Code Conventions

### Naming
- **Namespaces**: `io.github.bigconig-ai.once.*`
- **Keywords**: Fully namespaced (`::workflow/params`, `::bc/env`, `::render/profile`)
- **Entry points**: Functions ending with `*` are CLI/REPL entry points (`tofu*`, `ansible*`, `once*`); `validation/validate` and `describe/describe` are workflow step functions, while `validate-report` / `describe-report` are pure report builders
- **Private defs**: Use `^:private` metadata for implementation details not intended for external use

### Configuration Profiles (`options.clj`)
Two layers compose into a profile: private **compute** base maps (`oci`, `hcloud`, `digitalocean`, `no-infra-compute`) and public **application** profiles (`online`, `space`, `website`, `no-infra`) that pin a domain, package name, and the `:once {:applications [...]}` list deployed by Ansible. All four application profiles also merge a private `deploy` sub-profile carrying two SSH public keys: `:compute-pubkey` (private half must be loaded in `ssh-agent` so Ansible can reach the freshly provisioned VM — `bb validate` / `bb once package validate` enforce this for cloud compute profiles) and `:deploy-pubkey` (authorized on the remote `deploy` user with `ForceCommand`). Profiles are plain Clojure maps composed with `merge-with merge`:
```clojure
(def space (merge-with merge resend cloudflare r2 oci deploy
                       {::render/profile "space"
                        ::workflow/params {:domain "bigconfig.space"
                                           :package "space"
                                           :once {:applications [{:host "marketplace-api.bigconfig.space"
                                                                  :image "ghcr.io/amiorin/once-pocketbase"
                                                                  :env ["SUPERUSER_PASSWORD=<{ superuser-password }>"]}]}}}))
```
`online` and `space` ride on `oci`; `website` rides on `digitalocean`; `no-infra` targets an existing server. The `bb` var sets the active profile for Babashka tasks:
```clojure
(def bb website)  ; change this to switch profiles
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

All cloud profiles combine with `resend` (SMTP) and `cloudflare` (DNS) sub-profiles. The Cloudflare DNS template (provider `~> 5.0`) creates apex (`@`) and wildcard (`*`) A records proxied through Cloudflare and applies a fixed bundle of zone settings (TLS 1.3, strict SSL, always-use-HTTPS, brotli, etc.). Outgoing mail is sent from `info@notifications.<domain>`.

## Dependencies

To use `big-config` from local source during development, swap in `deps.edn`:
```clojure
;; comment out:
io.github.amiorin/big-config {:git/sha "364fe1f..."}
;; uncomment:
io.github.amiorin/big-config {:local/root "../../big-config/main"}
```

## Git Conventions

Commit messages use [Conventional Commits](https://www.conventionalcommits.org/):
- `feat:` new feature
- `refactor:` code restructuring
- `deps:` dependency updates
- `docs:` documentation changes

## What to Avoid

- Do not add error handling for cases that cannot happen (big-config handles step failure via `::bc/exit` and `::bc/err`)
- Do not create new namespaces unless a genuine new concern arises; the six existing namespaces (`options`, `package`, `params`, `tools`, `validation`, `describe`) map cleanly to their responsibilities
- Do not modify `.dist/` — it is generated output, not source
- Credentials and tokens never go in source files; use `.envrc.private` (already gitignored via the whitelist `.gitignore`)
