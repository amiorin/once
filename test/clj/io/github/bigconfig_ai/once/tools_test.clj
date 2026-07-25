(ns io.github.bigconfig-ai.once.tools-test
  (:require
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
            (is (= {:create "main.yml"} playbooks))
            (testing "name is reserved in Ansible, so it is passed as host_alias"
              (is (= {:host_alias "once-test" :ip "203.0.113.10" :user "root"}
                     extra-vars))))))

      (testing "delete removes the rendered files without invoking ansible"
        (with-redefs [ansible/ansible-step
                      (fn [& _] (throw (ex-info "ansible must not run for delete" {})))]
          (let [result (tools/ansible-local-step (local-opts workdir :delete))]
            (is (zero? (:green/exit result))))))

      (finally
        (delete-tree! workdir)))))
