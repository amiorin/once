(ns io.github.amiorin.once.params
  (:require
   [babashka.process :as p]
   [big-config.render :as render]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [com.rpl.specter :as s]
   [io.github.amiorin.once.options :as options]))

(defn tofu-params
  [opts]
  (let [dir (workflow/path opts :io.github.amiorin.once.tools/tofu)]
    (merge-with merge opts {::workflow/params (try (-> (p/shell {:dir dir
                                                                 :out :string} "tofu output --json")
                                                       :out
                                                       (json/parse-string keyword)
                                                       (->> (s/select-one [:params :value])))
                                                   (catch Exception _
                                                     {:ip "192.168.0.1"}))})))

(defn tofu-smtp-params
  [opts]
  (let [dir (workflow/path opts :io.github.amiorin.once.tools/tofu-smtp)]
    (merge-with merge opts {::workflow/params (try (-> (p/shell {:dir dir
                                                                 :out :string} "tofu output --json")
                                                       :out
                                                       (json/parse-string keyword)
                                                       (->> (s/select-one [:params :value])))
                                                   (catch Exception _
                                                     {:id "domain-id-not-defined"
                                                      :records []}))})))

(comment
  (debug tap-values
    (-> {::render/profile "space"}
        (workflow/new-prefix :io.github.amiorin.once.package/start-create-or-delete)
        tofu-smtp-params))
  (-> tap-values))

(def opts-fn (comp tofu-params tofu-smtp-params workflow/read-bc-pars))

(comment
  (debug tap-values
    (-> options/oci
        (workflow/new-prefix :io.github.amiorin.once.package/start-create-or-delete)
        opts-fn))
  (-> tap-values))

(def once-opts (comp opts-fn #(workflow/new-prefix % :io.github.amiorin.once.package/start-create-or-delete)))

(comment
  (workflow/new-prefix {} :io.github.amiorin.once.package/start-create-or-delete)
  (once-opts options/oci))
