# Azure compute provider

Status: implemented and live-verified on 2026-08-04. Sweden Central replaced the original West Europe choice because Azure blocks new customers there; `Standard_D2pls_v6` replaced v5 because it is the available ARM64 generation for this subscription.

## Goal

Add `provider-compute: azure` to ONCE with byte-identical Green, Red, and Blue behavior, then prove it with a disposable full deployment at `azure.bigconfig.online`.

The deployment uses Azure compute, Resend SMTP, Cloudflare DNS, and local OpenTofu state. Real test resources may be created and deleted during verification.

## Fixed decisions

- Azure subscription: `6e639471-b818-40c3-a0ce-d36d5f2163b3` (active)
- Region: `swedencentral`
- Architecture: ARM64
- VM size: small cost-effective `Standard_D2pls_v6` (verified available for this subscription and region)
- Image: Ubuntu 24.04 LTS ARM64
- Boot disk: 30 GB
- SSH public key file: `~/.ssh/id_ed25519.pub`; private key remains in `ssh-agent`
- Authentication: native Azure CLI session from browser-based `az login`
- ONCE owns a dedicated resource group, VNet, subnet, public IP, network security group, NIC, and VM
- Application: `ghcr.io/getcolors/colors-website:latest` at `azure.bigconfig.online`
- No GitHub deployment integration

## Desired-state contract

Use flat keys consistent with the existing provider registries:

```yaml
provider-compute: azure
azure-subscription-id: 6e639471-b818-40c3-a0ce-d36d5f2163b3
azure-location: swedencentral
azure-resource-group: once-azure
azure-name: once-azure
azure-vm-size: Standard_D2pls_v6
azure-image-publisher: Canonical
azure-image-offer: ubuntu-24_04-lts
azure-image-sku: server-arm64
azure-image-version: 24.04.202608020
azure-vnet-cidr: 10.10.0.0/16
azure-subnet-cidr: 10.10.1.0/24
azure-boot-disk-size-gb: 30
azure-ssh-authorized-keys: ~/.ssh/id_ed25519.pub
```

Resolve and pin an actual image version during preflight rather than leaving `latest` in a real deployment. Azure credentials remain ambient and are not represented by `COLORS_PAR_*` secrets.

## OpenTofu shape

Add `tools/tofu/azure/main.tf`, byte-identical in all three packages, using `hashicorp/azurerm` internally. Configure `subscription_id`, `location`, and an empty `features {}` block.

Create:

1. `azurerm_resource_group`
2. `azurerm_virtual_network`
3. `azurerm_subnet`
4. Standard static `azurerm_public_ip`
5. `azurerm_network_security_group` allowing TCP 22, 80, and 443 and normal outbound traffic
6. Subnet or NIC security-group association
7. `azurerm_network_interface`
8. `azurerm_linux_virtual_machine` with the pinned ARM64 Ubuntu image, 30 GB OS disk, `ubuntu` administrator, password authentication disabled, and the public key read from the configured path

The VM carries `prevent_destroy = <{ compute-prevent-destroy }>` and waits for SSH through the agent before the Ansible stages. Output the standard `params` contract:

```hcl
{ ip = <public-ip>, user = "ubuntu", sudoer = "ubuntu", uid = "1000", name = <profile> }
```

## Package changes

Apply shared behavior in one commit:

- Green: provider registry, Ubuntu fallback params, and Azure template
- Red: provider registry, template import/map, and fallback params
- Blue: provider registry, packaged template list, and fallback params
- Add Azure keys to `colors.yml` and `test/parity/colors.yml`
- Add an Azure build variant to `scripts/parity.sh`
- Update the unified manual, Green README, and all three skill references
- Do not add credential values to templates, desired state, generated output, tests, or documentation

## Tests

Add provider-registry assertions for all required Azure keys and verify no Azure credential is required by ONCE. Build must work without `az`, credentials, or provider calls.

Run:

```sh
cd green && clojure -M:test
cd red && bun test && bun run typecheck
cd blue && uv run python -m pytest -q
./scripts/parity.sh
```

Also render `once-azure`, run `tofu fmt -check`, `tofu init`, `tofu validate`, and a real plan.

## Deployment fixture

Create sibling `once-azure/` using the same local working-tree launcher arrangement as `once-aws/`:

- `green` symlink
- linked `devenv.nix` and `devenv.lock`
- `.envrc` selecting local ONCE/Green trees and the Azure subscription
- gitignored, mode-0600 `.envrc.private`, copied from `once-aws/.envrc.private` without exposing values
- `colors.yml` with `profile: once-azure`, local backend, Resend, Cloudflare, and `azure.bigconfig.online`

## Live verification

1. Install/check Azure CLI and run browser-based `az login`.
2. Select the subscription and verify `az account show` reports `Enabled`.
3. Confirm `Standard_D2pls_v6` and the ARM64 Ubuntu image exist in `swedencentral`; pin the image version.
4. Run `build` and `create --dry-run` credential-free.
5. Run a compute-only OpenTofu apply/destroy to prove ARM boot and SSH-agent access.
6. Run full `./green create` and verify `describe`, SSH, the container digest, and HTTP 200 from `https://azure.bigconfig.online`.
7. Run `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false ./green delete`.
8. Verify the Azure resource group/resources, Resend domain records, Cloudflare records, and all OpenTofu state resources are gone.

Never delete resources outside the dedicated `once-azure` resource group or resources not created by this test.
