# Red ONCE configuration

The project manifest pins both repositories to full 40-character commits:

```json
{
  "type": "module",
  "dependencies": {
    "package-once-red": "github:bigconfig-ai/once#<once-commit>",
    "red": "github:amiorin/red#<red-commit>"
  }
}
```

Resolve and substitute both placeholders before running `bun install`; never
leave a branch name or unpinned dependency.

`red.yml` is a YAML map. Provider settings are flat; applications are the only
nested collection. Quote version-like YAML values such as `3.10`.

```yaml
profile: production
workdir: .once
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

Application `env` maps the container variable name to a flat desired-state key.
Do not add that key's value to YAML. Supply it as `RED_PAR_APP_DATABASE_URL` or
`ONCE_PAR_APP_DATABASE_URL`.

Provider choices and required fields match the unified repository manual:

- compute: `digitalocean`, `hcloud`, `yandex`, `oci`, `no-infra`
- SMTP: `resend`, `no-infra`
- DNS: `cloudflare`, `no-infra`
- backend: `local`, `s3`, `r2`

Credentials use the selected color prefix or portable `ONCE_PAR_*` alias:
`DO_TOKEN`, `HCLOUD_TOKEN`, `YANDEX_TOKEN`, `RESEND_API_KEY`,
`RESEND_PASSWORD`, `NO_INFRA_SMTP_PASSWORD`, `CLOUDFLARE_API_TOKEN`,
`R2_ACCESS_KEY_ID`, and `R2_SECRET_ACCESS_KEY`. OCI uses its configured profile;
S3 uses OpenTofu's ambient AWS chain; SSH uses `ssh-agent`.

Application hosts derive all DNS zones. Only listed hosts receive A records.
Every zone receives its own `notifications.<zone>` Resend domain.
