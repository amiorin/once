# package-once-blue

The Python/uv implementation of the production ONCE deployment package. It is
byte-compatible with Green and Red and can manage the same `.colors/<profile>/`
state.

```sh
uv sync
uv run python -m package_once_blue build
uv run python -m package_once_blue create --dry-run
uv run python -m pytest -q
```

Desired state is the `colors.yml` found by walking up from the working
directory — the same file green and red read, so switching colours needs no
change to it. Yandex compute supports `yandex-static-ip`,
`yandex-allow-stopping-for-update`, and optional `yandex-image-id`. Yandex Cloud
DNS creates public zones and direct records with `provider-dns: yandex`.
Secrets use `COLORS_PAR_*`; never put them in `colors.yml`. See
the unified [`../index.html`](../index.html) manual and
[`../skills/package-once-blue`](../skills/package-once-blue).

An application naming `github: owner/repo` gets `SSH_PRIVATE_KEY`, `SERVER_IP`,
`SERVER_USER`, and `SSH_KNOWN_HOSTS` published into an Actions environment named
after the profile on every `create` — but nothing reads them until a workflow in
that repository does.
[`../skills/package-once-blue/references/github-deploy.md`](../skills/package-once-blue/references/github-deploy.md)
is that workflow.
