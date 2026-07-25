# CLAUDE.md

This file describes the `once` codebase for AI assistants. Read it before making changes.

## Project Overview

`once` provisions and operates a single-server [ONCE](https://github.com/basecamp/once) installation with [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/). It targets "vibe coders" who want one-click deployment.

It is built on [`green`](https://github.com/amiorin/green), a DAG workflow engine: a graph of steps threaded by an `opts` map, with advice (before/after/around) attached per step. This branch is a rewrite — the BigConfig SDK, `bb run package …`, `options.clj` profiles, and `BC_PAR_*` variables are gone. Do not reintroduce those concepts.

The repository ships two things from one file:

- **The launcher** `green-once/green`, a single Babashka script. `./green` in the repository root is a symlink to it.
- **The `green-once` skill** (`green-once/SKILL.md` + `references/configuration.md`), whose payload is that same launcher, copied into a user's own project. Standing alone it resolves `once` and `green` as pinned git dependencies; inside this repository `bb.edn` supplies local roots and the bootstrap is skipped.

## Tech Stack

- **Language**: Clojure 1.12.5 (JVM), plus Babashka for the launcher and for the two scripts that run on the remote host
- **Workflow engine**: `io.github.amiorin/green` (`green.workflow`, `green.scaffold`, `green.tofu`, `green.ansible`, `green.cli`, `green.progress`, `green.dry-run`)
- **Infrastructure**: OpenTofu; **Config management**: Ansible
- **Key libraries**: `cheshire` (JSON), `selmer` (templates, via `green.scaffold`)
- **Dev environment**: Nix via `devenv` + `direnv`

## Repository Structure

```
green/
├── green-once/
│   ├── green                # THE launcher: bootstrap, validation, workflow, CLI, pin
│   ├── SKILL.md             # green-once skill definition
│   └── references/
│       └── configuration.md # desired-state reference the skill reads before generating green.edn
├── green                    # symlink -> green-once/green
├── green.edn                # desired state for this repository's own stack
├── src/
│   ├── clj/io/github/bigconfig_ai/once/
│   │   ├── tools.clj        # the six step functions, template specs, generated JSON
│   │   ├── describe.clj     # post-provisioning report (providers, SSH, deployed apps)
│   │   └── utils.clj        # contract number, GREEN_PAR_* overlay, zone derivation, process + YAML helpers
│   └── resources/io/github/bigconfig-ai/once/
│       ├── raw              # `<{ content|safe }>` — the template used for generated content
│       └── tools/
│           ├── tofu/{digitalocean,hcloud,oci,no-infra}/main.tf
│           ├── tofu-smtp/{resend,no-infra}/main.tf
│           ├── tofu-dns/{cloudflare,no-infra}/main.tf
│           ├── tofu-smtp-post/{resend,no-infra}/main.tf
│           ├── ansible/            # remote host: playbook, ansible.cfg, files/deploy, library/once
│           └── ansible-local/      # local machine: playbook, ansible.cfg, inventory.ini
├── test/clj/io/github/bigconfig_ai/once/
│   ├── tools_test.clj       # rendering, generated DNS records, ansible-local lifecycle
│   ├── describe_test.clj    # report parsing and assembly
│   ├── deploy_test.clj      # the deploy ForceCommand script
│   ├── once_module_test.clj # the `once` Ansible module
│   └── utils_test.clj       # zone derivation, ANSI stripping
├── index.html               # the user-facing manual for the green-once skill
├── deps.edn / bb.edn        # git-pinned green; bb.edn overrides with local roots
├── plans/                   # historical task briefs, several predating the rewrite — not authoritative
└── devenv.nix / .envrc      # Nix dev shell; .envrc sources .envrc.private (gitignored)
```

## Development Commands

```bash
bb green build                 # render <workdir>/<profile>/ only, no tofu, no ansible
bb green create                # provision and configure
bb green create --dry-run      # print the DAG actions, touch nothing
bb green delete                # destroy, in reverse
bb green describe              # providers, SSH status, deployed apps, image updates
bb green pin                   # stamp the launcher with this repository's HEAD

bb green build -f production.edn   # -f/--file selects a desired-state file (default: ./green.edn)

clojure -M:test                # cognitect test-runner over test/clj
clojure-lsp clean-ns && clojure-lsp format
clj-kondo --lint src/clj test/clj green-once/green
```

`.github/workflows/cicd.yml` runs `clojure -X:test` on pushes to `main` and tags `1.0.<commit-count>`.

## Desired state (`green.edn`)

A single flat EDN map, except for the nested `:once {:applications [...]}` collection. Provider selection and non-secret settings live here; credentials never do.

```clojure
{:profile "production"          ; names the workdir, the state keys, the compute
 :workdir ".green"              ; resource, and the ~/.ssh/config Host alias
 :deploy-pubkey "ssh-ed25519 AAAA... ci-deploy"
 :once {:applications [{:host "www.example.com"
                        :image "ghcr.io/example/site:latest"
                        :env {"DATABASE_URL" :app-database-url}}]}
 :provider-compute "digitalocean"  ; digitalocean | hcloud | oci | no-infra
 :provider-smtp "resend"           ; resend | no-infra
 :provider-dns "cloudflare"        ; cloudflare | no-infra
 :provider-backend "r2"            ; local | s3 | r2
 :compute-prevent-destroy true}
```

Load-bearing rules:

- **No domain key.** The application hosts are the source of truth. `utils/apps-domain` takes the last two labels they share; that zone is the Cloudflare zone, the parent of the Resend sending domain `notifications.<zone>`, and the `info@notifications.<zone>` From address. The launcher rejects applications spread over more than one domain. Templates read it as `<{ zone }>`, injected by `tools/with-zone` — nothing in desired state supplies it.
- **No apex or wildcard DNS record.** Each application host gets its own proxied `A` record, so an unlisted host does not resolve.
- **Resend's relay is hard-coded** (`smtp.resend.com`, 587, user `resend`) in `tools/resend-smtp`, because it is identical for every account. Only `GREEN_PAR_RESEND_API_KEY` and `GREEN_PAR_RESEND_PASSWORD` are configurable. The `no-infra` SMTP keys stay in desired state.
- **`GREEN_PAR_*` is the only secret channel.** `utils/read-green-pars` overlays any such variable onto the matching flat key — uppercased, hyphens as underscores, so `:do-token` ← `GREEN_PAR_DO_TOKEN`. Overrides are coerced to the type of the value they replace, so `GREEN_PAR_COMPUTE_PREVENT_DESTROY=false` stays a boolean. Any flat key can be overridden the same way. There is no `TF_VAR_*` and no second mechanism.
- Application `:env` maps a container variable **name** to the flat key holding its value, never to the value itself.

## Architecture

### The DAG

`wire-fn` in the launcher returns `[step-fn & next-steps]` per step and switches on `:green/event`. Create and build:

```text
start ─┬─ tofu-compute ─┐                          ┌─ ansible-local
       └─ tofu-smtp ────┴─ tofu-dns ─ smtp-post ───┴─ ansible-remote
```

Delete:

```text
start ─ ansible-cleanup ─ tofu-smtp-post ─ tofu-dns ─┬─ tofu-smtp
                                                     └─ tofu-compute
```

Compute and SMTP run concurrently; `tofu-dns` is a join, and the engine hands it the fork-point opts plus `:green/branches` (a vector of branch results) — `tools/joined-params` reads the branch results out of it. The two Ansible stages then run concurrently.

`workflow` also attaches: backend advice `:before` each Tofu step, `progress/advise` (the `>>> / <<<` lines), and `dry-run/advise` over `side-effecting-steps` (so `--dry-run` skips them).

### The opts map

One map is threaded through every step. Reserved keys are namespaced; desired-state keys are plain kebab-case keywords.

| Key | Meaning |
|---|---|
| `:green/exit` | 0 success, >0 failure — how steps report, instead of throwing |
| `:green/err`, `:green/trace` | failure message and stack trace |
| `:green/event` | `:build`, `:create`, or `:delete`, stamped by `green.cli` |
| `:green/dry-run` | set by `--dry-run` |
| `:green/branches` | branch results at a join |
| `:once/compute-params`, `:once/smtp-params` | outputs adopted from earlier stages |
| `:zone` | derived DNS zone (see above) |
| `:green.scaffold/written`, `:green.scaffold/deleted` | paths a scaffold touched |

### Stages

Each stage owns an isolated directory, `tools/tool-dir` = `<workdir>/<profile>/<tool>`:

| Step | Work dir | Templates | Does |
|---|---|---|---|
| `:once/tofu-compute` | `tofu-compute` | `tools/tofu/<provider>/` | provisions the VM (or passes through `no-infra`), outputs ip/user/sudoer/name |
| `:once/tofu-smtp` | `tofu-smtp` | `tools/tofu-smtp/<provider>/` | registers `notifications.<zone>` at Resend, outputs its id and DNS records |
| `:once/tofu-dns` | `tofu-dns` | `tools/tofu-dns/<provider>/` | zone settings, plus generated `apps.tf.json` and `smtp.tf.json` |
| `:once/tofu-smtp-post` | `tofu-smtp-post` | `tools/tofu-smtp-post/<provider>/` | verifies the Resend domain once DNS resolves |
| `:once/ansible-local` | `ansible-local` | `tools/ansible-local/` | writes the managed `Host <profile>` block into `~/.ssh/config` |
| `:once/ansible-remote` | `ansible-remote` | `tools/ansible/` | installs docker, ONCE, bb; creates the restricted `deploy` user; reconciles applications |

Note the asymmetry: the compute step's work directory is `tofu-compute` but its templates live under `tools/tofu/`.

### Rendering

`green.scaffold` maps a qualified keyword to a classpath resource (`:io.github.bigconfig-ai.once.tools.tofu.oci/main.tf` → `io/github/bigconfig-ai/once/tools/tofu/oci/main.tf`) and renders it with Selmer. `tools/template-opts` overrides the delimiters, so templates use `<{ var }>` for values and `<% if … %>` for tags, leaving `{{ … }}` and `{% … %}` for Jinja2 in the Ansible files. Providers are selected by directory, not by conditionals in one file.

Content that is computed rather than templated is written through `raw-spec`, which renders the one-line `raw` template: `apps.tf.json`, `smtp.tf.json`, `inventory.json`, and `once.yml`. `tools/render-fn` builds the two DNS files, using `tofu-construct` / `deep-merge` / `sort-nested-map` so the JSON is deterministic. `backend.tf.json` is the exception — `green.tofu` writes it directly from the backend advice, outside the scaffold.

A `build` of the reference `green.edn` produces exactly:

```text
<workdir>/<profile>/
├── tofu-compute/     backend.tf.json  main.tf
├── tofu-smtp/        backend.tf.json  main.tf
├── tofu-dns/         backend.tf.json  main.tf  apps.tf.json  smtp.tf.json
├── tofu-smtp-post/   backend.tf.json  main.tf
├── ansible-local/    ansible.cfg  inventory.ini  main.yml
└── ansible-remote/   ansible.cfg  main.yml  inventory.json  once.yml
                      files/deploy  library/once
```

The two generated DNS files are Cloudflare-only; `no-infra` DNS renders `main.tf` alone.

### Parameter flow

1. `green.cli` reads the desired-state file and stamps `:green/event`.
2. `start-step` overlays `GREEN_PAR_*`, then validates (`state-errors`, and `secret-errors` for real create/delete).
3. Tofu stages parse their `params` output into `:once/compute-params` / `:once/smtp-params`; `joined-params` merges them into opts at the DNS join. Fallback maps (`fallback-compute-params`, `fallback-smtp-params`) stand in for `build` and dry-run so rendering never needs state.
4. Delete cannot re-derive those values, so `adopt-existing-state` reads the already-applied outputs back out of Tofu state before teardown.

### Secrets

Three separate channels, and nothing lands in a rendered file:

- **OpenTofu**: `tools/credential-env` maps flat keys to the variables each provider reads natively (`:do-token` → `DIGITALOCEAN_TOKEN`, `:cloudflare-api-token` → `CLOUDFLARE_API_TOKEN`, …) and passes them through the process environment. Unset credentials are omitted, so build and dry-run stay credential-free.
- **State backends**: R2 authenticates through `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`; naming them in `backend.tf.json` would also write them to `.terraform/terraform.tfstate`.
- **Ansible**: `tools/par-lookup` emits `{{ lookup('env','GREEN_PAR_…') }}`, so the SMTP password and application `:env` values are resolved when the play runs, not when the file is rendered. `tools_test` asserts the secrets never appear in the YAML — keep those tests passing.

### Backends

`backend-advice` writes `backend.tf.json` before each Tofu step: local, S3, or R2 as an S3-compatible backend with `region = "auto"`. Remote state keys are `<profile>/<tool>.tfstate`.

### Delete semantics

Deleting has to render before it can destroy: `tofu-with-spec` and `ansible-local-step` scaffold with `:green/event :create`, run the tool, and only then scaffold with `:delete` to remove the rendered tree. `ansible-cleanup-step` replays `ansible-local` so the managed `~/.ssh/config` block is dropped. `:compute-prevent-destroy` defaults to `true` and renders `lifecycle { prevent_destroy = true }`; a real delete refuses to start until `GREEN_PAR_COMPUTE_PREVENT_DESTROY=false`.

### The remote host

`tools/ansible/main.yml` installs Docker, ONCE, and Babashka, then creates a `deploy` user with NOPASSWD sudo limited to `/usr/local/bin/once *`, whose authorized key is pinned to a `ForceCommand` (`tools/ansible/files/deploy`). That script rejects anything but `once update <host>` for a host ONCE already serves. Applications are reconciled by `tools/ansible/library/once`, a Babashka Ansible module that diffs the desired list against `once list` and deploys or removes the difference, redacting secrets from anything it reports.

## The contract number and `green pin`

`utils/contract` and `launcher-contract` in the launcher are a compatibility handshake. A standalone launcher refuses to run when the `once` it resolved reports a lower contract, and names `green pin` instead of silently rendering from an older commit.

**Bump `utils/contract` (and `launcher-contract` to match) on any change a launcher pinned to an older commit could not survive** — a changed template variable, a renamed desired-state key, a new function the launcher calls. Then, after committing and pushing: `bb green pin` stamps `once-sha` (and `green-sha`, when `GREEN_LIB_ROOT` points at a green checkout) and the result is committed as `fix: re-pin bundled launcher to once <sha>`. `pin` refuses to run on a dirty tree or an unpushed HEAD, and the pins are marked *managed — do not edit by hand*.

## Code Conventions

- **Namespaces**: `io.github.bigconfig-ai.once.*`. Three of them, mapping to distinct concerns — adding a fourth needs a genuinely new concern.
- **Keys**: plain kebab-case keywords for desired state (they match template variable names); namespaced keywords for engine state (`:green/…`, `:once/…`).
- **Steps** take `opts` and return `opts`, and report failure through `:green/exit` / `:green/err`.
- **`^:private`** for everything not called from the launcher or the tests. The launcher's own helpers are `defn-`; the workflow steps it exposes are not.
- **Pure builders stay pure**: `tools/render-fn`, `tools/inventory`, `tools/ansible-once`, `utils/apps-domain` take data and return data. `describe/describe-report` keeps its single-argument arity (which shells out) separate from the arities that take an injected runner, so report construction stays process-free — preserve that split.
- **Tests avoid processes** by redefining `green.ansible/ansible-step` and `green.tofu/tofu-step`, or by driving the pure builders directly.

## Git Conventions

Stay on the `green` branch — each language has its own branch in this repository, and this one is the green rewrite. Commit only when explicitly asked. [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `deps:`, with `!` and a `BREAKING CHANGE:` footer when desired state or the contract changes.

## What to Avoid

- Do not reintroduce BigConfig SDK concepts: `bb run package …`, `::workflow/params`, `BC_PAR_*`, `options.clj` profile maps.
- Do not add error handling for cases that cannot happen — failure travels through `:green/exit` and `:green/err`, and `green.workflow` converts thrown exceptions itself.
- Do not edit `.green/` (or any configured `:workdir`) — it is generated output.
- Do not put credentials, tokens, or private keys in source, in `green.edn`, or in a rendered file. `.envrc.private` is the local channel.
- Do not give the launcher a dependency outside `green`, `once`, and Babashka's built-ins: it has to work as a lone file copied into a stranger's project.
- Do not hand-edit `once-sha` / `green-sha`; run `bb green pin`.
- When desired state changes, update all four surfaces that document it: `green.edn`, `green-once/references/configuration.md`, `green-once/SKILL.md`, and `index.html`.
