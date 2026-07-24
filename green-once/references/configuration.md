# Configuration reference

The generated `green.edn` is a flat EDN map. Include only selected providers' non-secret settings. Never add credentials or passwords.

## Base shape

```clojure
{:profile "production"
 :workdir ".green"
 :domain "example.com"
 :package "once-production"

 :compute-pubkey "ssh-ed25519 AAAA... operator-compute"
 :deploy-pubkey  "ssh-ed25519 AAAA... ci-deploy"

 :once {:applications
        [{:host "www.example.com"
          :image "ghcr.io/example/site:latest"
          :env {"DATABASE_URL" "ONCE_APP_DATABASE_URL"}}]}

 :provider-compute "digitalocean"
 :provider-smtp "resend"
 :provider-dns "cloudflare"
 :provider-backend "r2"
 :compute-prevent-destroy true

 ;; Add the selected providers' non-secret fields here.
 }
```

Application hostnames must equal `:domain` or be subdomains of it. `:env` maps container variable names to environment-variable names; it never contains values. Application options supported by the ONCE reconciler also include `:auto_update`, `:auto_backup`, `:backup_path`, `:disable_tls`, `:cpus`, and `:memory`.

`:compute-pubkey` is required for cloud compute and is registered with the new server. `:deploy-pubkey` authorizes only `sudo once update <configured-host>` through a remote ForceCommand. Private keys remain outside the project and should be loaded in `ssh-agent`.

## Compute providers

### DigitalOcean

```clojure
:provider-compute "digitalocean"
:digitalocean-name "once"
:digitalocean-region "ams3"
:digitalocean-size "s-1vcpu-1gb-35gb-intel"
:digitalocean-image "ubuntu-24-04-x64"
;; Optional:
:digitalocean-vpc-uuid "non-secret-vpc-uuid"
```

Required secret environment variable: `DIGITALOCEAN_TOKEN`.

### Hetzner Cloud

```clojure
:provider-compute "hcloud"
:hcloud-name "once"
:hcloud-image "ubuntu-24.04"
:hcloud-server-type "cx23"
:hcloud-location "hel1"
```

Required secret environment variable: `HCLOUD_TOKEN`.

### Oracle Cloud Infrastructure

```clojure
:provider-compute "oci"
:oci-region "eu-frankfurt-1"
:oci-subnet-id "ocid1.subnet..."
:oci-compartment-id "ocid1.compartment..."
:oci-availability-domain "..."
:oci-display-name "once"
:oci-shape "VM.Standard.A1.Flex"
:oci-ocpus 1
:oci-memory-in-gbs 4
:oci-boot-volume-size-in-gbs 50
:oci-boot-volume-vpus-per-gb 30
```

Required environment variables: `TF_VAR_oci_tenancy_ocid`, `TF_VAR_oci_user_ocid`, `TF_VAR_oci_fingerprint`, and secret `TF_VAR_oci_private_key`.

### Existing server

```clojure
:provider-compute "no-infra"
:no-infra-compute-ip "203.0.113.10"
:no-infra-compute-user "root"
:no-infra-compute-sudoer "root"
:no-infra-compute-uid 0
:no-infra-compute-name "once"
```

No compute API credential is required. SSH authentication must already work through `ssh-agent`. `:compute-pubkey` may be omitted.

## SMTP providers

### Resend

```clojure
:provider-smtp "resend"
:resend-server "smtp.resend.com"
:resend-port 587
:resend-username "resend"
```

Required secrets: `TF_VAR_resend_api_key` and, for create/provisioning, `ONCE_SMTP_PASSWORD`.

### Existing SMTP

```clojure
:provider-smtp "no-infra"
:no-infra-smtp-server "smtp.example.net"
:no-infra-smtp-port 587
:no-infra-smtp-username "smtp-user"
```

Required secret for create/provisioning: `ONCE_SMTP_PASSWORD`.

## DNS providers

Use `:provider-dns "cloudflare"` or `:provider-dns "no-infra"`.

Cloudflare requires `CLOUDFLARE_API_TOKEN`. The token needs permission to discover the configured zone and manage its DNS records. `no-infra` renders an empty DNS module and requires no credential.

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

Required secrets: `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`. `AWS_SESSION_TOKEN` is honored when present. State keys are derived as `<profile>/<tool>.tfstate`.

### Cloudflare R2

```clojure
:provider-backend "r2"
:r2-bucket "once-tfstate"
:r2-endpoint "https://ACCOUNT_ID.r2.cloudflarestorage.com"
```

Required secrets: `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, set to the R2 access-key credentials. State keys are derived as `<profile>/<tool>.tfstate`.

## Safe lifecycle

`build` and dry-run do not require provider credentials. Real create validates all selected provider credentials and every application environment-variable reference before running. Real delete validates provider credentials and refuses while `:compute-prevent-destroy` is true.

To authorize an intentional delete without editing committed desired state:

```sh
export GREEN_PAR_COMPUTE_PREVENT_DESTROY=false
./green delete --dry-run
./green delete
```

`GREEN_PAR_*` may override flat non-secret values as well: strip the prefix, lowercase the name, and replace underscores with hyphens. For example, `GREEN_PAR_DIGITALOCEAN_REGION=fra1` overrides `:digitalocean-region`.
