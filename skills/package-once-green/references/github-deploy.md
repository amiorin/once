# Continuous deployment from GitHub Actions

`create` publishes one repository's deploy credentials into a GitHub Actions
environment named after the `profile`. This file is the workflow that consumes
them.

It belongs in the **application's** repository — the one named by
`github: owner/repo` — which is often not the repository holding `colors.yml`.
Write it there, not next to desired state.

## What ONCE publishes

| Name | Kind | Value |
|---|---|---|
| `SSH_PRIVATE_KEY` | secret | the deploy key's private half, regenerated on every `create` and never stored |
| `SERVER_IP` | variable | the server address |
| `SERVER_USER` | variable | always `deploy` |
| `SSH_KNOWN_HOSTS` | variable | the server's own host key, read over an authenticated session |

All four are scoped to the environment named after the profile. A job that does
not declare `environment:` cannot see them at all — every value resolves to
empty rather than erroring, so a workflow missing that block fails deep inside
`ssh` with a useless message. Nothing is published at repository scope.

The address and user are variables rather than secrets deliberately: DNS
reveals the address anyway, and masking them only makes CI logs harder to read.

## What a deploy is

A ping. The client sends no command at all: the key's `authorized_keys` entry
carries a forced command that already names every host that repository serves,
and updates all of them. So no hostname appears in the workflow, and adding a
host to `colors.yml` needs no change to this file. The exit status is the deploy
result for every host that key owns.

The server pulls the tag named in the application's `image:`. With
`ghcr.io/example/site:latest`, the ping is only meaningful once `:latest` points
at the new build — which is why the build and manifest jobs come first.

## The workflow

```yaml
name: Build and Publish Docker Image

on:
  push:
    branches:
      - main
  workflow_dispatch:

permissions:
  contents: read
  packages: write

jobs:
  # Each architecture pushes by digest under no tag at all. Mutable :arm and
  # :amd tags would be shared across runs, so two pushes landing close together
  # could interleave and leave :latest with its arm64 half from one commit and
  # its amd64 half from another — undetected, and :latest is what the server
  # pulls.
  build:
    strategy:
      fail-fast: false
      matrix:
        include:
          - arch: arm
            platform: linux/arm64
            runner: ubuntu-24.04-arm
          - arch: amd
            platform: linux/amd64
            runner: ubuntu-24.04
    runs-on: ${{ matrix.runner }}
    steps:
      - name: Checkout repository
        uses: actions/checkout@v5

      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Build and push by digest
        id: build
        uses: docker/build-push-action@v6
        with:
          context: .
          platforms: ${{ matrix.platform }}
          outputs: type=image,name=ghcr.io/${{ github.repository }},push-by-digest=true,name-canonical=true,push=true
          provenance: false
          cache-from: type=gha,scope=${{ matrix.arch }}
          cache-to: type=gha,mode=max,scope=${{ matrix.arch }}

      - name: Export digest
        run: |
          mkdir -p /tmp/digests
          touch "/tmp/digests/${DIGEST#sha256:}"
        env:
          DIGEST: ${{ steps.build.outputs.digest }}

      - name: Upload digest
        uses: actions/upload-artifact@v4
        with:
          name: digests-${{ matrix.arch }}
          path: /tmp/digests/*
          if-no-files-found: error
          retention-days: 1

  # One imagetools call builds both tags from the digests just pushed, so the
  # two halves of a manifest always come from the same run. It also reads each
  # child's platform itself, which is why there are no manual
  # `docker manifest annotate --os/--arch` pairs here.
  manifest:
    runs-on: ubuntu-24.04-arm
    needs:
      - build
    steps:
      - name: Download digests
        uses: actions/download-artifact@v4
        with:
          path: /tmp/digests
          pattern: digests-*
          merge-multiple: true

      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Compute short sha
        id: sha
        run: echo "short=${GITHUB_SHA::7}" >> "$GITHUB_OUTPUT"

      - name: Create and push the manifest list
        working-directory: /tmp/digests
        run: |
          docker buildx imagetools create \
            --tag ghcr.io/${{ github.repository }}:latest \
            --tag ghcr.io/${{ github.repository }}:sha-${{ steps.sha.outputs.short }} \
            $(printf 'ghcr.io/${{ github.repository }}@sha256:%s ' *)

      - name: Show what was published
        run: docker buildx imagetools inspect ghcr.io/${{ github.repository }}:latest

  deploy:
    runs-on: ubuntu-24.04-arm
    needs:
      - manifest
    # The environment ONCE publishes into, named after the colors.yml profile.
    # Without this the job cannot see the environment-scoped secret or
    # variables at all, and every value below resolves to empty.
    environment:
      name: PROFILE
      url: https://www.example.com
    # Serialize rather than cancel: interrupting a deploy midway is worse than
    # making the next one wait.
    concurrency:
      group: deploy-PROFILE
      cancel-in-progress: false
    steps:
      - name: Deploy via SSH
        env:
          # Only the key is a secret. The address and user are variables, so CI
          # logs stay readable — DNS reveals the address anyway.
          SSH_PRIVATE_KEY: ${{ secrets.SSH_PRIVATE_KEY }}
          SERVER_IP: ${{ vars.SERVER_IP }}
          SERVER_USER: ${{ vars.SERVER_USER }}
          SSH_KNOWN_HOSTS: ${{ vars.SSH_KNOWN_HOSTS }}
        run: |
          # Create the .ssh directory and start the agent
          mkdir -p ~/.ssh
          eval $(ssh-agent -s)

          # Add the private key to the agent
          echo "$SSH_PRIVATE_KEY" | tr -d '\r' | ssh-add -

          # Pinned by ONCE at provisioning time, not scanned here. Fail closed:
          # falling back to ssh-keyscan would trust whatever answers on that
          # address, which is the weakness pinning exists to remove.
          if [ -z "$SSH_KNOWN_HOSTS" ]; then
            echo "SSH_KNOWN_HOSTS is unset - run 'once create' to publish it" >&2
            exit 1
          fi
          printf '%s\n' "$SSH_KNOWN_HOSTS" >> ~/.ssh/known_hosts

          # A ping, not a command. This key's authorized_keys entry already
          # names every host this repository serves, and its forced command
          # updates all of them — so there is no hostname to duplicate here,
          # and adding a host to colors.yml needs no change to this file.
          #
          # Connect to SERVER_IP: ONCE keys the pinned SSH_KNOWN_HOSTS entry on
          # the address, so a hostname here would find no match under
          # StrictHostKeyChecking=yes.
          #
          # The exit status is the deploy result for every host this key owns.
          ssh -T -o StrictHostKeyChecking=yes "$SERVER_USER@$SERVER_IP" < /dev/null
```

## Adapting it

- Replace `PROFILE` in both places with the `profile` from `colors.yml`. Any
  other name resolves an environment that has no variables in it.
- `environment.url` is cosmetic — the application's host, so the deployment
  shows a link in the GitHub UI.
- The image the manifest publishes must be exactly the application's `image:`
  in `colors.yml`. `ghcr.io/${{ github.repository }}` expands to
  `ghcr.io/owner/repo`, so it matches only when desired state names that.
- `permissions: packages: write` is what lets `GITHUB_TOKEN` push to ghcr.io.
  Without it the run fails at the push rather than at login.
- Building for one architecture only: drop the unwanted matrix entry and the
  whole `manifest` job, and give `build-push-action` `tags:` and `push: true`
  instead of `outputs:`.
- The repository must already have a `Dockerfile` at `context: .`. This workflow
  builds the image the application runs; it does not create one.

## Order of operations

Run `create` before the first workflow run. ONCE creates the environment
itself, so nothing has to exist in GitHub beforehand — but until then the
environment is empty, and the deploy job stops on the `SSH_KNOWN_HOSTS` check
rather than trusting an unverified host.

`COLORS_PAR_GITHUB_TOKEN` must be set for `create` to publish, and for `delete`,
which withdraws all four values again. A token that cannot write environments
leaves the workflow with nothing to read.
