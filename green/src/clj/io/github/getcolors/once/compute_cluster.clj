(ns io.github.getcolors.once.compute-cluster
  "The multi-node contract the Compute Provider Standard defers in its §1,
  as a sibling of the single-node `compute` namespace: the same registry,
  the same selection, the same sources and name rules — called, never
  copied — plus what a cluster adds: roles and counts, one id per node, the
  fallback addresses `build` renders with, and a refusal for every state
  that does not describe the whole cluster.

  A package describes itself with the compute spec plus three keys:

  ```clojure
  {:registry compute-providers   ; entries as in compute, plus :network
   :default  \"vultr\"
   :sources  {:non-empty [\"ssh-sources\"] :may-be-empty []}
   :roles    [{:role nil :count-key :node-count :count 3}]
   :entry    {:role nil :index 0}        ; optional; default the first node
   :fallback-subnet \"10.110.0.0/20\"}   ; optional; discovered networks only
  ```

  `:roles` is a vector in play order. `:role` is a string, or nil for a
  homogeneous cluster (then the only entry). `:count-key` names the
  desired-state integer that sets the count and `:count` is the fixed
  count, or the default when the key is absent. `:fallback-offset` is the
  offset of the role's first fallback address; default 10 plus the number
  of nodes in the roles before it. A registry entry's `:network` is
  `{:mode :created :key <cidr key>}`, `{:mode :discovered}` or
  `{:mode :none}`; absent means `:none`.

  **The one representation of compute state is `params`**: ONCE reads
  exactly `provider`, `ssh_key_id` and `nodes`, and on every node the five
  fields `ip`, `vpc_ip`, `name`, `user`, `sudoer` plus its `role` and
  `index`. Node keys are spelled as `output-params` delivers them —
  keywordized with the underscore kept: `:ip :vpc_ip :name :user :sudoer
  :role :index`, never hyphenated — and fallback nodes use the same
  spelling so every later stage sees one shape. Anything else a package
  emits, on a node (`droplet_id`) or at the top level (`reserved_ip`,
  `vpc_id`, `vpc_ip_range`), is preserved verbatim under `:once/cluster`.

  `spec-errors` is developer-facing and throws; every other `-errors`
  function returns a vector of operator-facing messages that are contract,
  printed by `scripts/cluster-*` and diffed across colours."
  (:require
   [clojure.string :as str]
   [io.github.getcolors.once.compute :as compute]
   [io.github.getcolors.once.ssh :as ssh]
   [io.github.getcolors.once.validate :as validate]))

(def role-re
  "What a role may be called: lowercase, digits, single hyphens between
  words. Alias-safe, because `<profile>-<role>` and `<profile>-<role>-<n>`
  must not collide with `<profile>-<n>` or with another role."
  #"^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

(def public-fallback-network
  "TEST-NET-1: where `build` and `--dry-run` put every public fallback
  address, offset + index from its network address."
  "192.0.2.0/24")

(def ^:private canonical-message " must be a canonical IPv4 network such as 10.40.0.0/24")

(def no-params-message
  "The `resolved-cluster` refusal: a real converge never falls back."
  "compute produced no params output; refusing to converge against the documentation addresses")

(defn- non-blank-string? [x] (and (string? x) (not (str/blank? x))))

(defn- spec-error [msg]
  (throw (ex-info msg {:once/compute-cluster msg})))

;;; ------------------------------------------------------------ addresses

(defn- ipv4->long [s]
  (reduce (fn [acc octet] (+ (* acc 256) (Long/parseLong octet))) 0 (str/split s #"\.")))

(defn- long->ipv4 [n]
  (let [n (bit-and n 0xFFFFFFFF)]
    (str/join "." (map #(bit-and (bit-shift-right n %) 255) [24 16 8 0]))))

(defn ipv4-network
  "`s` parsed as a canonical IPv4 network — compute's `cidr?` grammar, IPv4
  only, host bits zero — as `{:cidr :address :prefix :first :last}` with
  the network address and the first and last usable host as 32-bit
  integers, or nil. A /31 or /32 parses and has no usable host."
  [s]
  (when (and (compute/cidr? s) (not (str/includes? (str s) ":")))
    (let [[address prefix] (str/split (str s) #"/")
          n (Long/parseLong prefix)
          a (ipv4->long address)
          mask (bit-and (bit-shift-left -1 (- 32 n)) 0xFFFFFFFF)
          size (bit-shift-left 1 (- 32 n))]
      (when (= a (bit-and a mask))
        {:cidr (str s) :address a :prefix n :first (inc a) :last (+ a size -2)}))))

;;; -------------------------------------------------------------- network

(defn network
  "The selected entry's network declaration; `{:mode :none}` when absent
  or when nothing is selected."
  [spec opts]
  (or (:network (compute/provider spec opts)) {:mode :none}))

(defn network-mode [spec opts] (:mode (network spec opts)))

(defn fallback-cidr
  "The CIDR the private fallback addresses are cut from: the created
  network's key value, the spec's `:fallback-subnet` for a discovered one,
  nil for none. On a real run the discovered CIDR is the package's
  `params.vpc_ip_range`; this exists for `build` alone."
  [spec opts]
  (let [{:keys [mode key]} (network spec opts)]
    (case mode
      :created (get opts key)
      :discovered (:fallback-subnet spec)
      nil)))

;;; ---------------------------------------------------------------- roles

(defn roles [spec] (vec (:roles spec)))

(defn- role-entry [spec role]
  (first (filter #(= role (:role %)) (roles spec))))

(defn node-count
  "How many nodes `role` (a declared role name, nil for the homogeneous
  role) has: the count key's value whenever the key is present in opts —
  whatever it is, validation refuses a present non-positive-integer before
  any derivation runs — and `:count` only when the key is absent or the
  role declares none."
  [spec opts role]
  (let [{:keys [count-key] :as entry} (role-entry spec role)]
    (if (and count-key (contains? opts count-key))
      (get opts count-key)
      (:count entry))))

(defn- counts-valid? [spec opts]
  (every? #(pos-int? (node-count spec opts (:role %))) (roles spec)))

(defn node-ids
  "`[{:role :index}]` over `:roles` in declared order, `index` 0-based per
  role. Assumes valid counts; run `topology-errors` first."
  [spec opts]
  (vec (for [{:keys [role]} (roles spec)
             i (range (node-count spec opts role))]
         {:role role :index i})))

(defn node-id-str
  "How an id renders in a message: `<index>` for the nil role,
  `<role>-<index>` otherwise. A nil index (a legacy state's `index: null`)
  renders as `null` in every colour."
  [{:keys [role index]}]
  (let [i (if (nil? index) "null" (str index))]
    (if (nil? role) i (str role "-" i))))

(defn- ids-str [ids] (str/join ", " (map node-id-str ids)))

(defn entry-id
  "The node the bare `<profile>` alias points to: the spec's `:entry`, else
  the first node of the first role."
  [spec]
  (if-let [entry (:entry spec)]
    (select-keys entry [:role :index])
    {:role (:role (first (roles spec))) :index 0}))

(defn fallback-offset
  "The offset of `role`'s first fallback address inside each fallback
  network: the role's `:fallback-offset`, else 10 plus the number of nodes
  in the roles declared before it."
  [spec opts role]
  (loop [[entry & more] (roles spec) before 0]
    (cond
      (nil? entry) nil
      (= role (:role entry)) (or (:fallback-offset entry) (+ 10 before))
      :else (recur more (+ before (node-count spec opts (:role entry)))))))

(defn- offset-address
  "network address + offset + index, as a 32-bit integer; nil when `cidr`
  is not a canonical IPv4 network."
  [cidr spec opts {:keys [role index]}]
  (when-let [net (ipv4-network cidr)]
    (+ (:address net) (fallback-offset spec opts role) index)))

(defn fallback-ip
  "The public fallback address of `id`: `192.0.2.0/24` + offset + index."
  [spec opts id]
  (long->ipv4 (offset-address public-fallback-network spec opts id)))

(defn fallback-vpc-ip
  "The private fallback address of `id`: the fallback CIDR's network
  address + offset + index with 32-bit arithmetic, so a /20's nodes cross
  an octet correctly. Nil when the network mode is `:none` or the CIDR
  does not parse (validation reports the latter; the node then carries no
  `:vpc_ip` at all)."
  [spec opts id]
  (some-> (offset-address (fallback-cidr spec opts) spec opts id) long->ipv4))

(defn- name-suffix [spec opts {:keys [role index]}]
  (cond
    (nil? role) (str "-" index)
    (= 1 (node-count spec opts role)) (str "-" role)
    :else (str "-" role "-" index)))

(defn fallback-node-name
  "`<compute-name>-<index>` (nil role), `<compute-name>-<role>` (a role of
  count 1), `<compute-name>-<role>-<index>`; compute's `name` supplies the
  base. Governs fallbacks and new packages only: a package whose legacy
  names differ overrides `:name` on its fallback nodes in its own wrapper."
  [spec opts id]
  (str (compute/name opts) (name-suffix spec opts id)))

(defn aliases
  "`[profile]` then, per node in declared order, `<profile>-<index>` (nil
  role), `<profile>-<role>` (count 1) or `<profile>-<role>-<index>`."
  [spec opts]
  (let [profile (str (:profile opts))]
    (into [profile] (map #(str profile (name-suffix spec opts %)) (node-ids spec opts)))))

(defn fallback-nodes
  "What `build` and `--dry-run` render in place of a compute output: one
  node per id — `:role :index :name :ip :user \"root\" :sudoer \"root\"`,
  and `:vpc_ip` unless the network mode is `:none` — shaped like a real
  `params.nodes` entry so every later stage sees the same keys either way."
  [spec opts]
  (vec (for [id (node-ids spec opts)
             :let [vpc-ip (fallback-vpc-ip spec opts id)]]
         (cond-> (assoc id
                        :name (fallback-node-name spec opts id)
                        :ip (fallback-ip spec opts id)
                        :user "root"
                        :sudoer "root")
           vpc-ip (assoc :vpc_ip vpc-ip)))))

;;; --------------------------------------------------------------- params

(defn output-params
  "The compute stage's `params` output, as compute's: keywordized, the
  underscores kept."
  [result]
  (compute/output-params result))

(defn- node-id [n] {:role (:role n) :index (:index n)})

(defn- nodes-by-id
  "Reported nodes indexed by id, the first occurrence winning."
  [params]
  (reduce (fn [m n] (let [id (node-id n)] (if (contains? m id) m (assoc m id n))))
          {} (:nodes params)))

(defn node-errors
  "Nil when `params` is nil (a build); else, in this order: ids declared
  but not reported; ids reported but not declared; ids reported more than
  once (declared or not, in first-reported order); and ids whose node
  lacks a non-blank string for any of `ip`,
  `name`, `user`, `sudoer` — and `vpc_ip` unless the network mode is
  `:none`. Absent, null, blank and non-string values all count as missing.
  Ids are matched exactly, so a legacy `index: null` (or a string index)
  is an undeclared id: packages translate before ONCE sees the state. A
  present `params` with an empty or absent `nodes` reports every declared
  id missing."
  [spec opts params]
  (when (some? params)
    (let [declared (node-ids spec opts)
          declared? (set declared)
          reported (mapv node-id (:nodes params))
          reported? (set reported)
          freq (frequencies reported)
          by-id (nodes-by-id params)
          fields (if (= :none (network-mode spec opts))
                   [:ip :name :user :sudoer]
                   [:ip :vpc_ip :name :user :sudoer])
          complete? (fn [n] (every? #(non-blank-string? (get n %)) fields))
          missing (remove reported? declared)
          undeclared (distinct (remove declared? reported))
          duplicated (filter #(> (get freq %) 1) (distinct reported))
          incomplete (filter #(and (contains? by-id %) (not (complete? (by-id %)))) declared)]
      (vec
       (concat
        (when (seq missing)
          [(str "the compute stage did not report nodes this package declares: " (ids-str missing))])
        (when (seq undeclared)
          [(str "the compute stage reported nodes this package does not declare: " (ids-str undeclared))])
        (when (seq duplicated)
          [(str "the compute stage reported " (ids-str duplicated) " more than once")])
        (when (seq incomplete)
          [(str "the compute stage did not report a complete node (ip, vpc_ip, name, user, sudoer) for "
                (ids-str incomplete) "; refusing to render a partial cluster")]))))))

(defn nodes
  "The cluster's nodes in declared order. `params` nil (a build) yields the
  fallbacks; a present `params` must pass `node-errors` — callers check
  first, this throws otherwise — and then every node comes from state with
  every field as recorded and no fallback substitution. Keys are spelled as
  `output-params` delivers them, `:vpc_ip` with the underscore; fields ONCE
  does not name are preserved verbatim."
  [spec opts params]
  (if (nil? params)
    (fallback-nodes spec opts)
    (let [errors (node-errors spec opts params)]
      (when (seq errors)
        (throw (ex-info (str/join "\n" errors) {:once/node-errors errors})))
      (let [by-id (nodes-by-id params)]
        (mapv by-id (node-ids spec opts))))))

;;; ----------------------------------------------------------- validation

(defn- host-range-errors
  "The ids whose private fallback address falls outside `cidr`'s usable
  hosts, blamed on `subject` (the key or the network that owns the CIDR).
  Nothing when the CIDR does not parse or a count is invalid: both are
  reported by their own rule."
  [spec opts subject cidr]
  (when (and (ipv4-network cidr) (counts-valid? spec opts))
    (let [{:keys [first last]} (ipv4-network cidr)
          outside (filter #(let [a (offset-address cidr spec opts %)] (not (<= first a last)))
                          (node-ids spec opts))]
      (when (seq outside)
        [(str subject " has no usable host address for " (ids-str outside))]))))

(defn- duplicate-errors [what values]
  (let [freq (frequencies values)]
    (for [v (distinct values) :when (> (get freq v) 1)]
      (str "the " what " " v " is generated for more than one node"))))

(defn spec-errors
  "Static checks over the spec alone, run in a package's spec-content test
  and at the head of `state-errors`. Developer-facing: throws `ex-info` on
  the first problem and returns `[]` otherwise. `:roles` is non-empty; a
  nil role is the only entry; role names match `role-re`, are unique, and
  none equals another followed by `-<digits>`; every `:count` is a positive
  integer and every `:fallback-offset` a non-negative one; `:entry` names a
  declared role with a non-negative index below that role's static `:count`
  (the count-key override is `topology-errors`' to check); `:fallback-subnet`,
  when
  present, is a canonical IPv4 network and is permitted only when some
  advertised entry's network is `:discovered`."
  [spec]
  (let [rs (roles spec)
        names (map :role rs)]
    (when (empty? rs) (spec-error ":roles must be a non-empty vector"))
    (when (and (some nil? names) (> (count rs) 1))
      (spec-error "the nil role must be the only entry in :roles"))
    (doseq [r names :when (some? r)]
      (when-not (and (string? r) (re-matches role-re r))
        (spec-error (str "role \"" r "\" must match ^[a-z][a-z0-9]*(-[a-z0-9]+)*$"))))
    (doseq [[r n] (frequencies names) :when (> n 1)]
      (spec-error (str "role \"" r "\" is declared more than once")))
    (doseq [r names other names
            :when (and r other (re-matches (re-pattern (str "^" other "-\\d+$")) r))]
      (spec-error (str "role \"" r "\" reads as an alias of role \"" other "\"")))
    (doseq [{:keys [role fallback-offset] :as entry} rs
            :let [label (if (nil? role) "the nil role" (str "role \"" role "\""))]]
      (when-not (pos-int? (:count entry))
        (spec-error (str ":count of " label " must be a positive integer")))
      (when (and (contains? entry :fallback-offset) (not (nat-int? fallback-offset)))
        (spec-error (str ":fallback-offset of " label " must be a non-negative integer"))))
    (when-let [entry (:entry spec)]
      (when-not (and (map? entry) (contains? entry :role) (contains? entry :index))
        (spec-error ":entry must carry :role and :index"))
      (when-not (some #(= (:role entry) %) names)
        (spec-error ":entry :role must name a declared role"))
      (when-not (nat-int? (:index entry))
        (spec-error ":entry :index must be a non-negative integer"))
      (let [{:keys [role]} entry
            label (if (nil? role) "the nil role" (str "role \"" role "\""))]
        (when-not (< (:index entry) (:count (role-entry spec role)))
          (spec-error (str ":entry :index must be below :count of " label)))))
    (when (contains? spec :fallback-subnet)
      (when-not (ipv4-network (:fallback-subnet spec))
        (spec-error (str ":fallback-subnet" canonical-message)))
      (when-not (some #(= :discovered (get-in % [:network :mode])) (vals (:registry spec)))
        (spec-error ":fallback-subnet is permitted only when an advertised provider's network is discovered")))
    []))

(defn network-errors
  "Created: the key is required, must be a canonical IPv4 network (host
  bits zero, parsed as a network — not the syntactic `cidr?`), and every
  private fallback address must fall inside its usable host range.
  Discovered: nothing beyond compute's refusals of a pinned VPC. None:
  nothing. `:fallback-subnet`, when present, is held to the same canonical
  rule under its own name."
  [spec opts]
  (let [{:keys [mode key]} (network spec opts)
        value (get opts key)]
    (vec
     (concat
      (when (= :created mode)
        (cond
          (validate/placeholder? value) [(str key " is required")]
          (not (ipv4-network value)) [(str key canonical-message)]
          :else (host-range-errors spec opts key value)))
      (when (and (contains? spec :fallback-subnet) (not (ipv4-network (:fallback-subnet spec))))
        [(str ":fallback-subnet" canonical-message)])))))

(defn topology-errors
  "With desired state: each present count key a positive integer — and
  nothing else until they all are, because every derivation below needs
  them; `:entry` inside the effective count; `:fallback-subnet` present
  when the selected network is discovered; every public fallback address
  inside `192.0.2.0/24` and every private one inside `:fallback-subnet`
  (a created network's range is `network-errors`' to check); addresses,
  names and aliases unique; names and aliases at most 63 characters; and
  every generated name accepted by the selected provider's name rule —
  the spec's `:name-rules` or compute's defaults."
  [spec opts]
  (let [count-errors (for [{:keys [count-key]} (roles spec)
                           :when (and count-key (contains? opts count-key))
                           :when (not (pos-int? (get opts count-key)))]
                       (str count-key " must be a positive integer"))]
    (if (seq count-errors)
      (vec count-errors)
      (let [ids (node-ids spec opts)
            mode (network-mode spec opts)
            cidr (fallback-cidr spec opts)
            entry (entry-id spec)
            public (map #(fallback-ip spec opts %) ids)
            private (keep #(fallback-vpc-ip spec opts %) ids)
            names (map #(fallback-node-name spec opts %) ids)
            alias-names (aliases spec opts)
            {:keys [re message]} (get (or (:name-rules spec) compute/default-name-rules)
                                      (:provider-compute opts))]
        (vec
         (concat
          (when-not (some #(= entry %) ids)
            [(str ":entry names " (node-id-str entry) ", a node this topology does not declare")])
          (when (and (= :discovered mode) (not (contains? spec :fallback-subnet)))
            [":fallback-subnet is required when the selected provider's network is discovered"])
          (host-range-errors spec opts public-fallback-network public-fallback-network)
          (duplicate-errors "public fallback address" public)
          (when (= :discovered mode) (host-range-errors spec opts ":fallback-subnet" cidr))
          (duplicate-errors "private fallback address" private)
          (duplicate-errors "fallback name" (map #(str "\"" % "\"") names))
          (for [n names
                :when (or (> (count n) 63) (and re (not (re-matches re n))))]
            (str "the fallback name \"" n "\" "
                 (if re message "must be at most 63 characters")))
          (duplicate-errors "alias" (map #(str "\"" % "\"") alias-names))
          (for [a alias-names :when (> (count a) 63)]
            (str "the alias \"" a "\" must be at most 63 characters"))))))))

(def ^:private digitalocean-vpc-refusals
  "compute's two DigitalOcean refusals of a pinned VPC. They hold for a
  discovered network and are dropped for a created one, where the package
  does own a VPC; compute itself is untouched."
  #{":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"
    ":digitalocean-vpc-cidr must be absent; this package must not create a VPC"})

(defn state-errors
  "`spec-errors` (thrown), then compute's `state-errors` with the two
  DigitalOcean VPC refusals filtered out when the selected entry's network
  mode is `:created`, then — only when a provider is selected, as compute
  does — `network-errors` and `topology-errors`."
  [spec opts]
  (spec-errors spec)
  (let [created? (= :created (network-mode spec opts))]
    (vec
     (concat
      (cond->> (compute/state-errors spec opts)
        created? (remove digitalocean-vpc-refusals))
      (when (compute/provider spec opts)
        (concat (network-errors spec opts) (topology-errors spec opts)))))))

;;; ---------------------------------------------------------------- state

(defn read-state
  "compute's, re-exported: `{:params m}` or `{:error message}`."
  [opts reader]
  (compute/read-state opts reader))

(defn provider-state-errors
  "compute's, re-exported: reads `params.provider` alone."
  [spec opts params]
  (compute/provider-state-errors spec opts params))

(defn provider-validator
  "compute's, re-exported: the provider mismatch pre-empts the secrets."
  [spec opts params secret-errors-fn]
  (compute/provider-validator spec opts params secret-errors-fn))

(defn resolved-cluster
  "Refuse to hand the documentation addresses to Ansible. Nil outputs — no
  `params` from the compute stage — exit 1; outputs with any `node-errors`
  exit 1 with the messages; else `result`, `fallback` and `{:once/cluster
  outputs}` merged in that order, so the whole recorded `params` — the
  nodes and every extension key — is what the cluster stages read."
  [spec opts result fallback outputs]
  (if (nil? outputs)
    (assoc result :green/exit 1 :green/err no-params-message)
    (let [errors (node-errors spec opts outputs)]
      (if (seq errors)
        (assoc result :green/exit 1 :green/err (str/join "\n" errors))
        (merge result fallback {:once/cluster outputs})))))

(defn adopt-state
  "Events that run against the existing cluster take it from state rather
  than from a fresh apply. `{:error e}` fails closed with compute's two-line
  message; `params` with any `node-errors` exits 1 with them; a readable
  state without `params` leaves `:once/cluster` absent and the package
  decides what that means for the event; else `:once/cluster` holds the
  recorded `params` verbatim over `ssh/with-machine-key`."
  [spec opts event {:keys [params error] :as state}]
  (cond
    error (compute/adopt-state opts event state)
    (nil? params) (merge (ssh/with-machine-key opts true) {:green/exit 0})
    :else (let [errors (node-errors spec opts params)]
            (if (seq errors)
              (assoc opts :green/exit 1 :green/err (str/join "\n" errors))
              (merge (ssh/with-machine-key opts true) {:once/cluster params :green/exit 0})))))

(defn ssh-config-hosts
  "The local ssh-config play's extra-vars: `{:name profile :ip <entry
  ip>}` then one `{:name alias :ip ip}` per node. `nodes` is what `nodes`
  returns, in declared order, so aliases pair with them by position."
  [spec opts nodes]
  (let [[profile & per-node] (aliases spec opts)
        position (first (keep-indexed (fn [i id] (when (= id (entry-id spec)) i))
                                      (node-ids spec opts)))
        entry (when position (nth nodes position nil))]
    (into [{:name profile :ip (:ip entry)}]
          (map (fn [alias node] {:name alias :ip (:ip node)}) per-node nodes))))
