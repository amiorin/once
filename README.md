# Once

`once` provisions a single-server [ONCE](https://github.com/basecamp/once)
installation with OpenTofu and Ansible. It uses the
[`green`](https://github.com/amiorin/green) DAG workflow engine.

## Workflow

Create and build use this graph:

```text
tofu-compute ─┐                                      ┌─ ansible-local
               ├─ tofu-dns ─ tofu-smtp-post ─────────┤
tofu-smtp ────┘                                      └─ ansible-remote
```

Compute and SMTP run concurrently. DNS joins their outputs, SMTP verification
runs after DNS, and the two Ansible stages then run concurrently. Build renders
the same files without invoking OpenTofu or Ansible.

Delete reverses the graph:

```text
ansible-cleanup ─ tofu-smtp-post ─ tofu-dns ─┬─ tofu-smtp
                                              └─ tofu-compute
```

Cleanup removes the rendered Ansible trees, SMTP verification and DNS are
destroyed in order, then SMTP and compute are destroyed concurrently. Delete
reads the compute and SMTP outputs already in state so the destroy renders with
real values.

Generated files and isolated OpenTofu state directories live under
`.green/<profile>/`.

## Requirements

- Babashka
- OpenTofu
- Ansible
- `skopeo` for registry comparisons in `describe`

Cloud-provider credentials are needed only for the providers selected in
`green.edn`.

## Configuration

Desired state is the flat map in [`green.edn`](green.edn). Select compute,
SMTP, DNS, and backend providers with:

```clojure
{:provider-compute "digitalocean" ; digitalocean, hcloud, oci, no-infra
 :provider-smtp "resend"          ; resend, no-infra
 :provider-dns "cloudflare"       ; cloudflare, no-infra
 :provider-backend "r2"}          ; r2, s3, local
```

Credential keys are absent from the committed file: they arrive as `GREEN_PAR_*`
environment variables, which are overlaid onto the matching flat key before the
workflow starts. Any flat key can be overridden the same way; names are
lowercased and underscores become hyphens, and the override takes the type of
the value it replaces:

```bash
export GREEN_PAR_DO_TOKEN="..."
export GREEN_PAR_CLOUDFLARE_API_TOKEN="..."
export GREEN_PAR_RESEND_API_KEY="..."
export GREEN_PAR_RESEND_PASSWORD="..."
export GREEN_PAR_R2_ACCESS_KEY_ID="..."
export GREEN_PAR_R2_SECRET_ACCESS_KEY="..."
```

Use `.envrc.private` for local secrets. To permit compute destruction when the
default safeguard is enabled:

```bash
export GREEN_PAR_COMPUTE_PREVENT_DESTROY=false
```

## Commands

Run from the repository root:

```bash
bb green build                 # render .green/<profile>/ only
bb green create                # provision and configure
bb green create --dry-run      # print the DAG actions, touch nothing
bb green delete                # destroy infrastructure
bb green delete --dry-run
bb green describe              # providers, SSH status, apps, image updates
```

Use another desired-state file with `-f` or `--file`:

```bash
bb green build -f production.edn
```

`describe` reads compute and SMTP values from their OpenTofu state before
probing the remote host. Infrastructure connectivity failures are reported as
soft failures; a missing remote `once` command produces a non-zero exit.

## Providers and generated configuration

- Compute templates: DigitalOcean, Hetzner Cloud, OCI, and an existing
  `no-infra` host.
- SMTP templates: Resend or `no-infra` SMTP settings.
- DNS templates: Cloudflare or `no-infra`; Resend DNS records are generated at
  the compute/SMTP join.
- Backends: local, S3, and Cloudflare R2, emitted as `backend.tf.json` and
  isolated by profile and tool.
- `ansible-local` scaffolds local SSH configuration files.
- `ansible-remote` generates inventory and the ONCE reconciliation task, then
  runs `ansible-playbook` during create.

## Development

```bash
clojure -M:test
clojure-lsp clean-ns
clojure-lsp format
```

Source namespaces are under `src/clj/io/github/bigconfig_ai/once/`; templates
are under `src/resources/io/github/bigconfig-ai/once/tools/`. `.green/` is
generated and must not be edited.

The launcher is a single file, `green-once/green`; `./green` in the repository
root is a symlink to it. The same file is the skill payload: copied into a
project on its own it resolves `once` and `green` as pinned git dependencies,
while inside this repository `bb.edn` supplies them from local roots and the
bootstrap is skipped.

After committing and pushing a change to the launcher, `src/clj`, or the
templates, repin so standalone copies resolve the new sources:

```bash
bb green pin        # stamps the launcher with the current HEAD
```

`pin` refuses to run on a dirty tree or an unpushed HEAD. A launcher whose pin
predates the sources it needs fails with a message naming `green pin` rather
than rendering silently from an older commit; the check is the `contract`
number in `utils.clj`.

## License

Copyright © 2026 Alberto Miorin

Distributed under the MIT License.
