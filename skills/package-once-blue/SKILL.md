---
name: package-once-blue
description: Creates and operates production single-server Basecamp ONCE deployments with Blue, uv, OpenTofu, and Ansible. Use for colors.yml setup, safe builds and dry-runs, provisioning, deletion, or status reports.
license: MIT
---

# ONCE with Blue

Use this skill in the user's current directory. Read
[references/configuration.md](references/configuration.md) before creating or
changing desired state and before a real create or delete, and
[references/github-deploy.md](references/github-deploy.md) before writing or
changing a GitHub Actions workflow.

## Safety

- Never request or print a secret, private key, token, password, or application value.
- Secrets use `COLORS_PAR_*`, the one namespace every colour shares, and never belong in `colors.yml`, generated files, commands, or logs.
- Read only an approved public `.pub` file; never inspect a private key.
- Do not overwrite `blue` or `colors.yml` without explicit approval.
- Default to `build` and `create --dry-run`; require explicit confirmation for real create/delete.
- Build and dry-run do not validate credentials.
- Delete is blocked until `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` or its `COLORS_PAR_*` alias is set.
- Never edit `.colors/`. It is shared with Green and Red; never run implementations concurrently against it.

## Initialize

Gather profile, applications, providers, selected providers' non-secret
settings. Deploy keys are not among them: ONCE generates one per repository
named under `github` on every create and publishes it to a GitHub environment
named after the profile, so ask only for `COLORS_PAR_GITHUB_TOKEN`. Never
gather secret values. When an application names a repository, also ask whether
the user wants continuous deployment — see below.

After confirmation:

1. Copy this skill's bundled `blue` to `./blue` and make it executable. The PEP 723 metadata must pin both `package-once-blue` and `blue` to immutable Git commits.
2. Write `colors.yml` from the reference with `workdir: .colors`.
3. Add `.colors/` and private environment files to `.gitignore` without replacing unrelated entries.
4. Run `./blue build` and `./blue create --dry-run`.
5. Report required variable names only and do not provision automatically.

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
./blue build
./blue create --dry-run
./blue create
./blue describe
./blue delete --dry-run
./blue delete
```

Use `-f|--file` for another desired-state file. Check credential presence only
before a real operation and let the launcher perform final validation.
