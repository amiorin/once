(ns io.github.bigconig-ai.once.validation-test
  (:require
   [big-config :as bc]
   [big-config.workflow :as workflow]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconig-ai.once.options :as options]
   [io.github.bigconig-ai.once.validation :as v]))

(def ^:private test-compute-pubkey
  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 test@example.com")

(def ^:private test-deploy-pubkey
  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII1Lbgxiv2OnDKwc8Wx25SQlGyI+iY1drUii/IMZ3YSh deploy@example.com")

(defn- with-creds
  "Replace REPLACE_ME placeholders with schema-valid test values."
  [opts]
  (update opts ::workflow/params merge
          {:compute-pubkey test-compute-pubkey
           :deploy-pubkey test-deploy-pubkey
           :resend-api-key "stub"
           :resend-password "stub"
           :cloudflare-api-token "stub"
           :hcloud-token "stub"
           :hcloud-ssh-keys "stub-key"
           :do-token "stub"
           :digitalocean-vpc-uuid "stub-vpc"
           :digitalocean-ssh-keys "stub-key"
           :oci-config-file-profile "DEFAULT"
           :oci-subnet-id "stub-subnet"
           :oci-compartment-id "stub-compartment"
           :oci-availability-domain "stub-ad"
           :oci-ssh-authorized-keys "~/.ssh/id_ed25519.pub"
           :no-infra-compute-ip "192.0.2.10"
           :no-infra-compute-user "ubuntu"
           :no-infra-compute-sudoer "ubuntu"
           :no-infra-compute-uid "1000"
           :no-infra-compute-name "once"
           :no-infra-smtp-password "stub"
           :r2-bucket "stub-bucket"
           :r2-endpoint "https://stub.r2.cloudflarestorage.com"
           :r2-access-key-id "stub"
           :r2-secret-access-key "stub"
           :s3-bucket "stub-bucket"
           :s3-region "eu-west-1"}))

(deftest public-profiles-pass-schema-with-stub-creds
  (doseq [[name profile] [["profile-alpha"    (with-creds options/profile-alpha)]
                          ["profile-beta"     (with-creds options/profile-beta)]
                          ["profile-gamma"    (with-creds options/profile-gamma)]
                          ["profile-no-infra" (with-creds options/profile-no-infra)]]]
    (testing name
      (is (nil? (v/schema-errors profile))))))

(deftest placeholder-credential-is-reported
  (let [errors (v/schema-errors options/profile-alpha)]
    (is (seq errors))
    (is (some #(and (str/includes? (:detail %) "resend-api-key")
                    (str/includes? (:detail %) "REPLACE_ME"))
              errors)
        "placeholder :resend-api-key should be flagged by the schema phase")))

(deftest missing-compute-pubkey-is-reported
  (let [errors (v/schema-errors (update (with-creds options/profile-alpha)
                                        ::workflow/params dissoc :compute-pubkey))]
    (is (seq errors))
    (is (some #(str/includes? (:detail %) "compute-pubkey") errors))))

(deftest bad-domain-format-is-reported
  (let [bad    (-> options/profile-alpha
                   with-creds
                   (assoc-in [::workflow/params :domain] "not_a_domain")
                   (assoc-in [::workflow/params :once :applications] []))
        errors (v/schema-errors bad)]
    (is (seq errors))
    (is (some #(and (str/includes? (:detail %) "domain")
                    (str/includes? (:detail %) "valid domain"))
              errors))))

(deftest cross-field-mismatched-host-is-reported
  (let [bad    (-> options/profile-alpha
                   with-creds
                   (assoc-in [::workflow/params :once :applications]
                             [{:host  "alien.example.com"
                               :image "ghcr.io/foo/bar:latest"}]))
        errors (v/schema-errors bad)]
    (is (seq errors))
    (is (some #(str/includes? (:detail %) "subdomain") errors))))

(deftest cross-field-apex-and-subdomain-pass
  (let [ok (-> options/profile-alpha
               with-creds
               (assoc-in [::workflow/params :once :applications]
                         [{:host "alpha.example.com"
                           :image "ghcr.io/foo/bar:latest"}
                          {:host "www.alpha.example.com"
                           :image "ghcr.io/foo/bar:latest"}]))]
    (is (nil? (v/schema-errors ok)))))

(deftest validate-workflow-step-sets-exit-status
  (testing "valid report succeeds"
    (with-redefs [v/validate-report (constantly {:ok? true :errors []})]
      (let [result (atom nil)]
        (with-out-str
          (reset! result (v/validate [] {})))
        (is (= 0 (::bc/exit @result)))
        (is (= {:ok? true :errors []} (::v/result @result))))))
  (testing "invalid report fails"
    (with-redefs [v/validate-report (constantly {:ok? false
                                                 :errors [{:check :schema
                                                           :detail "bad"}]})]
      (let [result (atom nil)]
        (with-out-str
          (reset! result (v/validate [] {})))
        (is (= 1 (::bc/exit @result)))
        (is (= "validation failed" (::bc/err @result)))))))

(deftest provider-tools-picks-right-clis
  (testing "hcloud + s3"
    (is (= #{"hcloud" "aws"}
           (set (map :cmd (v/provider-tools {:provider-compute "hcloud"
                                             :provider-backend "s3"}))))))
  (testing "oci + s3"
    (is (= #{"oci" "aws"}
           (set (map :cmd (v/provider-tools {:provider-compute "oci"
                                             :provider-backend "s3"}))))))
  (testing "digitalocean + s3"
    (is (= #{"doctl" "aws"}
           (set (map :cmd (v/provider-tools {:provider-compute "digitalocean"
                                             :provider-backend "s3"}))))))
  (testing "hcloud + r2"
    (is (= #{"hcloud" "aws"}
           (set (map :cmd (v/provider-tools {:provider-compute "hcloud"
                                             :provider-backend "r2"}))))))
  (testing "oci + r2"
    (is (= #{"oci" "aws"}
           (set (map :cmd (v/provider-tools {:provider-compute "oci"
                                             :provider-backend "r2"}))))))
  (testing "no-infra + local"
    (is (= #{}
           (set (map :cmd (v/provider-tools {:provider-compute "no-infra"
                                             :provider-backend "local"}))))))
  (testing "hcloud + local"
    (is (= #{"hcloud"}
           (set (map :cmd (v/provider-tools {:provider-compute "hcloud"
                                             :provider-backend "local"})))))))

(deftest tool-errors-honors-injected-which-fn
  (let [params     (::workflow/params (with-creds options/profile-alpha))
        which-stub #(not= % "tofu")
        errors     (v/tool-errors params which-stub)]
    (is (= 1 (count errors)))
    (is (str/includes? (:detail (first errors)) "OpenTofu"))))

(deftest ssh-agent-checks-cloud-compute-pubkey
  (let [params      {:provider-compute "hcloud"
                     :compute-pubkey test-compute-pubkey}
        key-id-line (str/join " " (take 2 (str/split test-compute-pubkey #"\s+")))]
    (testing "missing SSH_AUTH_SOCK is reported for cloud compute"
      (let [errors (#'v/ssh-agent-errors params {})]
        (is (= 1 (count errors)))
        (is (str/includes? (first errors) "SSH_AUTH_SOCK"))))
    (testing "no-infra skips the ssh-agent check"
      (is (empty? (#'v/ssh-agent-errors (assoc params :provider-compute "no-infra") {}))))
    (testing "loaded key is matched by type and body, ignoring comments"
      (with-redefs [v/run (fn
                            ([_args]
                             (throw (ex-info "unexpected one-arg run" {})))
                            ([args extra-env]
                             (is (= ["ssh-add" "-L"] args))
                             (is (= {"SSH_AUTH_SOCK" "/tmp/agent.sock"} extra-env))
                             {:ok? true :exit 0 :out (str key-id-line " other-comment\n") :err ""}))]
        (is (empty? (#'v/ssh-agent-errors params {"SSH_AUTH_SOCK" "/tmp/agent.sock"})))))
    (testing "missing loaded key is reported"
      (with-redefs [v/run (fn
                            ([_args]
                             (throw (ex-info "unexpected one-arg run" {})))
                            ([_args _extra-env]
                             {:ok? true :exit 0 :out "ssh-ed25519 AAAAother comment\n" :err ""}))]
        (let [errors (#'v/ssh-agent-errors params {"SSH_AUTH_SOCK" "/tmp/agent.sock"})]
          (is (= 1 (count errors)))
          (is (str/includes? (first errors) "not loaded")))))
    (testing "dead SSH_AUTH_SOCK is reported"
      (with-redefs [v/run (fn
                            ([_args]
                             (throw (ex-info "unexpected one-arg run" {})))
                            ([_args _extra-env]
                             {:ok? false :exit 2 :out "" :err "Error connecting to agent: No such file or directory"}))]
        (let [errors (#'v/ssh-agent-errors params {"SSH_AUTH_SOCK" "/tmp/dead.sock"})]
          (is (= 1 (count errors)))
          (is (str/includes? (first errors) "ssh-add -L failed")))))))

(deftest cloudflare-zone-checks-configured-domain
  (testing "configured zone exists"
    (with-redefs [v/run (fn
                          ([args]
                           (is (some #(str/includes? % "name=alpha.example.com") args))
                           {:ok? true :exit 0 :out "{\"success\":true,\"result\":[{\"id\":\"zone-id\"}]}" :err ""})
                          ([args _extra-env]
                           (is (some #(str/includes? % "name=alpha.example.com") args))
                           {:ok? true :exit 0 :out "{\"success\":true,\"result\":[{\"id\":\"zone-id\"}]}" :err ""}))]
      (is (empty? (#'v/credential-errors
                   (select-keys (::workflow/params (with-creds options/profile-alpha))
                                [:provider-dns :domain :cloudflare-api-token]))))))
  (testing "configured zone is missing"
    (with-redefs [v/run (fn
                          ([_args]
                           {:ok? true :exit 0 :out "{\"success\":true,\"result\":[]}" :err ""})
                          ([_args _extra-env]
                           {:ok? true :exit 0 :out "{\"success\":true,\"result\":[]}" :err ""}))]
      (let [errors (#'v/credential-errors
                    (select-keys (::workflow/params (with-creds options/profile-alpha))
                                 [:provider-dns :domain :cloudflare-api-token]))]
        (is (= 1 (count errors)))
        (is (str/includes? (:detail (first errors)) "Cloudflare zone"))
        (is (str/includes? (:detail (first errors)) "alpha.example.com"))))))

(deftest domain-regex-table
  (let [params-of (fn [domain]
                    (-> options/profile-alpha
                        with-creds
                        (assoc-in [::workflow/params :domain] domain)
                        (assoc-in [::workflow/params :once :applications] [])))]
    (testing "valid"
      (doseq [d ["example.com" "foo.bar.example.com" "a.b" "ex-ample.co"]]
        (is (nil? (v/schema-errors (params-of d)))
            (str d " should be valid"))))
    (testing "invalid"
      (doseq [d ["not_a_domain" "" "no-dot" "UPPER.case" ".leading"]]
        (is (seq (v/schema-errors (params-of d)))
            (str d " should be invalid"))))))

(deftest image-regex-table
  (let [params-of (fn [image]
                    (-> options/profile-alpha
                        with-creds
                        (assoc-in [::workflow/params :once :applications]
                                  [{:host "www.alpha.example.com" :image image}])))]
    (testing "valid"
      (doseq [i ["ghcr.io/foo/bar"
                 "ghcr.io/foo/bar:latest"
                 "ghcr.io/org/path/sub:tag-1.2"
                 "registry.example.com/foo/bar:v1"]]
        (is (nil? (v/schema-errors (params-of i)))
            (str i " should be valid"))))
    (testing "invalid"
      (doseq [i ["nginx" "" "/no-registry" "Foo/Bar" "ghcr.io/foo/bar:bad tag"]]
        (is (seq (v/schema-errors (params-of i)))
            (str i " should be invalid"))))))
