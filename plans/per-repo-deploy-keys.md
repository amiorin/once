# Per-repository deploy keys, and a ping instead of a command

Status: supersedes `per-app-deploy-keys.md`.

## The bug

Deploy keys are one per application. The same container often serves several
hosts — a redirect image answering for every domain that points at one target —
and those hosts are one repository, one pipeline, one push.

Two applications naming the same `github` value both publish into
`--repo X --env <profile>`, so `gh secret set SSH_PRIVATE_KEY` runs twice
against the same environment and the last one wins. The repository ends up
holding a single key whose ForceCommand names a single host, and can redeploy
one of its applications. Nothing reports this: the shape check on `github` is
`owner/repo` and there is no uniqueness rule.

It is already latent in the reference state. `colors.yml` carries two hosts on
one image with only the first naming the repository, so the second was never
reachable from CI at all.

## The unit is the repository

A deploy key exists to be stored somewhere and used by someone. It is stored in
a GitHub environment belonging to a repository, and it is used by a push to that
repository. The application is none of those things. Group by `github`:

- one keypair per distinct repository named in desired state
- its `authorized_keys` entry carries **every** host that repository serves
- publication happens once per repository, so nothing overwrites anything

Isolation across repositories is unchanged — a key still cannot touch an
application belonging to a different repository. What changes is that the
boundary now falls where ownership actually falls.

## Ping, not a command

The client sends nothing. The ForceCommand takes its hosts from its own
`authorized_keys` entry and updates them.

Today a workflow sends the literal string `sudo once update www.example.com`,
which `files/deploy` parses, counts, regex-checks, and then compares against the
host it already had from `$1`. Every one of those steps validates client input
against a value the box supplied in the first place. Deleting them removes the
duplication of every hostname into every consuming repository's workflow, and it
is what lets one key serve several hosts without the client enumerating them.

`SSH_ORIGINAL_COMMAND` is **ignored rather than rejected**, and the script says
so on stderr when one arrives. Rejecting would break every existing workflow at
the moment `create` installs the new script, before the repositories have been
updated; ignoring lets the two land in either order. There is no laxity in it —
`restrict` plus the forced command mean the client's string was never able to
select anything.

### The exit code

The script updates every host in its entry and exits non-zero if any of them
failed. That aggregate is only meaningful because the set is scoped to the
caller: every host in it belongs to the repository whose key just connected, so
a red pipeline is that pipeline's own failure. The same aggregation over a
box-wide key would be noise — one repository's broken image turning another
repository's build red, with no way to tell the two apart.

A host in the entry that ONCE does not serve counts as a failure for that host.
The other hosts are still attempted; the run reports which ones failed.

## Retention has to key on the comment

`files/authorized-keys` keeps the previous generation per entry so that a
publication failing after the box has been updated leaves the old key working.
It currently identifies an entry by the host it parses out of the ForceCommand.

With a host *list* in there, adding a host to a repository rewrites that string.
The previously installed line then matches nothing in the current generation and
is pruned as a departed host — losing the retention window at exactly the moment
desired state is changing.

Key on the trailing comment instead. `key-comment` carries no timestamp by
design, so both generations of a repository's key have byte-identical comments
and grouping is a string equality, with no parsing. That matters: with the
comment `once-deploy-<profile>-<owner>-<repo>`, a profile, owner, or repository
containing a dash makes splitting ambiguous, and comparing whole strings never
has to care.

The comment is read as everything following the algorithm and the key body,
rather than as the last whitespace-separated token. Generated comments contain
no spaces, so the two agree — reading the remainder just keeps that from being
load-bearing.

`managed-marker` is unchanged. Its no-trailing-space form still matches the
argument-less legacy entries and the new list-carrying ones, so pre-existing
lines are still recognised as ours and pruned rather than preserved forever as
foreign.

## Rendered bytes

```text
restrict,command="/usr/local/bin/deploy www.example.com www.example.net" ssh-ed25519 AAAA… once-deploy-production-bigconfig-ai-colors-redirect
restrict,command="/usr/local/bin/deploy app.example.com" ssh-ed25519 AAAA… once-deploy-production-bigconfig-ai-once-bigconfig
```

One line per repository. Hosts appear in desired-state order within a
repository, and repositories in order of first appearance, so the artifact stays
a pure function of the file the three colours read.

Build and dry-run keep rendering the fixed placeholder public key. Generation
stays a create-time side effect; a fresh key per build would make the artifact
nondeterministic and break `scripts/parity.sh`.

## `github` stays optional

Unchanged and verified end to end: with no application naming a repository there
are no groups, so no `ssh-keygen` runs, `deploy_keys` renders empty, no `gh`
command is issued, and `COLORS_PAR_GITHUB_TOKEN` is not required. Grouping makes
this structural — no repositories, no groups — rather than a filter that has to
be applied correctly at four separate call sites.

The `deploy` account and both scripts stay installed unconditionally. With an
empty `authorized_keys` the account cannot authenticate, so it is inert, and
gating it would put a conditional into bytes that `scripts/parity.sh` compares
across three implementations for no security gain.

## The contract number

Not bumped. Unlike 7 → 8 this changes no desired-state key and no signature the
launcher calls — `github` means what it meant, and the grouping is internal to
steps the library owns. A launcher pinned to an older commit resolves this code
and behaves correctly.

## Surface

- `validate`: `github-applications` becomes `deploy-groups`, returning
  `{github, hosts}` per distinct repository. The `owner/repo` shape check and
  the `github-token` rule are unchanged.
- `github`: `key-comment` takes a repository rather than a host and slugs the
  `/`; `generate-keys`, `public-keys`, `publish-commands`, and
  `revoke-commands` all iterate groups. The `gh` argv sequence is otherwise
  untouched, and revoke stops issuing duplicate calls for a repository named
  twice.
- `tools/deploy-keys-content`: joins a group's hosts into the ForceCommand.
- `files/deploy`, `files/authorized-keys`: as above, byte-identical in all three.
- Docs: root `colors.yml`, root `index.html`, three READMEs, three skills,
  `green/CLAUDE.md`.

## Stale claims to remove

`validate/github-applications` and `per-app-deploy-keys.md` both state that an
application without `github` "gets a key on the box and nothing else". It gets
nothing — the filter excludes it before any key is generated.

## Consuming workflows

A deploy job still declares `environment: <profile>` and still reads
`vars.SERVER_IP`, `vars.SERVER_USER`, `vars.SSH_KNOWN_HOSTS`, and
`secrets.SSH_PRIVATE_KEY`. The step loses its command and its hostname:

```diff
-ssh -o StrictHostKeyChecking=yes "$SERVER_USER@$SERVER_IP" "sudo once update $HOST"
+ssh -T -o StrictHostKeyChecking=yes "$SERVER_USER@$SERVER_IP" < /dev/null
```

Connect to `SERVER_IP`. `known-hosts-line` keys the pinned entry on the address,
so substituting a hostname makes `StrictHostKeyChecking=yes` find no match.

The artifact is still not pinned from the pipeline. The forced command accepts
no digest, so pinning remains a deliberate edit to `image:` in desired state;
the pipeline exists to trigger a re-pull of a mutable tag.
