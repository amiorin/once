---
name: package-once-blue
description: Creates and operates production single-server Basecamp ONCE deployments with Blue, uv, OpenTofu, and Ansible. Use for colors.yml setup, safe builds and dry-runs, provisioning, deletion, or status reports.
license: MIT
---

# ONCE with Blue

Use this skill in the user's current directory. Read
[references/configuration.md](references/configuration.md) before creating or
changing desired state and before a real create or delete.

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
settings, and the deploy public key. Never gather secret values.

After confirmation:

1. Copy this skill's bundled `blue` to `./blue` and make it executable. The PEP 723 metadata must pin both `package-once-blue` and `blue` to immutable Git commits.
2. Write `colors.yml` from the reference with `workdir: .colors`.
3. Add `.colors/` and private environment files to `.gitignore` without replacing unrelated entries.
4. Run `./blue build` and `./blue create --dry-run`.
5. Report required variable names only and do not provision automatically.

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
