(ns io.github.bigconfig-ai.once.github-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.process :as process]
   [io.github.bigconfig-ai.once.github :as sut]
   [io.github.bigconfig-ai.once.tools :as tools]))

(def ^:private opts
  {:profile "prod"
   :ip "203.0.113.10"
   :github-token "gh_token"
   :once {:applications [{:host "www.example.com"
                          :github "acme/site"}
                         {:host "www.example.net"}]}})

(defn- recorder
  "A fake runner in place of green.process/run-with-timeout. Records every
  invocation so a test can assert the exact argv without a process."
  ([] (recorder 0))
  ([exit]
   (let [calls (atom [])]
     {:calls calls
      :run-fn (fn [args opts* _timeout]
                (swap! calls conj {:args args :env (:extra-env opts*)})
                {:exit exit :out "" :err (if (zero? exit) "" "boom")})})))

;;; ------------------------------------------------------------- the gh commands

(deftest only-applications-naming-a-repository-are-published
  (let [keys [{:host "www.example.com" :github "acme/site" :private-file "/tmp/k"}]
        cmds (sut/commands (assoc opts :green/event :create :once/deploy-keys keys))]
    (is (= 5 (count cmds)))
    (is (every? #(str/includes? (:label %) "acme/site") cmds))))

(deftest publish-sends-the-address-as-a-variable-and-the-key-as-a-secret
  (let [[environment ip user known-hosts secret] (sut/publish-commands
                          opts
                          {:github "acme/site" :private-file "/tmp/once/key-0"})]
    (testing "the environment is created first — writing into one that does not
              exist is a 404, and nothing guarantees a workflow made it"
      (is (= ["gh" "api" "--method" "PUT" "--silent"
              "repos/acme/site/environments/prod"]
             (vec (:args environment)))))
    (testing "the address and user are variables — DNS already reveals them, and
              masking them only makes CI logs harder to read"
      (is (= ["gh" "variable" "set" "SERVER_IP"
              "--repo" "acme/site" "--env" "prod"
              "--body" "203.0.113.10"]
             (vec (:args ip))))
      (is (= ["gh" "variable" "set" "SERVER_USER"
              "--repo" "acme/site" "--env" "prod"
              "--body" "deploy"]
             (vec (:args user)))))

    (testing "the environment is named after the profile"
      (is (every? #(str/includes? (str/join " " (:args %)) "--env prod")
                  [ip user])))

    (testing "the host key is pinned as a variable, so CI stops asking the
              network who the server is on every deploy"
      (is (= ["gh" "variable" "set" "SSH_KNOWN_HOSTS"
              "--repo" "acme/site" "--env" "prod" "--body" ""]
             (vec (:args known-hosts)))))

    (testing "the private key is read from its file, never passed as an argument"
      (is (= ["sh" "-c"] (take 2 (:args secret))))
      (is (str/includes? (last (:args secret)) "< '/tmp/once/key-0'"))
      (is (not-any? #(str/includes? (str %) "PRIVATE KEY") (:args secret))))))

(deftest revoking-needs-no-key-material
  (let [cmds (sut/revoke-commands opts {:github "acme/site"})]
    (is (= [["gh" "variable" "delete" "SERVER_IP" "--repo" "acme/site" "--env" "prod"]
            ["gh" "variable" "delete" "SERVER_USER" "--repo" "acme/site" "--env" "prod"]
            ["gh" "variable" "delete" "SSH_KNOWN_HOSTS" "--repo" "acme/site" "--env" "prod"]
            ["gh" "secret" "delete" "SSH_PRIVATE_KEY" "--repo" "acme/site" "--env" "prod"]]
           (mapv (comp vec :args) cmds)))))

;;; -------------------------------------------------------------------- the step

(deftest a-build-never-reaches-github
  (testing "wire-fn runs the same branch for build and create, so the event
            check here is what keeps a build offline"
    (let [{:keys [calls run-fn]} (recorder)]
      (sut/github-step (assoc opts :green/event :build) run-fn)
      (is (= [] @calls)))))

(deftest the-token-travels-in-the-environment
  (let [{:keys [calls run-fn]} (recorder)
        keys [{:host "www.example.com" :github "acme/site" :private-file "/tmp/k"}]]
    (sut/github-step (assoc opts :green/event :create :once/deploy-keys keys) run-fn)
    (is (seq @calls))
    (testing "every gh call carries it; the host-key read is an ssh call and
              has no business with a GitHub token"
      (is (every? #(= {"GH_TOKEN" "gh_token"} (:env %))
                  (filter #(= "gh" (first (:args %))) @calls))))))

(deftest a-failed-publish-fails-the-step
  (let [{:keys [run-fn]} (recorder 1)
        keys [{:host "www.example.com" :github "acme/site" :private-file "/tmp/k"}]
        result (sut/github-step (assoc opts :green/event :create :once/deploy-keys keys)
                                run-fn)]
    (is (= 1 (:green/exit result)))
    (is (str/includes? (:green/err result) "acme/site"))))

(deftest a-failed-revoke-does-not
  (testing "delete has to be re-runnable, and a missing secret is the state it
            is trying to reach"
    (let [{:keys [calls run-fn]} (recorder 1)
          result (sut/github-step (assoc opts :green/event :delete) run-fn)]
      (is (= 0 (:green/exit result)))
      (is (= 4 (count @calls)) "every revoke is attempted, not just the first"))))

(deftest a-host-key-becomes-a-known-hosts-line
  (testing "the trailing comment is the server's own hostname at key generation
            time and means nothing to a client"
    (is (= "203.0.113.10 ssh-ed25519 AAAAC3Nz"
           (sut/known-hosts-line "203.0.113.10"
                                 "ssh-ed25519 AAAAC3Nz root@once\n"))))
  (testing "anything that is not a public key yields nothing to pin"
    (is (nil? (sut/known-hosts-line "203.0.113.10" "")))
    (is (nil? (sut/known-hosts-line "203.0.113.10" "No such file or directory")))))

;;; ------------------------------------------------------- the rendered key lines

(deftest each-key-is-pinned-to-its-own-host
  (let [keys [{:host "www.example.com" :public "ssh-ed25519 AAAA one"}
              {:host "www.example.net" :public "ssh-ed25519 BBBB two"}]
        content (tools/deploy-keys-content (assoc opts :once/deploy-keys keys))]
    (is (= (str "restrict,command=\"/usr/local/bin/deploy www.example.com\" ssh-ed25519 AAAA one\n"
                "restrict,command=\"/usr/local/bin/deploy www.example.net\" ssh-ed25519 BBBB two\n")
           content))))

(deftest a-build-renders-a-fixed-placeholder
  (testing "a fresh key per build would make the artifact nondeterministic and
            break byte parity between the colours"
    (let [a (sut/placeholder-keys opts)
          b (sut/placeholder-keys opts)]
      (is (= a b))
      (is (= 1 (count a)) "only applications naming a repository get a key")
      (is (str/ends-with? (:public (first a)) "once-deploy-prod-www.example.com")))))

(deftest the-key-comment-carries-no-clock-reading
  (is (= "once-deploy-prod-www.example.com"
         (sut/key-comment opts "www.example.com"))))

;;; ------------------------------------------------------------- the reconciler

(def ^:private reconciler
  (.getAbsolutePath
   (io/file "src/resources/io/github/bigconfig-ai/once/tools/ansible/files/authorized-keys")))

(defn- line [host key-body]
  (format "restrict,command=\"/usr/local/bin/deploy %s\" %s" host key-body))

(defn- reconcile!
  [current existing]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "authorized-keys-test"
                      (into-array java.nio.file.attribute.FileAttribute [])))
        staged (io/file dir "deploy_keys")
        target (io/file dir "authorized_keys")]
    (spit staged (str (str/join "\n" current) "\n"))
    (when existing (spit target (str (str/join "\n" existing) "\n")))
    (let [{:keys [exit out]} (process/run ["bb" reconciler
                                           (.getAbsolutePath staged)
                                           (.getAbsolutePath target)])]
      {:exit exit
       :status (str/trim out)
       :lines (when (.exists target)
                (vec (remove str/blank? (str/split-lines (slurp target)))))})))

(deftest a-first-install-writes-just-the-current-key
  (let [{:keys [exit lines status]} (reconcile! [(line "a.example.com" "KEY1")] nil)]
    (is (= 0 exit))
    (is (= "changed" status))
    (is (= [(line "a.example.com" "KEY1")] lines))))

(deftest the-previous-generation-survives-one-round
  (testing "the old key keeps working until the new one has been published,
            which is what makes a failed publish harmless"
    (let [{:keys [lines]} (reconcile! [(line "a.example.com" "KEY2")]
                                      [(line "a.example.com" "KEY1")])]
      (is (= [(line "a.example.com" "KEY1")
              (line "a.example.com" "KEY2")]
             lines)))))

(deftest only-one-previous-generation-survives
  (testing "anything more only extends how long a leaked key stays usable"
    (let [{:keys [lines]} (reconcile! [(line "a.example.com" "KEY3")]
                                      [(line "a.example.com" "KEY1")
                                       (line "a.example.com" "KEY2")])]
      (is (= [(line "a.example.com" "KEY2")
              (line "a.example.com" "KEY3")]
             lines)))))

(deftest keys-are-retained-per-host
  (let [{:keys [lines]} (reconcile! [(line "a.example.com" "A2")
                                     (line "b.example.com" "B2")]
                                    [(line "a.example.com" "A1")
                                     (line "b.example.com" "B1")])]
    (is (= [(line "a.example.com" "A1") (line "a.example.com" "A2")
            (line "b.example.com" "B1") (line "b.example.com" "B2")]
           lines))))

(deftest a-host-that-left-desired-state-loses-its-keys
  (let [{:keys [lines]} (reconcile! [(line "a.example.com" "A2")]
                                    [(line "a.example.com" "A1")
                                     (line "gone.example.com" "G1")])]
    (is (= [(line "a.example.com" "A1") (line "a.example.com" "A2")] lines))))

(deftest a-key-from-before-per-application-keys-is-pruned
  (testing "an entry written when one key served every host is ours, not a
            stranger's, and upgrading has to remove it rather than preserve it"
    (let [legacy "restrict,command=\"/usr/local/bin/deploy\" ssh-ed25519 OLDKEY ci-deploy"
          {:keys [lines]} (reconcile! [(line "a.example.com" "A1")] [legacy])]
      (is (= [(line "a.example.com" "A1")] lines)))))

(deftest foreign-keys-are-left-alone
  (let [{:keys [lines]} (reconcile! [(line "a.example.com" "A1")]
                                    ["ssh-ed25519 SOMEONEELSE operator"])]
    (is (= ["ssh-ed25519 SOMEONEELSE operator" (line "a.example.com" "A1")] lines))))

(deftest running-twice-changes-nothing
  (testing "the playbook reports changed only when the file actually moved"
    (let [existing [(line "a.example.com" "A1")]
          first-run (reconcile! [(line "a.example.com" "A1")] existing)]
      (is (= "unchanged" (:status first-run)))
      (is (= existing (:lines first-run))))))
