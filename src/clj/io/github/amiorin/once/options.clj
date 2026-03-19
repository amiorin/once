(ns io.github.amiorin.once.options
  (:require
   [big-config.render :as render]
   [big-config.workflow :as workflow]))

(def defaults {::workflow/params {:hyperscaler "oci"
                                  :package "once"}})

(def oci {::render/profile "oci"
          ::workflow/params {:hyperscaler "oci"
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
                                :package "once"
                                :name "once"
                                :image "ubuntu-24.04"
                                :server-type "cx23"
                                :location "hel1"
                                :ssh-keys "32617+amiorin@users.noreply.github.com"}})
