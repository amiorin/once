(ns io.github.amiorin.once.validation-test
  (:require
   [big-config.workflow :as workflow]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.amiorin.once.options :as options]
   [io.github.amiorin.once.validation :as v]))

(defn- with-creds
  "Stub all credential keys that come from BC_PAR_* in real runs."
  [opts]
  (update opts ::workflow/params merge
          {:resend-api-key "stub"
           :resend-password "stub"
           :cloudflare-api-token "stub"
           :hcloud-token "stub"
           :do-token "stub"
           :no-infra-smtp-password "stub"
           :r2-endpoint "https://stub.r2.cloudflarestorage.com"
           :r2-access-key-id "stub"
           :r2-secret-access-key "stub"}))

(defn- complete-no-infra
  "Fill in keys that `options/no-infra` deliberately leaves blank — they're
  expected to come from BC_PAR_* env vars in production."
  [opts]
  (update opts ::workflow/params merge
          {:domain   "example.com"
           :package  "no-infra"
           :once     {:applications []}
           :provider-backend "local"}))

(deftest public-profiles-pass-schema-with-stub-creds
  (doseq [[name profile] [["website"  (with-creds options/website)]
                          ["online"   (with-creds options/online)]
                          ["space"    (with-creds options/space)]
                          ["no-infra" (-> options/no-infra with-creds complete-no-infra)]]]
    (testing name
      (is (nil? (v/schema-errors profile))))))

(deftest missing-required-credential-is-reported
  (let [errors (v/schema-errors options/website)]
    (is (seq errors))
    (is (some #(str/includes? (:detail %) "resend-api-key") errors)
        "missing :resend-api-key should be flagged by the schema phase")))

(deftest bad-domain-format-is-reported
  (let [bad    (-> options/website
                   with-creds
                   (assoc-in [::workflow/params :domain] "not_a_domain")
                   (assoc-in [::workflow/params :once :applications] []))
        errors (v/schema-errors bad)]
    (is (seq errors))
    (is (some #(and (str/includes? (:detail %) "domain")
                    (str/includes? (:detail %) "valid domain"))
              errors))))

(deftest cross-field-mismatched-host-is-reported
  (let [bad    (-> options/website
                   with-creds
                   (assoc-in [::workflow/params :once :applications]
                             [{:host  "alien.example.com"
                               :image "ghcr.io/foo/bar:latest"}]))
        errors (v/schema-errors bad)]
    (is (seq errors))
    (is (some #(str/includes? (:detail %) "subdomain") errors))))

(deftest cross-field-apex-and-subdomain-pass
  (let [ok (-> options/website
               with-creds
               (assoc-in [::workflow/params :once :applications]
                         [{:host "bigconfig.website"
                           :image "ghcr.io/foo/bar:latest"}
                          {:host "www.bigconfig.website"
                           :image "ghcr.io/foo/bar:latest"}]))]
    (is (nil? (v/schema-errors ok)))))

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
  (let [params     (::workflow/params (with-creds options/website))
        which-stub #(not= % "tofu")
        errors     (v/tool-errors params which-stub)]
    (is (= 1 (count errors)))
    (is (str/includes? (:detail (first errors)) "OpenTofu"))))

(deftest cloudflare-zone-checks-configured-domain
  (testing "configured zone exists"
    (with-redefs [v/run (fn
                          ([args]
                           (is (some #(str/includes? % "name=bigconfig.website") args))
                           {:ok? true :exit 0 :out "{\"success\":true,\"result\":[{\"id\":\"zone-id\"}]}" :err ""})
                          ([args _extra-env]
                           (is (some #(str/includes? % "name=bigconfig.website") args))
                           {:ok? true :exit 0 :out "{\"success\":true,\"result\":[{\"id\":\"zone-id\"}]}" :err ""}))]
      (is (empty? (#'v/credential-errors
                   (select-keys (::workflow/params (with-creds options/website))
                                [:provider-dns :domain :cloudflare-api-token]))))))
  (testing "configured zone is missing"
    (with-redefs [v/run (fn
                          ([_args]
                           {:ok? true :exit 0 :out "{\"success\":true,\"result\":[]}" :err ""})
                          ([_args _extra-env]
                           {:ok? true :exit 0 :out "{\"success\":true,\"result\":[]}" :err ""}))]
      (let [errors (#'v/credential-errors
                    (select-keys (::workflow/params (with-creds options/website))
                                 [:provider-dns :domain :cloudflare-api-token]))]
        (is (= 1 (count errors)))
        (is (str/includes? (:detail (first errors)) "Cloudflare zone"))
        (is (str/includes? (:detail (first errors)) "bigconfig.website"))))))

(deftest domain-regex-table
  (let [params-of (fn [domain]
                    (-> options/website
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
                    (-> options/website
                        with-creds
                        (assoc-in [::workflow/params :once :applications]
                                  [{:host "www.bigconfig.website" :image image}])))]
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
