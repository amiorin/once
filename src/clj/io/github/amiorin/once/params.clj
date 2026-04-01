(ns io.github.amiorin.once.params
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [big-config.render :as render]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [clojure.string :as str]
   [com.rpl.specter :as s]
   [io.github.amiorin.once.options :as options]))

(def prefix "BC_VAR_")

(defn- read-bc-vars [opts]
  (let [params-from-env (->> (System/getenv)
                             (filter #(str/starts-with? % prefix))
                             (map (fn [[k v]] [(-> k
                                                   (subs (count prefix))
                                                   str/lower-case
                                                   (str/replace "_" "-")
                                                   (str/replace "." "-")
                                                   keyword) v]))
                             (into {}))]
    (merge-with merge opts {::workflow/params params-from-env})))

(comment
  (read-bc-vars {::workflow/params {:foo :bar}}))

(defn tofu-params
  [opts]
  (let [dir (workflow/path opts :io.github.amiorin.once.tools/tofu)]
    (merge-with merge opts {::workflow/params (if (fs/exists? dir)
                                                (-> (p/shell {:dir dir
                                                              :out :string} "tofu output --json")
                                                    :out
                                                    (json/parse-string keyword)
                                                    (->> (s/select-one [:params :value])))
                                                {:ip "192.168.0.1"})})))

(defn tofu-smtp-params
  [opts]
  (let [dir (workflow/path opts :io.github.amiorin.once.tools/tofu-smtp)]
    (merge-with merge opts {::workflow/params (if (fs/exists? dir)
                                                (-> (p/shell {:dir dir
                                                              :out :string} "tofu output --json")
                                                    :out
                                                    (json/parse-string keyword)
                                                    (->> (s/select-one [:params :value :resend_domain]))
                                                    (select-keys [:records :id]))
                                                {:records []})})))

(comment
  (debug tap-values
    (-> {::render/profile "oci"}
        (workflow/new-prefix :io.github.amiorin.once.package/start-create-or-delete)
        tofu-smtp-params))
  (-> tap-values))

(def opts-fn (comp tofu-params tofu-smtp-params read-bc-vars))

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
