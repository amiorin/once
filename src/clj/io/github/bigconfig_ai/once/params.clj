(ns io.github.bigconfig-ai.once.params
  (:require
   [babashka.process :as p]
   [big-config.render :as render]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [com.rpl.specter :as s]
   [io.github.bigconfig-ai.once.options :as options]))

(defn- tofu-output
  [dir]
  (try (-> (p/shell {:dir dir
                    :out :string
                    :err :string} "tofu output --json")
           :out
           (json/parse-string keyword)
           (->> (s/select-one [:params :value])))
       (catch Exception _
         nil)))

(defn- fallback-compute-params
  [{:keys [package provider-compute] :as params}]
  (let [name (or package "once")]
    (case provider-compute
      "oci" {:ip "192.168.0.1"
             :sudoer "ubuntu"
             :uid "1001"
             :name name
             :user "ubuntu"}
      "no-infra" (cond-> {:ip (or (:no-infra-compute-ip params) "192.168.0.1")
                          :sudoer (or (:no-infra-compute-sudoer params) "root")
                          :name name
                          :user (or (:no-infra-compute-user params) "root")}
                   (:no-infra-compute-uid params) (assoc :uid (:no-infra-compute-uid params)))
      {:ip "192.168.0.1"
       :sudoer "root"
       :name name
       :user "root"})))

(defn- fallback-smtp-params
  [{:keys [provider-smtp] :as params}]
  (merge {:id "domain-id-not-defined"
          :records []}
         (case provider-smtp
           "no-infra" {:smtp_username (:no-infra-smtp-username params)
                       :smtp_password (:no-infra-smtp-password params)
                       :smtp_server (:no-infra-smtp-server params)
                       :smtp_port (:no-infra-smtp-port params)
                       :smtp_use_starttls true}
           "resend" {:smtp_username (:resend-username params)
                     :smtp_password (:resend-password params)
                     :smtp_server (:resend-server params)
                     :smtp_port (:resend-port params)
                     :smtp_use_starttls true}
           {})))

(defn tofu-params
  [opts]
  (let [params (::workflow/params opts)
        dir (workflow/path opts :io.github.bigconfig-ai.once.tools/tofu)]
    (merge-with merge opts {::workflow/params (merge (fallback-compute-params params)
                                                     (or (tofu-output dir) {}))})))

(defn tofu-smtp-params
  [opts]
  (let [params (::workflow/params opts)
        dir (workflow/path opts :io.github.bigconfig-ai.once.tools/tofu-smtp)]
    (merge-with merge opts {::workflow/params (merge (fallback-smtp-params params)
                                                     (or (tofu-output dir) {}))})))

(comment
  (debug tap-values
    (-> {::render/profile "profile-gamma"}
        (workflow/new-prefix :io.github.bigconfig-ai.once.package/start-create-or-delete)
        tofu-smtp-params))
  (-> tap-values))

(def opts-fn (comp tofu-params tofu-smtp-params workflow/read-bc-pars))

(comment
  (debug tap-values
    (-> options/bb
        (workflow/new-prefix :io.github.bigconfig-ai.once.package/start-create-or-delete)
        opts-fn))
  (-> tap-values))

(def once-opts (comp opts-fn #(workflow/new-prefix % :io.github.bigconfig-ai.once.package/start-create-or-delete)))

(comment
  (workflow/new-prefix {} :io.github.bigconfig-ai.once.package/start-create-or-delete)
  (once-opts options/bb))
