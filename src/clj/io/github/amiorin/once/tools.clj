(ns io.github.amiorin.once.tools
  (:require
   [big-config :as bc]
   [big-config.pluggable :as pluggable]
   [big-config.render :as render]
   [big-config.run :as run]
   [big-config.step-fns :as step-fns]
   [big-config.utils :as utils :refer [debug keyword->path]]
   [big-config.workflow :as workflow]
   [big-tofu.core :refer [->Construct add-suffix construct]]
   [cheshire.core :as json]
   [clojure.string :as str]
   [io.github.amiorin.once.options :as options]
   [io.github.amiorin.once.params :as params]))

(def step-fns [workflow/print-step-fn
               (step-fns/->exit-step-fn ::workflow/end)
               (step-fns/->print-error-step-fn ::workflow/end)])

(def delimiters {:tag-open \<
                 :tag-close \>
                 :filter-open \{
                 :filter-close \}})

(defn run-steps-with-plugin
  [plugin-step step-fns opts]
  (-> (update opts ::workflow/steps #(reduce (fn [steps step]
                                               (into steps (if (= step :render)
                                                             [step plugin-step]
                                                             [step]))) [] %))
      (->> (workflow/run-steps step-fns))))

(def plugin-step ::render-tofu-backend)

(defn keyword->name
  "Converts a keyword into a file name string. Namespaces are treated as
  words and dots are converted into dashes.
  Example: `:big-config.core/foo` -> `\"big-config-core-foo\"`"
  [kw]
  (let [full-str (if-let [ns (namespace kw)]
                   (str ns "-" (name kw))
                   (name kw))]
    (-> full-str
        (str/replace "/" "-")
        (str/replace "." "-"))))

(defmethod pluggable/handle-step plugin-step
  [_step step-fns {:keys [::workflow/name] :as opts}]
  (let [prepare-keys [::workflow/name ::workflow/path-fn ::workflow/prefix ::workflow/params]
        plugin-opts (-> (workflow/prepare {::workflow/name name
                                           ::render/templates [{:template (keyword->path ::tofu-backend)
                                                                :overwrite true
                                                                :provider-backend "s3"
                                                                :data-fn (fn [data {:keys [::workflow/name ::workflow/prefix]}]
                                                                           (assoc data :s3-key (format "%s/%s.tfstate" prefix (keyword->name name))))
                                                                :transform [["{{ provider-backend }}"
                                                                             delimiters]]}]}
                                          (select-keys opts prepare-keys))
                        (->> (render/templates step-fns)))]
    (-> opts
        (merge (select-keys plugin-opts [::bc/exit ::bc/err]))
        (update plugin-step (fnil conj []) plugin-opts))))

(defn tofu
  [step-fns opts]
  (let [opts (workflow/prepare {::workflow/name ::tofu
                                ::render/templates [{:template (keyword->path ::tofu)
                                                     :overwrite true
                                                     :provider-compute "hcloud"
                                                     :transform [["{{ provider-compute }}"
                                                                  delimiters]]}]}
                               opts)]
    (run-steps-with-plugin plugin-step step-fns opts)))

(defn tofu*
  [args & [opts]]
  (let [opts (merge (workflow/parse-args args)
                    {::bc/env :shell}
                    opts)]
    (tofu step-fns opts)))

(comment
  (debug tap-values
    (tofu* "render"
           (params/once-opts (merge options/oci
                                    {::bc/env :repl
                                     ::run/shell-opts {:err *err*
                                                       :out *err*}}))))
  (-> tap-values))

(defn tofu-smtp
  [step-fns opts]
  (let [opts (workflow/prepare {::workflow/name ::tofu-smtp
                                ::render/templates [{:template (keyword->path ::tofu-smtp)
                                                     :overwrite true
                                                     :data-fn (fn [{:keys [ip] :as data} _]
                                                                (assoc data :ip (or ip "192.168.0.1")))
                                                     :provider-smtp "resend"
                                                     :transform [["{{ provider-smtp }}"
                                                                  delimiters]]}]}
                               opts)]
    (run-steps-with-plugin plugin-step step-fns opts)))

(defn tofu-smtp*
  [args & [opts]]
  (let [opts (merge (workflow/parse-args args)
                    {::bc/env :shell}
                    opts)]
    (tofu-smtp step-fns opts)))

(comment
  (debug tap-values
    (tofu-smtp* "render tofu:init tofu:apply:-auto-approve"
                (params/once-opts (merge options/oci
                                         {::bc/env :repl
                                          ::run/shell-opts {:err *err*
                                                            :out *err*}}))))
  (-> tap-values))

(defn render-fn
  [src {:keys [records cloudflare-zone-id]}]
  (case src
    :smtp (let [cloudflare-recores (for [{:keys [name priority record type value]} records]
                                     (->Construct :resource
                                                  :cloudflare_record
                                                  (add-suffix ::smtp-dns (format "-%s-%s" record type))
                                                  (cond-> {:zone_id cloudflare-zone-id
                                                           :name name
                                                           :ttl "60"
                                                           :type type
                                                           :proxied false}
                                                    (= type "TXT") (merge {:content (format "\"%s\"" value)})
                                                    (= type "MX") (merge {:priority priority
                                                                          :content value}))))
                m (or (->> cloudflare-recores
                           (map construct)
                           (apply utils/deep-merge)
                           utils/sort-nested-map) {})]
            (json/generate-string m {:pretty true}))))

(comment
  (debug tap-values
    #_(render-fn :smtp {:cloudflare-zone-id "cloudflare-zone-id"
                        :records []})
    (render-fn :smtp {:cloudflare-zone-id "cloudflare-zone-id"
                      :records [{:name "name"
                                 :priority 10
                                 :record "SPF"
                                 :type "TXT"
                                 :value "v=spf1 include:amazonses.com ~all"}]}))
  (-> tap-values))

(defn tofu-dns
  [step-fns opts]
  (let [opts (workflow/prepare {::workflow/name ::tofu-dns
                                ::render/templates [{:template (keyword->path ::tofu-dns)
                                                     :overwrite true
                                                     :data-fn (fn [{:keys [ip] :as data} _]
                                                                (assoc data :ip (or ip "192.168.0.1")))
                                                     :provider-dns "cloudflare"
                                                     :transform [["{{ provider-dns }}"
                                                                  delimiters]
                                                                 [render-fn
                                                                  {:smtp "smtp.tf.json"}
                                                                  delimiters]]}]}
                               opts)]
    (run-steps-with-plugin plugin-step step-fns opts)))

(defn tofu-dns*
  [args & [opts]]
  (let [opts (merge (workflow/parse-args args)
                    {::bc/env :shell}
                    opts)]
    (tofu-dns step-fns opts)))

(comment
  (debug tap-values
    (tofu-dns* "render tofu:init tofu:plan"
               (params/once-opts (merge options/oci
                                        {::bc/env :repl
                                         ::run/shell-opts {:err *err*
                                                           :out *err*}}))))
  (-> tap-values))

(defn tofu-smtp-post
  [step-fns opts]
  (let [opts (workflow/prepare {::workflow/name ::tofu-smtp-post
                                ::render/templates [{:template (keyword->path ::tofu-smtp-post)
                                                     :overwrite true
                                                     :provider-smtp "resend"
                                                     :transform [["{{ provider-smtp }}"
                                                                  delimiters]]}]}
                               opts)]
    (run-steps-with-plugin plugin-step step-fns opts)))

(defn tofu-smtp-post*
  [args & [opts]]
  (let [opts (merge (workflow/parse-args args)
                    {::bc/env :shell}
                    opts)]
    (tofu-smtp-post step-fns opts)))

(comment
  (debug tap-values
    (tofu-smtp-post* "render"
                     (params/once-opts (merge options/oci
                                              {::bc/env :repl
                                               ::run/shell-opts {:err *err*
                                                                 :out *err*}}))))
  (-> tap-values))

(defn data-fn [{:keys [ip sudoer] :as data} _]
  (let [sudoer (or sudoer "root")
        hosts [(or ip "64.227.72.100")]]
    (merge data {:sudoer sudoer
                 :hosts hosts
                 :users []})))

(defn inventory
  [{:keys [sudoer hosts users]}]
  (let [users (-> (filter (complement :remove) users)
                  (->> (map #(for [host hosts]
                               (assoc % :host host))))
                  flatten)
        admins (-> [{:ansible_user sudoer}]
                   (->> (map #(for [host hosts]
                                (-> %
                                    (merge {:host host
                                            :name sudoer})))))
                   flatten)
        users-hosts (reduce #(let [{:keys [name uid host]} %2]
                               (assoc %1 (format "%s@%s" name host) {:ansible_host host
                                                                     :ansible_user name
                                                                     :uid uid})) {} users)
        admins-hosts (reduce #(let [{:keys [name host]} %2]
                                (assoc %1 (format "root@%s" host) {:ansible_host host
                                                                   :ansible_user name})) {} admins)
        inventory {:all {:children {:admin {:hosts admins-hosts}
                                    :users {:hosts users-hosts}}}}]
    (json/generate-string inventory {:pretty true})))

(defn render
  [target data]
  (case target
    :inventory (inventory data)))

(comment
  (render :inventory (data-fn {} {})))

(defn ansible
  [step-fns opts]
  (let [opts (workflow/prepare {::workflow/name ::ansible
                                ::render/templates [{:template (keyword->path ::ansible)
                                                     :overwrite true
                                                     :data-fn data-fn
                                                     :transform [["."
                                                                  :raw]
                                                                 [render
                                                                  {:inventory "inventory.json"}
                                                                  :raw]]}]}
                               opts)]
    (workflow/run-steps step-fns opts)))

(defn ansible*
  [args & [opts]]
  (let [opts (merge (workflow/parse-args args)
                    {::bc/env :shell}
                    opts)]
    (ansible step-fns opts)))

(comment
  (debug tap-values
    (ansible* "render ansible-playbook:main.yml" {::bc/env :repl
                                                  ::run/shell-opts {:err *err*
                                                                    :out *err*}}))
  (-> tap-values))

(defn ansible-local
  [step-fns opts]
  (let [opts (workflow/prepare {::workflow/name ::ansible-local
                                ::render/templates [{:template (keyword->path ::ansible-local)
                                                     :overwrite true
                                                     :transform [["."]]}]}
                               opts)]
    (workflow/run-steps step-fns opts)))

(defn ansible-local*
  [args & [opts]]
  (let [opts (merge (workflow/parse-args args)
                    {::bc/env :shell}
                    opts)]
    (ansible-local step-fns opts)))

(comment
  (debug tap-values
    (ansible-local* "render ansible-playbook:main.yml" {::bc/env :repl
                                                        ::workflow/params {:ip "159.223.11.241"}
                                                        ::run/shell-opts {:err *err*
                                                                          :out *out*}}))
  (-> tap-values))

