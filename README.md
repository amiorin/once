# Once

`once` is an infrastructure automation tool for [ONCE](https://github.com/basecamp/once). It simplifies the provisioning and configuration of cloud resources using [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/). The audience is the vibe coder who wants to deploy a vibe-coded application with a "one-click" experience.

It is a TypeScript application built on the TypeScript SDK (the [`big-config`](https://github.com/bigconfig-ai/big-config) package) — pinned to a GitHub commit in `package.json` — for the workflow engine, template renderer, and step runner. To develop against a local SDK checkout, override the dependency with `"big-config": "file:../../big-config/typescript"` and re-run `npm install`.

![Demo](.github/media/demo.gif)

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

- **[Node.js](https://nodejs.org/) 20+**: The runtime.
- **[OpenTofu](https://opentofu.org/docs/intro/install/)**: For infrastructure management.
- **[Ansible](https://docs.ansible.com/ansible/latest/installation_guide/intro_installation.html)**: For configuration management.
- **[skopeo](https://github.com/containers/skopeo)** and **`ssh` / `curl`**: Used by `validate` and `describe`.
- **[AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)**: Required for the S3 / R2 backend.
- **Cloud Credentials**: e.g., `DIGITALOCEAN_TOKEN`, `HCLOUD_TOKEN`, `CLOUDFLARE_API_TOKEN`, `RESEND_API_KEY`, or OCI configuration.

A Nix `devenv` environment (`devenv.nix`) is provided that supplies Node.js and the external CLIs.

## Install

```bash
git clone https://github.com/bigconfig-ai/once
cd once
npm install
npm run build
```

Run the CLI from source during development with `npm run once -- <args>`, or after `npm run build` via `node dist/src/cli.js <args>`.

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

The active profile is defined in `src/once/options.ts`. Switch it by changing the `bb` export:

```typescript
// options.ts — switch between profileAlpha, profileBeta, profileGamma, profileNoInfra
export const bb = profileAlpha;
```

`profileAlpha` rides on DigitalOcean; `profileBeta` and `profileGamma` ride on OCI; `profileNoInfra` targets an existing server. Each application profile pins a domain, package name, and the list of containerized apps deployed by Ansible. All profiles merge in the `deploy` sub-profile, which carries two SSH public keys:
- `compute-pubkey` — the operator's key (its private half must be loaded in `ssh-agent` for Ansible to reach a new VM on cloud providers; `validate` checks this).
- `deploy-pubkey` — the key authorized on the remote `deploy` user with `ForceCommand`.

### Main Workflow

`validate` and `describe` are explicit workflow steps; they do not run automatically before or after `create`.

```bash
once package validate       # pre-flight checks for the active profile
once package describe       # providers, SSH reachability, deployed apps
once package build          # render all stages without applying/provisioning
once package create         # full 6-stage create pipeline
once package delete         # reverse the 4 Tofu stages
once package validate create # validate, then create only if validation passes
once package git-check lock build unlock-any # advanced Git/lock workflow helpers
```

Compute resources render with `lifecycle { prevent_destroy = true }` by default. To run `once package delete`, first override it:

```bash
export BC_PAR_COMPUTE_PREVENT_DESTROY=false
```

### Targeted Tools

Each tool requires a `render` step first to generate config files into `.dist/`:

```bash
once tofu render tofu:init tofu:apply:-auto-approve
once tofu git-check lock render tofu:init tofu:plan unlock-any
once tofu-smtp render tofu:init tofu:apply:-auto-approve
once tofu-dns render tofu:init tofu:apply:-auto-approve
once tofu-smtp-post render tofu:init tofu:apply:-auto-approve
once ansible render -- ansible-playbook main.yml
once ansible-local render -- ansible-playbook main.yml
```

### Programmatic Usage

```typescript
import { onceStar } from "./src/once/package.js";
import { bb } from "./src/once/options.js";

onceStar(["validate"], bb);
onceStar(["create"], bb);
onceStar(["describe"], bb);
```

The pure report builders remain available for tests and tooling:

```typescript
import { validateReport } from "./src/once/validation.js";
import { describeReport } from "./src/once/describe.js";

validateReport(bb);
describeReport(bb);
```

## How It Works

1. **Template Rendering**: BigConfig SDK takes templates from `src/resources/` and the merged parameters to generate valid Tofu and Ansible files in `.dist/`.
2. **Infrastructure Hook**: `create` first runs OpenTofu to provision resources.
3. **Inventory & Config Bridging**: Tofu output (the new server IP, SMTP records) is captured with `tofu output --json` and injected into the DNS configuration and Ansible inventory.
4. **Local Finalization**: The local Ansible playbook updates `~/.ssh/config` so the new server is reachable before the remote stage runs.
5. **Configuration**: Ansible connects to the new host using the generated inventory and applies the playbooks.

## Development

```bash
npm run typecheck   # tsc --noEmit
npm test            # vitest run
npm run build       # compile to dist/
```

The `deploy` ForceCommand script tests require `babashka` (`bb`) on PATH; they are skipped automatically when it is missing.

## License

Copyright © 2026 Alberto Miorin

Distributed under the MIT License.
