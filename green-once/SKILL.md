---
name: green-once
description: Creates and operates production single-server Basecamp ONCE deployments with Green, OpenTofu, and Ansible. Use when initializing an ONCE project, generating green.edn, selecting cloud/SMTP/DNS/state providers, building or dry-running configuration, provisioning, deleting, or describing an ONCE server.
license: MIT
compatibility: Requires Babashka; create/delete require OpenTofu and Ansible. Describe requires OpenSSH. Cloud operations require the selected providers' credentials in environment variables.
---

# ONCE with Green

Use this skill to initialize or operate an ONCE deployment in the user's current directory.

## Non-negotiable safety rules

- Never ask the user to paste a secret into chat.
- Never put API tokens, passwords, private keys, access keys, or application secret values in `green.edn`, `green`, shell history, logs, or generated examples. Every secret is supplied by a `GREEN_PAR_*` environment variable named after the key it fills.
- Ask only for the **names** of application-secret environment variables and whether required variables are set.
- Public SSH keys are not secrets. Read only a user-approved `.pub` file; never read a private SSH key.
- Do not overwrite an existing `green` or `green.edn` without explicit approval. If an existing project is valid, operate it instead of regenerating it.
- Default to `build` and `create --dry-run`. Run a real `create` or `delete` only after the user explicitly confirms that exact operation.
- Before delete, remind the user that `:compute-prevent-destroy` defaults to `true` and must be overridden deliberately.

Read [references/configuration.md](references/configuration.md) before generating or changing desired state.

## Initialize in the current directory

Determine this skill's directory from the loaded `SKILL.md` path. Do not assume the skill directory is the current working directory.

Gather these non-secret inputs conversationally:

- profile name and working directory (default `.green`)
- one or more applications: hostname, container image, and optional mapping of container variable names to the `green.edn` keys holding their values. The hostnames must share one domain; that shared domain is the DNS zone and the mail domain, and only the listed hostnames get DNS records
- compute, SMTP, DNS, and backend providers
- the selected providers' non-secret settings
- compute and deploy SSH public keys where applicable

Do not request secret values. Tell the user which environment-variable names are required for their selected providers.

After confirming the inputs:

- Copy the bundled `green` file from this skill directory to `./green` and make it executable.
- Write `./green.edn` as a single flat EDN map following the reference. Omit all secret keys and values.
- Ensure the configured work directory is ignored by Git. Append one precise ignore entry without replacing unrelated `.gitignore` content.
- Verify that `green.edn` contains no credential, password, access-key, or private-key fields. Do not read environment-variable values; verify presence only. Confirm that `green` is an exact copy of the bundled launcher.
- Run `./green build -f ./green.edn`.
- Run `./green create -f ./green.edn --dry-run`.
- Report generated paths and required environment-variable names, but never their values.

If verification fails, fix only generated project files and rerun the safe checks. Do not proceed to real provisioning automatically.

## Operate an existing project

Read `green.edn` first and identify its providers and work directory. Use:

```sh
./green build
./green create --dry-run
./green create
./green describe
./green delete --dry-run
./green delete
```

`build` renders OpenTofu and Ansible configuration without invoking them. Dry-run touches nothing. `describe` reads existing OpenTofu outputs, probes SSH, and lists remote ONCE applications.

Before real create/delete, check required environment variables by presence only. Do not print them. Let the launcher perform its own final desired-state and environment validation.

## Generated application environment

Represent application environment as a map from container variable name to the flat `green.edn` key that holds its value:

```clojure
:env {"DATABASE_URL" :app-database-url
      "SECRET_KEY_BASE" :app-secret-key-base}
```

Never emit `"KEY=secret"` values in EDN, and never add the referenced keys to `green.edn`. The user exports `GREEN_PAR_APP_DATABASE_URL` and `GREEN_PAR_APP_SECRET_KEY_BASE`; the launcher resolves them when it renders, so secret values are not written into desired state. Tell the user which `GREEN_PAR_*` names their configuration requires.
