# Once

`once` is a BigConfig package for [ONCE](https://github.com/basecamp/once). This BigConfig package is an infrastructure automation tool that simplifies the provisioning and configuration of cloud resources using [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/). The audience is the vibe coder who wants to deploy his vibe coded application with a "one-click" experience.

It is built on top of [big-config](https://github.com/amiorin/big-config), leveraging its workflow and configuration management capabilities.

## Features

- **End-to-End Orchestration**: A seamless five-stage workflow:
  1. **Infrastructure**: Provisioning with OpenTofu.
  2. **SMTP**: Email infrastructure with OpenTofu (Resend).
  3. **DNS**: Domain configuration with OpenTofu (Cloudflare), including automatic SMTP records.
  4. **Remote Config**: System configuration with Ansible on the remote host.
  5. **Local Config**: Finalizing setup with Ansible on the local machine.
- **Multi-Cloud Support**: Native templates for:
  - **Hetzner Cloud** (`hcloud`)
  - **Oracle Cloud Infrastructure** (`oci`)
  - **No-Infra** (`no-infra`): For when the server is already there.
- **Dynamic Inventory**: Automatically bridge the gap by generating Ansible inventory directly from OpenTofu outputs.
- **Configurable Workflows**: Execute complex multi-step processes like `tofu init/apply` followed by multiple `ansible-playbook` runs.

## Prerequisites

To use `once`, you need the following tools installed:

- **[Clojure](https://clojure.org/guides/install_clojure)**: The core engine.
- **[Babashka](https://babashka.org/)**: Recommended for running CLI tasks.
- **[OpenTofu](https://opentofu.org/docs/intro/install/)**: For infrastructure management.
- **[Ansible](https://docs.ansible.com/ansible/latest/installation_guide/intro_installation.html)**: For configuration management.
- **Cloud Credentials**: e.g., `HCLOUD_TOKEN`, `CLOUDFLARE_API_TOKEN`, `RESEND_API_KEY`, or OCI configuration.

## Usage

### Via Babashka (Recommended)

The easiest way to interact with `once` is through the provided Babashka tasks.

#### 1. Setup

Clone the repository and configure your options:

```bash
git clone https://github.com/amiorin/once
cd once
# Edit your chosen provider options
edit src/clj/io/github/amiorin/once/options.clj
```

In `bb.edn`, you can switch the active profile by changing the `require` statement:

```clojure
;; bb.edn
:requires [...
           ;; Switch between oci, hcloud, or no-infra
           [io.github.amiorin.once.options :refer [oci] :rename {oci options}]
           ...]
```

#### 2. Main Workflow

The `once` task handles the full lifecycle. You can pass multiple commands:

- **Full Setup**: `bb once create` (Tofu -> Tofu SMTP -> Tofu DNS -> Ansible -> Ansible Local)
- **Tear Down**: `bb once delete` (Tofu DNS Destroy -> Tofu SMTP Destroy -> Tofu Destroy)
- **Sequential**: `bb once delete create` (Clean slate redeploy)

#### 3. Targeted Tools

You can also run the underlying tools individually. Most tasks require a `render` step first to generate the necessary config files from templates into the `.dist/` directory.

- **OpenTofu (Infrastructure)**:
  ```bash
  bb tofu render tofu:init tofu:apply:-auto-approve
  ```
- **OpenTofu (SMTP)**:
  ```bash
  bb tofu-smtp render tofu:init tofu:apply:-auto-approve
  ```
- **OpenTofu (DNS)**:
  ```bash
  bb tofu-dns render tofu:init tofu:apply:-auto-approve
  ```
- **Remote Ansible**:
  ```bash
  bb ansible render -- ansible-playbook main.yml
  ```
- **Local Ansible**:
  ```bash
  bb ansible-local render -- ansible-playbook main.yml
  ```

### Programmatic Usage

You can trigger workflows directly from a Clojure REPL:

```clojure
(require '[io.github.amiorin.once.package :as once])
(require '[io.github.amiorin.once.options :as options])

;; Run the "create" workflow using OCI profile
(once/once* "create" options/oci)
```

## How It Works

1. **Template Rendering**: `big-config` takes templates from `src/resources` and your options to generate valid Tofu and Ansible files in `.dist/`.
2. **Infrastructure Hook**: When `create` runs, it first executes OpenTofu to provision resources.
3. **Inventory & Config Bridging**: The Tofu output (like the new server IP or SMTP records) is captured using `tofu output --json` and injected into the DNS configuration and Ansible inventory generation logic.
4. **Configuration**: Ansible then connects to the new host using the dynamically generated inventory to apply your playbooks.

## Project Structure

- `src/clj/.../once/`:
  - `package.clj`: Defines the high-level `create`/`delete` workflows.
  - `params.clj`: Logic for extracting parameters from Tofu outputs.
  - `tools.clj`: Implementation details for Tofu, Tofu SMTP, Tofu DNS, and Ansible wrappers.
  - `options.clj`: Where you define your cloud profiles and credentials.
- `src/resources/.../once/tools/`:
  - `tofu/`: Multi-cloud `.tf` templates.
  - `tofu-smtp/`: SMTP configuration templates (Resend).
  - `tofu-dns/`: DNS configuration templates (Cloudflare).
  - `ansible/`: Remote system playbooks.
  - `ansible-local/`: Local machine configuration playbooks.

## Development

If you are contributing to `once`, you can use the following task to keep the code clean:

```bash
bb tidy
```

This uses `clojure-lsp` to clean namespaces and format the source code.

## License

Copyright © 2026 Alberto Miorin

Distributed under the MIT License.
