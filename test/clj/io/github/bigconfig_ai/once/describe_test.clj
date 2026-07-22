(ns io.github.bigconfig-ai.once.describe-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconfig-ai.once.describe :as d]))

(def ^:private base-opts
  {:profile "test"
   :provider-compute "digitalocean"
   :provider-backend "r2"
   :provider-smtp "resend"
   :provider-dns "cloudflare"
   :ip "203.0.113.10"
   :user "root"})

(defn- ok
  ([] (ok ""))
  ([out] {:ok? true :exit 0 :out out :err ""}))

(defn- fail
  [err]
  {:ok? false :exit 255 :out "" :err err})

(defn- remote-command
  [args]
  (let [target-idx (first (keep-indexed (fn [idx arg]
                                          (when (str/includes? arg "@") idx))
                                        args))]
    (vec (drop (inc target-idx) args))))

(defn- once-command-check
  []
  @#'d/once-command-check-args)

(deftest provider-summary-extracts-provider-names
  (is (= {:compute "digitalocean"
          :backend "r2"
          :smtp "resend"
          :dns "cloudflare"}
         (d/provider-summary base-opts))))

(deftest no-infra-compute-target-uses-configured-ip-and-user-when-state-is-missing
  (is (= {:ip "10.0.0.5" :user "ubuntu"}
         (#'d/compute-target {:provider-compute "no-infra"
                              :ip "192.168.0.1"
                              :no-infra-compute-ip "10.0.0.5"
                              :no-infra-compute-user "ubuntu"}))))

(deftest image-ref-parsing-handles-tags-defaults-and-registry-ports
  (is (= {:repository "ghcr.io/org/app"
          :tag "1.2.3"
          :image "ghcr.io/org/app:1.2.3"}
         (d/image->repository+tag "ghcr.io/org/app:1.2.3")))
  (is (= {:repository "ghcr.io/org/app"
          :tag "latest"
          :image "ghcr.io/org/app:latest"}
         (d/image->repository+tag "ghcr.io/org/app")))
  (is (= {:repository "localhost:5000/org/app"
          :tag "latest"
          :image "localhost:5000/org/app:latest"}
         (d/image->repository+tag "localhost:5000/org/app")))
  (is (= {:repository "localhost:5000/org/app"
          :tag "dev"
          :image "localhost:5000/org/app:dev"}
         (d/image->repository+tag "localhost:5000/org/app:dev"))))

(deftest once-list-parsing-strips-ansi-and-reads-status
  (let [output (str "\u001b[32mwww.example.com\u001b[0m (running)\n"
                    "forms.example.com (stopped)\n")]
    (is (= [{:host "www.example.com" :status "running"}
            {:host "forms.example.com" :status "stopped"}]
           (d/parse-once-list output)))))

(deftest docker-container-matching-uses-labels-and-names
  (let [containers [{:Name "/once-www-example-com"
                     :Config {:Image "ghcr.io/org/app:latest"
                              :Labels {:traefik.http.routers.app.rule "Host(`www.example.com`)"}}}
                    {:Name "/other"
                     :Config {:Image "ghcr.io/org/other:latest"}}]]
    (is (= "ghcr.io/org/app:latest"
           (get-in (#'d/find-container-for-host containers "www.example.com")
                   [:Config :Image])))))

(deftest digest-selection-and-comparison
  (is (= "sha256:aaa"
         (d/matching-repo-digest "ghcr.io/org/app"
                                 ["ghcr.io/org/app@sha256:aaa"
                                  "ghcr.io/org/other@sha256:bbb"])))
  (is (false? (#'d/update-available? "sha256:aaa" "sha256:aaa")))
  (is (true? (#'d/update-available? "sha256:aaa" "sha256:bbb")))
  (is (nil? (#'d/update-available? nil "sha256:bbb"))))

(deftest describe-ssh-failure-soft-fails-and-skips-remote-apps
  (let [calls  (atom [])
        run-fn (fn [args _opts]
                 (swap! calls conj args)
                 (when (some #{"once"} args)
                   (throw (ex-info "remote apps should not be checked" {})))
                 (fail "Permission denied"))
        result (d/describe-report base-opts run-fn)]
    (is (false? (get-in result [:compute :running?])))
    (is (= [] (:applications result)))
    (is (str/includes? (:applications-error result) "not checked"))
    (is (= 1 (count @calls)))))

(deftest describe-remote-command-failure-keeps-compute-running
  (let [run-fn (fn [args _opts]
                 (let [cmd (remote-command args)]
                   (cond
                     (= ["true"] cmd) (ok)
                     (= (once-command-check) cmd) (ok)
                     (= ["sudo" "-n" "once" "list"] cmd) (fail "once missing")
                     :else (throw (ex-info "unexpected command" {:args args})))))
        result (d/describe-report base-opts run-fn)]
    (is (true? (get-in result [:compute :running?])))
    (is (= [] (:applications result)))
    (is (false? (:fatal-error? result)))
    (is (str/includes? (:applications-error result) "once list failed"))))

(deftest describe-missing-remote-once-command-is-fatal
  (let [run-fn (fn [args _opts]
                 (let [cmd (remote-command args)]
                   (cond
                     (= ["true"] cmd) (ok)
                     (= (once-command-check) cmd) {:ok? false
                                                   :exit 127
                                                   :out ""
                                                   :err "once: command not found"}
                     :else (throw (ex-info "unexpected command" {:args args})))))
        result (d/describe-report base-opts run-fn)]
    (is (true? (get-in result [:compute :running?])))
    (is (= [] (:applications result)))
    (is (true? (:fatal-error? result)))
    (is (str/includes? (:applications-error result) "once command check failed"))))

(deftest describe-workflow-step-sets-exit-status
  (testing "soft report succeeds"
    (with-redefs [d/describe-report (constantly {:profile "test"
                                                 :providers {}
                                                 :compute {}
                                                 :applications []
                                                 :fatal-error? false})]
      (let [result (atom nil)]
        (with-out-str
          (reset! result (d/describe base-opts)))
        (is (= 0 (:green/exit @result)))
        (is (false? (get-in @result [::d/result :fatal-error?]))))))
  (testing "fatal report fails"
    (with-redefs [d/describe-report (constantly {:profile "test"
                                                 :providers {}
                                                 :compute {}
                                                 :applications []
                                                 :fatal-error? true})]
      (let [result (atom nil)]
        (with-out-str
          (reset! result (d/describe base-opts)))
        (is (= 1 (:green/exit @result)))
        (is (= "describe failed" (:green/err @result)))))))

(deftest describe-success-reports-image-digests-and-update-status
  (let [container {:Id "container-1"
                   :Name "/once-www-example-com"
                   :Image "sha256:local-image"
                   :Config {:Image "ghcr.io/org/app:latest"
                            :Labels {:traefik.http.routers.app.rule "Host(`www.example.com`)"}}
                   :State {:Status "running"}}
        image     {:Id "sha256:local-image"
                   :RepoTags ["ghcr.io/org/app:latest"]
                   :RepoDigests ["ghcr.io/org/app@sha256:old"]
                   :Architecture "amd64"
                   :Os "linux"}
        run-fn    (fn [args _opts]
                    (if (= "skopeo" (first args))
                      (do
                        (is (some #{"--override-os"} args))
                        (is (some #{"--override-arch"} args))
                        (is (some #{"docker://ghcr.io/org/app:latest"} args))
                        (ok (json/generate-string {:Digest "sha256:new"})))
                      (let [cmd (remote-command args)]
                        (cond
                          (= ["true"] cmd) (ok)
                          (= (once-command-check) cmd) (ok)
                          (= ["sudo" "-n" "once" "list"] cmd) (ok "www.example.com (running)\n")
                          (= ["sudo" "-n" "docker" "ps" "-q"] cmd) (ok "container-1\n")
                          (= ["sudo" "-n" "docker" "inspect" "--type" "container" "container-1"] cmd)
                          (ok (json/generate-string [container]))
                          (= ["sudo" "-n" "docker" "image" "inspect" "sha256:local-image" "ghcr.io/org/app:latest"] cmd)
                          (ok (json/generate-string [image]))
                          :else (throw (ex-info "unexpected command" {:args args}))))))
        result    (d/describe-report base-opts run-fn)
        app       (first (:applications result))]
    (is (nil? (:applications-error result)))
    (is (= "www.example.com" (:host app)))
    (is (= "ghcr.io/org/app:latest" (:image app)))
    (is (= "latest" (:version app)))
    (is (= "sha256:old" (:digest app)))
    (is (= "sha256:new" (:registry-digest app)))
    (is (true? (:new-version? app)))))
