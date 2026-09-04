(ns io.github.getcolors.once.compute-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.once.compute :as sut]
   [io.github.getcolors.once.ssh :as ssh]))

;; A two-provider stub registry shaped like clickstack's: the package-owned
;; data ONCE takes as a spec value. The same stub drives red and blue.
(def registry
  {"vultr" {:required [:vultr-region :vultr-plan :vultr-os-id
                       :vultr-ssh-sources :vultr-http-sources]
            :secrets [:vultr-api-key]
            :tofu-env {:vultr-api-key "VULTR_API_KEY"}}
   "digitalocean" {:required [:digitalocean-region :digitalocean-size :digitalocean-image
                              :digitalocean-ssh-sources :digitalocean-http-sources]
                   :secrets [:do-token]
                   :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}})

(def spec {:registry registry
           :default "vultr"
           :sources {:non-empty ["ssh-sources"] :may-be-empty ["http-sources"]}})

(defn- vultr [& kvs]
  (merge {:profile "prod" :provider-compute "vultr"} (apply hash-map kvs)))

(defn- digitalocean [& kvs]
  (merge {:profile "prod" :provider-compute "digitalocean"} (apply hash-map kvs)))

(def selection-message ":provider-compute must be one of digitalocean, vultr")

(deftest selection-refuses-an-unadvertised-provider-with-the-sorted-list
  (is (nil? (sut/provider spec {:provider-compute "hetzner"})))
  (is (= [selection-message] (sut/selection-errors spec {:provider-compute "hetzner"})))
  (is (= [selection-message] (sut/selection-errors spec {})))
  (is (= [] (sut/selection-errors spec (vultr))))
  (testing "an unselected provider reports the selection alone; its keys are not checked"
    (is (= [selection-message]
           (sut/state-errors spec {:provider-compute "hetzner"
                                   :hetzner-ssh-sources ["nope"]
                                   :hetzner-name "BAD NAME"})))))

(deftest selection-ignores-keys-of-the-unselected-provider
  (is (= [] (sut/state-errors spec (vultr :vultr-ssh-sources ["10.0.0.0/8"]
                                          :vultr-os-id 2284
                                          :digitalocean-ssh-sources ["nope"]
                                          :digitalocean-vpc-uuid "vpc-123"
                                          :digitalocean-name "BAD NAME")))))

(deftest required-keys-secrets-and-tofu-env-follow-the-selected-entry
  (is (= (get-in registry ["vultr" :required]) (sut/required-keys spec (vultr))))
  (is (= (get-in registry ["digitalocean" :required]) (sut/required-keys spec (digitalocean))))
  (is (= [] (sut/required-keys spec {:provider-compute "hetzner"})))
  (is (= [:vultr-api-key] (sut/secrets spec (vultr))))
  (is (= [:do-token] (sut/secrets spec (digitalocean))))
  (is (= [] (sut/secrets spec {})))
  (is (= {:vultr-api-key "VULTR_API_KEY"} (sut/tofu-env spec (vultr))))
  (is (= {:do-token "DIGITALOCEAN_TOKEN"} (sut/tofu-env spec (digitalocean))))
  (is (= {} (sut/tofu-env spec {}))))

(deftest compute-key-and-name-follow-the-selected-provider
  (is (= :vultr-ssh-sources (sut/key (vultr) "ssh-sources")))
  (is (= :digitalocean-name (sut/key (digitalocean) "name")))
  (is (= "prod" (sut/name (vultr))))
  (is (= "box" (sut/name (vultr :vultr-name "box"))))
  (is (= "box" (sut/name (vultr :vultr-name " box "))) "trimmed")
  (is (= "prod" (sut/name (vultr :vultr-name "REPLACE_ME"))))
  (is (= "prod" (sut/name (vultr :vultr-name ""))))
  (is (= "prod" (sut/name (vultr :digitalocean-name "other"))) "the other provider's override is not read"))

(deftest cidr-grammar
  (testing "v4"
    (is (sut/cidr? "10.0.0.0/8"))
    (is (sut/cidr? "203.0.113.7/32"))
    (is (sut/cidr? "0.0.0.0/0")))
  (testing "v6"
    (is (sut/cidr? "2001:db8::/32"))
    (is (sut/cidr? "::1/128"))
    (is (sut/cidr? "1:2:3:4:5:6:7:8/128")))
  (testing "::"
    (is (sut/cidr? "::/0")))
  (testing "v4-tail"
    (is (sut/cidr? "::ffff:203.0.113.7/128"))
    (is (sut/cidr? "64:ff9b::192.0.2.33/96"))
    (is (not (sut/cidr? "::ffff:300.0.0.1/128")))
    (is (not (sut/cidr? "192.0.2.1::/96")) "a dotted quad anywhere but the tail fails"))
  (testing "bad prefix"
    (is (not (sut/cidr? "10.0.0.0/33")))
    (is (not (sut/cidr? "2001:db8::/129")))
    (is (not (sut/cidr? "10.0.0.0/")))
    (is (not (sut/cidr? "10.0.0.0")))
    (is (not (sut/cidr? "10.0.0.0/8/8"))))
  (testing "bad octet and bad groups"
    (is (not (sut/cidr? "256.0.0.1/8")))
    (is (not (sut/cidr? "2001:db8:::1/64")))
    (is (not (sut/cidr? "1:2:3:4:5:6:7:8:9/64")))
    (is (not (sut/cidr? "2001:db8::g/64"))))
  (testing "hostname"
    (is (not (sut/cidr? "example.com/32"))))
  (testing "blank"
    (is (not (sut/cidr? "")))
    (is (not (sut/cidr? nil)))))

(deftest source-errors-follow-the-spec
  (testing "an empty :non-empty key is refused"
    (is (= [":vultr-ssh-sources must list at least one CIDR"]
           (sut/source-errors spec (vultr :vultr-ssh-sources [])))))
  (testing "an empty :may-be-empty key is allowed"
    (is (= [] (sut/source-errors spec (vultr :vultr-ssh-sources ["10.0.0.0/8"]
                                             :vultr-http-sources [])))))
  (testing "malformed entries are counted per key"
    (is (= [":vultr-ssh-sources entry \"nope\" is not an IPv4 or IPv6 CIDR"
            ":vultr-http-sources entry \"::1/129\" is not an IPv4 or IPv6 CIDR"]
           (sut/source-errors spec (vultr :vultr-ssh-sources ["10.0.0.0/8" "nope"]
                                          :vultr-http-sources ["::1/129" "1.2.3.4/32"])))))
  (testing "an overlay string parses"
    (is (= ["10.0.0.0/8" "192.0.2.0/24"]
           (sut/cidrs (vultr :vultr-ssh-sources "10.0.0.0/8, 192.0.2.0/24") :vultr-ssh-sources)))
    (is (= [] (sut/source-errors spec (vultr :vultr-ssh-sources "10.0.0.0/8, 192.0.2.0/24"))))
    (is (= [":vultr-ssh-sources entry \"bad\" is not an IPv4 or IPv6 CIDR"]
           (sut/source-errors spec (vultr :vultr-ssh-sources "10.0.0.0/8 bad")))))
  (testing "an absent key is skipped — presence is required-keys' job"
    (is (= [] (sut/source-errors spec (vultr))))
    (is (= [] (sut/source-errors spec (vultr :vultr-ssh-sources "  ")))))
  (testing "the spec decides which suffixes exist"
    (let [three (assoc spec :sources {:non-empty ["ssh-sources"]
                                      :may-be-empty ["http-sources" "stun-sources"]})]
      (is (= [":vultr-stun-sources entry \"x\" is not an IPv4 or IPv6 CIDR"]
             (sut/source-errors three (vultr :vultr-stun-sources ["x"]))))
      (is (= [] (sut/source-errors spec (vultr :vultr-stun-sources ["x"])))))))

(def do-name-message
  ":digitalocean-name must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters")

(deftest provider-errors-check-the-resolved-name-and-the-provider-rules
  (testing "default name rules on the raw override, blamed on the override key"
    (is (= [":vultr-name must be a safe 1-63 character name"]
           (sut/provider-errors spec (vultr :vultr-name "bad name!"))))
    (is (= [do-name-message] (sut/provider-errors spec (digitalocean :digitalocean-name "Upper")))))
  (testing "default name rules on the resolved profile, blamed on the profile"
    (is (= [":profile (the vultr machine name) must be a safe 1-63 character name"]
           (sut/provider-errors spec (vultr :profile "bad name!"))))
    (is (= [":profile (the digitalocean machine name) must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"]
           (sut/provider-errors spec (digitalocean :profile "under_score"))))
    (is (= [":profile (the digitalocean machine name) must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"]
           (sut/provider-errors spec (digitalocean :profile "Bad" :digitalocean-name "REPLACE_ME")))
        "a placeholder override falls through to the profile"))
  (testing "length and the valid shapes"
    (is (= [do-name-message] (sut/provider-errors spec (digitalocean :digitalocean-name (apply str (repeat 64 "a"))))))
    (is (= [] (sut/provider-errors spec (digitalocean :digitalocean-name (apply str (repeat 63 "a"))))))
    (is (= [] (sut/provider-errors spec (digitalocean :digitalocean-name "prod-1.example"))))
    (is (= [] (sut/provider-errors spec (vultr :vultr-name "Prod_1"))))
    (is (= [] (sut/provider-errors spec (vultr :vultr-name " Prod_1 "))) "trimmed before the check")
    (is (= [] (sut/provider-errors spec (digitalocean :profile ""))) "a blank resolved name is skipped"))
  (testing "a spec-supplied rule set wins"
    (let [own (assoc spec :name-rules {"vultr" {:re #"^x$" :message "must be x"}})]
      (is (= [":vultr-name must be x"] (sut/provider-errors own (vultr :vultr-name "prod"))))
      (is (= [] (sut/provider-errors own (digitalocean :digitalocean-name "Upper")))
          "no rule for a provider means no name check")))
  (testing "Vultr os-id"
    (is (= [":vultr-os-id must be Vultr's numeric operating-system id"]
           (sut/provider-errors spec (vultr :vultr-os-id "2284"))))
    (is (= [] (sut/provider-errors spec (vultr :vultr-os-id 2284))))
    (is (= [] (sut/provider-errors spec (vultr)))))
  (testing "DigitalOcean vpc bans"
    (is (= [":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"]
           (sut/provider-errors spec (digitalocean :digitalocean-vpc-uuid "vpc-123"))))
    (is (= [":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]
           (sut/provider-errors spec (digitalocean :digitalocean-vpc-cidr "10.0.0.0/16"))))
    (is (= [":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"
            ":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]
           (sut/provider-errors spec (digitalocean :digitalocean-vpc-uuid "vpc-123"
                                                   :digitalocean-vpc-cidr "10.0.0.0/16")))))
  (testing "nothing when the other provider is selected"
    (is (= [] (sut/provider-errors spec (vultr :digitalocean-vpc-uuid "vpc-123"
                                               :digitalocean-name "BAD NAME"))))
    (is (= [] (sut/provider-errors spec (digitalocean :vultr-os-id "2284"
                                                      :vultr-name "bad name!"))))))

(deftest state-errors-order-selection-then-source-then-provider
  (is (= [":digitalocean-ssh-sources entry \"nope\" is not an IPv4 or IPv6 CIDR"
          do-name-message]
         (sut/state-errors spec (digitalocean :digitalocean-ssh-sources ["nope"]
                                              :digitalocean-name "Upper")))))

(def legacy-message
  "state holds a machine with no recorded provider, created before this package recorded one, which makes it a vultr machine; set provider-compute back to vultr and delete first")

(deftest provider-state-errors-implement-the-switch-and-legacy-rules
  (testing "nil params"
    (is (= [] (sut/provider-state-errors spec (vultr) nil))))
  (testing "match"
    (is (= [] (sut/provider-state-errors spec (vultr) {:provider "vultr" :ip "1.2.3.4"})))
    (is (= [] (sut/provider-state-errors spec (digitalocean) {:provider "digitalocean"}))))
  (testing "mismatch both ways"
    (is (= ["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
           (sut/provider-state-errors spec (vultr) {:provider "digitalocean"})))
    (is (= ["state holds a vultr machine; set provider-compute back to vultr and delete first"]
           (sut/provider-state-errors spec (digitalocean) {:provider "vultr"}))))
  (testing "legacy on the default"
    (is (= [] (sut/provider-state-errors spec (vultr) {:ip "1.2.3.4"}))))
  (testing "legacy on a non-default"
    (is (= [legacy-message] (sut/provider-state-errors spec (digitalocean) {:ip "1.2.3.4"})))
    (is (= [legacy-message] (sut/provider-state-errors spec (digitalocean) {:provider ""}))
        "an empty recorded provider is no recorded provider")))

(deftest resolved-compute-refuses-a-missing-ip
  (let [fallback (sut/fallback-params (vultr))]
    (testing "missing ip refuses"
      (let [out (sut/resolved-compute {:a 1} fallback nil)]
        (is (= 1 (:green/exit out)))
        (is (= "compute produced no ip output; refusing to converge against the documentation address"
               (:green/err out))))
      (is (= 1 (:green/exit (sut/resolved-compute {} fallback {:name "prod"})))))
    (testing "present ip merges outputs over the fallback"
      (let [out (sut/resolved-compute {:a 1} fallback {:ip "1.2.3.4" :name "box"})]
        (is (= {:a 1 :provider "vultr" :ip "1.2.3.4" :user "root" :sudoer "root" :name "box"} out))))
    (testing "output-params keywordizes and otherwise leaves the map alone"
      (is (= {:ip "1.2.3.4" :ssh_key_id "77"}
             (sut/output-params {:tofu/outputs {:params {"ip" "1.2.3.4" "ssh_key_id" "77"}}})))
      (is (nil? (sut/output-params {:tofu/outputs {}})))
      (is (nil? (sut/output-params {}))))))

(deftest read-state-catches-only-the-step-error
  (testing "the reader's step error becomes :error"
    (is (= {:error "tofu output failed: boom"}
           (sut/read-state {} (fn [_] (throw (ex-info "tofu output failed: boom" {:dir "/x"})))))))
  (testing "a step error without a message reads as the fixed fallback string"
    (is (= {:error "state read failed without a message"}
           (sut/read-state {} (fn [_] (throw (ex-info nil {:dir "/x"}))))))
    (is (= {:error "state read failed without a message"}
           (sut/read-state {} (fn [_] (throw (ex-info "" {:dir "/x"})))))))
  (testing "nil from the reader is a readable state holding nothing"
    (is (= {:params nil} (sut/read-state {} (constantly nil)))))
  (testing "a launch failure reaches here as the SDK's step error — green's tofu runner reports it as a failed exit"
    (is (= {:error "tofu output failed: Cannot run program \"tofu\" (in directory \"/nope\"): error=2"}
           (sut/read-state {} (fn [_] (throw (ex-info "tofu output failed: Cannot run program \"tofu\" (in directory \"/nope\"): error=2" {:dir "/nope"})))))))
  (testing "a raw IOException from the reader is not the SDK's shape and propagates"
    (is (thrown? java.io.IOException
                 (sut/read-state {} (fn [_] (throw (java.io.IOException. "Cannot run program \"tofu\"")))))))
  (testing "params pass through, and the reader sees opts"
    (is (= {:params {:ip "1.2.3.4" :seen "prod"}}
           (sut/read-state {:profile "prod"} (fn [o] {:ip "1.2.3.4" :seen (:profile o)})))))
  (testing "any other exception propagates"
    (is (thrown? RuntimeException
                 (sut/read-state {} (fn [_] (throw (RuntimeException. "defect"))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/read-state {} (fn [_] (throw (ex-info "not a step error" {}))))))))

(deftest provider-validator-pre-empts-the-thunk-on-a-mismatch
  (let [called (atom 0)
        thunk (fn [] (swap! called inc) ["required credential is not set: COLORS_PAR_VULTR_API_KEY"])]
    (testing "mismatch pre-empts the thunk"
      (is (= ["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
             (sut/provider-validator spec (vultr) {:provider "digitalocean"} thunk)))
      (is (zero? @called)))
    (testing "match calls it"
      (is (= ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]
             (sut/provider-validator spec (vultr) {:provider "vultr"} thunk)))
      (is (= 1 @called)))
    (testing "no state calls it"
      (is (= ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]
             (sut/provider-validator spec (vultr) nil thunk)))
      (is (= 2 @called)))))

(defn- tmp-home []
  (str (java.nio.file.Files/createTempDirectory
        "once-compute-test"
        (into-array java.nio.file.attribute.FileAttribute []))))

(deftest adopt-state-fails-closed-and-adopts-the-recorded-address
  (let [opt-out (vultr :vultr-ssh-keys "key-uuid")]
    (testing "error exits 1 with the delete wording and the reason"
      (let [out (sut/adopt-state opt-out :delete {:error "HTTP 403 from backend"})]
        (is (= 1 (:green/exit out)))
        (is (= (str "could not read the infrastructure state for the delete cleanup: HTTP 403 from backend\n"
                    "fix the backend credentials and retry; a delete that cannot see its state has nothing to address")
               (:green/err out)))))
    (testing "rehearse wording"
      (let [out (sut/adopt-state opt-out :rehearse {:error "HTTP 403 from backend"})]
        (is (= 1 (:green/exit out)))
        (is (= (str "could not read the infrastructure state for rehearse: HTTP 403 from backend\n"
                    "fix the backend credentials and retry; a rehearse that cannot see its state has nothing to address")
               (:green/err out)))))
    (testing "params merged; an :ip already in opts does not override the recorded address; ssh_key_id kept as written"
      (let [out (sut/adopt-state (assoc opt-out :ip "9.9.9.9") :delete
                                 {:params {:ip "1.2.3.4" :ssh_key_id "77" :provider "vultr"}})]
        (is (= 0 (:green/exit out)))
        (is (= "1.2.3.4" (:ip out)))
        (is (= "77" (:ssh_key_id out)))
        (is (not (contains? out :ssh-keygen)) "opt-out opts pass through with-machine-key untouched")))
    (testing "a readable state holding nothing leaves :ip unset"
      (let [out (sut/adopt-state opt-out :delete {:params nil})]
        (is (= 0 (:green/exit out)))
        (is (not (contains? out :ip)))))
    (testing "keygen mode fills the machine key through once.ssh, never touching the real ~/.ssh"
      (let [dir (tmp-home)]
        (with-redefs [ssh/home-dir (constantly dir)]
          (let [out (sut/adopt-state (vultr) :delete {:params {:ip "1.2.3.4"}})]
            (is (= 0 (:green/exit out)))
            (is (true? (:ssh-keygen out)))
            (is (= (str (io/file dir ".ssh" "prod.pub")) (:vultr-ssh-keys out)))
            (is (str/starts-with? (:ssh-private-key-path out) dir))))))))

(deftest fallback-params-carry-the-provider-and-lifecycle-event-covers-the-four-combinations
  (is (= {:provider "vultr" :ip "192.0.2.10" :user "root" :sudoer "root" :name "prod"}
         (sut/fallback-params (vultr))))
  (is (= {:provider "digitalocean" :ip "192.0.2.10" :user "root" :sudoer "root" :name "box"}
         (sut/fallback-params (digitalocean :digitalocean-name "box"))))
  (is (true? (sut/lifecycle-event? {:event :create :real? true})))
  (is (true? (sut/lifecycle-event? {:event :delete :real? true})))
  (is (false? (sut/lifecycle-event? {:event :create :real? false})))
  (is (false? (sut/lifecycle-event? {:event :build :real? true}))))
