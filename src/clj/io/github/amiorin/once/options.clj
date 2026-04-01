(ns io.github.amiorin.once.options
  (:require
   [big-config.render :as render]
   [big-config.workflow :as workflow]))

(def oci {::render/profile "oci"
          ::workflow/params {:domain "bigconfig.website"
                             :hyperscaler "oci"
                             :dns-provider "cloudflare"
                             :zone-id "f526f293f6aaa115c0e8fb498b3b99f8"
                             :smtp-provider "resend"
                             :resend-server "smtp.resend.com"
                             :resend-port 587
                             :resend-username "resend"
                             :package "once"
                             :config-file-profile "DEFAULT"
                             :subnet-id "ocid1.subnet.oc1.eu-frankfurt-1.aaaaaaaaotya32pihejgi25vrdfnjda3qg52kpsjnd7od5oiqifbsi4rqqma"
                             :compartment-id "ocid1.tenancy.oc1..aaaaaaaal4wmmpzv2fzkdz2vrfdizywgzjid6dqlgcankrrr7jyydo7ozb3a"
                             :availability-domain "xTQn:EU-FRANKFURT-1-AD-1"
                             :display-name "my-ampere-instance"
                             :shape "VM.Standard.A1.Flex"
                             :ocpus 2
                             :memory-in-gbs 12
                             :boot-volume-size-in-gbs 100
                             :boot-volume-vpus-per-gb 30
                             :ssh-authorized-keys "~/.ssh/id_ed25519.pub"}})

(def hcloud {::render/profile "hcloud"
             ::workflow/params {:hyperscaler "hcloud"
                                :dns-provider "cloudflare"
                                :zone-id "f526f293f6aaa115c0e8fb498b3b99f8"
                                :smtp-provider "resend"
                                :package "once"
                                :name "once"
                                :image "ubuntu-24.04"
                                :server-type "cx23"
                                :location "hel1"
                                :ssh-keys "32617+amiorin@users.noreply.github.com"}})

(def no-infra {::render/profile "no-infra"
               ::workflow/params {:hyperscaler "no-infra"
                                  :dns-provider "cloudflare"
                                  :zone-id "f526f293f6aaa115c0e8fb498b3b99f8"
                                  :smtp-provider "resend"
                                  :ip "192.168.0.1"
                                  :user "ubuntu"
                                  :sudoer "ubuntu"
                                  :uid "1000"
                                  :package "once"
                                  :name "once"}})
