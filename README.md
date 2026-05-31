# Once

`once` is an infrastructure automation tool for [ONCE](https://github.com/basecamp/once). It simplifies the provisioning and configuration of cloud resources using [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/). The audience is the vibe coder who wants to deploy a vibe-coded application with a "one-click" experience.

This is the Python implementation, built on the [`big-config`](https://github.com/bigconfig-ai/big-config) Python package — pinned to a GitHub commit in `pyproject.toml` — for the workflow engine, template renderer, and step runner. It mirrors the Clojure (`../clojure`) and TypeScript (`../typescript`) implementations and produces byte-equivalent `.dist/` output.

## Features

- **End-to-End Orchestration**: A seamless six-stage workflow:
  1. **Infrastructure**: Provisioning with OpenTofu.
  2. **SMTP**: Email infrastructure with OpenTofu (Resend).
  3. **DNS**: Domain configuration with OpenTofu (Cloudflare provider v5), including automatic SMTP records, apex (`@`) and wildcard (`*`) A records proxied through Cloudflare, and a curated bundle of zone settings (TLS 1.3, strict SSL, always-use-HTTPS, etc.).
  4. **SMTP Post-Verification**: Finalizing SMTP setup (e.g., domain verification) with OpenTofu.
  5. **Local Config**: Ansible on the local machine wires up `~/.ssh/config` so the freshly provisioned host is reachable for the next stage.
  6. **Remote Config**: Ansible on the remote host installs Docker and ONCE, provisions a restricted `deploy` user for one-command redeploys, and reconciles the configured applications.
- **OpenTofu Remote Backend**: Support for remote state management using S3 or Cloudflare R2, automatically rendered for all Tofu-based stages.
- **Multi-Cloud Support**: Native templates for **DigitalOcean** (`digitalocean`), **Hetzner Cloud** (`hcloud`), **Oracle Cloud Infrastructure** (`oci`), and **No-Infra** (`no-infra`, for when the server already exists).
- **Dynamic Inventory**: Generates the Ansible inventory directly from OpenTofu outputs.
- **SMTP Testing Ready**: Installs `s-nail` and configures `.mailrc` on the remote host for immediate SMTP verification.
- **Restricted Deploy SSH**: Provisions a `deploy` user with NOPASSWD sudo limited to `once`, and an SSH `ForceCommand` script that accepts only `sudo once update <host>` for hosts present in `once list`.
- **Environment Overrides**: Override any configuration parameter via environment variables (e.g., `BC_PAR_DOMAIN`).

## Prerequisites

- **[Python](https://www.python.org/) 3.12+** and **[`uv`](https://docs.astral.sh/uv/)**: The runtime and dev runner.
- **[OpenTofu](https://opentofu.org/docs/intro/install/)**: For infrastructure management.
- **[Ansible](https://docs.ansible.com/ansible/latest/installation_guide/intro_installation.html)**: For configuration management.
- **[skopeo](https://github.com/containers/skopeo)** and **`ssh` / `curl`**: Used by `validate` and `describe`.
- **[AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)**: Required for the S3 / R2 backend.
- **Cloud Credentials**: e.g., `DIGITALOCEAN_TOKEN`, `HCLOUD_TOKEN`, `CLOUDFLARE_API_TOKEN`, `RESEND_API_KEY`, or OCI configuration.

## Install

```bash
git clone https://github.com/bigconfig-ai/once
cd once
uv sync
```

Run the CLI from source during development with `uv run once -- <args>`. The root `run` script (the launcher-friendly entry point that carries a safe default profile) runs via `uv run python run <args>`.

## Usage

### Configuration Overrides

Override any parameter using environment variables prefixed with `BC_PAR_`. The variable name is lowercased, and underscores or dots become hyphens.

```bash
export BC_PAR_DO_TOKEN="your-digitalocean-token"
export BC_PAR_RESEND_PASSWORD="your-smtp-password"
export BC_PAR_DOMAIN="example.com"
```

To enable the S3 backend for OpenTofu:
```bash
export BC_PAR_PROVIDER_BACKEND="s3"
export BC_PAR_S3_BUCKET="your-tf-state-bucket"
export BC_PAR_S3_REGION="eu-west-1"
```

To enable the Cloudflare R2 backend instead:
```bash
export BC_PAR_PROVIDER_BACKEND="r2"
export BC_PAR_R2_BUCKET="your-tf-state-bucket"
export BC_PAR_R2_ENDPOINT="https://<account-id>.r2.cloudflarestorage.com"
export BC_PAR_R2_ACCESS_KEY_ID="your-r2-access-key"
export BC_PAR_R2_SECRET_ACCESS_KEY="your-r2-secret"
```

Sensitive credentials belong in `.envrc.private` (gitignored).

### Selecting a Profile

The active profile is defined in `src/once/options.py`. Switch it by changing the `bb` binding:

```python
# options.py — switch between profile_alpha, profile_beta, profile_gamma, profile_no_infra
bb: Opts = profile_alpha
```

`profile_alpha` rides on DigitalOcean; `profile_beta` and `profile_gamma` ride on OCI; `profile_no_infra` targets an existing server. Each application profile pins a domain, package name, and the list of containerized apps deployed by Ansible. All profiles merge in the `deploy` sub-profile, which carries two SSH public keys:
- `compute-pubkey` — the operator's key (its private half must be loaded in `ssh-agent` for Ansible to reach a new VM on cloud providers; `validate` checks this).
- `deploy-pubkey` — the key authorized on the remote `deploy` user with `ForceCommand`.

CamelCase aliases (`profileAlpha`, …) are also exposed for cross-language parity.

### Main Workflow

`validate` and `describe` are explicit workflow steps; they do not run automatically before or after `create`.

```bash
uv run once -- package validate       # pre-flight checks for the active profile
uv run once -- package describe       # providers, SSH reachability, deployed apps
uv run once -- package build          # render all stages without applying/provisioning
uv run once -- package create         # full 6-stage create pipeline
uv run once -- package delete         # reverse the 4 Tofu stages
uv run once -- package validate create # validate, then create only if validation passes
uv run once -- validate               # shortcut for `once package validate`
```

Compute resources render with `lifecycle { prevent_destroy = true }` by default. To run `once package delete`, first override it:

```bash
export BC_PAR_COMPUTE_PREVENT_DESTROY=false
```

### Targeted Tools

Each tool requires a `render` step first to generate config files into `.dist/`:

```bash
uv run once -- tofu render tofu:init tofu:apply:-auto-approve
uv run once -- tofu-smtp render tofu:init tofu:apply:-auto-approve
uv run once -- tofu-dns render tofu:init tofu:apply:-auto-approve
uv run once -- tofu-smtp-post render tofu:init tofu:apply:-auto-approve
uv run once -- ansible render -- ansible-playbook main.yml
uv run once -- ansible-local render -- ansible-playbook main.yml
```

### Programmatic Usage

```python
from once.package import once_star
from once.options import bb

once_star(["validate"], bb)
once_star(["create"], bb)
once_star(["describe"], bb)
```

The pure report builders remain available for tests and tooling:

```python
from once.validation import validate_report
from once.describe import describe_report

validate_report(bb)
describe_report(bb)
```

## How It Works

1. **Template Rendering**: `big-config` takes templates from `src/resources/` and the merged parameters to generate valid Tofu and Ansible files in `.dist/`.
2. **Infrastructure Hook**: `create` first runs OpenTofu to provision resources.
3. **Inventory & Config Bridging**: Tofu output (the new server IP, SMTP records) is captured with `tofu output --json` and injected into the DNS configuration and Ansible inventory.
4. **Local Finalization**: The local Ansible playbook updates `~/.ssh/config` so the new server is reachable before the remote stage runs.
5. **Configuration**: Ansible connects to the new host using the generated inventory and applies the playbooks.

## Development

```bash
uv sync                 # install dev dependencies
uv run pytest -q        # run the test suite
uv run once -- --help   # run the CLI from source
```

`uv run pytest` also runs a `build` parity check against the reference `.dist/profile-alpha-<hash>/` artifact. The `deploy` ForceCommand script tests require `babashka` (`bb`) on PATH and are skipped automatically when it is missing.

`.dist/` is generated output — do not edit it directly.

## License

Copyright © 2026 Alberto Miorin

Distributed under the MIT License.
