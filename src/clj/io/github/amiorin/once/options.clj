(ns io.github.amiorin.once.options
  (:require
   [big-config.render :as render]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]))

(def common {::workflow/params {:domain "bigconfig.online"
                                :package "once"}})

(def resend {::workflow/params {:provider-smtp "resend"
                                :resend-server "smtp.resend.com"
                                :resend-port 587
                                :resend-username "resend"}})

(def cloudflare {::workflow/params {:provider-dns "cloudflare"
                                    :cloudflare-zone-id "f8d9f9cb95c9431f754df2adec8fd504"}})

(def oci (merge-with merge resend common cloudflare
                     {::render/profile "oci"
                      ::workflow/params {:provider-compute "oci"
                                         :oci-config-file-profile "DEFAULT"
                                         :oci-subnet-id "ocid1.subnet.oc1.eu-frankfurt-1.aaaaaaaaotya32pihejgi25vrdfnjda3qg52kpsjnd7od5oiqifbsi4rqqma"
                                         :oci-compartment-id "ocid1.tenancy.oc1..aaaaaaaal4wmmpzv2fzkdz2vrfdizywgzjid6dqlgcankrrr7jyydo7ozb3a"
                                         :oci-availability-domain "xTQn:EU-FRANKFURT-1-AD-1"
                                         :oci-display-name "my-ampere-instance"
                                         :oci-shape "VM.Standard.A1.Flex"
                                         :oci-ocpus 2
                                         :oci-memory-in-gbs 12
                                         :oci-boot-volume-size-in-gbs 100
                                         :oci-boot-volume-vpus-per-gb 30
                                         :oci-ssh-authorized-keys "~/.ssh/id_ed25519.pub"}}))

(comment
  (debug tap-values
    (-> oci))
  (-> tap-values))

(def hcloud (merge-with merge resend cloudflare common
                        {::render/profile "hcloud"
                         ::workflow/params {:provider-compute "hcloud"
                                            :hcloud-name "once"
                                            :hcloud-image "ubuntu-24.04"
                                            :hcloud-server-type "cx23"
                                            :hcloud-location "hel1"
                                            :hcloud-ssh-keys "32617+amiorin@users.noreply.github.com"}}))

(comment
  (debug tap-values
    (-> hcloud))
  (-> tap-values))

(def no-infra {::render/profile "no-infra"
               ::workflow/params {:provider-compute "no-infra"
                                  :provider-dns "no-infra"
                                  :provider-smtp "no-infra"
                                  :ip "192.168.0.1"
                                  :user "ubuntu"
                                  :sudoer "ubuntu"
                                  :uid "1000"
                                  :package "once"
                                  :name "once"}})

(comment
  (debug tap-values
    (-> no-infra))
  (-> tap-values))
