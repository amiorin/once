# Blue ONCE

Python/uv implementation of the production ONCE package. Read `../CLAUDE.md`
and `/home/ubuntu/code/blue/README.md` before editing.

```sh
uv sync
uv run python -m pytest -q
uv run python -m package_once_blue build
```

Source and packaged resources live under `src/package_once_blue/`; tests are in
`tests/`. `./blue` links to the `package-once-blue` skill launcher.

Maintain exact behavioral and byte parity with Green and Red. Steps may be sync
or async, return new dicts, and keep engine state under `blue/*`. Use Blue's
runtime seam and pass subprocess environments explicitly; never mutate global
environment around parallel branches.

`.colors/` is generated and shared state—never edit it. Secrets use
`COLORS_PAR_*`, the one namespace every colour shares, and must never be
written to `colors.yml` or generated files. Keep domain logic out of the copied
launcher.

Blue reads YAML through PyYAML, whose defaults are YAML 1.1. `blue.cli` replaces
both the boolean and integer resolvers — and the integer constructor — to reach
1.2 core-schema semantics. Do not relax that: `./scripts/parity.sh` asserts blue
types every scalar exactly as green and red do.
