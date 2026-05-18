(ns io.github.amiorin.once.options
  (:require
   [big-config.render :as render]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]))

(def ^:private deploy {::workflow/params {:compute-pubkey "REPLACE_ME"
                                          :deploy-pubkey "REPLACE_ME"}})

(def ^:private resend {::workflow/params {:provider-smtp "resend"
                                          :resend-server "smtp.resend.com"
                                          :resend-port 587
                                          :resend-username "resend"
                                          :resend-api-key "REPLACE_ME"
                                          :resend-password "REPLACE_ME"}})

(def ^:private cloudflare {::workflow/params {:provider-dns "cloudflare"
                                              :cloudflare-api-token "REPLACE_ME"}})

(def ^:private s3 {::workflow/params {:provider-backend "s3"
                                      :s3-bucket "REPLACE_ME"
                                      :s3-region "REPLACE_ME"}})

(def ^:private r2 {::workflow/params {:provider-backend "r2"
                                      :r2-bucket "REPLACE_ME"
                                      :r2-endpoint "REPLACE_ME"
                                      :r2-access-key-id "REPLACE_ME"
                                      :r2-secret-access-key "REPLACE_ME"}})

(def ^:private local {::workflow/params {:provider-backend "local"}})

(def ^:private oci {::workflow/params {:provider-compute "oci"
                                       :oci-config-file-profile "REPLACE_ME"
                                       :oci-subnet-id "REPLACE_ME"
                                       :oci-compartment-id "REPLACE_ME"
                                       :oci-availability-domain "REPLACE_ME"
                                       :oci-display-name "once"
                                       :oci-shape "VM.Standard.A1.Flex"
                                       :oci-ocpus 1
                                       :oci-memory-in-gbs 4
                                       :oci-boot-volume-size-in-gbs 50
                                       :oci-boot-volume-vpus-per-gb 30
                                       :oci-ssh-authorized-keys "REPLACE_ME"}})

(def ^:private hcloud {::workflow/params {:provider-compute "hcloud"
                                          :hcloud-name "once"
                                          :hcloud-image "ubuntu-24.04"
                                          :hcloud-server-type "cx23"
                                          :hcloud-location "hel1"
                                          :hcloud-ssh-keys "REPLACE_ME"
                                          :hcloud-token "REPLACE_ME"}})

(def ^:private digitalocean {::workflow/params {:provider-compute "digitalocean"
                                                :digitalocean-name "once"
                                                :digitalocean-region "ams3"
                                                :digitalocean-size "s-1vcpu-1gb-35gb-intel"
                                                :digitalocean-image "ubuntu-25-10-x64"
                                                :digitalocean-vpc-uuid "REPLACE_ME"
                                                :digitalocean-ssh-keys "REPLACE_ME"
                                                :do-token "REPLACE_ME"}})

(def ^:private no-infra-compute {::workflow/params {:provider-compute "no-infra"
                                                    :no-infra-compute-ip "REPLACE_ME"
                                                    :no-infra-compute-user "REPLACE_ME"
                                                    :no-infra-compute-sudoer "REPLACE_ME"
                                                    :no-infra-compute-uid "REPLACE_ME"
                                                    :no-infra-compute-name "REPLACE_ME"}})

(def ^:private no-infra-smtp {::workflow/params {:provider-smtp "no-infra"
                                                 :no-infra-smtp-server "smtp.resend.com"
                                                 :no-infra-smtp-port 587
                                                 :no-infra-smtp-username "resend"
                                                 :no-infra-smtp-password "REPLACE_ME"}})

(def ^:private no-infra-dns {::workflow/params {:provider-dns "no-infra"}})

(def profile-alpha (merge-with merge resend cloudflare r2 digitalocean deploy
                               {::render/profile "profile-alpha"
                                ::workflow/params {:domain "alpha.example.com"
                                                   :package "profile-alpha"
                                                   :once {:applications [{:host  "www.alpha.example.com"
                                                                          :image "ghcr.io/bigconfig-ai/once-bigconfig:latest"}
                                                                         {:host  "alpha.example.com"
                                                                          :image "ghcr.io/bigconfig-ai/once-caddy-redirect:latest"}
                                                                         {:host "forms.alpha.example.com"
                                                                          :image "ghcr.io/bigconfig-ai/once-forms:latest"
                                                                          :env ["TARGET_EMAIL=forms@alpha.example.com"]}]}}}))

(def profile-beta (merge-with merge resend cloudflare r2 oci deploy
                              {::render/profile "profile-beta"
                               ::workflow/params {:domain "beta.example.com"
                                                  :package "profile-beta"
                                                  :once {:applications [{:host "www.beta.example.com"
                                                                         :image "ghcr.io/bigconfig-ai/once-bigconfig"}]}}}))

(def profile-gamma (merge-with merge resend cloudflare r2 oci deploy
                               {::render/profile "profile-gamma"
                                ::workflow/params {:domain "gamma.example.com"
                                                   :package "profile-gamma"
                                                   :once {:applications [{:host "marketplace-api.gamma.example.com"
                                                                          :image "ghcr.io/amiorin/once-pocketbase"
                                                                          :env ["SUPERUSER_PASSWORD=<{ superuser-password }>"]}]}}}))

(def profile-no-infra (merge-with merge no-infra-compute no-infra-smtp no-infra-dns local deploy
                                  {::render/profile "profile-no-infra"
                                   ::workflow/params {:domain "no-infra.example.com"
                                                      :package "profile-no-infra"
                                                      :once {:applications [{:host "www.no-infra.example.com"
                                                                             :image "ghcr.io/bigconfig-ai/once-bigconfig:latest"}]}}}))

(def bb profile-alpha)

(comment
  (debug tap-values
    (-> {:deploy deploy
         :resend resend
         :cloudflare cloudflare
         :s3 s3
         :r2 r2
         :local local
         :oci oci
         :hcloud hcloud
         :digitalocean digitalocean
         :no-infra-compute no-infra-compute
         :no-infra-smtp no-infra-smtp
         :no-infra-dns no-infra-dns
         :profile-alpha profile-alpha
         :profile-beta profile-beta
         :profile-gamma profile-gamma
         :profile-no-infra profile-no-infra
         :bb bb}))
  (-> tap-values))
