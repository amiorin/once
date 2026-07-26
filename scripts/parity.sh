#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

build_variant() {
  local variant=$1
  shift
  (
    cd "$root/green"
    env ONCE_PAR_WORKDIR="$tmp/$variant/green" "$@" bb green build -f ../test/parity/green.edn >/dev/null
  )
  (
    cd "$root/red"
    env ONCE_PAR_WORKDIR="$tmp/$variant/red" "$@" ./red build -f ../test/parity/red.yml >/dev/null
  )
  (
    cd "$root/blue"
    env ONCE_PAR_WORKDIR="$tmp/$variant/blue" "$@" uv run python -m package_once_blue build -f ../test/parity/blue.yml >/dev/null
  )
  diff -qr "$tmp/$variant/green/parity" "$tmp/$variant/red/parity"
  diff -qr "$tmp/$variant/green/parity" "$tmp/$variant/blue/parity"
}

build_variant local
build_variant digitalocean-vpc ONCE_PAR_DIGITALOCEAN_VPC_UUID=vpc-123
build_variant hcloud ONCE_PAR_PROVIDER_COMPUTE=hcloud
build_variant yandex ONCE_PAR_PROVIDER_COMPUTE=yandex
build_variant oci ONCE_PAR_PROVIDER_COMPUTE=oci
build_variant no-infra-compute ONCE_PAR_PROVIDER_COMPUTE=no-infra
build_variant no-infra-smtp ONCE_PAR_PROVIDER_SMTP=no-infra
build_variant no-infra-dns ONCE_PAR_PROVIDER_DNS=no-infra
build_variant s3 ONCE_PAR_PROVIDER_BACKEND=s3
build_variant r2 ONCE_PAR_PROVIDER_BACKEND=r2

diff -qr "$root/green/src/resources/io/github/bigconfig-ai/once" "$root/red/resources"
diff -qr "$root/green/src/resources/io/github/bigconfig-ai/once" "$root/blue/src/package_once_blue/resources"
echo "green, red, and blue build artifacts are byte-identical"
