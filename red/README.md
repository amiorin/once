# package-once-red

The TypeScript/Bun implementation of the production ONCE deployment package.
It is byte-compatible with the Green and Blue implementations and manages the
same `.once/<profile>/` state.

```sh
bun install
./red build -f red.yml
./red create --dry-run -f red.yml
bun test
bun run typecheck
```

Desired state is YAML. Secrets use `RED_PAR_*` or portable `ONCE_PAR_*`; never
place them in `red.yml`. See the unified [`../index.html`](../index.html) manual
and [`../skills/package-once-red`](../skills/package-once-red).
