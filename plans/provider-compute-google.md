# Google Cloud compute provider

Status: planned.

## Goal

Add `provider-compute: google` to ONCE with byte-identical Green, Red, and Blue behavior, then prove it with a disposable full deployment at `google.bigconfig.online`.

The deployment uses Google Compute Engine, Resend SMTP, Cloudflare DNS, and local OpenTofu state. Real test resources may be created and deleted during verification. The deployment directory is named `once-google`, not `once-gcloud`.

## Fixed decisions

- Google Cloud project: `once-504515` (billing enabled)
- Region: `europe-west1`
- Zone: `europe-west1-b`
- Architecture: ARM64
- Machine type: `t2a-standard-1` (1 vCPU, 4 GB RAM)
- Image: Ubuntu 24.04 LTS ARM64
- Boot disk: 30 GB
- SSH public key file: `~/.ssh/id_ed25519.pub`; private key remains in `ssh-agent`
- Authentication: browser-based Application Default Credentials from `gcloud auth application-default login`
- ONCE owns a dedicated VPC, subnet, firewall rules, static external IP, and Compute Engine instance
- Application: `ghcr.io/getcolors/colors-website:latest` at `google.bigconfig.online`
- No GitHub deployment integration

## Desired-state contract

Use flat keys consistent with the existing provider registries:

```yaml
provider-compute: google
google-project: once-504515
google-region: europe-west1
google-zone: europe-west1-b
google-name: once-google
google-machine-type: t2a-standard-1
google-image-project: ubuntu-os-cloud
google-image-family: ubuntu-2404-lts-arm64
google-image-id: <pinned-self-link>
google-vpc-cidr: 10.20.0.0/16
google-subnet-cidr: 10.20.1.0/24
google-boot-disk-size-gb: 30
google-ssh-authorized-keys: ~/.ssh/id_ed25519.pub
```

Use a pinned image self-link or immutable image name in the actual VM. The family is only for discovering that pin; a routine create must not silently replace the VM when Google publishes a newer image. Google credentials remain ambient and are not represented by `COLORS_PAR_*` secrets.

## OpenTofu shape

Add `tools/tofu/google/main.tf`, byte-identical in all three packages, using `hashicorp/google` internally. Configure project, region, and zone explicitly while leaving credentials to Application Default Credentials.

Create:

1. `google_compute_network` in custom-subnet mode
2. Regional `google_compute_subnetwork`
3. Firewall rules allowing TCP 22, 80, and 443 to instances carrying a dedicated network tag
4. Regional static `google_compute_address`
5. `google_compute_instance` using `t2a-standard-1`, the pinned ARM64 Ubuntu image, a 30 GB balanced persistent boot disk, the dedicated subnet, and the static external IP
6. Instance metadata installing `ubuntu:<public-key>` from the configured public-key file

Do not disable the Compute Engine API on destroy. If API enablement is managed with `google_project_service`, set `disable_on_destroy = false`; otherwise make API enablement an explicit preflight step.

The instance carries `prevent_destroy = <{ compute-prevent-destroy }>` and waits for SSH through the agent before Ansible. Output the standard `params` contract:

```hcl
{ ip = <external-ip>, user = "ubuntu", sudoer = "ubuntu", uid = "1000", name = <profile> }
```

## Package changes

Apply shared behavior in one commit:

- Green: provider registry, Ubuntu fallback params, and Google template
- Red: provider registry, template import/map, and fallback params
- Blue: provider registry, packaged template list, and fallback params
- Add Google keys to `colors.yml` and `test/parity/colors.yml`
- Add a Google build variant to `scripts/parity.sh`
- Update the unified manual, Green README, and all three skill references
- Do not add credential values to templates, desired state, generated output, tests, or documentation

## Tests

Add provider-registry assertions for all required Google keys and verify no Google credential is required by ONCE. Build must work without `gcloud`, credentials, or provider calls.

Run:

```sh
cd green && clojure -M:test
cd red && bun test && bun run typecheck
cd blue && uv run python -m pytest -q
./scripts/parity.sh
```

Also render `once-google`, run `tofu fmt -check`, `tofu init`, `tofu validate`, and a real plan.

## Deployment fixture

Create sibling `once-google/` using the same local working-tree launcher arrangement as `once-aws/`:

- `green` symlink
- linked `devenv.nix` and `devenv.lock`
- `.envrc` selecting local ONCE/Green trees, project `once-504515`, region `europe-west1`, and zone `europe-west1-b`
- gitignored, mode-0600 `.envrc.private`, copied from `once-aws/.envrc.private` without exposing values
- `colors.yml` with `profile: once-google`, local backend, Resend, Cloudflare, and `google.bigconfig.online`

## Live verification

1. Install/check Google Cloud CLI.
2. Run browser-based `gcloud auth application-default login`, set project `once-504515`, and verify identity/quota project without printing credentials.
3. Confirm the Compute Engine API is enabled, `t2a-standard-1` is available in `europe-west1-b`, and resolve/pin the current Ubuntu 24.04 ARM64 image.
4. Run `build` and `create --dry-run` credential-free.
5. Run a compute-only OpenTofu apply/destroy to prove ARM boot and SSH-agent access.
6. Run full `./green create` and verify `describe`, SSH, the container digest, and HTTP 200 from `https://google.bigconfig.online`.
7. Run `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false ./green delete`.
8. Verify the Google instance, address, firewall rules, subnet, VPC, Resend resources, Cloudflare records, and all OpenTofu state resources are gone.

Never delete project-wide resources not created by this test. In particular, do not delete the project, billing configuration, default networks, shared APIs, or unrelated IAM bindings.
