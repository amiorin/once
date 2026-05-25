/**
 * Cloud profiles and the active profile.
 *
 * Two layers compose into a profile: private compute / sub-profile base maps
 * and public application profiles that pin a domain, package name and the list
 * of applications deployed by Ansible.
 */
import type { Opts } from "big-config";
import { PARAMS, PROFILE, syncAliases } from "./interop.js";

export interface Application {
  host: string;
  image: string;
  env?: string[];
}

export interface Profile {
  profile?: string;
  params: Record<string, any>;
}

/** Compose layers like Clojure's `(merge-with merge ...)`: params merge shallowly. */
function compose(...layers: Profile[]): Opts {
  let params: Record<string, any> = {};
  let profile: string | undefined;
  for (const layer of layers) {
    if (layer.profile !== undefined) profile = layer.profile;
    params = { ...params, ...layer.params };
  }
  return syncAliases({ [PROFILE]: profile, [PARAMS]: params });
}

const deploy: Profile = {
  params: {
    "compute-pubkey": "REPLACE_ME",
    "deploy-pubkey": "REPLACE_ME",
  },
};

const resend: Profile = {
  params: {
    "provider-smtp": "resend",
    "resend-server": "smtp.resend.com",
    "resend-port": 587,
    "resend-username": "resend",
    "resend-api-key": "REPLACE_ME",
    "resend-password": "REPLACE_ME",
  },
};

const cloudflare: Profile = {
  params: {
    "provider-dns": "cloudflare",
    "cloudflare-api-token": "REPLACE_ME",
  },
};

const s3: Profile = {
  params: {
    "provider-backend": "s3",
    "s3-bucket": "REPLACE_ME",
    "s3-region": "REPLACE_ME",
  },
};

const r2: Profile = {
  params: {
    "provider-backend": "r2",
    "r2-bucket": "REPLACE_ME",
    "r2-endpoint": "REPLACE_ME",
    "r2-access-key-id": "REPLACE_ME",
    "r2-secret-access-key": "REPLACE_ME",
  },
};

const local: Profile = {
  params: { "provider-backend": "local" },
};

const oci: Profile = {
  params: {
    "provider-compute": "oci",
    "oci-config-file-profile": "REPLACE_ME",
    "oci-subnet-id": "REPLACE_ME",
    "oci-compartment-id": "REPLACE_ME",
    "oci-availability-domain": "REPLACE_ME",
    "oci-display-name": "once",
    "oci-shape": "VM.Standard.A1.Flex",
    "oci-ocpus": 1,
    "oci-memory-in-gbs": 4,
    "oci-boot-volume-size-in-gbs": 50,
    "oci-boot-volume-vpus-per-gb": 30,
    "oci-ssh-authorized-keys": "REPLACE_ME",
  },
};

const hcloud: Profile = {
  params: {
    "provider-compute": "hcloud",
    "hcloud-name": "once",
    "hcloud-image": "ubuntu-24.04",
    "hcloud-server-type": "cx23",
    "hcloud-location": "hel1",
    "hcloud-ssh-keys": "REPLACE_ME",
    "hcloud-token": "REPLACE_ME",
  },
};

const digitalocean: Profile = {
  params: {
    "provider-compute": "digitalocean",
    "digitalocean-name": "once",
    "digitalocean-region": "ams3",
    "digitalocean-size": "s-1vcpu-1gb-35gb-intel",
    "digitalocean-image": "ubuntu-25-10-x64",
    "digitalocean-vpc-uuid": "REPLACE_ME",
    "digitalocean-ssh-keys": "REPLACE_ME",
    "do-token": "REPLACE_ME",
  },
};

const noInfraCompute: Profile = {
  params: {
    "provider-compute": "no-infra",
    "no-infra-compute-ip": "REPLACE_ME",
    "no-infra-compute-user": "REPLACE_ME",
    "no-infra-compute-sudoer": "REPLACE_ME",
    "no-infra-compute-uid": "REPLACE_ME",
    "no-infra-compute-name": "REPLACE_ME",
  },
};

const noInfraSmtp: Profile = {
  params: {
    "provider-smtp": "no-infra",
    "no-infra-smtp-server": "smtp.resend.com",
    "no-infra-smtp-port": 587,
    "no-infra-smtp-username": "resend",
    "no-infra-smtp-password": "REPLACE_ME",
  },
};

const noInfraDns: Profile = {
  params: { "provider-dns": "no-infra" },
};

export const profileAlpha: Opts = compose(resend, cloudflare, r2, digitalocean, deploy, {
  profile: "profile-alpha",
  params: {
    domain: "alpha.example.com",
    package: "profile-alpha",
    once: {
      applications: [
        {
          host: "www.alpha.example.com",
          image: "ghcr.io/bigconfig-ai/once-bigconfig:latest",
        },
        {
          host: "alpha.example.com",
          image: "ghcr.io/bigconfig-ai/once-caddy-redirect:latest",
        },
        {
          host: "forms.alpha.example.com",
          image: "ghcr.io/bigconfig-ai/once-forms:latest",
          env: ["TARGET_EMAIL=forms@alpha.example.com"],
        },
      ],
    },
  },
});

export const profileBeta: Opts = compose(resend, cloudflare, r2, oci, deploy, {
  profile: "profile-beta",
  params: {
    domain: "beta.example.com",
    package: "profile-beta",
    once: {
      applications: [
        { host: "www.beta.example.com", image: "ghcr.io/bigconfig-ai/once-bigconfig" },
      ],
    },
  },
});

export const profileGamma: Opts = compose(resend, cloudflare, r2, oci, deploy, {
  profile: "profile-gamma",
  params: {
    domain: "gamma.example.com",
    package: "profile-gamma",
    once: {
      applications: [
        {
          host: "marketplace-api.gamma.example.com",
          image: "ghcr.io/amiorin/once-pocketbase",
          env: ["SUPERUSER_PASSWORD=<{ superuser-password }>"],
        },
      ],
    },
  },
});

export const profileNoInfra: Opts = compose(
  noInfraCompute,
  noInfraSmtp,
  noInfraDns,
  local,
  deploy,
  {
    profile: "profile-no-infra",
    params: {
      domain: "no-infra.example.com",
      package: "profile-no-infra",
      once: {
        applications: [
          {
            host: "www.no-infra.example.com",
            image: "ghcr.io/bigconfig-ai/once-bigconfig:latest",
          },
        ],
      },
    },
  },
);

/** The active profile. Change this to switch profiles. */
export const bb: Opts = profileAlpha;
