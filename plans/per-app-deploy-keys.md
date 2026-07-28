# Per-application ephemeral deploy keys

Status: **superseded by `per-repo-deploy-keys.md`.** Keys are now per
repository, and a deploy is a ping rather than a command. Kept for the
reasoning that still holds — why the key can be ephemeral, why two generations
are retained, why generation shells out to `ssh-keygen`, and why the
environment is created with an idempotent PUT.

Implemented and verified against production on 2026-07-28.

## Goal

An application in `colors.yml` may name a GitHub repository. ONCE then owns
that repository's deployment credentials end to end: it generates a keypair per
application on every `create`, installs the public half on the box restricted to
that one application's host, and publishes the private half plus the connection
details into a GitHub environment named after the profile. The operator never
sees, stores, or pastes a deploy key.

## Why the key can be ephemeral

`files/deploy` already pins the deploy user's authorized key to a ForceCommand
that accepts nothing but `once update <host>`. A leaked key can trigger a
redeploy and nothing else, so the key is low value and regenerating it costs
nothing. That makes storage the wrong question: there is no store, no state to
reconcile, and no recovery path to maintain. Two operators running `create`
converge on whoever went last.

This depends on `files/deploy` staying tight. That dependency is now load-bearing
and is noted in the script itself.

## Why per application

Today one key serves every application on the box, and the ForceCommand accepts
`once update <host>` for any host ONCE serves. One repository's leaked key can
therefore redeploy another repository's application. Per-application keys let the
ForceCommand carry the allowed host as an argument, so a key is useless against
anything but its own application.

## Retention: two generations

Installing a new key invalidates the old one the moment `ansible-remote` runs.
If the subsequent publish fails, GitHub holds a key the box no longer accepts and
deploys break until the next `create`.

Keeping the previous generation closes that window: the old key stays valid, so a
failed publish is harmless and self-heals. Two generations is the whole benefit —
anything more only extends how long a leaked key lives.

Retention state lives on the box, because ephemeral generation means ONCE does
not remember what it issued. It must not leak into rendered bytes: `main.yml`
renders only the current key, and `files/authorized-keys` merges it against
what is already installed, keeping at most two entries per host. File order
carries the ordering, so no timestamp enters a rendered artifact.

## Desired state

```yaml
once:
  applications:
    - host: www.example.com
      image: ghcr.io/bigconfig-ai/once-bigconfig:latest
      github: bigconfig-ai/once-bigconfig
      env:
        EXAMPLE: app-example
```

- `github` is optional. Absent, the application gets no key at all. (This line
  originally read "gets a key and no publication", which the code never did —
  the filter excludes it before anything is generated.)
- `deploy-pubkey` is **removed** from desired state. It is generated now.
- `github-token` joins the flat keys, supplied as `COLORS_PAR_GITHUB_TOKEN`.

## Generation

`ssh-keygen -t ed25519 -N ''` shelled through the runner seam, once per
application, into a temp directory that is removed after publication. Three
in-process crypto stacks would have to agree on OpenSSH private-key encoding;
one subprocess does not.

Generation happens **only on `:create`**. `:build` and dry-run use a fixed
placeholder public key, in the same spirit as `fallback-compute-params`. A random
key rendered into `ansible-remote/main.yml` would make build output
nondeterministic and break `scripts/parity.sh` outright.

## The DAG

```text
create/build:
start ─┬─ tofu-compute ─┐                          ┌─ ansible-local
       └─ tofu-smtp ────┴─ tofu-dns ─ smtp-post ───┴─ ansible-remote ─ github

delete:
start ─ github ─ ansible-cleanup ─ tofu-smtp-post ─ tofu-dns ─┬─ tofu-smtp
                                                              └─ tofu-compute
```

One `:once/github` step, switching on `:green/event` the way the Tofu stages
already switch between apply and destroy.

- **create** — per application carrying `github`:
  `gh variable set SERVER_IP --env <profile>`,
  `gh variable set SERVER_USER --env <profile>`,
  `gh variable set SSH_KNOWN_HOSTS --env <profile>` — the server's own host key,
  read over the administrative SSH connection, so a workflow can pin it rather
  than running `ssh-keyscan` on every deploy. That is still trust on first use
  at provisioning time; what it removes is CI re-trusting the network forever.
  `gh secret set SSH_PRIVATE_KEY --env <profile>` reading the key from its file.

  Not `--body`: that puts the key in the process argv, visible to `ps`. Not
  stdin either, though that was the intent — all three runtime seams spawn with
  the child's stdin closed (`green.process` closes it, Red and Blue pass
  `ignore`/`DEVNULL`), and those live in separately pinned library repositories.
  So the command is `sh -c 'gh secret set … < <keyfile>'`, which keeps the key
  out of the process table exactly as stdin would have. The quoting is written
  out by hand in all three rather than delegated to a stdlib helper, because
  Python's `shlex.quote` leaves safe strings bare and would have made Blue emit
  a different command than Green and Red for the same input.
- **delete** — removes the three variables and the secret by name. It needs no key
  material, so it works with the box already destroyed. It runs first, before
  anything is torn down.
- **build / dry-run** — no-op. `wire-fn` runs the same branch for `:build` and
  `:create`, so the step needs its own event check; adding it to
  `side-effecting-steps` only covers dry-run.

The environment is created first, with an idempotent
`gh api --method PUT repos/<repo>/environments/<profile>`. The original design
relied on a workflow bringing the environment into being by referencing it,
which keeps the token off admin scope — but that only holds once the workflow
has been wired to the environment, and nothing here can guarantee that came
first. Testing against two repositories that had never deployed found both
returning 404, so ONCE creates it.

## On the box

`authorized_keys` lines become, one per application:

```text
restrict,command="/usr/local/bin/deploy <host>" <pubkey> once-deploy-<profile>-<host>
```

`files/deploy` takes the allowed host as `$1` and rejects `once update` for any
other host, in addition to the existing check that ONCE actually serves it.

`files/authorized-keys` merges the rendered current keys into the live file,
keeping at most two generations per host and dropping entries for hosts no longer
in desired state.

## Cross-cutting

- `validate`: an application with `github` requires `COLORS_PAR_GITHUB_TOKEN`;
  `github` must look like `owner/repo`. `deploy-pubkey` validation is removed.
- `utils/contract` 7 → 8, and `launcher-contract` to match. An older launcher
  would silently ignore `github` and publish nothing.
- Tests: the step emits no bytes, so `parity.sh` cannot see it. Each colour gets
  a fake-runner test asserting an identical `gh` argv sequence.
- Docs: root `colors.yml`, three READMEs, root `index.html`, three skills.

## Consuming workflows

A deploy job must declare `environment: <profile>`, or the environment-scoped
secret and variables are invisible to it and every value resolves to empty. It
reads `vars.SERVER_IP`, `vars.SERVER_USER`, `vars.SSH_KNOWN_HOSTS` and
`secrets.SSH_PRIVATE_KEY` — only the key is a secret.

The artifact is **not** pinned from the pipeline. The ForceCommand permits
exactly `sudo once update <host>`, so a workflow has no way to name a digest;
pinning is a deliberate edit to `image:` in desired state. The pipeline exists
to trigger a re-pull when the tag is mutable.

## Repository visibility

On GitHub Free, environments work **only in public repositories**, and a
public-to-private conversion causes environment secrets to be *silently ignored*
rather than rejected. Both `getcolors` repositories were confirmed public before
this was relied on.
