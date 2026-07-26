# Once

A monorepo containing three byte-compatible implementations of the production
single-server [Basecamp ONCE](https://github.com/basecamp/once) deployment
workflow:

| Package | Runtime | Desired state | Skill |
|---|---|---|---|
| [Green](green/) | Clojure / Babashka | `green.edn` | `package-once-green` |
| [Red](red/) | TypeScript / Bun | `red.yml` | `package-once-red` |
| [Blue](blue/) | Python / uv | `blue.yml` | `package-once-blue` |

All three render the same OpenTofu and Ansible files and can operate the same
`.once/<profile>/` work directory and remote state. Switch implementations only
between completed commands; never run two against the same state concurrently.

## Skills

```sh
npx skills use bigconfig-ai/once@package-once-green
npx skills use bigconfig-ai/once@package-once-red
npx skills use bigconfig-ai/once@package-once-blue
```

Skill packages are under [`skills/`](skills/). Each guides desired-state setup,
protects secrets, and runs a build plus dry-run before any real provisioning.
The unified user manual is [`index.html`](index.html).

## Shared workflow

Create and build:

```text
       ┌─ tofu-compute ─┐                             ┌─ ansible-local
start ─┤                ├─ tofu-dns ─ tofu-smtp-post ─┤
       └─ tofu-smtp ────┘                             └─ ansible-remote
```

Delete reverses the graph and removes the managed local SSH block before
infrastructure. Providers are DigitalOcean, Hetzner Cloud, Yandex Cloud, OCI,
or an existing host; Resend or existing SMTP; Cloudflare or unmanaged DNS; and
local, S3, or R2 state.

## Secrets

Desired-state files contain non-secret values only. Each implementation accepts
its native prefix (`GREEN_PAR_*`, `RED_PAR_*`, or `BLUE_PAR_*`) and the portable
`ONCE_PAR_*` alias. Generated Ansible expressions are byte-identical and check
the portable and all native forms at play time. OCI, S3, and SSH continue to use
their native ambient credential mechanisms.

## Development

```sh
cd green && clojure -M:test
cd red && bun test && bun run typecheck
cd blue && uv run python -m pytest -q
./scripts/parity.sh
```

`parity.sh` builds a provider matrix through all three packages, compares every
complete generated tree byte-for-byte, and verifies that packaged resource
copies match Green's reference resources.

Generated `.once/` directories are artifacts and must not be edited as source.
