# Blue ONCE

Python/uv implementation of the production ONCE package. Read `../CLAUDE.md`
and `/home/ubuntu/code/blue/README.md` before editing.

```sh
uv sync
uv run python -m pytest -q
uv run python -m package_once_blue build -f blue.yml
```

Source and packaged resources live under `src/package_once_blue/`; tests are in
`tests/`. `./blue` links to the `package-once-blue` skill launcher.

Maintain exact behavioral and byte parity with Green and Red. Steps may be sync
or async, return new dicts, and keep engine state under `blue/*`. Use Blue's
runtime seam and pass subprocess environments explicitly; never mutate global
environment around parallel branches.

`.once/` is generated and shared state—never edit it. Secrets use `BLUE_PAR_*`
or `ONCE_PAR_*` and must never be written to desired state or generated files.
Keep domain logic out of the copied launcher.
