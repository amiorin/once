(ns io.github.bigconfig-ai.once.deploy-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.process :as process]))

(def deploy-script
  (.getAbsolutePath
   (io/file "src/resources/io/github/bigconfig-ai/once/tools/ansible/files/deploy")))

(defn- make-temp-dir! []
  (.toFile (java.nio.file.Files/createTempDirectory
            "deploy-test"
            (into-array java.nio.file.attribute.FileAttribute []))))

(defn- make-shim!
  "Create a temp dir with `sudo` and `once` shims. The `once` shim logs every
  invocation to calls.log, returns `list-output` for `once list`, and exits 0
  for `once update`."
  [list-output]
  (let [dir       (make-temp-dir!)
        sudo      (io/file dir "sudo")
        once      (io/file dir "once")
        log       (io/file dir "calls.log")
        list-file (io/file dir "list.output")]
    (spit list-file list-output)
    (spit sudo "#!/bin/sh\nexec \"$@\"\n")
    (.setExecutable sudo true false)
    (spit once (str "#!/bin/sh\n"
                    "echo \"$@\" >> " (.getAbsolutePath log) "\n"
                    "case \"$1\" in\n"
                    "  list)   cat " (.getAbsolutePath list-file) " ;;\n"
                    "  update) exit 0 ;;\n"
                    "  *)      exit 2 ;;\n"
                    "esac\n"))
    (.setExecutable once true false)
    {:dir (.getAbsolutePath dir)
     :log (.getAbsolutePath log)}))

(def ^:private default-permitted "bigconfig.website")

(defn- run-deploy
  "The permitted host arrives as an argument, the way the ForceCommand in
  authorized_keys supplies it — one key, one application."
  ([ssh-original-command] (run-deploy ssh-original-command nil default-permitted))
  ([ssh-original-command shim] (run-deploy ssh-original-command shim default-permitted))
  ([ssh-original-command shim permitted]
   (let [env (cond-> {"SSH_ORIGINAL_COMMAND" (or ssh-original-command "")}
               shim (assoc "PATH" (str (:dir shim) ":" (System/getenv "PATH"))))]
     (process/run (cond-> ["bb" deploy-script]
                    permitted (conj permitted))
                  {:extra-env env}))))

(deftest rejects-empty-ssh-command
  (let [{:keys [exit err]} (run-deploy "")]
    (is (= 1 exit))
    (is (str/includes? err "interactive sessions"))))

(deftest rejects-unrelated-command
  (let [{:keys [exit err]} (run-deploy "rm -rf /")]
    (is (= 1 exit))
    (is (str/includes? err "command not allowed"))))

(deftest rejects-wrong-once-subcommand
  (let [{:keys [exit err]} (run-deploy "sudo once list")]
    (is (= 1 exit))
    (is (str/includes? err "command not allowed"))))

(deftest rejects-too-many-tokens
  (let [{:keys [exit err]} (run-deploy "sudo once update foo bar")]
    (is (= 1 exit))
    (is (str/includes? err "command not allowed"))))

(deftest rejects-chained-command
  (let [{:keys [exit err]} (run-deploy "sudo once update foo.com; rm -rf /")]
    (is (= 1 exit))
    (is (str/includes? err "command not allowed"))))

(deftest rejects-shell-metacharacters-in-host
  (let [{:keys [exit err]} (run-deploy "sudo once update foo;bar")]
    (is (= 1 exit))
    (is (str/includes? err "invalid host"))))

(deftest rejects-host-not-in-once-list
  (let [shim (make-shim! "bigconfig.website (running)\n")
        {:keys [exit err]} (run-deploy "sudo once update bogus.example.com" shim
                                       "bogus.example.com")]
    (is (= 1 exit))
    (is (str/includes? err "host not allowed"))))

(deftest rejects-host-this-key-is-not-for
  (testing "a key issued for one application cannot redeploy another"
    (let [shim (make-shim! "bigconfig.website (running)\nother.example.com (running)\n")
          {:keys [exit err]} (run-deploy "sudo once update other.example.com" shim
                                         "bigconfig.website")]
      (is (= 1 exit))
      (is (str/includes? err "host not permitted for this key")))))

(deftest rejects-a-key-with-no-permitted-host
  (testing "an entry missing its ForceCommand argument grants nothing"
    (let [{:keys [exit err]} (run-deploy "sudo once update bigconfig.website" nil nil)]
      (is (= 1 exit))
      (is (str/includes? err "no permitted host configured")))))

(deftest runs-update-for-allowed-host
  (let [shim (make-shim! "bigconfig.website (running)\nforms.bigconfig.website (running)\n")
        {:keys [exit]} (run-deploy "sudo once update bigconfig.website" shim)
        log (slurp (:log shim))]
    (is (= 0 exit))
    (is (str/includes? log "update bigconfig.website"))))

(deftest parses-host-list-with-ansi-escapes
  (let [shim (make-shim! (slurp (io/resource "ansi.output")))
        {:keys [exit]} (run-deploy "sudo once update foo.bigconfig.space" shim
                                   "foo.bigconfig.space")
        log (slurp (:log shim))]
    (is (= 0 exit))
    (is (str/includes? log "update foo.bigconfig.space"))))
