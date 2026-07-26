# CLAUDE.md

## Repository

This is the `bigconfig-ai/once` monorepo. It contains three implementations of
one production ONCE workflow:

- `green/` — Clojure/Babashka over `/home/ubuntu/code/green`
- `red/` — TypeScript/Bun over `/home/ubuntu/code/red`
- `blue/` — Python/uv over `/home/ubuntu/code/blue`
- `skills/package-once-{green,red,blue}/` — agent skills and launcher payloads
- `index.html` — the only HTML manual in this repository

Read the implementation's own `CLAUDE.md` before editing inside it. Red's
library instructions are `/home/ubuntu/code/red/CLAUDE.md`; Green's are
`/home/ubuntu/code/green/CLAUDE.md`; Blue's conventions are in
`/home/ubuntu/code/blue/README.md`.

## Required parity

Green, Red, and Blue are interchangeable managers of the same OpenTofu state.
For equivalent desired state they must preserve:

- the same create/build/delete DAG and failure semantics
- the same stage directories and `<profile>/<tool>.tfstate` backend keys
- the same provider versions, resource addresses, output contracts, and secrets
- byte-identical generated files

Static resources are packaged separately in every implementation. Run
`./scripts/parity.sh`; it compares both generated artifacts and all resource
copies. Never fix a parity failure by weakening that comparison.

The shared workdir is `.once/<profile>/`. It is generated output: never edit or
use it as source. Implementations may be switched between completed commands,
but must never run concurrently against the same state.

## Commands

```sh
cd green && clojure -M:test
cd red && bun test && bun run typecheck
cd blue && uv run python -m pytest -q
./scripts/parity.sh
```

The root npm package is the `package-once-red` facade because npm Git
dependencies cannot select a monorepo subdirectory. Green uses the git
`:deps/root "green"`; Blue uses the Python git subdirectory.

## Architecture

Create/build:

```text
start ─┬─ tofu-compute ─┐                          ┌─ ansible-local
       └─ tofu-smtp ────┴─ tofu-dns ─ smtp-post ───┴─ ansible-remote
```

Delete runs cleanup, SMTP post, DNS, then SMTP and compute in parallel. Step
failures travel as color-namespaced exit/error keys rather than uncaught
exceptions. Builds render without invoking tools; dry-runs touch nothing.

Desired-state boundary keys remain kebab-case. Engine keys use each library's
reserved namespace. Credentials use the native color prefix or `ONCE_PAR_*`;
never render secret values. The generated Ansible lookup expression must remain
identical in all three implementations.

## Documentation and skills

The skill names are `package-once-green`, `package-once-red`, and
`package-once-blue`; `green-once` is intentionally not retained. Keep the root
manual unified—do not add leaf `index.html` files. When configuration or
behavior changes, update all three skills, implementation READMEs, and the root
manual.

## Git

Work on the current branch. The nested workflow libraries are separate Git
repositories. Do not commit or push any repository unless explicitly asked.
Library and launcher pins can only be finalized after the relevant commits are
pushed; never invent or hand-edit a nonexistent SHA.
