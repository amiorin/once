;; Drive the Compute Provider Standard's operations — selection, the network
;; contract, the name rules, the §4 switch and legacy refusals, the state
;; read, adoption and the missing-ip refusal — through green's `compute`
;; module with a two-provider stub spec, printing one normalized
;; `case <name> exit=<n> errors=<messages joined by " | ">` line per scenario
;; (value-bearing scenarios append ` value=<fields>`). Red and blue print the
;; same shape, so parity.sh can diff them: none of this logic reaches a build
;; artifact, and the messages are contract for every package that delegates
;; to ONCE. Exit is the real `:green/exit` where a scenario returns opts and
;; 2 (the CLI's validation exit) where it returns messages.
(require '[clojure.string :as str]
         '[io.github.getcolors.once.compute :as compute]
         '[io.github.getcolors.once.ssh :as ssh])

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
(def three (assoc spec :sources {:non-empty ["ssh-sources"]
                                 :may-be-empty ["http-sources" "stun-sources"]}))
(def own (assoc spec :name-rules {"vultr" {:re #"^x$" :message "must be x"}}))

(defn vultr [& kvs] (merge {:profile "prod" :provider-compute "vultr"} (apply hash-map kvs)))
(defn digitalocean [& kvs] (merge {:profile "prod" :provider-compute "digitalocean"} (apply hash-map kvs)))

(defn line
  ([case-name exit errors] (line case-name exit errors nil))
  ([case-name exit errors value]
   (println (str "case " case-name " exit=" exit
                 " errors=" (str/join " | " (map #(str/replace % "\n" "\\n") errors))
                 (when value (str " value=" value))))))

(defn errs [case-name errors] (line case-name (if (empty? errors) 0 2) errors))
(defn out [case-name opts & [value]]
  (line case-name (or (:green/exit opts) 0) (remove nil? [(:green/err opts)]) value))

(defn tmp-dir []
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "once-compute-parity"
                  (into-array java.nio.file.attribute.FileAttribute [])))]
    (alter-var-root #'ssh/home-dir (constantly (constantly dir)))
    dir))

;; --- selection
(errs "selection-unknown" (compute/selection-errors spec {:provider-compute "hetzner"}))
(errs "selection-unselected-skips-checks"
      (compute/state-errors spec {:provider-compute "hetzner" :hetzner-ssh-sources ["nope"]
                                  :hetzner-name "BAD NAME"}))
(errs "selection-ignores-other-provider"
      (compute/state-errors spec (vultr :vultr-ssh-sources ["10.0.0.0/8"] :vultr-os-id 2284
                                        :digitalocean-ssh-sources ["nope"]
                                        :digitalocean-vpc-uuid "vpc-123"
                                        :digitalocean-name "BAD NAME")))
(line "required-keys" 0 []
      (str/join ";" [(str/join "," (map name (compute/required-keys spec (vultr))))
                     (str/join "," (map name (compute/required-keys spec (digitalocean))))
                     (count (compute/required-keys spec {}))]))
(line "secrets-and-tofu-env" 0 []
      (str/join ";" [(str/join "," (map name (compute/secrets spec (vultr))))
                     (str/join "," (map name (compute/secrets spec (digitalocean))))
                     (count (compute/secrets spec {}))
                     (str/join "," (map (fn [[k v]] (str (name k) "=" v)) (compute/tofu-env spec (vultr))))
                     (str/join "," (map (fn [[k v]] (str (name k) "=" v)) (compute/tofu-env spec (digitalocean))))
                     (count (compute/tofu-env spec {}))]))
(line "compute-key-and-name" 0 []
      (str/join ";" [(name (compute/key (vultr) "ssh-sources"))
                     (name (compute/key (digitalocean) "name"))
                     (compute/name (vultr))
                     (compute/name (vultr :vultr-name " box "))
                     (compute/name (vultr :vultr-name "REPLACE_ME"))
                     (compute/name (vultr :vultr-name ""))
                     (compute/name (vultr :digitalocean-name "other"))]))

;; --- sources
(errs "source-empty-non-empty" (compute/source-errors spec (vultr :vultr-ssh-sources [])))
(errs "source-empty-may-be-empty"
      (compute/source-errors spec (vultr :vultr-ssh-sources ["10.0.0.0/8"] :vultr-http-sources [])))
(errs "source-malformed-per-key"
      (compute/source-errors spec (vultr :vultr-ssh-sources ["10.0.0.0/8" "nope"]
                                         :vultr-http-sources ["::1/129" "1.2.3.4/32"])))
(errs "source-overlay-string"
      (compute/source-errors spec (vultr :vultr-ssh-sources "10.0.0.0/8, 192.0.2.0/24 bad")))
(errs "source-absent-skipped" (compute/source-errors spec (vultr)))
(errs "source-blank-skipped" (compute/source-errors spec (vultr :vultr-ssh-sources "  ")))
(errs "source-v4-grammar"
      (compute/source-errors spec (vultr :vultr-ssh-sources
                                         ["10.0.0.0/8" "0.0.0.0/0" "203.0.113.7/32" "10.0.0.0/33"
                                          "256.0.0.1/8" "example.com/32" "10.0.0.0" "10.0.0.0/" "é/32" "a\"b/32" "a\\b/32"
                                          "10.0.0.0/8/8"])))
(errs "source-v6-grammar"
      (compute/source-errors spec (vultr :vultr-ssh-sources
                                         ["2001:db8::/32" "::/0" "::1/128" "1:2:3:4:5:6:7:8/128"
                                          "2001:db8:::1/64" "1:2:3:4:5:6:7:8:9/64" "2001:db8::/129"
                                          "2001:db8::g/64"])))
(errs "source-v4-tail"
      (compute/source-errors spec (vultr :vultr-ssh-sources
                                         ["::ffff:203.0.113.7/128" "64:ff9b::192.0.2.33/96"
                                          "::ffff:300.0.0.1/128" "192.0.2.1::/96"])))
(errs "source-stun-spec"
      (compute/source-errors three (vultr :vultr-ssh-sources ["10.0.0.0/8"] :vultr-stun-sources ["x"])))
(errs "source-stun-outside-spec" (compute/source-errors spec (vultr :vultr-stun-sources ["x"])))

;; --- provider
(errs "name-vultr-override-bad" (compute/provider-errors spec (vultr :vultr-name "bad name!")))
(errs "name-vultr-profile-bad" (compute/provider-errors spec (vultr :profile "bad name!")))
(errs "name-do-override-bad" (compute/provider-errors spec (digitalocean :digitalocean-name "Upper")))
(errs "name-do-profile-bad" (compute/provider-errors spec (digitalocean :profile "under_score")))
(errs "name-do-placeholder-falls-through"
      (compute/provider-errors spec (digitalocean :profile "Bad" :digitalocean-name "REPLACE_ME")))
(errs "name-do-too-long"
      (compute/provider-errors spec (digitalocean :digitalocean-name (apply str (repeat 64 "a")))))
(errs "name-ok"
      (concat (compute/provider-errors spec (digitalocean :digitalocean-name (apply str (repeat 63 "a"))))
              (compute/provider-errors spec (digitalocean :digitalocean-name "prod-1.example"))
              (compute/provider-errors spec (vultr :vultr-name " Prod_1 "))
              (compute/provider-errors spec (digitalocean :profile ""))))
(errs "name-spec-rules-win"
      (concat (compute/provider-errors own (vultr :vultr-name "prod"))
              (compute/provider-errors own (digitalocean :digitalocean-name "Upper"))))
(errs "vultr-os-id-string" (compute/provider-errors spec (vultr :vultr-os-id "2284")))
(errs "vultr-os-id-int" (compute/provider-errors spec (vultr :vultr-os-id 2284)))
(errs "do-vpc-bans"
      (compute/provider-errors spec (digitalocean :digitalocean-vpc-uuid "vpc-123"
                                                  :digitalocean-vpc-cidr "10.0.0.0/16")))
(errs "provider-other-selected"
      (concat (compute/provider-errors spec (vultr :digitalocean-vpc-uuid "vpc-123"
                                                   :digitalocean-name "BAD NAME"))
              (compute/provider-errors spec (digitalocean :vultr-os-id "2284" :vultr-name "bad name!"))))
(errs "state-errors-order"
      (compute/state-errors spec (digitalocean :digitalocean-ssh-sources ["nope"]
                                               :digitalocean-name "Upper")))

;; --- provider-state
(errs "pse-nil" (compute/provider-state-errors spec (vultr) nil))
(errs "pse-match" (compute/provider-state-errors spec (vultr) {:provider "vultr" :ip "1.2.3.4"}))
(errs "pse-mismatch-do-on-vultr" (compute/provider-state-errors spec (vultr) {:provider "digitalocean"}))
(errs "pse-mismatch-vultr-on-do" (compute/provider-state-errors spec (digitalocean) {:provider "vultr"}))
(errs "pse-legacy-default" (compute/provider-state-errors spec (vultr) {:ip "1.2.3.4"}))
(errs "pse-legacy-non-default" (compute/provider-state-errors spec (digitalocean) {:ip "1.2.3.4"}))
(errs "pse-legacy-empty-recorded" (compute/provider-state-errors spec (digitalocean) {:provider ""}))

;; --- params
(let [fb (compute/fallback-params (vultr :vultr-name "box"))]
  (line "fallback-params" 0 [] (str/join ";" [(:provider fb) (:ip fb) (:user fb) (:sudoer fb) (:name fb)])))
(line "lifecycle-event" 0 []
      (str/join ";" (map #(str (compute/lifecycle-event? %))
                         [{:event :create :real? true} {:event :delete :real? true}
                          {:event :create :real? false} {:event :build :real? true}])))
(out "resolved-missing-ip" (compute/resolved-compute {} (compute/fallback-params (vultr)) nil))
(out "resolved-no-ip-key" (compute/resolved-compute {} (compute/fallback-params (vultr)) {:name "prod"}))
(let [o (compute/resolved-compute {} (compute/fallback-params (vultr)) {:ip "1.2.3.4" :name "box"})]
  (out "resolved-present-ip" o (str/join ";" [(:provider o) (:ip o) (:user o) (:sudoer o) (:name o)])))
(let [p (compute/output-params {:tofu/outputs {:params {"ip" "1.2.3.4" "ssh_key_id" "77"}}})]
  (line "output-params" 0 [] (str/join ";" [(:ip p) (:ssh_key_id p) (nil? (compute/output-params {}))])))

;; --- read-state: each SDK's typed failure is constructed here, since no
;; tofu runs. Green's is an ex-info carrying :dir, the shape green.tofu throws.
(defn step-error [msg] (ex-info msg {:dir "/state"}))
(defn rs [case-name reader]
  (let [r (try (compute/read-state (vultr) reader)
               (catch Exception e {:propagated (ex-message e)}))]
    (line case-name (if (:error r) 1 0) (remove nil? [(:error r)])
          (cond
            (contains? r :propagated) (str "propagated:" (:propagated r))
            (contains? r :params) (str "params:" (if-let [p (:params r)]
                                                    (str (:ip p) "," (:seen p))
                                                    "none"))
            :else "error"))))
(rs "read-state-step-error" (fn [_] (throw (step-error "tofu output failed: boom"))))
(rs "read-state-no-message" (fn [_] (throw (step-error nil))))
(rs "read-state-empty-message" (fn [_] (throw (step-error ""))))
(rs "read-state-nil" (fn [_] nil))
(rs "read-state-params" (fn [o] {:ip "1.2.3.4" :seen (:profile o)}))
(rs "read-state-other-propagates" (fn [_] (throw (RuntimeException. "defect"))))
(rs "read-state-untyped-propagates" (fn [_] (throw (ex-info "defect" {}))))

;; --- provider-validator
(let [called (atom 0)
      thunk (fn [] (swap! called inc) ["required credential is not set: COLORS_PAR_VULTR_API_KEY"])
      v (fn [case-name params]
          (let [e (compute/provider-validator spec (vultr) params thunk)]
            (line case-name (if (empty? e) 0 2) e (str "thunk-calls:" @called))))]
  (v "validator-mismatch" {:provider "digitalocean"})
  (v "validator-match" {:provider "vultr"})
  (v "validator-no-state" nil))

;; --- adopt-state
(let [opt-out (vultr :vultr-ssh-keys "key-uuid")]
  (out "adopt-delete-error" (compute/adopt-state opt-out :delete {:error "HTTP 403 from backend"}))
  (out "adopt-rehearse-error" (compute/adopt-state opt-out :rehearse {:error "HTTP 403 from backend"}))
  (out "adopt-describe-error" (compute/adopt-state opt-out :describe {:error "HTTP 403 from backend"}))
  (let [o (compute/adopt-state (assoc opt-out :ip "9.9.9.9") :delete
                               {:params {:ip "1.2.3.4" :ssh_key_id "77" :provider "vultr"}})]
    (out "adopt-params" o (str "ip:" (:ip o) ";ssh_key_id:" (:ssh_key_id o)
                               ";keygen:" (contains? o :ssh-keygen))))
  (let [o (compute/adopt-state opt-out :delete {:params nil})]
    (out "adopt-nil-params" o (str "ip:" (contains? o :ip))))
  (let [dir (tmp-dir)
        o (compute/adopt-state (vultr) :delete {:params {:ip "1.2.3.4"}})]
    (out "adopt-keygen" o (str "ip:" (:ip o) ";keygen:" (:ssh-keygen o)
                               ";key-under-home:" (str/starts-with? (str (:vultr-ssh-keys o)) dir)))))
