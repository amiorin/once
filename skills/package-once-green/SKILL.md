---
name: package-once-green
description: Creates and operates production single-server Basecamp ONCE deployments with Green, OpenTofu, and Ansible. Use when initializing an ONCE project, generating colors.yml, selecting cloud/SMTP/DNS/state providers, building or dry-running configuration, provisioning, deleting, or describing an ONCE server.
license: MIT
---

# ONCE with Green

Use this skill to initialize or operate an ONCE deployment in the user's current directory.

## Requirements

Babashka runs the launcher. `create` and `delete` also require OpenTofu and Ansible. `describe` requires OpenTofu and OpenSSH locally, `docker` and passwordless `sudo -n` on the remote host, and `skopeo` locally for image-digest comparison. Provider credentials use `COLORS_PAR_*` variables, the one namespace every colour shares, except OCI, which uses the profile named in `~/.oci/config`, and S3, which uses OpenTofu's ambient AWS credential chain.

## Non-negotiable safety rules

- Never ask the user to paste a secret into chat.
- Never put API tokens, passwords, private keys, access keys, or application secret values in `colors.yml`, `green`, shell history, logs, or generated examples. Launcher-managed provider credentials and application secrets use a `COLORS_PAR_*` environment variable named after the key it fills. S3 uses OpenTofu's ambient AWS credential chain, OCI uses the configured profile in `~/.oci/config`, and SSH private keys remain in `ssh-agent`; never copy those credentials into project files.
- Ask only for the **names** of application-secret environment variables and whether required variables are set. Suggest the user keep those exports in a gitignored file such as `.envrc.private`, never inline in a command their shell history records.
- Public SSH keys are not secrets. Read only a user-approved `.pub` file; never read a private SSH key. Yandex needs that public content in `:compute-pubkey`. OCI is the exception: `:oci-ssh-authorized-keys` holds the *path* to a public-key file that OpenTofu reads at plan time, so record the path and never inline that file's contents.
- Do not overwrite an existing `green` or `colors.yml` without explicit approval. If an existing project is valid, operate it instead of regenerating it.
- If the launcher reports a contract mismatch, its pinned commit is older than the launcher itself. Re-copy `green` from an updated skill; there is no command in the project that fixes it.
- Default to `build` and `create --dry-run`. Run a real `create` or `delete` only after the user explicitly confirms that exact operation.
- `build` and `create --dry-run` are credential-free by design and check no `COLORS_PAR_*` at all. A clean dry-run says nothing about whether real provisioning would authenticate; never report it as credential validation.
- Before delete, remind the user that `:compute-prevent-destroy` defaults to `true`. Authorize an intentional delete with `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` (or its `COLORS_PAR_*` alias) rather than editing desired state.
- Never edit `.colors/`. Green, Red, and Blue may use it interchangeably, but two implementations must never run concurrently against the same state.

Read [references/configuration.md](references/configuration.md) before generating or changing desired state, and before any real `create` or `delete`.

## Initialize in the current directory

Determine this skill's directory from the loaded `SKILL.md` path. Do not assume the skill directory is the current working directory.

Gather these non-secret inputs conversationally:

- profile name and working directory (default `.colors`)
- one or more applications: hostname, container image, and optional mapping of container variable names to the `colors.yml` keys holding their values. Hostnames may span domains; Green manages every derived DNS zone and Resend sending domain, and only the listed hostnames get application DNS records
- compute, SMTP, DNS, and backend providers
- the selected providers' non-secret settings
- `:github` on any application whose deploy credentials ONCE should publish, as `owner/repo`. Deploy keys themselves are never collected: ONCE generates one per repository on every create and publishes it to a GitHub environment named after the profile, so ask only for `COLORS_PAR_GITHUB_TOKEN`. Also collect `:compute-pubkey` when Yandex is selected; Yandex installs it for the `ubuntu` user through instance metadata. Other providers instead reference a key already registered with them (`:digitalocean-ssh-keys`, `:hcloud-ssh-keys`) or a local public-key file path (`:oci-ssh-authorized-keys`)

Do not request secret values. Tell the user which `COLORS_PAR_*` names and native credential mechanisms are required for their selected providers.

After confirming the inputs:

- Copy the bundled `green` file from this skill directory to `./green` and make it executable.
- Write `./colors.yml` following the reference: keep provider and setting keys in the root map, and nest applications exactly under `once.applications`. Omit all secret keys and values.
- Ensure the configured work directory is ignored by Git, and that any file holding `COLORS_PAR_*` exports is too. Append precise ignore entries without replacing unrelated `.gitignore` content.
- Verify that `colors.yml` contains no credential, password, access-key, or private-key fields. Do not read environment-variable values; verify presence only. Confirm that `green` is an exact copy of the bundled launcher.
- Run `./green build`.
- Run `./green create --dry-run`.
- Report generated paths and required environment-variable names, but never their values. State plainly that neither check validated credentials, and list which `COLORS_PAR_*` names a real `create` will require.

If verification fails, correct only `colors.yml`, the ignore entries, or the copied `green` launcher as appropriate, then rerun the safe checks. Never edit the configured work directory; it is generated output. Do not proceed to real provisioning automatically.

## Operate an existing project

Read `colors.yml` first and identify its providers and work directory. Use:

```sh
./green build
./green create --dry-run
./green create
./green describe
./green delete --dry-run
./green delete
```

Every command reads `./colors.yml` unless `-f|--file` names another desired-state file, which is how one project holds several stacks (`./green build -f production.edn`).

`build` renders OpenTofu and Ansible configuration without invoking them. Dry-run touches nothing. `describe` reads the OpenTofu outputs already in the work directory, probes SSH, lists remote ONCE applications through `once list` and `docker inspect` under passwordless `sudo`, and uses `skopeo` when available to compare image digests. Compute is reported as `running`, `unreachable` (state holds an address but SSH failed), or `absent` (the `tofu-compute` stage has no outputs, so it was never created); `no-infra` hosts are never `absent`, since OpenTofu does not create them. Describe exits non-zero when compute is not `running` and when the remote `once` command is missing; every other live check is a soft failure named in its output, so read the report rather than the exit status alone.

Before real create/delete, check required `COLORS_PAR_*` variables by presence only. Do not print them. For OCI, S3, and SSH, confirm that the selected native credential mechanism is configured without reading secret material. Let the launcher perform its own final desired-state and environment validation.

## Generated application environment

Represent application environment as a map from container variable name to the flat `colors.yml` key that holds its value:

```yaml
env:
  DATABASE_URL: app-database-url
  SECRET_KEY_BASE: app-secret-key-base
```

Never emit `KEY=secret` values, and never add the referenced keys to `colors.yml`. The user exports `COLORS_PAR_APP_DATABASE_URL` and `COLORS_PAR_APP_SECRET_KEY_BASE`; the rendered Ansible file carries a lookup of those variables rather than their values, so secrets are resolved when the play runs and never land in desired state or in generated output. Tell the user which `COLORS_PAR_*` names their configuration requires.
