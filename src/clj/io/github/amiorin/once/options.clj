(ns io.github.amiorin.once.options
  (:require
   [big-config.render :as render]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]))

(def ^:private common {::workflow/params {:domain "bigconfig.website"
                                          :package "once"}})

(def ^:private resend {::workflow/params {:provider-smtp "resend"
                                          :resend-server "smtp.resend.com"
                                          :resend-port 587
                                          :resend-username "resend"}})

(def ^:private cloudflare {::workflow/params {:provider-dns "cloudflare"
                                              :cloudflare-zone-id "f526f293f6aaa115c0e8fb498b3b99f8"}})

(def oci (merge-with merge resend common cloudflare
                     {::render/profile "oci"
                      ::workflow/params {:provider-compute "oci"
                                         :oci-config-file-profile "DEFAULT"
                                         :oci-subnet-id "ocid1.subnet.oc1.eu-frankfurt-1.aaaaaaaaotya32pihejgi25vrdfnjda3qg52kpsjnd7od5oiqifbsi4rqqma"
                                         :oci-compartment-id "ocid1.tenancy.oc1..aaaaaaaal4wmmpzv2fzkdz2vrfdizywgzjid6dqlgcankrrr7jyydo7ozb3a"
                                         :oci-availability-domain "xTQn:EU-FRANKFURT-1-AD-1"
                                         :oci-display-name "my-ampere-instance"
                                         :oci-shape "VM.Standard.A1.Flex"
                                         :oci-ocpus 4
                                         :oci-memory-in-gbs 24
                                         :oci-boot-volume-size-in-gbs 200
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

(def digitalocean (merge-with merge resend cloudflare common
                              {::render/profile "digitalocean"
                               ::workflow/params {:provider-compute "digitalocean"
                                                  :digitalocean-name "once"
                                                  :digitalocean-region "ams3"
                                                  :digitalocean-size "s-1vcpu-1gb-35gb-intel"
                                                  :digitalocean-image "ubuntu-25-10-x64"
                                                  :digitalocean-vpc-uuid "b6938e67-dc83-11e8-a3da-3cfdfea9f0d8"
                                                  :digitalocean-ssh-keys "812184"}}))

(comment
  (debug tap-values
    (-> digitalocean))
  (-> tap-values))

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

(def no-infra (merge-with merge common no-infra-compute no-infra-smtp no-infra-dns
                          {::render/profile "no-infra"}))

(comment
  (debug tap-values
    (-> no-infra))
  (-> tap-values))

(def bb oci)

(comment
  (debug tap-values
    (-> bb))
  (-> tap-values))
