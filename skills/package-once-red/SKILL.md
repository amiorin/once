---
name: package-once-red
description: Creates and operates production single-server Basecamp ONCE deployments with Red, Bun, OpenTofu, and Ansible. Use for colors.yml setup, safe builds and dry-runs, provisioning, deletion, or status reports.
license: MIT
---

# ONCE with Red

Use this skill in the user's current directory. Read
[references/configuration.md](references/configuration.md) before creating or
changing desired state and before a real create or delete.

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
Do not gather secret values.

After confirmation:

1. Copy this skill's bundled `red` to `./red` and make it executable.
2. Write a `package.json` whose `dependencies` are the two lines quoted in the
   bundled `red`'s header comment, copied verbatim. Those are the commits this
   skill was built from — do not resolve them yourself, and never relax one to a
   branch or tag. Merge them into an existing manifest rather than replacing it.
3. Write `colors.yml` following the reference, with `workdir: .colors`.
4. Add `.colors/` and any private environment file to `.gitignore` without replacing unrelated entries.
5. Run `bun install`, `./red build`, and `./red create --dry-run`.
6. Report required variable names only. Do not run a real create automatically.

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
