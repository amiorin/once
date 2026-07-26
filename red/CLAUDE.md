# Red ONCE

TypeScript/Bun implementation of the production ONCE package. Read the root
`../CLAUDE.md` and `/home/ubuntu/code/red/CLAUDE.md` before changes.

```sh
bun install
bun test
bun run typecheck
./red build
```

Source is under `src/`, packaged templates under `resources/`, tests under
`test/`. `./red` links to `../skills/package-once-red/red`. The repository root
`package.json` is the installable npm facade; this leaf manifest is development
only.

Maintain exact behavior and generated-byte parity with Green and Blue. Use
Red's immutable workflow values, return new opts objects, and keep engine keys
under `red/*`. All subprocesses use Red's runtime seam. Never mutate process
environment around parallel branches; pass per-command environments.

Never edit `.colors/`. Secrets use `COLORS_PAR_*` — the one namespace every
colour shares — remain out of `colors.yml` and rendered content, and are tested
only by presence. Do not put production logic into the copied launcher.

Red reads YAML with `Bun.YAML`, which follows the 1.2 core schema. Keep it that
way: green and blue use different parsers, and `./scripts/parity.sh` asserts
all three type every scalar identically.
