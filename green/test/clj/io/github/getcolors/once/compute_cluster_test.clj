(ns io.github.getcolors.once.compute-cluster-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.once.compute-cluster :as sut]
   [io.github.getcolors.once.ssh :as ssh]))

;; A three-provider stub registry: a created network on Vultr, a discovered
;; one on DigitalOcean, and a provider with no network at all. The same stub
;; drives red and blue and the cluster parity driver.
(def registry
  {"vultr" {:required [:vultr-region :vultr-plan :vultr-os-id :vultr-ssh-sources :vultr-vpc-subnet]
            :secrets [:vultr-api-key]
            :tofu-env {:vultr-api-key "VULTR_API_KEY"}
            :network {:mode :created :key :vultr-vpc-subnet}}
   "digitalocean" {:required [:digitalocean-region :digitalocean-size :digitalocean-image
                              :digitalocean-ssh-sources]
                   :secrets [:do-token]
                   :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}
                   :network {:mode :discovered}}
   "none" {:required [] :secrets [] :tofu-env {}}})

(def base {:registry registry
           :default "vultr"
           :sources {:non-empty ["ssh-sources"] :may-be-empty []}
           :fallback-subnet "10.110.0.0/20"})

(def homog (assoc base :roles [{:role nil :count-key :node-count :count 3}]))

(def roles (assoc base
                  :roles [{:role "neon" :count 1}
                          {:role "redis" :count 1}
                          {:role "clickhouse" :count-key :clickhouse-count :count 3 :fallback-offset 20}
                          {:role "app" :count 1 :fallback-offset 12}]
                  :entry {:role "app" :index 0}))

(defn- vultr [& kvs]
  (merge {:profile "prod" :provider-compute "vultr"
          :vultr-ssh-sources ["10.0.0.0/8"] :vultr-vpc-subnet "10.40.0.0/24"}
         (apply hash-map kvs)))

(defn- digitalocean [& kvs]
  (merge {:profile "prod" :provider-compute "digitalocean" :digitalocean-ssh-sources ["10.0.0.0/8"]}
         (apply hash-map kvs)))

(defn- none [& kvs]
  (merge {:profile "prod" :provider-compute "none"} (apply hash-map kvs)))

(defn- node [role index & kvs]
  (merge {:role role :index index :ip (str "203.0.113." (+ 10 index)) :vpc_ip (str "10.40.0." (+ 10 index))
          :name (str "n-" index) :user "root" :sudoer "root"}
         (apply hash-map kvs)))

(def homog-params
  {:provider "vultr" :ssh_key_id "77" :nodes [(node nil 0) (node nil 1) (node nil 2)]})

(defn- spec-message [spec]
  (try (sut/spec-errors spec) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest spec-errors-throw-on-the-first-static-problem-and-pass-both-shapes
  (is (= [] (sut/spec-errors homog)))
  (is (= [] (sut/spec-errors roles)))
  (is (= ":roles must be a non-empty vector" (spec-message (assoc base :roles []))))
  (is (= ":roles must be a non-empty vector" (spec-message base)))
  (is (= "the nil role must be the only entry in :roles"
         (spec-message (assoc base :roles [{:role nil :count 1} {:role "app" :count 1}]))))
  (is (= "role \"Foo\" must match ^[a-z][a-z0-9]*(-[a-z0-9]+)*$"
         (spec-message (assoc base :roles [{:role "Foo" :count 1}]))))
  (is (= "role \"foo-\" must match ^[a-z][a-z0-9]*(-[a-z0-9]+)*$"
         (spec-message (assoc base :roles [{:role "foo-" :count 1}]))))
  (is (= "role \"app\" is declared more than once"
         (spec-message (assoc base :roles [{:role "app" :count 1} {:role "app" :count 2}]))))
  (is (= "role \"foo-0\" reads as an alias of role \"foo\""
         (spec-message (assoc base :roles [{:role "foo" :count 2} {:role "foo-0" :count 1}]))))
  (is (= ":count of role \"app\" must be a positive integer"
         (spec-message (assoc base :roles [{:role "app" :count 0}]))))
  (is (= ":count of the nil role must be a positive integer"
         (spec-message (assoc base :roles [{:role nil :count-key :n}]))))
  (is (= ":fallback-offset of role \"app\" must be a non-negative integer"
         (spec-message (assoc base :roles [{:role "app" :count 1 :fallback-offset "12"}]))))
  (testing "entry"
    (is (= ":entry must carry :role and :index" (spec-message (assoc homog :entry {:index 0}))))
    (is (= ":entry :role must name a declared role" (spec-message (assoc roles :entry {:role "web" :index 0}))))
    (is (= ":entry :index must be a non-negative integer" (spec-message (assoc roles :entry {:role "app" :index -1}))))
    (is (= [] (sut/spec-errors (assoc homog :entry {:role nil :index 7})))
        "the index against the count is topology-errors' job"))
  (testing "fallback-subnet"
    (is (= ":fallback-subnet must be a canonical IPv4 network such as 10.40.0.0/24"
           (spec-message (assoc homog :fallback-subnet "10.110.0.1/20"))))
    (is (= ":fallback-subnet is permitted only when an advertised provider's network is discovered"
           (spec-message (assoc homog :registry (dissoc registry "digitalocean")))))
    (is (= [] (sut/spec-errors (dissoc homog :fallback-subnet))))))

(deftest node-ids-and-counts-follow-the-roles-and-the-present-count-key
  (is (= [{:role nil :index 0} {:role nil :index 1} {:role nil :index 2}] (sut/node-ids homog (vultr))))
  (is (= [{:role nil :index 0} {:role nil :index 1} {:role nil :index 2} {:role nil :index 3} {:role nil :index 4}]
         (sut/node-ids homog (vultr :node-count 5))))
  (is (= ["neon-0" "redis-0" "clickhouse-0" "clickhouse-1" "clickhouse-2" "app-0"]
         (map sut/node-id-str (sut/node-ids roles (vultr)))))
  (is (= 3 (sut/node-count homog (vultr) nil)) "absent key: the default")
  (is (= 5 (sut/node-count homog (vultr :node-count 5) nil)))
  (is (= "3" (sut/node-count homog (vultr :node-count "3") nil)) "a present value is used as-is")
  (is (= 1 (sut/node-count roles (vultr) "app")) "a fixed role ignores opts")
  (is (= "null" (sut/node-id-str {:role nil :index nil})))
  (is (= "app-null" (sut/node-id-str {:role "app" :index nil})))
  (is (= {:role nil :index 0} (sut/entry-id homog)))
  (is (= {:role "app" :index 0} (sut/entry-id roles))))

(deftest topology-errors-hold-counts-entry-subnet-addresses-and-names
  (is (= [] (sut/topology-errors homog (vultr))))
  (is (= [] (sut/topology-errors roles (digitalocean))))
  (testing "count key: zero, string, negative; nothing else is reported until it is fixed"
    (is (= [":node-count must be a positive integer"] (sut/topology-errors homog (vultr :node-count 0))))
    (is (= [":node-count must be a positive integer"] (sut/topology-errors homog (vultr :node-count "3"))))
    (is (= [":clickhouse-count must be a positive integer"]
           (sut/topology-errors (assoc roles :entry {:role "app" :index 9}) (vultr :clickhouse-count -1)))))
  (testing "entry outside the effective count"
    (is (= [":entry names 3, a node this topology does not declare"]
           (sut/topology-errors (assoc homog :entry {:role nil :index 3}) (vultr))))
    (is (= [":entry names clickhouse-2, a node this topology does not declare"]
           (sut/topology-errors (assoc roles :entry {:role "clickhouse" :index 2}) (vultr :clickhouse-count 2)))))
  (testing "fallback-subnet is required by a discovered network alone"
    (is (= [":fallback-subnet is required when the selected provider's network is discovered"]
           (sut/topology-errors (dissoc homog :fallback-subnet) (digitalocean))))
    (is (= [] (sut/topology-errors (dissoc homog :fallback-subnet) (vultr)))))
  (testing "overlapping explicit offsets collide on both address families"
    (let [overlap (assoc base :roles [{:role "a" :count 2 :fallback-offset 10}
                                      {:role "b" :count 2 :fallback-offset 11}])]
      (is (= ["the public fallback address 192.0.2.11 is generated for more than one node"
              "the private fallback address 10.40.0.11 is generated for more than one node"]
             (sut/topology-errors overlap (vultr))))
      (is (= ["the public fallback address 192.0.2.11 is generated for more than one node"]
             (sut/topology-errors overlap (none)))
          "no network, no private addresses")))
  (testing "the public range is checked here for every mode"
    (let [high (assoc base :roles [{:role nil :count 3 :fallback-offset 254}])]
      (is (= ["192.0.2.0/24 has no usable host address for 1, 2"]
             (sut/topology-errors high (none))))
      (is (= ["192.0.2.0/24 has no usable host address for 1, 2"]
             (sut/topology-errors high (vultr :vultr-vpc-subnet "10.40.0.0/20")))
          "the created range is network-errors' job")
      (is (= ["192.0.2.0/24 has no usable host address for 1, 2"
              ":fallback-subnet has no usable host address for 1, 2"]
             (sut/topology-errors (assoc high :fallback-subnet "10.110.0.0/24") (digitalocean)))
          "the discovered fallback subnet is checked here")))
  (testing "names: the provider's rule and the length, aliases the length"
    (let [own (assoc homog :name-rules {"vultr" {:re #"^prod$" :message "must be prod"}})]
      (is (= ["the fallback name \"prod-0\" must be prod"
              "the fallback name \"prod-1\" must be prod"
              "the fallback name \"prod-2\" must be prod"]
             (sut/topology-errors own (vultr)))))
    (let [long-profile (apply str (repeat 62 "a"))
          one (assoc base :roles [{:role nil :count 1}])]
      (is (= [(str "the fallback name \"" long-profile "-0\" must be a safe 1-63 character name")
              (str "the alias \"" long-profile "-0\" must be at most 63 characters")]
             (sut/topology-errors one (vultr :profile long-profile))))
      (is (= [(str "the fallback name \"" long-profile "-0\" must be at most 63 characters")
              (str "the alias \"" long-profile "-0\" must be at most 63 characters")]
             (sut/topology-errors one (none :profile long-profile)))
          "no rule for the provider: the length alone")
      (is (= [] (sut/topology-errors one (vultr :profile (apply str (repeat 61 "a")))))))))

(deftest network-errors-hold-the-created-key-and-the-fallback-subnet
  (is (= [] (sut/network-errors homog (vultr))))
  (is (= [] (sut/network-errors homog (digitalocean))))
  (is (= [] (sut/network-errors homog (none))))
  (is (= [":vultr-vpc-subnet is required"] (sut/network-errors homog (vultr :vultr-vpc-subnet nil))))
  (is (= [":vultr-vpc-subnet is required"] (sut/network-errors homog (vultr :vultr-vpc-subnet "REPLACE_ME"))))
  (is (= [":vultr-vpc-subnet must be a canonical IPv4 network such as 10.40.0.0/24"]
         (sut/network-errors homog (vultr :vultr-vpc-subnet "10.40.0.1/24"))))
  (is (= [":vultr-vpc-subnet must be a canonical IPv4 network such as 10.40.0.0/24"]
         (sut/network-errors homog (vultr :vultr-vpc-subnet "2001:db8::/64"))))
  (is (= [":vultr-vpc-subnet must be a canonical IPv4 network such as 10.40.0.0/24"]
         (sut/network-errors homog (vultr :vultr-vpc-subnet "10.40.0.0"))))
  (testing "every fallback offset must fit the usable host range"
    (is (= [":vultr-vpc-subnet has no usable host address for 0, 1, 2"]
           (sut/network-errors homog (vultr :vultr-vpc-subnet "10.40.0.0/29"))))
    (is (= [] (sut/network-errors homog (vultr :vultr-vpc-subnet "10.40.0.0/28")))
        "hosts 1-14 hold offsets 10, 11, 12")
    (is (= [":vultr-vpc-subnet has no usable host address for 5"]
           (sut/network-errors homog (vultr :vultr-vpc-subnet "10.40.0.0/28" :node-count 6)))
        "offset 15 is the broadcast address")
    (is (= [] (sut/network-errors roles (vultr :vultr-vpc-subnet "10.40.0.0/27"))))
    (let [high (assoc base :roles [{:role nil :count 3 :fallback-offset 254}])]
      (is (= [":vultr-vpc-subnet has no usable host address for 1, 2"]
             (sut/network-errors high (vultr))))
      (is (= [] (sut/network-errors high (vultr :vultr-vpc-subnet "10.40.0.0/20")))
          "a /20 holds the crossing")))
  (testing "an invalid count is reported by topology-errors, not here"
    (is (= [] (sut/network-errors homog (vultr :node-count "3")))))
  (testing "fallback-subnet under its own name"
    (is (= [":fallback-subnet must be a canonical IPv4 network such as 10.40.0.0/24"]
           (sut/network-errors (assoc homog :fallback-subnet "10.110.0.1/20") (digitalocean))))))

(deftest fallbacks-cut-both-families-from-the-offset-with-32-bit-arithmetic
  (is (= [{:role nil :index 0 :name "prod-0" :ip "192.0.2.10" :user "root" :sudoer "root" :vpc_ip "10.40.0.10"}
          {:role nil :index 1 :name "prod-1" :ip "192.0.2.11" :user "root" :sudoer "root" :vpc_ip "10.40.0.11"}
          {:role nil :index 2 :name "prod-2" :ip "192.0.2.12" :user "root" :sudoer "root" :vpc_ip "10.40.0.12"}]
         (sut/fallback-nodes homog (vultr))))
  (is (= ["prod-neon" "prod-redis" "prod-clickhouse-0" "prod-clickhouse-1" "prod-clickhouse-2" "prod-app"]
         (map :name (sut/fallback-nodes roles (vultr)))))
  (is (= ["192.0.2.10" "192.0.2.11" "192.0.2.20" "192.0.2.21" "192.0.2.22" "192.0.2.12"]
         (map :ip (sut/fallback-nodes roles (vultr)))))
  (is (= ["10.110.0.10" "10.110.0.11" "10.110.0.20" "10.110.0.21" "10.110.0.22" "10.110.0.12"]
         (map :vpc_ip (sut/fallback-nodes roles (digitalocean))))
      "discovered: the spec's fallback subnet")
  (is (= "box-clickhouse-1" (:name (nth (sut/fallback-nodes roles (vultr :vultr-name "box")) 3)))
      "compute's name supplies the base")
  (is (= "prod-clickhouse" (sut/fallback-node-name roles (vultr :clickhouse-count 1) {:role "clickhouse" :index 0}))
      "a role of count 1 drops the index")
  (testing "a /20 crosses the octet boundary"
    (let [high (assoc base :roles [{:role nil :count 3 :fallback-offset 254}])]
      (is (= ["10.40.0.254" "10.40.0.255" "10.40.1.0"]
             (map :vpc_ip (sut/fallback-nodes high (vultr :vultr-vpc-subnet "10.40.0.0/20")))))
      (is (= ["192.0.2.254" "192.0.2.255" "192.0.3.0"]
             (map :ip (sut/fallback-nodes high (vultr :vultr-vpc-subnet "10.40.0.0/20")))))))
  (testing "no network: no :vpc_ip key at all"
    (is (= [{:role nil :index 0 :name "prod-0" :ip "192.0.2.10" :user "root" :sudoer "root"}]
           (sut/fallback-nodes (assoc base :roles [{:role nil :count 1}]) (none)))))
  (testing "an unparsable created subnet leaves :vpc_ip absent; validation reports it"
    (is (not (contains? (first (sut/fallback-nodes homog (vultr :vultr-vpc-subnet "nope"))) :vpc_ip))))
  (testing "ipv4-network"
    (is (= {:cidr "10.40.0.0/24" :address 170393600 :prefix 24 :first 170393601 :last 170393854}
           (sut/ipv4-network "10.40.0.0/24")))
    (is (nil? (sut/ipv4-network "10.40.0.1/24")))
    (is (nil? (sut/ipv4-network "2001:db8::/32")))
    (is (nil? (sut/ipv4-network nil)))
    (is (> (:first (sut/ipv4-network "10.0.0.0/32")) (:last (sut/ipv4-network "10.0.0.0/32"))) "no usable host")))

(def complete-message
  "the compute stage did not report a complete node (ip, vpc_ip, name, user, sudoer) for ")

(deftest node-errors-report-the-four-classes-in-order
  (is (nil? (sut/node-errors homog (vultr) nil)))
  (is (= [] (sut/node-errors homog (vultr) homog-params)))
  (testing "an empty or absent nodes list reports every declared id missing"
    (is (= ["the compute stage did not report nodes this package declares: 0, 1, 2"]
           (sut/node-errors homog (vultr) {:provider "vultr" :nodes []})))
    (is (= ["the compute stage did not report nodes this package declares: 0, 1, 2"]
           (sut/node-errors homog (vultr) {:provider "vultr"}))))
  (is (= ["the compute stage did not report nodes this package declares: 1"]
         (sut/node-errors homog (vultr) {:nodes [(node nil 0) (node nil 2)]})))
  (is (= ["the compute stage reported nodes this package does not declare: 3"]
         (sut/node-errors homog (vultr) {:nodes [(node nil 0) (node nil 1) (node nil 2) (node nil 3)]})))
  (is (= ["the compute stage reported 1 more than once"]
         (sut/node-errors homog (vultr) {:nodes [(node nil 0) (node nil 1) (node nil 2) (node nil 1)]})))
  (is (= [(str complete-message "1; refusing to render a partial cluster")]
         (sut/node-errors homog (vultr) {:nodes [(node nil 0) (dissoc (node nil 1) :name) (node nil 2)]})))
  (testing "blank, null, whitespace and non-strings count as missing"
    (is (= [(str complete-message "0, 1, 2; refusing to render a partial cluster")]
           (sut/node-errors homog (vultr) {:nodes [(node nil 0 :ip "") (node nil 1 :name nil) (node nil 2 :user 7)]})))
    (is (= [(str complete-message "0; refusing to render a partial cluster")]
           (sut/node-errors homog (vultr) {:nodes [(node nil 0 :sudoer "  ") (node nil 1) (node nil 2)]}))))
  (testing "vpc_ip is required unless the network mode is :none"
    (let [without (mapv #(dissoc % :vpc_ip) (:nodes homog-params))]
      (is (= [(str complete-message "0, 1, 2; refusing to render a partial cluster")]
             (sut/node-errors homog (vultr) {:nodes without})))
      (is (= [(str complete-message "0, 1, 2; refusing to render a partial cluster")]
             (sut/node-errors homog (digitalocean) {:nodes without})))
      (is (= [] (sut/node-errors homog (none) {:nodes without})))))
  (testing "a legacy index: null is an undeclared id"
    (is (= ["the compute stage did not report nodes this package declares: 0"
            "the compute stage reported nodes this package does not declare: null"]
           (sut/node-errors (assoc base :roles [{:role nil :count 1}]) (vultr)
                            {:nodes [(assoc (node nil 0) :index nil)]}))))
  (testing "role-based ids render as role-index in declared order"
    (is (= ["the compute stage did not report nodes this package declares: neon-0, clickhouse-2"
            "the compute stage reported nodes this package does not declare: web-0"]
           (sut/node-errors roles (vultr)
                            {:nodes [(node "app" 0) (node "clickhouse" 1) (node "clickhouse" 0)
                                     (node "redis" 0) (node "web" 0)]}))))
  (testing "all four classes at once, in order"
    (is (= ["the compute stage did not report nodes this package declares: 2"
            "the compute stage reported nodes this package does not declare: 9"
            "the compute stage reported 0 more than once"
            (str complete-message "1; refusing to render a partial cluster")]
           (sut/node-errors homog (vultr)
                            {:nodes [(node nil 0) (node nil 1 :ip nil) (node nil 0) (node nil 9)]})))))

(deftest nodes-come-from-state-in-declared-order-with-extras-preserved
  (is (= (sut/fallback-nodes homog (vultr)) (sut/nodes homog (vultr) nil)))
  (let [params {:provider "digitalocean" :reserved_ip "203.0.113.7"
                :nodes [(node "app" 0 :droplet_id "3") (node "clickhouse" 2 :droplet_id "2")
                        (node "clickhouse" 0) (node "clickhouse" 1) (node "redis" 0) (node "neon" 0)]}
        out (sut/nodes roles (digitalocean) params)]
    (is (= ["neon-0" "redis-0" "clickhouse-0" "clickhouse-1" "clickhouse-2" "app-0"]
           (map sut/node-id-str out)))
    (is (= (node "app" 0 :droplet_id "3") (last out)) "verbatim, extras kept")
    (is (= "2" (:droplet_id (nth out 4)))))
  (testing "a partial state throws with the messages"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not report nodes this package declares: 2"
                          (sut/nodes homog (vultr) {:nodes [(node nil 0) (node nil 1)]})))))

(deftest output-params-and-the-re-exports-are-computes
  (is (= {:provider "vultr" :ssh_key_id "77" :nodes [{:ip "1.2.3.4" :vpc_ip "10.0.0.4" :index 0 :role nil}]}
         (sut/output-params {:tofu/outputs {:params {"provider" "vultr" "ssh_key_id" "77"
                                                     "nodes" [{"ip" "1.2.3.4" "vpc_ip" "10.0.0.4" "index" 0 "role" nil}]}}})))
  (is (nil? (sut/output-params {})))
  (is (= {:error "tofu output failed: boom"}
         (sut/read-state {} (fn [_] (throw (ex-info "tofu output failed: boom" {:dir "/x"}))))))
  (is (= {:params nil} (sut/read-state {} (constantly nil))))
  (is (= ["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
         (sut/provider-state-errors homog (vultr) {:provider "digitalocean"})))
  (let [called (atom 0)
        thunk (fn [] (swap! called inc) ["required credential is not set: COLORS_PAR_VULTR_API_KEY"])]
    (is (= ["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
           (sut/provider-validator homog (vultr) {:provider "digitalocean"} thunk)))
    (is (zero? @called) "the mismatch pre-empts the secrets")
    (is (= ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]
           (sut/provider-validator homog (vultr) homog-params thunk)))
    (is (= 1 @called))))

(deftest resolved-cluster-refuses-nil-and-partial-outputs
  (let [fallback {:once/cluster {:nodes (sut/fallback-nodes homog (vultr))}}]
    (testing "nil outputs"
      (let [out (sut/resolved-cluster homog (vultr) {:a 1} fallback nil)]
        (is (= 1 (:green/exit out)))
        (is (= "compute produced no params output; refusing to converge against the documentation addresses"
               (:green/err out)))
        (is (= 1 (:a out)))))
    (testing "partial outputs join the messages with a newline"
      (let [out (sut/resolved-cluster homog (vultr) {} fallback {:nodes [(node nil 0 :ip "") (node nil 9)]})]
        (is (= 1 (:green/exit out)))
        (is (= (str "the compute stage did not report nodes this package declares: 1, 2\n"
                    "the compute stage reported nodes this package does not declare: 9\n"
                    complete-message "0; refusing to render a partial cluster")
               (:green/err out)))))
    (testing "complete outputs replace the fallback under :once/cluster"
      (let [outputs (assoc homog-params :reserved_ip "203.0.113.7")
            out (sut/resolved-cluster homog (vultr) {:a 1} fallback outputs)]
        (is (= {:a 1 :once/cluster outputs} out))
        (is (not (contains? out :green/exit)))))))

(defn- tmp-home []
  (str (java.nio.file.Files/createTempDirectory
        "once-compute-cluster-test"
        (into-array java.nio.file.attribute.FileAttribute []))))

(deftest adopt-state-fails-closed-refuses-a-partial-cluster-and-adopts-params-verbatim
  (let [opt-out (vultr :vultr-ssh-keys "key-uuid")]
    (testing "error: compute's two-line message"
      (let [out (sut/adopt-state homog opt-out :delete {:error "HTTP 403 from backend"})]
        (is (= 1 (:green/exit out)))
        (is (= (str "could not read the infrastructure state for the delete cleanup: HTTP 403 from backend\n"
                    "fix the backend credentials and retry; a delete that cannot see its state has nothing to address")
               (:green/err out))))
      (is (str/starts-with? (:green/err (sut/adopt-state homog opt-out :describe {:error "x"}))
                            "could not read the infrastructure state for describe: x")))
    (testing "partial params exit 1 with the node errors"
      (let [out (sut/adopt-state homog opt-out :delete {:params {:nodes [(node nil 0)]}})]
        (is (= 1 (:green/exit out)))
        (is (= "the compute stage did not report nodes this package declares: 1, 2" (:green/err out)))
        (is (not (contains? out :once/cluster)))))
    (testing "complete params land verbatim under :once/cluster; nothing is flattened into opts"
      (let [params (assoc homog-params :reserved_ip "203.0.113.7")
            out (sut/adopt-state homog (assoc opt-out :ip "9.9.9.9") :delete {:params params})]
        (is (= 0 (:green/exit out)))
        (is (= params (:once/cluster out)))
        (is (= "9.9.9.9" (:ip out)) "no top-level :ip is adopted; the cluster is the whole map")
        (is (not (contains? out :nodes)))
        (is (not (contains? out :ssh-keygen)) "opt-out opts pass through with-machine-key untouched")))
    (testing "a readable state holding nothing leaves :once/cluster absent"
      (let [out (sut/adopt-state homog opt-out :delete {:params nil})]
        (is (= 0 (:green/exit out)))
        (is (not (contains? out :once/cluster)))))
    (testing "keygen mode fills the machine key through once.ssh"
      (let [dir (tmp-home)]
        (with-redefs [ssh/home-dir (constantly dir)]
          (let [out (sut/adopt-state homog (vultr) :delete {:params homog-params})]
            (is (= 0 (:green/exit out)))
            (is (true? (:ssh-keygen out)))
            (is (str/starts-with? (:vultr-ssh-keys out) dir))))))))

(deftest aliases-and-ssh-config-hosts-follow-the-shape-and-the-entry
  (is (= ["prod" "prod-0" "prod-1" "prod-2"] (sut/aliases homog (vultr))))
  (is (= ["prod" "prod-0"] (sut/aliases homog (vultr :node-count 1))))
  (is (= ["prod" "prod-neon" "prod-redis" "prod-clickhouse-0" "prod-clickhouse-1" "prod-clickhouse-2" "prod-app"]
         (sut/aliases roles (vultr))))
  (is (= ["prod" "prod-neon" "prod-redis" "prod-clickhouse" "prod-app"]
         (sut/aliases roles (vultr :clickhouse-count 1))))
  (is (= ["prod" "prod-0" "prod-1" "prod-2"] (sut/aliases homog (vultr :vultr-name "box")))
      "aliases follow the profile, never the compute name")
  (testing "hosts from the fallbacks: the bare profile points at the entry node"
    (is (= [{:name "prod" :ip "192.0.2.10"} {:name "prod-0" :ip "192.0.2.10"}
            {:name "prod-1" :ip "192.0.2.11"} {:name "prod-2" :ip "192.0.2.12"}]
           (sut/ssh-config-hosts homog (vultr) (sut/nodes homog (vultr) nil))))
    (is (= [{:name "prod" :ip "192.0.2.12"} {:name "prod-neon" :ip "192.0.2.10"}
            {:name "prod-redis" :ip "192.0.2.11"} {:name "prod-clickhouse-0" :ip "192.0.2.20"}
            {:name "prod-clickhouse-1" :ip "192.0.2.21"} {:name "prod-clickhouse-2" :ip "192.0.2.22"}
            {:name "prod-app" :ip "192.0.2.12"}]
           (sut/ssh-config-hosts roles (vultr) (sut/nodes roles (vultr) nil)))))
  (testing "hosts from state"
    (is (= [{:name "prod" :ip "203.0.113.10"} {:name "prod-0" :ip "203.0.113.10"}
            {:name "prod-1" :ip "203.0.113.11"} {:name "prod-2" :ip "203.0.113.12"}]
           (sut/ssh-config-hosts homog (vultr) (sut/nodes homog (vultr) homog-params))))))

(deftest state-errors-throw-on-the-spec-then-compose-compute-network-and-topology
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^:roles must be a non-empty vector$"
                        (sut/state-errors base (vultr))))
  (is (= [] (sut/state-errors homog (vultr))))
  (is (= [] (sut/state-errors roles (digitalocean))))
  (is (= [":provider-compute must be one of digitalocean, none, vultr"]
         (sut/state-errors homog {:provider-compute "hetzner"}))
      "nothing selected: compute's selection error alone")
  (testing "order: compute's, then network, then topology"
    (is (= [":vultr-ssh-sources entry \"nope\" is not an IPv4 or IPv6 CIDR"
            ":vultr-vpc-subnet must be a canonical IPv4 network such as 10.40.0.0/24"
            ":node-count must be a positive integer"]
           (sut/state-errors homog (vultr :vultr-ssh-sources ["nope"] :vultr-vpc-subnet "10.40.0.1/24"
                                          :node-count 0)))))
  (testing "a discovered entry keeps compute's DigitalOcean VPC refusals"
    (is (= [":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"
            ":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]
           (sut/state-errors homog (digitalocean :digitalocean-vpc-uuid "vpc-123"
                                                 :digitalocean-vpc-cidr "10.50.0.0/24")))))
  (testing "a created DigitalOcean entry drops them and checks its own key"
    (let [created (-> homog
                      (assoc-in [:registry "digitalocean" :network] {:mode :created :key :digitalocean-vpc-cidr})
                      (dissoc :fallback-subnet))]
      (is (= [] (sut/state-errors created (digitalocean :digitalocean-vpc-cidr "10.50.0.0/24"))))
      (is (= [":digitalocean-vpc-cidr must be a canonical IPv4 network such as 10.40.0.0/24"]
             (sut/state-errors created (digitalocean :digitalocean-vpc-cidr "10.50.0.1/24"))))
      (is (= [":digitalocean-vpc-cidr is required"]
             (sut/state-errors created (digitalocean :digitalocean-vpc-uuid "vpc-123")))
          "both refusals are dropped for a created entry; the key is then required"))))
