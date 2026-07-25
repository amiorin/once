# Once

`once` provisions and operates a single-server [ONCE](https://github.com/basecamp/once)
installation with OpenTofu and Ansible. It uses the
[`green`](https://github.com/amiorin/green) DAG workflow engine.

The repository ships two things from one file. `green-once/green` is the
launcher — a single Babashka script, symlinked as `./green` in the repository
root. That same file is the payload of the `green-once` agent skill, which
copies it into a user's project together with a generated `green.edn`:

```bash
npx skills use bigconfig-ai/once@green-once     # one-off session
npx skills add bigconfig-ai/once --skill green-once
```

Standing alone, the launcher resolves `once` and `green` as pinned git
dependencies. Inside this repository `bb.edn` supplies local roots and the
bootstrap is skipped, so commands run as `bb green <command>`.

## Workflow

Create and build use this graph:

```text
       ┌─ tofu-compute ─┐                             ┌─ ansible-local
start ─┤                ├─ tofu-dns ─ tofu-smtp-post ─┤
       └─ tofu-smtp ────┘                             └─ ansible-remote
```

Compute and SMTP run concurrently. DNS joins their outputs, SMTP verification
runs after DNS, and the two Ansible stages then run concurrently. Build renders
the same files without invoking OpenTofu or Ansible; the join falls back to
placeholder outputs so rendering never needs state.

Delete reverses the graph:

```text
start ─ ansible-cleanup ─ tofu-smtp-post ─ tofu-dns ─┬─ tofu-smtp
                                                     └─ tofu-compute
```

Cleanup replays the local Ansible play to drop the managed `~/.ssh/config`
block and removes the rendered Ansible trees; SMTP verification and DNS are
destroyed in order, then SMTP and compute are destroyed concurrently. Delete
reads the compute and SMTP outputs already in state so the destroy renders with
real values.

Generated files and isolated OpenTofu state directories live under
`.green/<profile>/`.

## Requirements

- Babashka
- OpenTofu
- Ansible
- OpenSSH, with the compute key loaded into `ssh-agent`
- `skopeo` for registry comparisons in `describe`

Cloud-provider credentials are needed only for the providers selected in
`green.edn`, and only for a real `create` or `delete`.

## Configuration

Desired state is the flat map in [`green.edn`](green.edn), except for the
nested `:once {:applications [...]}` collection:

```clojure
{:profile "production"          ; names the workdir, the state keys, the compute
 :workdir ".green"              ; resource, and the ~/.ssh/config Host alias
 :deploy-pubkey "ssh-ed25519 AAAA... ci-deploy"
 :once {:applications [{:host "www.example.com"
                        :image "ghcr.io/example/site:latest"
                        :env {"DATABASE_URL" :app-database-url}}
                       {:host "www.example.net"
                        :image "ghcr.io/example/another-site:latest"}]}
 :provider-compute "digitalocean" ; digitalocean, hcloud, oci, no-infra
 :provider-smtp "resend"          ; resend, no-infra
 :provider-dns "cloudflare"       ; cloudflare, no-infra
 :provider-backend "r2"           ; r2, s3, local
 :compute-prevent-destroy true}
```

There is no domain key. Application hosts are the source of truth and may span
domains. Green derives every DNS zone from each host's last two labels, creates
and verifies a Resend sending domain at `notifications.<zone>`, and gives each
application an `info@notifications.<zone>` From address in its own zone. Each
host gets its own proxied `A` record — there is no implicit apex or wildcard
record, so an unlisted host does not resolve.

`:env` maps a container variable **name** to the flat key holding its value,
never to the value itself.

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
export GREEN_PAR_APP_DATABASE_URL="..."   # one per application :env entry
```

Nothing lands in a rendered file. OpenTofu credentials are passed to the
process environment under the variable each provider reads natively; Ansible
receives `{{ lookup('env','GREEN_PAR_…') }}` expressions that resolve when the
play runs.

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
bb green pin                   # stamp the launcher with this repository's HEAD
```

Use another desired-state file with `-f` or `--file`:

```bash
bb green build -f production.edn
```

`build` and `--dry-run` require no credentials. A real `create` additionally
validates every provider credential and every application `:env` reference; a
real `delete` validates provider credentials and refuses while
`:compute-prevent-destroy` is true.

`describe` reads compute and SMTP values from their OpenTofu state before
probing the remote host. Infrastructure connectivity failures are reported as
soft failures; a missing remote `once` command produces a non-zero exit.

## Providers and generated configuration

- Compute templates: DigitalOcean, Hetzner Cloud, OCI, and an existing
  `no-infra` host.
- SMTP templates: Resend or `no-infra` SMTP settings.
- DNS templates: Cloudflare or `no-infra`; the per-application and Resend DNS
  records are generated as `apps.tf.json` and `smtp.tf.json` at the
  compute/SMTP join.
- Backends: local, S3, and Cloudflare R2, emitted as `backend.tf.json` and
  isolated by profile and tool under the state key `<profile>/<tool>.tfstate`.
- `ansible-local` runs a playbook that writes the managed `Host <profile>`
  block into `~/.ssh/config`, and removes it again on delete.
- `ansible-remote` installs Docker, ONCE, and Babashka, creates the restricted
  `deploy` user whose key is pinned to a `ForceCommand`, and reconciles the
  declared applications with the `once` Ansible module.

A build of the example desired state produces:

```text
.green/production/
├── tofu-compute/     backend.tf.json  main.tf
├── tofu-smtp/        backend.tf.json  main.tf
├── tofu-dns/         backend.tf.json  main.tf  apps.tf.json  smtp.tf.json
├── tofu-smtp-post/   backend.tf.json  main.tf
├── ansible-local/    ansible.cfg  inventory.ini  main.yml
└── ansible-remote/   ansible.cfg  main.yml  inventory.json  once.yml
                      files/deploy  library/once
```

## Development

```bash
clojure -M:test
clojure-lsp clean-ns
clojure-lsp format
clj-kondo --lint src/clj test/clj green-once/green
```

Source namespaces are under `src/clj/io/github/bigconfig_ai/once/`; templates
are under `src/resources/io/github/bigconfig-ai/once/tools/`. `.green/` is
generated and must not be edited.

After committing and pushing a change to the launcher, `src/clj`, or the
templates, repin so standalone copies resolve the new sources:

```bash
bb green pin        # stamps the launcher with the current HEAD
```

`pin` refuses to run on a dirty tree or an unpushed HEAD. A launcher whose pin
predates the sources it needs fails with a message naming `green pin` rather
than rendering silently from an older commit; the check is the `contract`
number in `utils.clj`, which has to be bumped whenever an older launcher could
not survive the change.

## License

Copyright © 2026 Alberto Miorin

Distributed under the MIT License.
