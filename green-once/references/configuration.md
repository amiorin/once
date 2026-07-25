# Configuration reference

The generated `green.edn` is a flat EDN map. Include only selected providers' non-secret settings. Never add credentials or passwords.

Every secret reaches the workflow through a `GREEN_PAR_*` environment variable, which is overlaid onto the matching flat key before anything runs. The variable name is the key uppercased with hyphens as underscores, so `:do-token` is supplied by `GREEN_PAR_DO_TOKEN`. There is no second mechanism: no `TF_VAR_*`, no provider-native variable names, no Ansible environment lookups. Overrides are coerced to the type of the value they replace, so booleans and integers stay booleans and integers.

## Base shape

```clojure
{:profile "production"
 :workdir ".green"
 :domain "example.com"
 :package "once-production"

 :deploy-pubkey "ssh-ed25519 AAAA... ci-deploy"

 :once {:applications
        [{:host "www.example.com"
          :image "ghcr.io/example/site:latest"
          :env {"DATABASE_URL" :app-database-url}}]}

 :provider-compute "digitalocean"
 :provider-smtp "resend"
 :provider-dns "cloudflare"
 :provider-backend "r2"
 :compute-prevent-destroy true

 ;; Add the selected providers' non-secret fields here.
 }
```

Application hostnames must equal `:domain` or be subdomains of it. `:env` maps a container variable name to the flat key holding its value; the value itself never appears in the file, and is supplied by the `GREEN_PAR_*` variable named after that key (`:app-database-url` ← `GREEN_PAR_APP_DATABASE_URL`). Application options supported by the ONCE reconciler also include `:auto_update`, `:auto_backup`, `:backup_path`, `:disable_tls`, `:cpus`, and `:memory`.

`:deploy-pubkey` is required. It authorizes only `sudo once update <configured-host>` through a remote ForceCommand. Private keys remain outside the project and should be loaded in `ssh-agent`.

`:compute-pubkey` is accepted but currently unused: each compute provider references a key already registered with it (`:digitalocean-ssh-keys`, `:hcloud-ssh-keys`) or a local public-key file (`:oci-ssh-authorized-keys`). If present it must still look like a public key.

## Compute providers

### DigitalOcean

```clojure
:provider-compute "digitalocean"
:digitalocean-name "once"
:digitalocean-region "ams3"
:digitalocean-size "s-1vcpu-1gb-35gb-intel"
:digitalocean-image "ubuntu-24-04-x64"
:digitalocean-ssh-keys "fingerprint-or-id-already-in-the-account"
;; Optional:
:digitalocean-vpc-uuid "non-secret-vpc-uuid"
```

Required credential: `GREEN_PAR_DO_TOKEN`.

### Hetzner Cloud

```clojure
:provider-compute "hcloud"
:hcloud-name "once"
:hcloud-image "ubuntu-24.04"
:hcloud-server-type "cx23"
:hcloud-location "hel1"
:hcloud-ssh-keys "key-name-or-id-already-in-the-project"
```

Required credential: `GREEN_PAR_HCLOUD_TOKEN`.

### Oracle Cloud Infrastructure

```clojure
:provider-compute "oci"
:oci-config-file-profile "DEFAULT"
:oci-subnet-id "ocid1.subnet..."
:oci-compartment-id "ocid1.compartment..."
:oci-availability-domain "..."
:oci-display-name "once"
:oci-shape "VM.Standard.A1.Flex"
:oci-ocpus 1
:oci-memory-in-gbs 4
:oci-boot-volume-size-in-gbs 50
:oci-boot-volume-vpus-per-gb 30
:oci-ssh-authorized-keys "/home/user/.ssh/once.pub"
```

No credential variable is required: OCI authenticates through the named profile in `~/.oci/config`. `:oci-ssh-authorized-keys` is a path to a public-key file on the machine running the launcher, read at plan time.

### Existing server

```clojure
:provider-compute "no-infra"
:no-infra-compute-ip "203.0.113.10"
:no-infra-compute-user "root"
:no-infra-compute-sudoer "root"
:no-infra-compute-uid 0
:no-infra-compute-name "once"
```

No compute API credential is required. SSH authentication must already work through `ssh-agent`.

## SMTP providers

### Resend

```clojure
:provider-smtp "resend"
:resend-server "smtp.resend.com"
:resend-port 587
:resend-username "resend"
```

Required credentials: `GREEN_PAR_RESEND_API_KEY` for the Resend API, and `GREEN_PAR_RESEND_PASSWORD` for the SMTP password written into the server's mail configuration.

### Existing SMTP

```clojure
:provider-smtp "no-infra"
:no-infra-smtp-server "smtp.example.net"
:no-infra-smtp-port 587
:no-infra-smtp-username "smtp-user"
```

Required credential: `GREEN_PAR_NO_INFRA_SMTP_PASSWORD`.

## DNS providers

Use `:provider-dns "cloudflare"` or `:provider-dns "no-infra"`.

Cloudflare requires `GREEN_PAR_CLOUDFLARE_API_TOKEN`. The token needs permission to discover the configured zone and manage its DNS records. `no-infra` renders an empty DNS module and requires no credential.

## State backends

### Local

```clojure
:provider-backend "local"
```

Each tool keeps isolated state under `<workdir>/<profile>/<tool>/`.

### Amazon S3

```clojure
:provider-backend "s3"
:s3-bucket "once-tfstate"
:s3-region "eu-west-1"
```

No `GREEN_PAR_*` credential: the generated backend names only the bucket, key, and region, so OpenTofu resolves credentials through its own AWS chain (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, a shared profile, or an instance role). State keys are derived as `<profile>/<tool>.tfstate`.

### Cloudflare R2

```clojure
:provider-backend "r2"
:r2-bucket "once-tfstate"
:r2-endpoint "https://ACCOUNT_ID.r2.cloudflarestorage.com"
```

Required credentials: `GREEN_PAR_R2_ACCESS_KEY_ID` and `GREEN_PAR_R2_SECRET_ACCESS_KEY`. R2 is configured as an S3-compatible backend with `region = "auto"`. State keys are derived as `<profile>/<tool>.tfstate`.

## Safe lifecycle

`build` and dry-run do not require credentials; unset values simply render empty, so a build never writes a secret to disk. Real create validates all selected provider credentials and every application `:env` reference before running. Real delete validates provider credentials and refuses while `:compute-prevent-destroy` is true.

To authorize an intentional delete without editing committed desired state:

```sh
export GREEN_PAR_COMPUTE_PREVENT_DESTROY=false
./green delete --dry-run
./green delete
```

`GREEN_PAR_*` overrides any flat key, not just secrets: strip the prefix, lowercase the name, and replace underscores with hyphens. For example, `GREEN_PAR_DIGITALOCEAN_REGION=fra1` overrides `:digitalocean-region`.
