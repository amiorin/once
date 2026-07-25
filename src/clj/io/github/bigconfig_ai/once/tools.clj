(ns io.github.bigconfig-ai.once.tools
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [green.ansible :as ansible]
   [green.scaffold :as sc]
   [green.tofu :as tofu]
   [io.github.bigconfig-ai.once.utils :as utils]))

(def ^:private template-root "io.github.bigconfig-ai.once.tools")
(def ^:private raw-template :io.github.bigconfig-ai.once/raw)
(def ^:private template-opts {:tag-open \<
                              :tag-close \>
                              :filter-open \{
                              :filter-close \}})

(defn tool-dir
  "Return the isolated working directory for `tool` in the active profile."
  [opts tool]
  (str (io/file (or (:workdir opts) ".green")
                (or (:profile opts) "default")
                tool)))

(defn- tool-template
  [tool provider file]
  (keyword (str template-root "." tool "." provider) file))

(defn- static-template
  [tool file]
  (keyword (str template-root "." tool) file))

(defn- template-spec
  [template target data]
  {:template template
   :target target
   :data data
   :opts template-opts})

(defn- raw-spec
  [target content]
  (template-spec raw-template target {:content content}))

(defn- failed?
  [opts]
  (pos? (:green/exit opts 0)))

(defn- output-params
  [opts]
  (some-> (get-in opts [:tofu/outputs :params]) walk/keywordize-keys))

(def ^:private credential-env-vars
  "Flat key -> the variable each OpenTofu provider reads natively. Credentials
  reach tofu through the process environment so they never render into .tf
  files, where they would sit in plaintext under the work directory."
  {:do-token "DIGITALOCEAN_TOKEN"
   :hcloud-token "HCLOUD_TOKEN"
   :resend-api-key "RESEND_API_KEY"
   :cloudflare-api-token "CLOUDFLARE_API_TOKEN"
   :r2-access-key-id "AWS_ACCESS_KEY_ID"
   :r2-secret-access-key "AWS_SECRET_ACCESS_KEY"})

(defn- backend-credential-keys
  "State-backend credentials. Unlike provider credentials these belong to every
  stage, since each one reads and writes state. R2 is an S3-compatible backend,
  so it authenticates through the AWS chain; s3 uses the ambient chain already
  and local needs nothing."
  [opts]
  (if (= "r2" (:provider-backend opts))
    [:r2-access-key-id :r2-secret-access-key]
    []))

(defn- credential-env
  "Environment additions for `ks`, plus whatever the state backend needs. Unset
  credentials are omitted, so build and dry-run stay credential-free."
  [opts ks]
  (not-empty
   (into {}
         (keep (fn [k]
                 (when-let [v (not-empty (str (get opts k)))]
                   [(credential-env-vars k) v])))
         (concat ks (backend-credential-keys opts)))))

(defn backend-credential-env
  "Environment additions for a process that only reads OpenTofu state, such as
  `tofu output`. Provider credentials are left out on purpose: reading state
  never calls a provider API."
  [opts]
  (credential-env opts []))

(defn- tofu-with-spec
  [opts dir specs fallback result-key env]
  (cond
    (= :build (:green/event opts))
    (cond-> (sc/scaffold opts specs)
      result-key (assoc result-key fallback))

    (= :delete (:green/event opts))
    (let [rendered (-> opts
                       (assoc :green/event :create)
                       (sc/scaffold specs)
                       (assoc :green/event :delete))
          result (tofu/tofu-step rendered {:dir dir :env env})]
      (if (failed? result)
        result
        (sc/scaffold result specs)))

    :else
    (let [rendered (sc/scaffold opts specs)
          result (tofu/tofu-step rendered {:dir dir :env env})]
      (if (or (failed? result) (nil? result-key))
        result
        (assoc result result-key (merge fallback (or (output-params result) {})))))))

(defn- fallback-compute-params
  [{:keys [package provider-compute] :as opts}]
  (let [name (or package "once")]
    (case provider-compute
      "oci" {:ip "192.168.0.1"
             :sudoer "ubuntu"
             :uid "1001"
             :name name
             :user "ubuntu"}
      "no-infra" (cond-> {:ip (or (:no-infra-compute-ip opts) "192.168.0.1")
                            :sudoer (or (:no-infra-compute-sudoer opts) "root")
                            :name name
                            :user (or (:no-infra-compute-user opts) "root")}
                     (:no-infra-compute-uid opts) (assoc :uid (:no-infra-compute-uid opts)))
      {:ip "192.168.0.1"
       :sudoer "root"
       :name name
       :user "root"})))

(defn- fallback-smtp-params
  [{:keys [provider-smtp] :as opts}]
  (merge {:id "domain-id-not-defined"
          :records []}
         (case provider-smtp
           "no-infra" {:smtp_username (:no-infra-smtp-username opts)
                       :smtp_password (:no-infra-smtp-password opts)
                       :smtp_server (:no-infra-smtp-server opts)
                       :smtp_port (:no-infra-smtp-port opts)}
           "resend" {:smtp_username (:resend-username opts)
                     :smtp_password (:resend-password opts)
                     :smtp_server (:resend-server opts)
                     :smtp_port (:resend-port opts)}
           {})))

(defn tofu-compute-step
  [opts]
  (let [provider (or (:provider-compute opts) "hcloud")
        dir (tool-dir opts "tofu-compute")
        specs [(template-spec (tool-template "tofu" provider "main.tf")
                              (str dir "/main.tf")
                              opts)]]
    (tofu-with-spec opts dir specs (fallback-compute-params opts) :once/compute-params
                    (credential-env opts [:do-token :hcloud-token]))))

(defn tofu-smtp-step
  [opts]
  (let [provider (or (:provider-smtp opts) "resend")
        dir (tool-dir opts "tofu-smtp")
        specs [(template-spec (tool-template "tofu-smtp" provider "main.tf")
                              (str dir "/main.tf")
                              opts)]]
    (tofu-with-spec opts dir specs (fallback-smtp-params opts) :once/smtp-params
                    (credential-env opts [:resend-api-key]))))

(defn- add-fqn-suffix
  [fqn suffix]
  (if-let [ns (namespace fqn)]
    (keyword ns (str (name fqn) suffix))
    (keyword (str (name fqn) suffix))))

(defn- tofu-fqn->name
  [fqn]
  (let [sanitize #(str/replace % #"[-\.]" "_")
        ns (some-> (namespace fqn) sanitize)
        n (sanitize (name fqn))]
    (str ns (when ns "_") n)))

(defn- tofu-construct
  [group type fqn block]
  {group {type {(tofu-fqn->name fqn) block}}})

(defn- deep-merge
  [& maps]
  (apply merge-with (fn [a b]
                      (if (and (map? a) (map? b))
                        (deep-merge a b)
                        b))
         maps))

(defn- sort-nested-map
  [x]
  (cond
    (map? x) (into (sorted-map)
                   (map (fn [[k v]] [k (sort-nested-map v)]))
                   x)
    (sequential? x) (mapv sort-nested-map x)
    :else x))

(defn render-fn
  [src {:keys [records]}]
  (case src
    :smtp (let [cloudflare-records
                (for [{:keys [name priority record type value]} records]
                  (tofu-construct :resource
                                  :cloudflare_dns_record
                                  (add-fqn-suffix ::smtp-dns (format "-%s-%s" record type))
                                  (cond-> {:zone_id "${data.cloudflare_zone.domain.id}"
                                           :name name
                                           :ttl "1"
                                           :type type
                                           :proxied false}
                                    (= type "TXT") (merge {:content (format "\"%s\"" value)})
                                    (= type "MX") (merge {:priority priority
                                                          :content value}))))
                m (if (seq cloudflare-records)
                    (sort-nested-map (apply deep-merge cloudflare-records))
                    {})]
            (json/generate-string m {:pretty true}))))

(defn- joined-params
  [opts]
  (let [branches (:green/branches opts)
        compute (or (some :once/compute-params branches)
                    (:once/compute-params opts)
                    (fallback-compute-params opts))
        smtp (or (some :once/smtp-params branches)
                 (:once/smtp-params opts)
                 (fallback-smtp-params opts))]
    (-> opts
        (merge compute smtp)
        (assoc :once/compute-params compute
               :once/smtp-params smtp))))

(defn tofu-dns-step
  [opts]
  (let [opts (if (= :delete (:green/event opts)) opts (joined-params opts))
        provider (or (:provider-dns opts) "cloudflare")
        dir (tool-dir opts "tofu-dns")
        specs (cond-> [(template-spec (tool-template "tofu-dns" provider "main.tf")
                                      (str dir "/main.tf")
                                      opts)]
                (= provider "cloudflare")
                (conj (raw-spec (str dir "/smtp.tf.json")
                                (render-fn :smtp {:records (:records opts)}))))]
    (tofu-with-spec opts dir specs {} nil
                    (credential-env opts [:cloudflare-api-token]))))

(defn tofu-smtp-post-step
  [opts]
  (let [provider (or (:provider-smtp opts) "resend")
        dir (tool-dir opts "tofu-smtp-post")
        specs [(template-spec (tool-template "tofu-smtp-post" provider "main.tf")
                              (str dir "/main.tf")
                              opts)]]
    (tofu-with-spec opts dir specs {} nil
                    (credential-env opts [:resend-api-key]))))

(defn data-fn
  ([data] (data-fn data nil))
  ([{:keys [ip sudoer] :as data} _]
   (let [sudoer (or sudoer "root")]
     (merge data {:sudoer sudoer
                  :hosts [(or ip "64.227.72.100")]
                  :users []}))))

(defn inventory
  [{:keys [sudoer hosts users]}]
  (let [users (->> users
                   (filter (complement :remove))
                   (mapcat (fn [user]
                             (map #(assoc user :host %) hosts))))
        admins (mapcat (fn [admin]
                         (map #(assoc admin :host % :name sudoer) hosts))
                       [{:ansible_user sudoer}])
        users-hosts (reduce (fn [result {:keys [name uid host]}]
                              (assoc result (format "%s@%s" name host)
                                     {:ansible_host host
                                      :ansible_user name
                                      :uid uid}))
                            {}
                            users)
        admins-hosts (reduce (fn [result {:keys [name host]}]
                               (assoc result (format "root@%s" host)
                                      {:ansible_host host
                                       :ansible_user name}))
                             {}
                             admins)
        result {:all {:children {:admin {:hosts admins-hosts}
                                 :users {:hosts users-hosts}}}}]
    (json/generate-string result {:pretty true})))

(defn- par-lookup
  "Jinja expression resolving `k`'s GREEN_PAR_* variable when Ansible runs, so
  the secret is templated at play time instead of written into the rendered
  file. The renderer's delimiters are <{ }>, so {{ }} survives untouched."
  [k]
  (format "{{ lookup('env','GREEN_PAR_%s') }}"
          (-> (name k) (str/replace "-" "_") str/upper-case)))

(defn- resolve-env
  "Resolve an application `:env` map of container variable name -> flat opts key
  into the [\"KEY=VALUE\"] list the once module expects. Each value defers to
  the key's `GREEN_PAR_*` variable, looked up when Ansible runs, so application
  secrets reach the host without being written into the rendered file. An unset
  variable still resolves to an empty value rather than the string \"null\".
  A list is passed through untouched."
  [env]
  (if (map? env)
    (mapv (fn [[var-name k]]
            (str (name var-name) "=" (par-lookup (keyword k))))
          env)
    env))

(defn- application-data
  [smtp app]
  (cond-> (merge app smtp)
    (map? (:env app)) (assoc :env (resolve-env (:env app)))))

(def ^:private smtp-password-keys
  {"resend" :resend-password
   "no-infra" :no-infra-smtp-password})

(defn ansible-once
  [{:keys [once domain provider-smtp] :as opts}]
  (let [pw-key (smtp-password-keys (or provider-smtp "resend"))
        smtp (-> (select-keys opts [:smtp_server :smtp_port :smtp_username :smtp_password])
                 (assoc :smtp_from (format "Info <info@notifications.%s>" domain)))
        smtp (cond-> smtp
               (and pw-key (:smtp_password smtp))
               (assoc :smtp_password (par-lookup pw-key)))
        once (update once :applications
                     (fn [applications]
                       (mapv #(application-data smtp %) applications)))
        data [{:name "Reconcile ONCE applications"
               :become true
               :once once}]]
    (utils/generate-yaml data)))

(defn render
  [target data]
  (case target
    :inventory (inventory data)
    :ansible-once (ansible-once data)))

(defn- ansible-remote-specs
  [opts]
  (let [dir (tool-dir opts "ansible-remote")
        data (data-fn opts)]
    [(template-spec (static-template "ansible" "ansible.cfg")
                    (str dir "/ansible.cfg")
                    data)
     (template-spec (static-template "ansible" "main.yml")
                    (str dir "/main.yml")
                    data)
     (template-spec (static-template "ansible" "files/deploy")
                    (str dir "/files/deploy")
                    data)
     (template-spec (static-template "ansible" "library/once")
                    (str dir "/library/once")
                    data)
     (raw-spec (str dir "/inventory.json") (inventory data))
     (raw-spec (str dir "/once.yml") (ansible-once data))]))

(defn ansible-remote-step
  [opts]
  (let [dir (tool-dir opts "ansible-remote")
        rendered (sc/scaffold opts (ansible-remote-specs opts))]
    (if (or (= :build (:green/event opts))
            (= :delete (:green/event opts)))
      rendered
      (ansible/ansible-step rendered {:dir dir
                                      :inventory "inventory.json"
                                      :playbooks {:create "main.yml"}
                                      :host-key-checking false}))))

(defn- local-host-alias
  "The SSH alias the local playbook manages. Tofu reports it as `name`, itself
  rendered from `package`, so `package` answers when state cannot be read."
  [data]
  (or (not-empty (str (:name data)))
      (not-empty (str (:package data)))
      "once"))

(defn ansible-local-step
  [opts]
  (let [dir (tool-dir opts "ansible-local")
        data (data-fn opts)
        specs [(template-spec (static-template "ansible-local" "ansible.cfg")
                              (str dir "/ansible.cfg")
                              data)
               (template-spec (static-template "ansible-local" "inventory.ini")
                              (str dir "/inventory.ini")
                              data)
               (template-spec (static-template "ansible-local" "main.yml")
                              (str dir "/main.yml")
                              data)]
        delete? (= :delete (:green/event opts))
        ;; The playbook's variables are Ansible's, not Selmer's, so they arrive
        ;; as extra-vars: the local inventory targets localhost only and carries
        ;; no host vars of its own. `name` is reserved in Ansible, hence
        ;; host_alias. block_state drives blockinfile in both directions.
        config {:dir dir
                :inventory "inventory.ini"
                :playbooks {:create "main.yml" :delete "main.yml"}
                :extra-vars {:host_alias (local-host-alias data)
                             :ip (:ip data)
                             :user (:user data)
                             :block_state (if delete? "absent" "present")}}]
    (cond
      (= :build (:green/event opts))
      (sc/scaffold opts specs)

      ;; Delete renders the playbook so it can run, removes the managed block
      ;; from ~/.ssh/config, then deletes the rendered tree. Mirrors
      ;; tofu-with-spec: the tool runs while its inputs still exist.
      delete?
      (let [rendered (-> opts
                         (assoc :green/event :create)
                         (sc/scaffold specs)
                         (assoc :green/event :delete))
            result (ansible/ansible-step rendered config)]
        (if (failed? result)
          result
          (sc/scaffold result specs)))

      :else
      (ansible/ansible-step (sc/scaffold opts specs) config))))
