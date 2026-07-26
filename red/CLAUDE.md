# Red ONCE

TypeScript/Bun implementation of the production ONCE package. Read the root
`../CLAUDE.md` and `/home/ubuntu/code/red/CLAUDE.md` before changes.

```sh
bun install
bun test
bun run typecheck
./red build -f red.yml
```

Source is under `src/`, packaged templates under `resources/`, tests under
`test/`. `./red` links to `../skills/package-once-red/red`. The repository root
`package.json` is the installable npm facade; this leaf manifest is development
only.

Maintain exact behavior and generated-byte parity with Green and Blue. Use
Red's immutable workflow values, return new opts objects, and keep engine keys
under `red/*`. All subprocesses use Red's runtime seam. Never mutate process
environment around parallel branches; pass per-command environments.

Never edit `.once/`. Secrets use `RED_PAR_*` or `ONCE_PAR_*`, remain out of
state files and rendered content, and are tested only by presence. Do not put
production logic into the copied launcher.
