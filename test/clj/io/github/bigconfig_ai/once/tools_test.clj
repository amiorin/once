(ns io.github.bigconfig-ai.once.tools-test
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.ansible :as ansible]
   [io.github.bigconfig-ai.once.tools :as tools]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "once-tools-test"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree!
  [path]
  (doseq [f (reverse (file-seq (io/file path)))]
    (io/delete-file f true)))

(defn- local-opts
  [workdir event]
  {:workdir workdir
   :profile "test"
   :green/event event
   :name "once-test"
   :ip "203.0.113.10"
   :user "root"})

(defn- once-opts
  [provider-smtp password]
  {:provider-smtp provider-smtp
   :smtp_server "smtp.example.com"
   :smtp_port 587
   :smtp_username "user"
   :smtp_password password
   :once {:applications [{:host "www.example.com"
                          :image "ghcr.io/example/site:latest"}]}})

(deftest ansible-once-never-renders-the-smtp-password
  (testing "resend defers the password to a play-time env lookup"
    (let [yaml (tools/ansible-once (once-opts "resend" "re_a_real_secret"))]
      (is (not (str/includes? yaml "re_a_real_secret")))
      (is (str/includes?
           yaml
           "smtp_password: \"{{ lookup('env','GREEN_PAR_RESEND_PASSWORD') }}\""))
      (testing "the non-secret fields still render as values"
        (is (str/includes? yaml "smtp_username: \"user\""))
        (is (str/includes? yaml "smtp_from: \"Info <info@notifications.example.com>\"")))))

  (testing "no-infra points at its own variable"
    (let [yaml (tools/ansible-once (once-opts "no-infra" "another_secret"))]
      (is (not (str/includes? yaml "another_secret")))
      (is (str/includes?
           yaml
           "smtp_password: \"{{ lookup('env','GREEN_PAR_NO_INFRA_SMTP_PASSWORD') }}\""))))

  (testing "an unset password stays absent, so the deploy flag is omitted"
    (let [yaml (tools/ansible-once (once-opts "resend" nil))]
      (is (not (str/includes? yaml "lookup("))))))

(deftest ansible-once-never-renders-application-env-secrets
  (let [opts (-> (once-opts "resend" "re_a_real_secret")
                 (assoc-in [:once :applications 0 :env]
                           {"DATABASE_URL" :app-database-url
                            "SECRET_KEY_BASE" :app-secret-key-base})
                 ;; the launcher has already overlaid the GREEN_PAR_* values
                 (assoc :app-database-url "postgres://user:hunter2@db/app"
                        :app-secret-key-base "s3cret-key-base"))
        yaml (tools/ansible-once opts)]
    (is (not (str/includes? yaml "hunter2")))
    (is (not (str/includes? yaml "s3cret-key-base")))
    (is (str/includes?
         yaml
         "\"DATABASE_URL={{ lookup('env','GREEN_PAR_APP_DATABASE_URL') }}\""))
    (is (str/includes?
         yaml
         "\"SECRET_KEY_BASE={{ lookup('env','GREEN_PAR_APP_SECRET_KEY_BASE') }}\""))

    (testing "an :env list is passed through as written"
      (let [yaml (tools/ansible-once
                  (assoc-in opts [:once :applications 0 :env] ["TARGET_EMAIL=forms@example.com"]))]
        (is (str/includes? yaml "TARGET_EMAIL=forms@example.com"))))))

(deftest dns-records-follow-the-applications
  (let [json (tools/render-fn :apps {:ip "203.0.113.10"
                                     :applications [{:host "www.example.com"}
                                                    {:host "app.example.com"}]})
        records (get-in (json/parse-string json true)
                        [:resource :cloudflare_dns_record])]
    (testing "one record per application host, and nothing else"
      (is (= 2 (count records)))
      (is (= #{"www.example.com" "app.example.com"}
             (set (map :name (vals records)))))
      (is (not (str/includes? json "\"*\"")) "no wildcard record")
      (is (not (str/includes? json "\"@\"")) "no apex record"))

    (testing "every host is a proxied A record pointing at the server"
      (doseq [record (vals records)]
        (is (= "A" (:type record)))
        (is (true? (:proxied record)))
        (is (= 1 (:ttl record)))
        (is (= "203.0.113.10" (:content record)))
        (is (= "${data.cloudflare_zone.domain.id}" (:zone_id record))))))

  (testing "no applications, no records"
    (is (empty? (json/parse-string (tools/render-fn :apps {:ip "203.0.113.10"
                                                           :applications []}))))))

(deftest ansible-local-renders-and-runs-the-playbook
  (let [workdir (temp-dir)]
    (try
      (testing "build renders the playbook without invoking ansible"
        (with-redefs [ansible/ansible-step
                      (fn [& _] (throw (ex-info "ansible must not run for build" {})))]
          (let [result (tools/ansible-local-step (local-opts workdir :build))]
            (is (zero? (:green/exit result)))))
        (let [main (io/file (tools/tool-dir {:workdir workdir :profile "test"}
                                            "ansible-local")
                            "main.yml")]
          (is (.exists main))
          (testing "the SSH block leaves the identity to ssh-agent"
            (let [content (slurp main)]
              (is (str/includes? content "Host {{ host_alias }}"))
              (is (not (str/includes? content "IdentityFile")))
              (is (not (str/includes? content "IdentitiesOnly")))))))

      (testing "create runs the playbook with the vars it needs"
        (let [calls (atom [])]
          (with-redefs [ansible/ansible-step
                        (fn [opts args] (swap! calls conj args) opts)]
            (tools/ansible-local-step (local-opts workdir :create)))
          (is (= 1 (count @calls)))
          (let [{:keys [inventory playbooks extra-vars]} (first @calls)]
            (is (= "inventory.ini" inventory))
            (is (= {:create "main.yml" :delete "main.yml"} playbooks))
            (testing "name is reserved in Ansible, so it is passed as host_alias"
              (is (= {:host_alias "once-test"
                      :ip "203.0.113.10"
                      :user "root"
                      :block_state "present"}
                     extra-vars))))))

      (testing "delete drops the managed block, then removes the rendered files"
        (let [main (io/file (tools/tool-dir {:workdir workdir :profile "test"}
                                            "ansible-local")
                            "main.yml")
              calls (atom [])]
          (with-redefs [ansible/ansible-step
                        (fn [opts args]
                          ;; the playbook must still exist when ansible runs
                          (swap! calls conj (assoc args :playbook-present?
                                                   (.exists main)))
                          opts)]
            (let [result (tools/ansible-local-step (local-opts workdir :delete))]
              (is (zero? (:green/exit result)))))
          (is (= 1 (count @calls)) "ansible runs on delete")
          (let [{:keys [extra-vars playbook-present?]} (first @calls)]
            (is (true? playbook-present?))
            (is (= "absent" (:block_state extra-vars))
                "blockinfile removes the block rather than writing it")
            (is (= "once-test" (:host_alias extra-vars))
                "the marker must match what create wrote"))
          (is (not (.exists main)) "the rendered tree is removed afterwards")))

      (testing "the alias falls back to the profile when Tofu state is unreadable"
        (let [calls (atom [])]
          (with-redefs [ansible/ansible-step
                        (fn [opts args] (swap! calls conj args) opts)]
            (tools/ansible-local-step (-> (local-opts workdir :delete)
                                          (dissoc :name))))
          (is (= "test" (:host_alias (:extra-vars (first @calls)))))))

      (finally
        (delete-tree! workdir)))))
