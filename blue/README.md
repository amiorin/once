# package-once-blue

The Python/uv implementation of the production ONCE deployment package. It is
byte-compatible with Green and Red and can manage the same `.once/<profile>/`
state.

```sh
uv sync
uv run python -m package_once_blue build -f blue.yml
uv run python -m package_once_blue create --dry-run -f blue.yml
uv run python -m pytest -q
```

Desired state is YAML. Secrets use `BLUE_PAR_*` or portable `ONCE_PAR_*`; never
put them in `blue.yml`. See the unified [`../index.html`](../index.html) manual
and [`../skills/package-once-blue`](../skills/package-once-blue).
