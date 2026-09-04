(ns io.github.getcolors.once.compute
  "The operations of the Compute Provider Standard (workspace
  `standards/compute-provider.md`), over a registry the calling package owns.

  A package describes itself with one spec value and passes it to every
  function that needs it:

  ```clojure
  {:registry   compute-providers   ; provider -> {:required :secrets :tofu-env}
   :default    \"vultr\"           ; what a legacy state without params.provider is
   :sources    {:non-empty    [\"ssh-sources\"]    ; suffixes; each must list a CIDR
                :may-be-empty [\"http-sources\"]}  ; suffixes; may be []
   :name-rules default-name-rules}                 ; optional; this value by default
  ```

  Nothing here is stateful: no factory, no closure, no global a package could
  mutate, so every stub in every package test keeps working. The registry
  data, the default provider, the templates, the fixtures and the lifecycle
  wiring stay the package's; what lives here is the logic that was copied
  into six packages in three colours and had already drifted — four IPv6
  parsers, two wordings for one CIDR error, two shapes of the legacy-state
  refusal. Template lookup deliberately stays package-local: red packages
  hold template content in static imports a root string cannot reach.

  The error strings are contract. They are printed by `scripts/compute-*`
  and diffed across colours by `scripts/parity.sh`, because none of this
  reaches a build artifact and a message that differs per colour is a bug no
  rendered file can show."
  (:refer-clojure :exclude [name key])
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [io.github.getcolors.once.ssh :as ssh]
   [io.github.getcolors.once.validate :as validate]))

(def default-name-rules
  "What each provider accepts as a machine name, checked before the apply
  rather than discovered mid-apply. DigitalOcean droplet names are
  hostname-like; Vultr labels are free-form console text, held to a safe
  subset. An immutable value: a package that needs different rules passes
  its own under `:name-rules` in the spec."
  {"digitalocean" {:re #"^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$"
                   :message "must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"}
   "vultr" {:re #"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$"
            :message "must be a safe 1-63 character name"}})

(defn- missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

;;; ----------------------------------------------------------- selection

(defn provider
  "The selected registry entry, or nil when `:provider-compute` names none."
  [spec opts]
  (get (:registry spec) (:provider-compute opts)))

(defn key
  "Desired state names compute keys after the provider, so the shared steps
  reach them through the selected provider rather than a fixed prefix:
  `:<provider>-<suffix>`."
  [opts suffix]
  (keyword (str (:provider-compute opts) "-" suffix)))

(defn name
  "What this deployment calls its machine (Compute Name Standard §2): the
  selected provider's `<provider>-name` when present and not a placeholder,
  else the profile; trimmed. The one function that answers it, so every
  label derives from the same value."
  [opts]
  (let [override (get opts (key opts "name"))]
    (str/trim (str (if (validate/placeholder? override) (:profile opts) override)))))

(defn selection-errors
  "The §2 refusal: a `:provider-compute` outside the registry, naming the
  advertised providers sorted."
  [spec opts]
  (if (provider spec opts)
    []
    [(str ":provider-compute must be one of "
          (str/join ", " (sort (keys (:registry spec)))))]))

(defn required-keys
  "The selected entry's non-secret keys; `[]` when nothing is selected. The
  package concatenates its own required list and reports the missing ones."
  [spec opts]
  (vec (:required (provider spec opts))))

(defn secrets
  "The selected entry's credentials; `[]` when nothing is selected."
  [spec opts]
  (vec (:secrets (provider spec opts))))

(defn tofu-env
  "The selected entry's OpenTofu environment mapping; `{}` when nothing is
  selected."
  [spec opts]
  (or (:tofu-env (provider spec opts)) {}))

;;; ------------------------------------------------------------- sources

(defn cidrs
  "A source list as desired state or an overlay string carries it: a YAML
  list, or one string of comma- or space-separated entries."
  [opts k]
  (let [v (get opts k) xs (if (sequential? v) v (str/split (str v) #"[,\s]+"))]
    (->> xs (map (comp str/trim str)) (remove str/blank?) vec)))

;; Syntactic CIDR checks, the same in every colour and deliberately not a
;; resolver: an address library that accepts a hostname would let a firewall
;; source depend on DNS at apply time.
(def ^:private ipv4-re
  #"^(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$")
(def ^:private hex-group-re #"^[0-9A-Fa-f]{1,4}$")

(defn- fold-ipv4-tail
  "An IPv4-embedded address (`::ffff:192.0.2.1`, `64:ff9b::192.0.2.33`)
  carries a dotted quad in last position only. It stands for two 16-bit
  groups, so it is checked as IPv4 and folded into two zero groups before the
  group arithmetic; nil when the tail is dotted but not an IPv4 address. A
  dotted quad anywhere else falls through to the hex-group check and fails
  there."
  [s]
  (let [i (str/last-index-of s ":")
        tail (if i (subs s (inc i)) s)]
    (cond
      (not (str/includes? tail ".")) s
      (and i (re-matches ipv4-re tail)) (str (subs s 0 (inc i)) "0:0")
      :else nil)))

(defn- ipv6-address? [raw]
  (when-let [s (fold-ipv4-tail raw)]
    (let [groups (fn [part] (if (str/blank? part) [] (str/split part #":" -1)))]
      (if (str/includes? s "::")
        (let [halves (str/split s #"::" -1)]
          (and (= 2 (count halves))
               (let [gs (mapcat groups halves)]
                 (and (<= (count gs) 7) (every? #(re-matches hex-group-re %) gs)))))
        (let [gs (groups s)]
          (and (= 8 (count gs)) (every? #(re-matches hex-group-re %) gs)))))))

(defn cidr?
  "Whether `s` is a syntactically valid IPv4 or IPv6 CIDR: an address, a
  slash, and a prefix length the address family allows."
  [s]
  (let [[address prefix & more] (str/split (str s) #"/" -1)]
    (boolean
     (and (nil? more) (some? prefix) (re-matches #"^\d{1,3}$" prefix)
          (let [n (Long/parseLong prefix)]
            (cond
              (re-matches ipv4-re address) (<= 0 n 32)
              (ipv6-address? address) (<= 0 n 128)
              :else false))))))

(defn source-errors
  "The §5 network contract over the spec's `:sources`: every `:non-empty`
  suffix must list at least one CIDR — a machine nobody can reach is not a
  deployment — and every entry of every listed suffix must be one. A
  `:may-be-empty` list may be `[]` and means no public access on that port
  set. Keys absent from opts are skipped: presence is `required-keys`' job.
  Refusing beats defaulting: a silent default-open is worse than a validation
  error."
  [spec opts]
  (let [{:keys [non-empty may-be-empty]} (:sources spec)
        present? (fn [k] (not (missing? (get opts k))))
        non-empty-keys (map #(key opts %) non-empty)
        all-keys (map #(key opts %) (concat non-empty may-be-empty))]
    (vec
     (concat
      (for [k non-empty-keys
            :when (and (present? k) (empty? (cidrs opts k)))]
        (str k " must list at least one CIDR"))
      (for [k all-keys
            :when (present? k)
            entry (cidrs opts k)
            :when (not (cidr? entry))]
        (str k " entry " (pr-str entry) " is not an IPv4 or IPv6 CIDR"))))))

;;; ------------------------------------------------------------ provider

(defn provider-errors
  "Checks that hold only for the selected provider; keys of another provider
  are ignored, never refused. The *resolved* machine name is validated
  against the provider's rules (Compute Name Standard §2): an override is
  checked as itself, and a profile that falls through as the name is checked
  too, because a profile Vultr accepts as a label can be a droplet name
  DigitalOcean refuses mid-apply. The error names the key the value came
  from. A blank resolved value is skipped, so a missing profile reports `is
  required` alone."
  [spec opts]
  (let [selected (:provider-compute opts)
        name-key (key opts "name")
        {:keys [re message]} (get (or (:name-rules spec) default-name-rules) selected)
        resolved (name opts)
        source (if (validate/placeholder? (get opts name-key))
                 (str ":profile (the " selected " machine name)")
                 (str name-key))]
    (vec
     (concat
      (when (and re (not (str/blank? resolved))
                 (or (> (count resolved) 63) (not (re-matches re resolved))))
        [(str source " " message)])
      (case selected
        "vultr"
        (when-not (or (missing? (:vultr-os-id opts)) (integer? (:vultr-os-id opts)))
          [":vultr-os-id must be Vultr's numeric operating-system id"])
        "digitalocean"
        ;; No VPC is created: the region's default is discovered at plan time,
        ;; and a pinned UUID or a CIDR would make the package start owning one.
        (concat
         (when (contains? opts :digitalocean-vpc-uuid)
           [":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"])
         (when (contains? opts :digitalocean-vpc-cidr)
           [":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]))
        nil)))))

(defn state-errors
  "Selection, then — only when a provider is selected — the source and the
  provider checks, in that order. Presence of the required keys is reported
  by the package over `required-keys`."
  [spec opts]
  (vec
   (concat (selection-errors spec opts)
           (when (provider spec opts)
             (concat (source-errors spec opts) (provider-errors spec opts))))))

(defn provider-state-errors
  "The §4 switch and legacy rules. Provider switching is a rebuild, never an
  apply: every provider shares one state key, so a changed provider-compute
  on a profile whose state already holds compute would plan a cross-provider
  replacement — and a delete would render and destroy the *selected*
  provider's template against the wrong lifecycle. `params` is the compute
  stage's recorded output, or nil when the state holds none; its `provider`
  is the registry name the template that produced it belongs to. A recorded
  output without one predates the package recording it, which makes it the
  spec's `:default` provider's."
  [spec opts params]
  (let [selected (:provider-compute opts)
        default (:default spec)
        recorded (some-> (:provider params) str not-empty)]
    (cond
      (nil? params) []

      (and recorded (not= recorded selected))
      [(str "state holds a " recorded " machine; set provider-compute back to "
            recorded " and delete first")]

      (and (nil? recorded) (not= selected default))
      [(str "state holds a machine with no recorded provider, created before this "
            "package recorded one, which makes it a " default
            " machine; set provider-compute back to " default
            " and delete first")]

      :else [])))

;;; --------------------------------------------------------------- params

(defn fallback-params
  "What `build` and `--dry-run` render in place of a compute output: the
  documentation address, shaped like the selected provider's real `params`
  so every later stage sees the same keys either way."
  [opts]
  {:provider (:provider-compute opts) :ip "192.0.2.10" :user "root" :sudoer "root"
   :name (name opts)})

(defn output-params
  "The compute stage's `params` output, keywordized and otherwise untouched:
  the SSH Keypair Standard reads `:ssh_key_id` with the underscore from this
  map, and a renamed key reads as a key the deployment does not own."
  [result]
  (some-> (get-in result [:tofu/outputs :params]) walk/keywordize-keys))

(defn resolved-compute
  "Refuse to hand 192.0.2.10 to Ansible. That is the documentation address the
  credential-free build and dry-run paths render with; on a real converge a
  missing compute output must fail loudly rather than quietly point the whole
  playbook at TEST-NET."
  [result fallback outputs]
  (if (:ip outputs)
    (merge result fallback outputs)
    (assoc result :green/exit 1
           :green/err (str "compute produced no ip output; refusing to converge "
                           "against the documentation address"))))

;;; ---------------------------------------------------------------- state

(def ^:private no-message "state read failed without a message")

(defn read-state
  "One read of the compute state per run, shaped so a caller can tell nothing
  recorded from nothing readable: `{:params m}` where `m` may be nil, or
  `{:error message}`. `reader` is the package's `state-output` — it keeps
  that function local so `with-redefs` in its tests keeps working — and it
  throws when the backend is unreadable.

  Only the SDK's step error is caught: an `ExceptionInfo` whose `ex-data`
  carries `:dir`, which is exactly what `green.tofu/outputs` throws
  (`(ex-info (str \"tofu output failed: \" err) {:dir dir})`) and nothing
  else in the SDK does — this function depends on that shape, as its red and
  blue twins depend on `red/tofu` and `blue.tofu` throwing `StepError`. A
  message-less step error reads as the fixed string `state read failed
  without a message`. Any other exception propagates: a programmer defect in
  the reader must not read as \"no state\" and skip the switch guard."
  [opts reader]
  (try {:params (reader opts)}
       (catch clojure.lang.ExceptionInfo e
         (if (contains? (ex-data e) :dir)
           {:error (or (not-empty (ex-message e)) no-message)}
           (throw e)))))

(defn lifecycle-event?
  "A real create or delete: the two events that touch a provider."
  [{:keys [event real?]}]
  (boolean (and real? (contains? #{:create :delete} event))))

(defn provider-validator
  "Standard §4 before the credentials. The recorded provider is compared with
  the selected one first, so a mistaken provider edit reports the actionable
  error — put it back and delete — rather than a missing token for the
  provider that was just selected; validators aggregate, which is why a
  mismatch pre-empts the secrets check rather than sitting beside it.
  `secret-errors-fn` is the package's thunk, carrying its event and its
  application secrets, so ONCE never learns about them. On a create an
  unreadable backend counts as no state (a fresh clone has none) and the
  credentials are checked as usual; on a delete `adopt-state` refuses it
  after validation."
  [spec opts params secret-errors-fn]
  (let [mismatch (provider-state-errors spec opts params)]
    (if (seq mismatch) mismatch (vec (secret-errors-fn)))))

(defn adopt-state
  "Events that run against the existing machine take its address from state
  rather than from a fresh apply. A readable state without compute params
  leaves :ip unset — a delete's cleanup step then skips itself — while an
  unreadable backend fails loudly: swallowing it is how a live teardown once
  ended up converging against 192.0.2.10 (§4). Delete keeps the standard's
  wording; a package's rehearse or describe reads its own event name.

  No address override: the recorded params win over anything already in
  opts, and nothing here reads an `:ip` from desired state or the overlay. A
  package that wants one (posthog's `COLORS_PAR_IP`) wraps this function; the
  others must not gain a way to point a delete's cleanup at an arbitrary
  host. Synchronous in every colour."
  [opts event {:keys [params error]}]
  (if error
    (assoc opts :green/exit 1
           :green/err (str "could not read the infrastructure state for "
                           (if (= :delete event) "the delete cleanup" (clojure.core/name event))
                           ": " error "\n"
                           "fix the backend credentials and retry; a " (clojure.core/name event)
                           " that cannot see its state has nothing to address"))
    (merge (ssh/with-machine-key opts true) params {:green/exit 0})))
