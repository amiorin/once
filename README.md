# Once

A monorepo containing three byte-compatible implementations of the production
single-server [Basecamp ONCE](https://github.com/basecamp/once) deployment
workflow:

| Package | Runtime | YAML reader | Skill |
|---|---|---|---|
| [Green](green/) | Clojure / Babashka | yamlstar | `package-once-green` |
| [Red](red/) | TypeScript / Bun | `Bun.YAML` | `package-once-red` |
| [Blue](blue/) | Python / uv | PyYAML | `package-once-blue` |

All three read one `colors.yml`, render the same OpenTofu and Ansible files,
and operate the same `.colors/<profile>/` work directory and remote state.
Switching colours needs no change to desired state — only a different command.
Switch between completed commands; never run two against the same state
concurrently.

## Skills

```sh
npx skills use getcolors/once@package-once-green
npx skills use getcolors/once@package-once-red
npx skills use getcolors/once@package-once-blue
```

Skill packages are under [`skills/`](skills/). Each guides desired-state setup,
protects secrets, and runs a build plus dry-run before any real provisioning.
The unified user manual is [`index.html`](index.html).

## Shared workflow

Create and build:

```text
       ┌─ tofu-compute ─┐                             ┌─ ansible-local
start ─┤                ├─ tofu-dns ─ tofu-smtp-post ─┤
       └─ tofu-smtp ────┘                             └─ ansible-remote ─ github
```

Publishing follows the remote stage, not the local one: the credentials
describe a configured host, so a workstation-side failure does not gate them.

Delete reverses the graph. It withdraws the published credentials first — a
withdrawn credential against a live host is a loud, recoverable broken deploy,
while a live credential against a destroyed host is silent — then removes the
managed local SSH block before infrastructure. Providers are DigitalOcean,
Hetzner Cloud, Yandex Cloud, OCI, or an existing host; Resend or existing SMTP;
Cloudflare or unmanaged DNS; and local, S3, or R2 state.

## Secrets

`colors.yml` contains non-secret values only. Credentials travel in one
namespace, `COLORS_PAR_*`, which every colour reads — there is no per-colour
prefix. Generated Ansible expressions are byte-identical and resolve that one
name at play time. OCI, S3, and SSH continue to use their native ambient
credential mechanisms.

## Upgrading an existing project

This release renames the desired-state file, the work directory, and the
credential namespace. None of it migrates automatically.

**Move the work directory before running anything.** On the `local` backend —
the default when `provider-backend` is unset — OpenTofu state lives inside it,
so a command run against the new name finds no state and a `create` will build
a second server alongside the one you already have. `s3` and `r2` projects keep
state remotely and are unaffected.

```sh
mv .once .colors                     # do this first
```

Then rename desired state to `colors.yml` (Green projects also convert EDN to
YAML), set `workdir: .colors` inside it, rename every credential variable to
`COLORS_PAR_*`, and re-install the skill so the launcher is replaced. The old
`GREEN_PAR_*`, `RED_PAR_*`, `BLUE_PAR_*`, and `ONCE_PAR_*` names are no longer
read; a stale one is ignored and the run stops with `required credential is not
set`. An outdated launcher refuses to run rather than rendering from a stale
contract.

### Migrating DNS resource addresses (contract 10)

Contract 10 renames the Clojure namespaces to `io.github.getcolors.once.*`.
That name is not only internal: it is part of the address of every DNS record
this project manages, so the rename moves them and **an unmigrated `create`
will destroy and recreate every record in the zone.**

Run this once per project, after re-installing the skill and before the next
`create`. It rewrites the addresses in place; nothing is created or destroyed.

```sh
./green build                        # render the work tree at the new contract
cd .colors/<profile>/tofu-dns
tofu init
tofu state pull > /tmp/tofu-dns.backup.tfstate    # keep this until the plan is clean

for old in $(tofu state list | grep io_github_bigconfig_ai_once_tools_); do
  tofu state mv "$old" "${old/io_github_bigconfig_ai_once_tools_/io_github_getcolors_once_tools_}"
done

tofu plan                            # must report no changes
```

`tofu plan` reporting **no changes** is the whole proof: the addresses now match
what the templates render, and the records themselves were never touched. If it
proposes anything, restore with `tofu state push /tmp/tofu-dns.backup.tfstate`
before going further.

Only the `tofu-dns` stage is affected — compute, smtp, and smtp-post name their
resources without the namespace. Zone settings are keyed by zone and setting
name and do not move either.

## Development

```sh
cd green && clojure -M:test
cd red && bun test && bun run typecheck
cd blue && uv run python -m pytest -q
./scripts/parity.sh
```

`parity.sh` builds a provider matrix through all three packages from one
`test/parity/colors.yml`, compares every complete generated tree byte-for-byte,
verifies that packaged resource copies match Green's reference resources, and
checks that the three YAML readers type every scalar in
`test/parity/scalars.yml` identically.

Generated `.colors/` directories are artifacts and must not be edited as source.
