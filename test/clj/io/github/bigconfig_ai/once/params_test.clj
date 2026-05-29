(ns io.github.bigconfig-ai.once.params-test
  (:require
   [big-config.workflow :as workflow]
   [clojure.test :refer [deftest is]]
   [io.github.bigconfig-ai.once.params :as params]))

(defn- missing-prefix []
  (str "/tmp/once-params-test-" (random-uuid)))

(deftest tofu-params-falls-back-to-profile-compute-values
  (let [result (::workflow/params
                (params/tofu-params {::workflow/prefix (missing-prefix)
                                     ::workflow/params {:provider-compute "oci"
                                                        :package "space"}}))]
    (is (= {:ip "192.168.0.1"
            :sudoer "ubuntu"
            :uid "1001"
            :name "space"
            :user "ubuntu"}
           (select-keys result [:ip :sudoer :uid :name :user]))))

  (let [result (::workflow/params
                (params/tofu-params {::workflow/prefix (missing-prefix)
                                     ::workflow/params {:provider-compute "no-infra"
                                                        :package "existing"
                                                        :no-infra-compute-ip "203.0.113.10"
                                                        :no-infra-compute-sudoer "admin"
                                                        :no-infra-compute-user "deploy"
                                                        :no-infra-compute-uid "1002"}}))]
    (is (= {:ip "203.0.113.10"
            :sudoer "admin"
            :uid "1002"
            :name "existing"
            :user "deploy"}
           (select-keys result [:ip :sudoer :uid :name :user])))))

(deftest tofu-smtp-params-falls-back-to-profile-smtp-values
  (let [result (::workflow/params
                (params/tofu-smtp-params {::workflow/prefix (missing-prefix)
                                          ::workflow/params {:provider-smtp "resend"
                                                             :resend-username "resend"
                                                             :resend-password "secret"
                                                             :resend-server "smtp.resend.com"
                                                             :resend-port 587}}))]
    (is (= {:id "domain-id-not-defined"
            :records []
            :smtp_username "resend"
            :smtp_password "secret"
            :smtp_server "smtp.resend.com"
            :smtp_port 587
            :smtp_use_starttls true}
           (select-keys result [:id :records :smtp_username :smtp_password :smtp_server :smtp_port :smtp_use_starttls])))))
