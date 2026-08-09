# package-once-red

The TypeScript/Bun implementation of the production ONCE deployment package.
It is byte-compatible with the Green and Blue implementations and manages the
same `.colors/<profile>/` state.

```sh
bun install
./red build
./red create --dry-run
bun test
bun run typecheck
```

Desired state is the `colors.yml` found by walking up from the working
directory — the same file green and blue read, so switching colours needs no
change to it. Yandex compute supports `yandex-static-ip` and
`yandex-allow-stopping-for-update`; both default to `false`.
`yandex-image-id` optionally pins the boot image; without it, later family
releases do not replace the server. Secrets use
`COLORS_PAR_*`; never place them in `colors.yml`. See
the unified [`../index.html`](../index.html) manual and
[`../skills/package-once-red`](../skills/package-once-red).

An application naming `github: owner/repo` gets `SSH_PRIVATE_KEY`, `SERVER_IP`,
`SERVER_USER`, and `SSH_KNOWN_HOSTS` published into an Actions environment named
after the profile on every `create` — but nothing reads them until a workflow in
that repository does.
[`../skills/package-once-red/references/github-deploy.md`](../skills/package-once-red/references/github-deploy.md)
is that workflow.
