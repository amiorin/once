# Blue ONCE configuration

The copied launcher's PEP 723 metadata must resolve both packages from immutable
40-character commits:

```toml
# dependencies = ["package-once-blue", "blue"]
# [tool.uv.sources]
# package-once-blue = { git = "https://github.com/bigconfig-ai/once.git", rev = "<once-commit>", subdirectory = "blue" }
# blue = { git = "https://github.com/amiorin/blue.git", rev = "<blue-commit>" }
```

Replace both placeholders and remove development-only local paths before the
launcher is copied.

`colors.yml` has the same YAML shape as Red. Quote version-like values such as
`3.10` so YAML does not parse them as numbers.

```yaml
profile: production
workdir: .colors
deploy-pubkey: ssh-ed25519 AAAA... ci-deploy
once:
  applications:
    - host: www.example.com
      image: ghcr.io/example/site:latest
      env:
        DATABASE_URL: app-database-url
provider-compute: digitalocean
provider-smtp: resend
provider-dns: cloudflare
provider-backend: r2
compute-prevent-destroy: true
```

Application `env` maps a container variable to a flat key. Supply its value as
`COLORS_PAR_APP_DATABASE_URL` or `COLORS_PAR_APP_DATABASE_URL`; never put it in
YAML.

Providers:

- compute: `digitalocean`, `hcloud`, `yandex`, `oci`, `no-infra`
- SMTP: `resend`, `no-infra`
- DNS: `cloudflare`, `no-infra`
- backend: `local`, `s3`, `r2`

Credential suffixes are `DO_TOKEN`, `HCLOUD_TOKEN`, `YANDEX_TOKEN`,
`RESEND_API_KEY`, `RESEND_PASSWORD`, `NO_INFRA_SMTP_PASSWORD`,
`CLOUDFLARE_API_TOKEN`, `R2_ACCESS_KEY_ID`, and `R2_SECRET_ACCESS_KEY`. Prefix
with `COLORS_PAR_`. OCI uses its profile, S3 uses the AWS
credential chain, and SSH uses `ssh-agent`.

Application hosts derive DNS zones and Resend domains. Only explicitly listed
hosts receive application A records.
