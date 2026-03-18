# Once

`once` is a BigConfig package for [ONCE](https://github.com/basecamp/once). This BigConfig package is an infrastructure automation tool that simplifies the provisioning and configuration of cloud resources using [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/).

It is built on top of [big-config](https://github.com/amiorin/big-config), leveraging its workflow and configuration management capabilities.

## Features

- **Workflow Orchestration**: Orchestrate complex multi-step processes like `tofu init/apply` followed by `ansible-playbook` executions.
- **Hyperscaler Support**: Supports infrastructure provisioning on:
  - Hetzner Cloud (`hcloud`)
  - Oracle Cloud Infrastructure (`oci`)
- **Configurable Workflows**:
  - `create`: Provisions infrastructure using Tofu and then configures it using Ansible (both remote and local).
  - `delete`: Tears down infrastructure using Tofu.
- **Dynamic Inventory**: Automatically generates Ansible inventory from OpenTofu outputs.

## Prerequisites

To use package `once`, you need the following tools installed:

- [Clojure](https://clojure.org/guides/install_clojure)
- [Babashka](https://babashka.org/) (optional, for CLI tasks)
- [OpenTofu](https://opentofu.org/docs/intro/install/)
- [Ansible](https://docs.ansible.com/ansible/latest/installation_guide/intro_installation.html)
- Cloud provider credentials (e.g., `HCLOUD_TOKEN` or OCI configuration)

## Usage

`once` can be used via [Babashka](https://babashka.org/) tasks or as a Clojure library.

### Using Babashka Tasks

If you have `bb` installed, you can use the following tasks:

- **Create Everything**:
  ```bash
  bb once create
  ```
- **Delete Everything**:
  ```bash
  bb once delete
  ```
- **Run Tofu Directly**:
  ```bash
  bb tofu render tofu:init tofu:apply:-auto-approve
  ```
- **Run Ansible Directly**:
  ```bash
  bb ansible render -- ansible-playbook main.yml
  ```

### Using as a Clojure Library

You can also trigger workflows programmatically in a REPL:

```clojure
(require '[io.github.amiorin.once.package :as once])
(require '[io.github.amiorin.once.options :as options])

(once/once* "create" options/oci)
```

## Configuration

The tool uses `big-config` for configuration. Default options for different hyperscalers are defined in `io.github.amiorin.once.options`.

### OCI Configuration Example

```clojure
{:hyperscaler "oci"
 :package "once"
 :config-file-profile "DEFAULT"
 :subnet-id "..."
 :compartment-id "..."
 :availability-domain "..."
 :display-name "my-instance"
 :shape "VM.Standard.A1.Flex"
 :ocpus 2
 :memory-in-gbs 12
 :boot-volume-size-in-gbs 100
 :ssh-authorized-keys "~/.ssh/id_ed25519.pub"}
```

## Project Structure

- `src/clj/io/github/amiorin/once/`: Core logic and workflow definitions.
  - `tools.clj`: Wrappers for `tofu` and `ansible`.
  - `package.clj`: High-level `create` and `delete` workflows.
  - `options.clj`: Default configuration options.
- `src/resources/io/github/amiorin/once/tools/`: Templates for Tofu and Ansible.
  - `tofu/`: OpenTofu configuration templates for `hcloud` and `oci`.
  - `ansible/`: Ansible playbooks and configurations.
  - `ansible-local/`: Local Ansible configurations.

## License

Copyright © 2026 Alberto Miorin

Distributed under the MIT License.
