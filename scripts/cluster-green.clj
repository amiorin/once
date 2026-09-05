;; Drive the multi-node contract — roles and counts, the fallback addresses,
;; the node-id refusals, the created and discovered network rules, the
;; cluster read and adoption — through green's `compute-cluster` module with
;; a three-provider stub spec, printing one normalized
;; `case <name> exit=<n> errors=<messages joined by " | ">` line per scenario
;; (value-bearing scenarios append ` value=<fields>`). Red and blue print the
;; same shape, so parity.sh can diff them: none of this logic reaches a build
;; artifact, and the messages are contract for every package that delegates
;; to ONCE. Exit is the real `:green/exit` where a scenario returns opts, 2
;; (the CLI's validation exit) where it returns messages, and 2 with the
;; exception message where a developer-facing check throws.
(require '[clojure.string :as str]
         '[io.github.getcolors.once.compute-cluster :as cluster])

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
;; three nodes from offset 254: the private addresses cross an octet inside a
;; /20 and the public ones run off the end of 192.0.2.0/24
(def high (assoc base :roles [{:role nil :count 3 :fallback-offset 254}]))
(def overlap (assoc base :roles [{:role "a" :count 2 :fallback-offset 10}
                                 {:role "b" :count 2 :fallback-offset 11}]))
(def one (assoc base :roles [{:role nil :count 1}]))
(def own (assoc homog :name-rules {"vultr" {:re #"^prod$" :message "must be prod"}}))
(def do-created (-> homog
                    (assoc-in [:registry "digitalocean" :network]
                              {:mode :created :key :digitalocean-vpc-cidr})
                    (dissoc :fallback-subnet)))

(defn vultr [& kvs]
  (merge {:profile "prod" :provider-compute "vultr"
          :vultr-ssh-sources ["10.0.0.0/8"] :vultr-vpc-subnet "10.40.0.0/24"}
         (apply hash-map kvs)))
(defn digitalocean [& kvs]
  (merge {:profile "prod" :provider-compute "digitalocean" :digitalocean-ssh-sources ["10.0.0.0/8"]}
         (apply hash-map kvs)))
(defn none [& kvs] (merge {:profile "prod" :provider-compute "none"} (apply hash-map kvs)))

(defn node [role index & kvs]
  (merge {:role role :index index
          :ip (str "203.0.113." (+ 10 index)) :vpc_ip (str "10.40.0." (+ 10 index))
          :name (str "n-" index) :user "root" :sudoer "root"}
         (apply hash-map kvs)))
(def homog-params {:provider "vultr" :ssh_key_id "77" :nodes [(node nil 0) (node nil 1) (node nil 2)]})

(defn line
  ([case-name exit errors] (line case-name exit errors nil))
  ([case-name exit errors value]
   (println (str "case " case-name " exit=" exit
                 " errors=" (str/join " | " (map #(str/replace % "\n" "\\n") errors))
                 (when value (str " value=" value))))))

(defn errs [case-name errors] (line case-name (if (empty? errors) 0 2) errors))
(defn out [case-name opts & [value]]
  (line case-name (or (:green/exit opts) 0) (remove nil? [(:green/err opts)]) value))
;; A developer-facing check throws; print its message as the one error.
(defn thrown [case-name f & [value]]
  (try (let [r (f)] (line case-name (if (empty? r) 0 2) r (when value (value r))))
       (catch clojure.lang.ExceptionInfo e (line case-name 2 [(ex-message e)]))))

(defn id-str [id] (cluster/node-id-str id))
(defn node-str [n]
  (str (id-str n) "=" (:name n) "|" (:ip n) "|" (if (contains? n :vpc_ip) (:vpc_ip n) "-")
       "|" (:user n) "|" (:sudoer n)))
(defn nodes-str [nodes] (str/join "," (map node-str nodes)))
(defn hosts-str [hosts] (str/join "," (map #(str (:name %) "=" (:ip %)) hosts)))

;; --- spec-errors
(thrown "spec-homog-ok" #(cluster/spec-errors homog))
(thrown "spec-roles-ok" #(cluster/spec-errors roles))
(thrown "spec-roles-empty" #(cluster/spec-errors (assoc base :roles [])))
(thrown "spec-roles-absent" #(cluster/spec-errors base))
(thrown "spec-nil-role-not-alone"
        #(cluster/spec-errors (assoc base :roles [{:role nil :count 1} {:role "app" :count 1}])))
(thrown "spec-role-bad-name" #(cluster/spec-errors (assoc base :roles [{:role "Foo" :count 1}])))
(thrown "spec-role-duplicate"
        #(cluster/spec-errors (assoc base :roles [{:role "app" :count 1} {:role "app" :count 2}])))
(thrown "spec-role-alias-collision"
        #(cluster/spec-errors (assoc base :roles [{:role "foo" :count 2} {:role "foo-0" :count 1}])))
(thrown "spec-count-not-positive" #(cluster/spec-errors (assoc base :roles [{:role "app" :count 0}])))
(thrown "spec-count-absent-nil-role" #(cluster/spec-errors (assoc base :roles [{:role nil :count-key :n}])))
(thrown "spec-offset-not-integer"
        #(cluster/spec-errors (assoc base :roles [{:role "app" :count 1 :fallback-offset "12"}])))
(thrown "spec-entry-incomplete" #(cluster/spec-errors (assoc homog :entry {:index 0})))
(thrown "spec-entry-unresolved" #(cluster/spec-errors (assoc roles :entry {:role "web" :index 0})))
(thrown "spec-entry-bad-index" #(cluster/spec-errors (assoc roles :entry {:role "app" :index -1})))
(thrown "spec-entry-index-beyond-static-count" #(cluster/spec-errors (assoc roles :entry {:role "app" :index 9})))
;; the static count (3) admits index 2; the count-key override (2) does not:
;; spec-errors passes and the refusal is topology-errors'
(thrown "spec-entry-index-beyond-count-is-topology"
        #(let [s (assoc homog :entry {:role nil :index 2})]
           (cluster/spec-errors s)
           (cluster/topology-errors s (vultr :node-count 2))))
(thrown "spec-fallback-subnet-non-canonical" #(cluster/spec-errors (assoc homog :fallback-subnet "10.110.0.1/20")))
(thrown "spec-fallback-subnet-not-permitted"
        #(cluster/spec-errors (assoc homog :registry (dissoc registry "digitalocean"))))

;; --- ids and counts
(line "node-ids-homog" 0 [] (str/join "," (map id-str (cluster/node-ids homog (vultr)))))
(line "node-ids-roles" 0 [] (str/join "," (map id-str (cluster/node-ids roles (vultr)))))
(line "node-count-present-valid" 0 [] (str (cluster/node-count homog (vultr :node-count 5) nil)))
(line "node-count-absent-default" 0 [] (str (cluster/node-count homog (vultr) nil)))
(line "node-count-present-string-as-is" 0 [] (str (cluster/node-count homog (vultr :node-count "3") nil)))
(line "node-count-fixed-role" 0 [] (str (cluster/node-count roles (vultr :clickhouse-count 5) "app")))
(line "node-id-str" 0 []
      (str/join "," (map id-str [{:role nil :index 0} {:role "app" :index 2}
                                 {:role nil :index nil} {:role "app" :index nil}])))
(line "entry-id" 0 [] (str (id-str (cluster/entry-id homog)) ";" (id-str (cluster/entry-id roles))))

;; --- topology-errors
(errs "topology-homog-ok" (cluster/topology-errors homog (vultr)))
(errs "topology-roles-ok" (cluster/topology-errors roles (digitalocean)))
(errs "topology-count-zero" (cluster/topology-errors homog (vultr :node-count 0)))
(errs "topology-count-string" (cluster/topology-errors homog (vultr :node-count "3")))
(errs "topology-count-negative-pre-empts"
      (cluster/topology-errors (assoc roles :entry {:role "app" :index 9}) (vultr :clickhouse-count -1)))
(errs "topology-entry-outside-homog"
      (cluster/topology-errors (assoc homog :entry {:role nil :index 3}) (vultr)))
(errs "topology-entry-outside-roles"
      (cluster/topology-errors (assoc roles :entry {:role "clickhouse" :index 2}) (vultr :clickhouse-count 2)))
(errs "topology-fallback-subnet-required" (cluster/topology-errors (dissoc homog :fallback-subnet) (digitalocean)))
(errs "topology-fallback-subnet-not-required-created"
      (cluster/topology-errors (dissoc homog :fallback-subnet) (vultr)))
(errs "topology-offsets-overlap" (cluster/topology-errors overlap (vultr)))
(errs "topology-offsets-overlap-no-network" (cluster/topology-errors overlap (none)))
(errs "topology-public-outside-none" (cluster/topology-errors high (none)))
(errs "topology-public-outside-created-slash-20"
      (cluster/topology-errors high (vultr :vultr-vpc-subnet "10.40.0.0/20")))
(errs "topology-public-and-private-outside-discovered"
      (cluster/topology-errors (assoc high :fallback-subnet "10.110.0.0/24") (digitalocean)))
(errs "topology-name-rule-rejects" (cluster/topology-errors own (vultr)))
(let [long-profile (apply str (repeat 62 "a"))]
  (errs "topology-name-too-long-vultr" (cluster/topology-errors one (vultr :profile long-profile)))
  (errs "topology-name-too-long-no-rule" (cluster/topology-errors one (none :profile long-profile)))
  (errs "topology-name-63-ok" (cluster/topology-errors one (vultr :profile (apply str (repeat 61 "a"))))))

;; --- network-errors
(errs "network-created-ok" (cluster/network-errors homog (vultr)))
(errs "network-discovered-ok" (cluster/network-errors homog (digitalocean)))
(errs "network-none-ok" (cluster/network-errors homog (none)))
(errs "network-created-key-missing" (cluster/network-errors homog (vultr :vultr-vpc-subnet nil)))
(errs "network-created-key-placeholder" (cluster/network-errors homog (vultr :vultr-vpc-subnet "REPLACE_ME")))
(errs "network-created-non-canonical" (cluster/network-errors homog (vultr :vultr-vpc-subnet "10.40.0.1/24")))
(errs "network-created-ipv6" (cluster/network-errors homog (vultr :vultr-vpc-subnet "2001:db8::/64")))
(errs "network-created-no-prefix" (cluster/network-errors homog (vultr :vultr-vpc-subnet "10.40.0.0")))
(errs "network-created-offset-outside" (cluster/network-errors homog (vultr :vultr-vpc-subnet "10.40.0.0/29")))
(errs "network-created-slash-28-holds-three" (cluster/network-errors homog (vultr :vultr-vpc-subnet "10.40.0.0/28")))
(errs "network-created-slash-28-broadcast"
      (cluster/network-errors homog (vultr :vultr-vpc-subnet "10.40.0.0/28" :node-count 6)))
(errs "network-created-slash-24-crossing-refused" (cluster/network-errors high (vultr)))
(errs "network-created-slash-20-crossing-holds" (cluster/network-errors high (vultr :vultr-vpc-subnet "10.40.0.0/20")))
(errs "network-created-invalid-count-skipped" (cluster/network-errors homog (vultr :node-count "3")))
(errs "network-created-slash-0" (cluster/network-errors homog (vultr :vultr-vpc-subnet "0.0.0.0/0")))
(errs "network-created-slash-31" (cluster/network-errors homog (vultr :vultr-vpc-subnet "10.0.0.0/31")))
(errs "network-created-slash-32" (cluster/network-errors homog (vultr :vultr-vpc-subnet "10.0.0.1/32")))
(errs "network-fallback-subnet-non-canonical"
      (cluster/network-errors (assoc homog :fallback-subnet "10.110.0.1/20") (digitalocean)))

;; --- fallbacks
(line "fallback-homog-vultr-24" 0 [] (nodes-str (cluster/fallback-nodes homog (vultr))))
(line "fallback-homog-vultr-20" 0 [] (nodes-str (cluster/fallback-nodes homog (vultr :vultr-vpc-subnet "10.40.0.0/20"))))
(line "fallback-homog-do-20" 0 [] (nodes-str (cluster/fallback-nodes homog (digitalocean))))
(line "fallback-homog-do-24" 0 []
      (nodes-str (cluster/fallback-nodes (assoc homog :fallback-subnet "10.110.0.0/24") (digitalocean))))
(line "fallback-roles-vultr-24" 0 [] (nodes-str (cluster/fallback-nodes roles (vultr))))
(line "fallback-roles-vultr-20" 0 [] (nodes-str (cluster/fallback-nodes roles (vultr :vultr-vpc-subnet "10.40.0.0/20"))))
(line "fallback-roles-do-20" 0 [] (nodes-str (cluster/fallback-nodes roles (digitalocean))))
(line "fallback-roles-do-24" 0 []
      (nodes-str (cluster/fallback-nodes (assoc roles :fallback-subnet "10.110.0.0/24") (digitalocean))))
(line "fallback-roles-none" 0 [] (nodes-str (cluster/fallback-nodes roles (none))))
(line "fallback-roles-count-one-drops-index" 0 [] (nodes-str (cluster/fallback-nodes roles (vultr :clickhouse-count 1))))
(line "fallback-compute-name-base" 0 [] (nodes-str (cluster/fallback-nodes homog (vultr :vultr-name " box "))))
(line "fallback-slash-20-crosses-octet" 0 [] (nodes-str (cluster/fallback-nodes high (vultr :vultr-vpc-subnet "10.40.0.0/20"))))
(line "fallback-unparsable-subnet-omits-vpc-ip" 0 [] (nodes-str (cluster/fallback-nodes homog (vultr :vultr-vpc-subnet "nope"))))
(line "fallback-node-name-and-offset" 0 []
      (str/join ";" [(cluster/fallback-node-name roles (vultr) {:role "clickhouse" :index 1})
                     (cluster/fallback-node-name roles (vultr :clickhouse-count 1) {:role "clickhouse" :index 0})
                     (cluster/fallback-offset roles (vultr) "app")
                     (cluster/fallback-offset roles (vultr) "redis")
                     (cluster/fallback-offset roles (vultr :clickhouse-count 5) "app")]))

;; --- node-errors
(defn ne [case-name spec opts params]
  (let [e (cluster/node-errors spec opts params)]
    (line case-name (if (empty? e) 0 2) (or e []) (when (nil? e) "nil"))))
(ne "node-errors-params-nil" homog (vultr) nil)
(ne "node-errors-complete" homog (vultr) homog-params)
(ne "node-errors-empty-nodes" homog (vultr) {:provider "vultr" :nodes []})
(ne "node-errors-nodes-absent" homog (vultr) {:provider "vultr"})
(ne "node-errors-missing-id" homog (vultr) {:nodes [(node nil 0) (node nil 2)]})
(ne "node-errors-extra-id" homog (vultr) {:nodes [(node nil 0) (node nil 1) (node nil 2) (node nil 3)]})
(ne "node-errors-duplicate-id" homog (vultr) {:nodes [(node nil 0) (node nil 1) (node nil 2) (node nil 1)]})
(ne "node-errors-undeclared-duplicate" homog (vultr)
    {:nodes [(node nil 0) (node nil 1) (node nil 2) (node nil 9) (node nil 9)]})
(ne "node-errors-without-name" homog (vultr) {:nodes [(node nil 0) (dissoc (node nil 1) :name) (node nil 2)]})
(let [without (mapv #(dissoc % :vpc_ip) (:nodes homog-params))]
  (ne "node-errors-without-vpc-ip-none" homog (none) {:nodes without})
  (ne "node-errors-without-vpc-ip-created" homog (vultr) {:nodes without})
  (ne "node-errors-without-vpc-ip-discovered" homog (digitalocean) {:nodes without}))
(ne "node-errors-blank-ip" homog (vultr) {:nodes [(node nil 0 :ip "") (node nil 1) (node nil 2)]})
(ne "node-errors-null-name" homog (vultr) {:nodes [(node nil 0) (node nil 1 :name nil) (node nil 2)]})
(ne "node-errors-whitespace-and-non-string" homog (vultr)
    {:nodes [(node nil 0 :sudoer "  ") (node nil 1 :user 7) (node nil 2)]})
(ne "node-errors-legacy-null-index" one (vultr) {:nodes [(assoc (node nil 0) :index nil)]})
(ne "node-errors-string-index-undeclared" one (vultr) {:nodes [(assoc (node nil 0) :index "0")]})
(ne "node-errors-roles-ok" roles (vultr)
    {:nodes [(node "app" 0) (node "clickhouse" 2) (node "clickhouse" 0) (node "clickhouse" 1)
             (node "redis" 0) (node "neon" 0)]})
(ne "node-errors-roles-missing-and-extra" roles (vultr)
    {:nodes [(node "app" 0) (node "clickhouse" 1) (node "clickhouse" 0) (node "redis" 0) (node "web" 0)]})
(ne "node-errors-all-classes-in-order" homog (vultr)
    {:nodes [(node nil 0) (node nil 1 :ip nil) (node nil 0) (node nil 9)]})

;; --- nodes
(line "nodes-fallback-when-nil" 0 [] (nodes-str (cluster/nodes homog (vultr) nil)))
(let [params {:provider "digitalocean" :reserved_ip "203.0.113.7"
              :nodes [(node "app" 0 :droplet_id "3") (node "clickhouse" 2 :droplet_id "2")
                      (node "clickhouse" 0) (node "clickhouse" 1) (node "redis" 0) (node "neon" 0)]}
      ns (cluster/nodes roles (digitalocean) params)]
  (line "nodes-from-state-preserve-extras" 0 []
        (str (nodes-str ns) ";droplet_ids="
             (str/join "," (map #(if (contains? % :droplet_id) (:droplet_id %) "-") ns)))))
(thrown "nodes-throws-on-partial" #(cluster/nodes homog (vultr) {:nodes [(node nil 0) (node nil 1)]}))

;; --- output-params and the re-exports
(let [p (cluster/output-params {:tofu/outputs {:params {"provider" "vultr" "ssh_key_id" "77" "reserved_ip" "203.0.113.7"
                                                        "nodes" [{"ip" "1.2.3.4" "vpc_ip" "10.0.0.4" "index" 0 "role" nil "droplet_id" "9"}]}}})]
  (line "output-params" 0 []
        (str/join ";" [(:provider p) (:ssh_key_id p) (:reserved_ip p)
                       (:ip (first (:nodes p))) (:vpc_ip (first (:nodes p))) (:droplet_id (first (:nodes p)))
                       (nil? (cluster/output-params {}))])))
(let [r (cluster/read-state (vultr) (fn [_] (throw (ex-info "tofu output failed: boom" {:dir "/state"}))))]
  (line "read-state-error" 1 [(:error r)]))
(let [r (cluster/read-state (vultr) (fn [_] nil))]
  (line "read-state-nil" 0 [] (str "params:" (if (contains? r :params) "none" "absent"))))
(errs "provider-state-mismatch" (cluster/provider-state-errors homog (vultr) {:provider "digitalocean"}))
(errs "provider-state-match" (cluster/provider-state-errors homog (vultr) homog-params))
(let [called (atom 0)
      thunk (fn [] (swap! called inc) ["required credential is not set: COLORS_PAR_VULTR_API_KEY"])
      v (fn [case-name params]
          (let [e (cluster/provider-validator homog (vultr) params thunk)]
            (line case-name (if (empty? e) 0 2) e (str "thunk-calls:" @called))))]
  (v "validator-mismatch-before-secrets" {:provider "digitalocean"})
  (v "validator-match" homog-params)
  (v "validator-no-state" nil))

;; --- resolved-cluster
(let [fallback {:once/cluster {:nodes (cluster/fallback-nodes homog (vultr))}}]
  (out "resolved-nil-outputs" (cluster/resolved-cluster homog (vultr) {:a 1} fallback nil))
  (out "resolved-partial" (cluster/resolved-cluster homog (vultr) {} fallback {:nodes [(node nil 0 :ip "") (node nil 9)]}))
  (let [outputs (assoc homog-params :reserved_ip "203.0.113.7")
        o (cluster/resolved-cluster homog (vultr) {:a 1} fallback outputs)]
    (out "resolved-ok" o (str "a:" (:a o) ";reserved_ip:" (get-in o [:once/cluster :reserved_ip])
                              ";nodes:" (nodes-str (get-in o [:once/cluster :nodes]))
                              ";fallback-replaced:" (not= (:once/cluster fallback) (:once/cluster o))))))

;; --- adopt-state (opt-out opts: with-machine-key leaves them untouched)
(let [opt-out (vultr :vultr-ssh-keys "key-uuid")]
  (out "adopt-delete-error" (cluster/adopt-state homog opt-out :delete {:error "HTTP 403 from backend"}))
  (out "adopt-describe-error" (cluster/adopt-state homog opt-out :describe {:error "HTTP 403 from backend"}))
  (out "adopt-partial" (cluster/adopt-state homog opt-out :delete {:params {:nodes [(node nil 0)]}}))
  (let [params (assoc homog-params :reserved_ip "203.0.113.7"
                      :nodes [(node nil 0 :droplet_id "1") (node nil 1 :droplet_id "2") (node nil 2 :droplet_id "3")])
        o (cluster/adopt-state homog (assoc opt-out :ip "9.9.9.9") :delete {:params params})]
    (out "adopt-success-extras" o
         (str "cluster-equals-params:" (= params (:once/cluster o))
              ";reserved_ip:" (get-in o [:once/cluster :reserved_ip])
              ";droplet_ids:" (str/join "," (map :droplet_id (get-in o [:once/cluster :nodes])))
              ";ip:" (:ip o) ";nodes-flattened:" (contains? o :nodes) ";keygen:" (contains? o :ssh-keygen))))
  (let [o (cluster/adopt-state homog opt-out :delete {:params nil})]
    (out "adopt-no-params" o (str "cluster:" (contains? o :once/cluster)))))

;; --- aliases and ssh-config-hosts
(line "aliases-homog" 0 [] (str/join "," (cluster/aliases homog (vultr))))
(line "aliases-homog-count-one" 0 [] (str/join "," (cluster/aliases homog (vultr :node-count 1))))
(line "aliases-roles" 0 [] (str/join "," (cluster/aliases roles (vultr))))
(line "aliases-roles-count-one" 0 [] (str/join "," (cluster/aliases roles (vultr :clickhouse-count 1))))
(line "aliases-follow-profile-not-name" 0 [] (str/join "," (cluster/aliases homog (vultr :vultr-name "box"))))
(line "ssh-config-hosts-homog" 0 [] (hosts-str (cluster/ssh-config-hosts homog (vultr) (cluster/nodes homog (vultr) nil))))
(line "ssh-config-hosts-roles" 0 [] (hosts-str (cluster/ssh-config-hosts roles (vultr) (cluster/nodes roles (vultr) nil))))
(line "ssh-config-hosts-from-state" 0 []
      (hosts-str (cluster/ssh-config-hosts homog (vultr) (cluster/nodes homog (vultr) homog-params))))

;; --- state-errors
(thrown "state-errors-spec-throws" #(cluster/state-errors base (vultr)))
(thrown "state-errors-homog-ok" #(cluster/state-errors homog (vultr)))
(thrown "state-errors-roles-ok" #(cluster/state-errors roles (digitalocean)))
(thrown "state-errors-unselected" #(cluster/state-errors homog {:provider-compute "hetzner"}))
(thrown "state-errors-order"
        #(cluster/state-errors homog (vultr :vultr-ssh-sources ["nope"] :vultr-vpc-subnet "10.40.0.1/24"
                                            :node-count 0)))
(thrown "state-errors-discovered-keeps-do-vpc"
        #(cluster/state-errors homog (digitalocean :digitalocean-vpc-uuid "vpc-123"
                                                   :digitalocean-vpc-cidr "10.50.0.0/24")))
(thrown "state-errors-created-filters-do-vpc"
        #(cluster/state-errors do-created (digitalocean :digitalocean-vpc-cidr "10.50.0.0/24")))
(thrown "state-errors-created-checks-own-key"
        #(cluster/state-errors do-created (digitalocean :digitalocean-vpc-cidr "10.50.0.1/24")))
(thrown "state-errors-created-key-required"
        #(cluster/state-errors do-created (digitalocean :digitalocean-vpc-uuid "vpc-123")))
