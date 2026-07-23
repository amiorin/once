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
the same files without invoking OpenTofu or Ansible. Delete destroys DNS first,
then destroys SMTP and compute concurrently.

Generated files and isolated OpenTofu state directories live under
`.dist/<profile>/`.

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

Secrets can remain as `REPLACE_ME` in the committed file. Any flat key can be
overridden with `GREEN_PAR_*`; names are lowercased and underscores become
hyphens:

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
bb green build                 # render .dist/<profile>/ only
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
are under `src/resources/io/github/bigconfig-ai/once/tools/`. `.dist/` is
generated and must not be edited.

## License

Copyright © 2026 Alberto Miorin

Distributed under the MIT License.
