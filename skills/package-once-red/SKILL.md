---
name: package-once-red
description: Creates and operates production single-server Basecamp ONCE deployments with Red, Bun, OpenTofu, and Ansible. Use for colors.yml setup, safe builds and dry-runs, provisioning, deletion, or status reports.
license: MIT
---

# ONCE with Red

Use this skill in the user's current directory. Read
[references/configuration.md](references/configuration.md) before creating or
changing desired state and before a real create or delete, and
[references/github-deploy.md](references/github-deploy.md) before writing or
changing a GitHub Actions workflow.

## Safety

- Never request or print a secret, private key, token, password, or application value.
- Secrets use `COLORS_PAR_*`, the one namespace every colour shares, and never belong in `colors.yml`, generated files, commands, or logs.
- Read only a user-approved SSH `.pub` file. Never inspect a private key.
- Do not overwrite `red`, `colors.yml`, or `package.json` without explicit approval.
- Default to `build` and `create --dry-run`. A real create/delete needs explicit confirmation for that operation.
- Build and dry-run are credential-free; never claim they validate credentials.
- Delete remains blocked until `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` or `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` is present.
- Never edit `.colors/`; it is generated and is shared safely with the Green and Blue implementations. Never run two implementations concurrently against that state.

## Initialize

Gather profile, applications, provider choices, and the selected providers'
non-secret settings. Deploy keys are not among them: ONCE generates one per
repository named under `github` on every create and publishes it to a GitHub
environment named after the profile, so ask only for `COLORS_PAR_GITHUB_TOKEN`.
Do not gather secret values. When an application names a repository, also ask
whether the user wants continuous deployment — see below.

After confirmation:

1. Copy this skill's bundled `red` to `./red` and make it executable. Its `PINS`
   hold the immutable commits this skill was built from; the launcher resolves
   them itself on first run, into `~/.cache/package-once-red/`. No manifest and
   no install step are needed.
2. Write `colors.yml` following the reference, with `workdir: .colors`.
3. Add `.colors/` and any private environment file to `.gitignore` without replacing unrelated entries.
4. Run `./red build` and `./red create --dry-run`.
5. Report required variable names only. Do not run a real create automatically.

A project may still declare `package-once-red` in a `package.json` — those
versions win over the launcher's `PINS`, so a manifest and its lockfile stay
authoritative where one already exists. Do not add one to a project that has
none: it is a second place recording the same commit, and the two drift.

## Continuous deployment

`create` publishes `SSH_PRIVATE_KEY`, `SERVER_IP`, `SERVER_USER`, and
`SSH_KNOWN_HOSTS` into an Actions environment named after the profile; nothing
reads them until a workflow does. So whenever an application names `github`,
ask whether to also add the workflow that builds the image and pings the server
on every push to `main`. Do not ask when no application names a repository —
without it nothing is published.

On yes, adapt [references/github-deploy.md](references/github-deploy.md):
substitute the profile for `PROFILE` and the application host for the
environment URL. The workflow belongs in the **application's** repository, often
not the directory holding `colors.yml`. Compare the `origin` remote to
`owner/repo`: on a match write `.github/workflows/deploy.yml`, asking before
overwriting an existing one; otherwise show the YAML and name its path rather
than writing into the wrong repository. State that the repository needs its own
`Dockerfile`, that the published image must equal the application's `image`, and
that the first run works only after a real `create`.

On no, nothing further is needed: `ssh -T deploy@SERVER_IP </dev/null` is the
whole deploy interface, and `auto_update: true` updates without a ping.

## Operate

```sh
./red build
./red create --dry-run
./red create
./red describe
./red delete --dry-run
./red delete
```

Use `-f|--file` for another desired-state file. Before a real lifecycle event,
check required variables by presence only and let the launcher perform final
validation.
