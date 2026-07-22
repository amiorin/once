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
  (str (io/file (or (:workdir opts) ".dist")
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

(defn- tofu-with-spec
  [opts dir specs fallback result-key]
  (cond
    (= :build (:green/event opts))
    (cond-> (sc/scaffold opts specs)
      result-key (assoc result-key fallback))

    (= :delete (:green/event opts))
    (let [rendered (-> opts
                       (assoc :green/event :create)
                       (sc/scaffold specs)
                       (assoc :green/event :delete))
          result (tofu/tofu-step rendered {:dir dir})]
      (if (failed? result)
        result
        (sc/scaffold result specs)))

    :else
    (let [rendered (sc/scaffold opts specs)
          result (tofu/tofu-step rendered {:dir dir})]
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
                       :smtp_port (:no-infra-smtp-port opts)
                       :smtp_use_starttls true}
           "resend" {:smtp_username (:resend-username opts)
                     :smtp_password (:resend-password opts)
                     :smtp_server (:resend-server opts)
                     :smtp_port (:resend-port opts)
                     :smtp_use_starttls true}
           {})))

(defn tofu-compute-step
  [opts]
  (let [provider (or (:provider-compute opts) "hcloud")
        dir (tool-dir opts "tofu-compute")
        specs [(template-spec (tool-template "tofu" provider "main.tf")
                              (str dir "/main.tf")
                              opts)]]
    (tofu-with-spec opts dir specs (fallback-compute-params opts) :once/compute-params)))

(defn tofu-smtp-step
  [opts]
  (let [provider (or (:provider-smtp opts) "resend")
        dir (tool-dir opts "tofu-smtp")
        specs [(template-spec (tool-template "tofu-smtp" provider "main.tf")
                              (str dir "/main.tf")
                              opts)
               (template-spec (tool-template "tofu-smtp" provider "mailrc")
                              (str dir "/mailrc")
                              opts)]]
    (tofu-with-spec opts dir specs (fallback-smtp-params opts) :once/smtp-params)))

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
    (tofu-with-spec opts dir specs {} nil)))

(defn tofu-smtp-post-step
  [opts]
  (let [provider (or (:provider-smtp opts) "resend")
        dir (tool-dir opts "tofu-smtp-post")
        specs [(template-spec (tool-template "tofu-smtp-post" provider "main.tf")
                              (str dir "/main.tf")
                              opts)]]
    (tofu-with-spec opts dir specs {} nil)))

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

(defn ansible-once
  [{:keys [once domain] :as opts}]
  (let [smtp (-> (select-keys opts [:smtp_server :smtp_port :smtp_username :smtp_password])
                 (assoc :smtp_from (format "Info <info@notifications.%s>" domain)))
        once (update once :applications
                     (fn [applications]
                       (mapv #(merge % smtp) applications)))
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
                              data)]]
    (sc/scaffold opts specs)))
