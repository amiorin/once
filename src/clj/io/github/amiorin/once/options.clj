(ns io.github.amiorin.once.options
  (:require
   [big-config.render :as render]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]))

(def ^:private deploy {::workflow/params {:deploy-pubkey "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 32617+amiorin@users.noreply.github.com"}})

(def ^:private resend {::workflow/params {:provider-smtp "resend"
                                          :resend-server "smtp.resend.com"
                                          :resend-port 587
                                          :resend-username "resend"}})

(def ^:private cloudflare {::workflow/params {:provider-dns "cloudflare"}})

(def ^:private s3 {::workflow/params {:provider-backend "s3"
                                      :s3-bucket "once-dev-251213589273-eu-west-1"
                                      :s3-region "eu-west-1"}})

(def ^:private r2 {::workflow/params {:provider-backend "r2"
                                      :r2-bucket "tofu-state-319271fed8bc6d2d9059362be1165f37-eu"
                                      :r2-endpoint "https://319271fed8bc6d2d9059362be1165f37.eu.r2.cloudflarestorage.com"
                                      :r2-access-key-id ""
                                      :r2-secret-access-key ""}})

(def ^:private local {::workflow/params {:provider-backend "local"}})

(def ^:private oci {::workflow/params {:provider-compute "oci"
                                       :oci-config-file-profile "DEFAULT"
                                       :oci-subnet-id "ocid1.subnet.oc1.eu-frankfurt-1.aaaaaaaaotya32pihejgi25vrdfnjda3qg52kpsjnd7od5oiqifbsi4rqqma"
                                       :oci-compartment-id "ocid1.tenancy.oc1..aaaaaaaal4wmmpzv2fzkdz2vrfdizywgzjid6dqlgcankrrr7jyydo7ozb3a"
                                       :oci-availability-domain "xTQn:EU-FRANKFURT-1-AD-1"
                                       :oci-display-name "my-ampere-instance"
                                       :oci-shape "VM.Standard.A1.Flex"
                                       :oci-ocpus 1
                                       :oci-memory-in-gbs 4
                                       :oci-boot-volume-size-in-gbs 50
                                       :oci-boot-volume-vpus-per-gb 30
                                       :oci-ssh-authorized-keys "~/.ssh/id_ed25519.pub"}})

(def ^:private hcloud {::workflow/params {:provider-compute "hcloud"
                                          :hcloud-name "once"
                                          :hcloud-image "ubuntu-24.04"
                                          :hcloud-server-type "cx23"
                                          :hcloud-location "hel1"
                                          :hcloud-ssh-keys "32617+amiorin@users.noreply.github.com"}})

(def ^:private digitalocean {::workflow/params {:provider-compute "digitalocean"
                                                :digitalocean-name "once"
                                                :digitalocean-region "ams3"
                                                :digitalocean-size "s-1vcpu-1gb-35gb-intel"
                                                :digitalocean-image "ubuntu-25-10-x64"
                                                :digitalocean-vpc-uuid "b6938e67-dc83-11e8-a3da-3cfdfea9f0d8"
                                                :digitalocean-ssh-keys "812184"}})

(def ^:private no-infra-compute {::workflow/params {:provider-compute "no-infra"
                                                    :no-infra-compute-ip "192.168.0.1"
                                                    :no-infra-compute-user "ubuntu"
                                                    :no-infra-compute-sudoer "ubuntu"
                                                    :no-infra-compute-uid "1000"
                                                    :no-infra-compute-name "once"}})

(def ^:private no-infra-smtp {::workflow/params {:provider-smtp "no-infra"
                                                 :no-infra-smtp-server "smtp.resend.com"
                                                 :no-infra-smtp-port 587
                                                 :no-infra-smtp-username "resend"}})

(def ^:private no-infra-dns {::workflow/params {:provider-dns "no-infra"}})

(def website (merge-with merge resend cloudflare r2 digitalocean deploy
                         {::render/profile "website"
                          ::workflow/params {:domain "bigconfig.website"
                                             :package "website"
                                             :once {:applications [{:host  "www.bigconfig.website"
                                                                    :image "ghcr.io/bigconfig-ai/once-bigconfig:latest"}
                                                                   {:host  "bigconfig.website"
                                                                    :image "ghcr.io/bigconfig-ai/once-caddy-redirect:latest"}
                                                                   {:host "forms.bigconfig.website"
                                                                    :image "ghcr.io/bigconfig-ai/once-forms:latest"
                                                                    :env ["TARGET_EMAIL=forms@bigconfig.ai"]}]}}}))

(def online (merge-with merge resend cloudflare r2 oci deploy
                        {::render/profile "online"
                         ::workflow/params {:domain "bigconfig.online"
                                            :package "online"
                                            :once {:applications [{:host "www.bigconfig.online"
                                                                   :image "ghcr.io/bigconfig-ai/once-bigconfig"}]}}}))

(def space (merge-with merge resend cloudflare r2 oci deploy
                       {::render/profile "space"
                        ::workflow/params {:domain "bigconfig.space"
                                           :package "space"
                                           :once {:applications [{:host "marketplace-api.bigconfig.space"
                                                                  :image "ghcr.io/amiorin/once-pocketbase"
                                                                  :env ["SUPERUSER_PASSWORD=<{ superuser-password }>"]}]}}}))

(def no-infra (merge-with merge no-infra-compute no-infra-smtp no-infra-dns deploy
                          {::render/profile "no-infra"}))

(def bb website)

(comment
  (debug tap-values
    (-> {:local local
         :digitalocean digitalocean
         :online online
         :space space
         :no-infra no-infra
         :s3 s3
         :r2 r2
         :bb bb}))
  (-> tap-values))
