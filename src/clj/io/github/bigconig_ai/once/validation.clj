(ns io.github.bigconig-ai.once.validation
  "Validate the active profile before running `bb run once package create`.

  Four phases run in a single pass and their errors are collected into a flat
  list:

    1. Schema    — malli validates required keys, rejects REPLACE_ME placeholders,
                   value formats, and a cross-field rule (every application :host
                   must match :domain).
    2. Tools     — required CLIs (tofu, ansible-playbook, ssh, curl, skopeo,
                   plus per-provider CLIs) are on PATH.
    3. Credentials — tokens / cloud configs authenticate against their APIs via
                   curl or the provider CLI; for cloud compute profiles,
                   SSH_AUTH_SOCK points to an ssh-agent with :compute-pubkey
                   loaded.
    4. Images    — every image referenced by :once :applications resolves on
                   its registry via `skopeo inspect`.

  `validate` is the big-config workflow step behind `bb run once package validate`: it
  prints a grouped report and returns a non-zero workflow exit on failure."
  (:require
   [babashka.process :as p]
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.render :as render]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   [java.net URLEncoder]
   [java.nio.charset StandardCharsets]))

;;; -------------------------------------------------------------- regexes

(def ^:private domain-rx
  #"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

(def ^:private hostname-rx domain-rx)

(def ^:private image-rx
  #"^[a-z0-9.-]+/[a-z0-9._-]+(/[a-z0-9._-]+)*(:[a-zA-Z0-9._-]+)?$")

(def ^:private ssh-pubkey-rx
  #"^ssh-(ed25519|rsa|dss|ecdsa) [A-Za-z0-9+/=]+( .*)?$")

(defn- placeholder?
  [v]
  (and (string? v)
       (str/includes? v "REPLACE_ME")))

(defn- not-placeholder?
  [v]
  (not (placeholder? v)))

(defn- blank-or-placeholder?
  [v]
  (or (nil? v)
      (and (string? v)
           (or (str/blank? v)
               (placeholder? v)))))

(defn- real-value?
  [v]
  (not (blank-or-placeholder? v)))

(def ^:private no-placeholder
  [:fn {:error/message "must replace REPLACE_ME with a real value"} not-placeholder?])

(def ^:private string-value
  [:and :string no-placeholder])

(def ^:private int-value
  [:and no-placeholder :int])

(defn- re-schema [rx msg]
  [:and :string no-placeholder [:re {:error/message msg} rx]])

(def ^:private non-empty-string
  [:and :string no-placeholder [:fn {:error/message "must be a non-empty string"} seq]])

;;; -------------------------------------------------------------- sub-profile schemas

(def ^:private schema:resend
  [:map
   [:provider-smtp [:= "resend"]]
   [:resend-server string-value]
   [:resend-port int-value]
   [:resend-username string-value]
   [:resend-api-key string-value]
   [:resend-password string-value]])

(def ^:private schema:no-infra-smtp
  [:map
   [:provider-smtp [:= "no-infra"]]
   [:no-infra-smtp-server string-value]
   [:no-infra-smtp-port int-value]
   [:no-infra-smtp-username string-value]
   [:no-infra-smtp-password string-value]])

(def ^:private schema:smtp
  [:multi {:dispatch :provider-smtp}
   ["resend" schema:resend]
   ["no-infra" schema:no-infra-smtp]])

(def ^:private schema:cloudflare
  [:map
   [:provider-dns [:= "cloudflare"]]
   [:cloudflare-api-token string-value]])

(def ^:private schema:no-infra-dns
  [:map [:provider-dns [:= "no-infra"]]])

(def ^:private schema:dns
  [:multi {:dispatch :provider-dns}
   ["cloudflare" schema:cloudflare]
   ["no-infra" schema:no-infra-dns]])

(def ^:private schema:s3
  [:map
   [:provider-backend [:= "s3"]]
   [:s3-bucket string-value]
   [:s3-region string-value]])

(def ^:private schema:r2
  [:map
   [:provider-backend [:= "r2"]]
   [:r2-bucket non-empty-string]
   [:r2-endpoint non-empty-string]
   [:r2-access-key-id non-empty-string]
   [:r2-secret-access-key non-empty-string]])

(def ^:private schema:local
  [:map [:provider-backend [:= "local"]]])

(def ^:private schema:backend
  [:multi {:dispatch :provider-backend}
   ["s3" schema:s3]
   ["r2" schema:r2]
   ["local" schema:local]])

(def ^:private schema:oci
  [:map
   [:provider-compute [:= "oci"]]
   [:oci-config-file-profile string-value]
   [:oci-subnet-id string-value]
   [:oci-compartment-id string-value]
   [:oci-availability-domain string-value]
   [:oci-display-name string-value]
   [:oci-shape string-value]
   [:oci-ocpus int-value]
   [:oci-memory-in-gbs int-value]
   [:oci-boot-volume-size-in-gbs int-value]
   [:oci-boot-volume-vpus-per-gb int-value]
   [:oci-ssh-authorized-keys string-value]])

(def ^:private schema:hcloud
  [:map
   [:provider-compute [:= "hcloud"]]
   [:hcloud-name string-value]
   [:hcloud-image string-value]
   [:hcloud-server-type string-value]
   [:hcloud-location string-value]
   [:hcloud-ssh-keys string-value]
   [:hcloud-token string-value]])

(def ^:private schema:digitalocean
  [:map
   [:provider-compute [:= "digitalocean"]]
   [:digitalocean-name string-value]
   [:digitalocean-region string-value]
   [:digitalocean-size string-value]
   [:digitalocean-image string-value]
   [:digitalocean-vpc-uuid string-value]
   [:digitalocean-ssh-keys string-value]
   [:do-token string-value]])

(def ^:private schema:no-infra-compute
  [:map
   [:provider-compute [:= "no-infra"]]
   [:no-infra-compute-ip string-value]
   [:no-infra-compute-user string-value]
   [:no-infra-compute-sudoer string-value]
   [:no-infra-compute-uid string-value]
   [:no-infra-compute-name string-value]])

(def ^:private schema:compute
  [:multi {:dispatch :provider-compute}
   ["oci" schema:oci]
   ["hcloud" schema:hcloud]
   ["digitalocean" schema:digitalocean]
   ["no-infra" schema:no-infra-compute]])

;;; -------------------------------------------------------------- application + base + cross-field

(def ^:private schema:application
  [:map
   [:host (re-schema hostname-rx "must be a valid hostname")]
   [:image (re-schema image-rx "must be a valid image ref (e.g. ghcr.io/org/name:tag)")]
   [:env {:optional true} [:vector string-value]]])

(def ^:private schema:base-params
  [:map
   [:domain (re-schema domain-rx "must be a valid domain")]
   [:package non-empty-string]
   [:once [:map [:applications [:vector schema:application]]]]
   [:compute-pubkey (re-schema ssh-pubkey-rx "must look like an SSH public key")]
   [:deploy-pubkey (re-schema ssh-pubkey-rx "must look like an SSH public key")]])

(defn- hosts-match-domain?
  [{:keys [domain once]}]
  (every? (fn [{:keys [host]}]
            (and host
                 (or (= host domain)
                     (str/ends-with? host (str "." domain)))))
          (:applications once)))

(def ^:private schema:cross-field
  [:fn {:error/message "every :once :applications :host must equal or be a subdomain of :domain"}
   hosts-match-domain?])

(def ^:private schema:params
  [:and
   schema:base-params
   schema:smtp
   schema:dns
   schema:compute
   schema:backend
   schema:cross-field])

(def schema:profile
  [:map
   [::render/profile string-value]
   [::workflow/params schema:params]])

;;; -------------------------------------------------------------- schema errors

(defn- format-path
  [in]
  (if (seq in)
    (->> in
         (map (fn [k]
                (cond
                  (keyword? k) (if-let [ns- (namespace k)]
                                 (str ns- "/" (name k))
                                 (name k))
                  :else (str k))))
         (str/join " → "))
    "(root)"))

(defn schema-errors
  [opts]
  (when-let [{:keys [errors]} (m/explain schema:profile opts)]
    (mapv (fn [e]
            {:check :schema
             :detail (format "%s: %s"
                             (format-path (:in e))
                             (or (me/error-message e) "invalid"))})
          errors)))

;;; -------------------------------------------------------------- tool checks

(def base-tools
  [{:cmd "tofu"             :name "OpenTofu"  :hint "https://opentofu.org/docs/intro/install/"}
   {:cmd "ansible-playbook" :name "Ansible"   :hint "pipx install ansible"}
   {:cmd "ssh"              :name "OpenSSH"   :hint "your distro's openssh-client package"}
   {:cmd "curl"             :name "curl"      :hint "your distro's curl package"}
   {:cmd "skopeo"           :name "skopeo"    :hint "https://github.com/containers/skopeo/blob/main/install.md"}])

(defn provider-tools
  [{:keys [provider-compute provider-backend]}]
  (cond-> []
    (= provider-compute "oci")          (conj {:cmd "oci"    :name "OCI CLI" :hint "pip install oci-cli"})
    (= provider-compute "hcloud")       (conj {:cmd "hcloud" :name "hcloud"  :hint "https://github.com/hetznercloud/cli"})
    (= provider-compute "digitalocean") (conj {:cmd "doctl"  :name "doctl"   :hint "https://docs.digitalocean.com/reference/doctl/how-to/install/"})
    (= provider-backend "s3")           (conj {:cmd "aws"    :name "AWS CLI" :hint "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"})
    (= provider-backend "r2")           (conj {:cmd "aws"    :name "AWS CLI" :hint "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"})))

(defn- which?
  [cmd]
  (try
    (zero? (:exit @(p/process ["which" cmd] {:out :string :err :string})))
    (catch Exception _ false)))

(defn tool-errors
  ([params] (tool-errors params which?))
  ([params which-fn]
   (->> (concat base-tools (provider-tools params))
        (remove (fn [{:keys [cmd]}] (which-fn cmd)))
        (mapv (fn [{:keys [name hint]}]
                {:check :tool
                 :detail (format "%s not found on PATH. Install: %s" name hint)})))))

;;; -------------------------------------------------------------- credential checks

(def ^:private run-timeout-ms 30000)

(defn- run
  ([args] (run args nil))
  ([args extra-env]
   (try
     (let [proc   (p/process args (cond-> {:in  (java.io.ByteArrayInputStream. (byte-array 0))
                                           :out :string
                                           :err :string}
                                    (seq extra-env) (assoc :extra-env extra-env)))
           result (deref proc run-timeout-ms ::timeout)]
       (if (= ::timeout result)
         (do
           (p/destroy-tree proc)
           {:ok? false :exit -1 :out ""
            :err (format "command timed out after %dms" run-timeout-ms)})
         (let [{:keys [exit out err]} result]
           {:ok? (zero? exit) :exit exit :out out :err err})))
     (catch Exception e
       {:ok? false :exit -1 :out "" :err (.getMessage e)}))))

(defn- trim-snippet [s]
  (let [s (some-> s str/trim)]
    (when-not (str/blank? s)
      (if (> (count s) 200) (str (subs s 0 200) "…") s))))

(defn- bearer-check
  [label url token]
  (let [{:keys [ok? exit err]}
        (run ["curl" "-sf" "-o" "/dev/null"
              "-H" (str "Authorization: Bearer " token)
              url])]
    (when-not ok?
      (let [snippet (trim-snippet err)]
        (format "%s: token rejected (curl exit %d)%s"
                label exit
                (if snippet (str " — " snippet) ""))))))

(defn- url-encode [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- cloudflare-zone-check
  [domain token]
  (let [url (str "https://api.cloudflare.com/client/v4/zones?name="
                 (url-encode domain)
                 "&status=active&per_page=1")
        {:keys [ok? exit out err]} (run ["curl" "-sf"
                                         "-H" (str "Authorization: Bearer " token)
                                         url])]
    (if-not ok?
      (let [snippet (trim-snippet err)]
        (format "Cloudflare API: token rejected (curl exit %d)%s"
                exit
                (if snippet (str " — " snippet) "")))
      (try
        (let [{:keys [success result errors]} (json/parse-string out keyword)]
          (cond
            (false? success)
            (format "Cloudflare API: zone lookup failed%s"
                    (if-let [snippet (trim-snippet (pr-str errors))]
                      (str " — " snippet)
                      ""))

            (empty? result)
            (format "Cloudflare zone: %s not found or not active" domain)

            :else nil))
        (catch Exception e
          (format "Cloudflare API: invalid zone lookup response — %s"
                  (.getMessage e)))))))

(defn- cli-check
  ([label args] (cli-check label args nil))
  ([label args extra-env]
   (let [{:keys [ok? err]} (run args extra-env)]
     (when-not ok?
       (format "%s: %s" label (or (trim-snippet err) "command failed"))))))

(defn- oci-config-path []
  (or (some-> (System/getenv "OCI_CLI_CONFIG_FILE") not-empty)
      (some-> (System/getenv "OCI_CONFIG_FILE") not-empty)
      (str (System/getProperty "user.home") "/.oci/config")))

(defn- oci-config-error []
  (let [path (oci-config-path)]
    (when-not (.exists (java.io.File. ^String path))
      (format "OCI: config file not found at %s — run 'oci setup config' to create one"
              path))))

(defn- classify-head-bucket-error
  "Classify a failed `aws s3api head-bucket` invocation by inspecting stderr.

  R2 with an Object-scoped token can't call list-buckets, so head-bucket is
  the only signal we have. The AWS CLI prints lines like
  `An error occurred (404) when calling the HeadBucket operation: Not Found`,
  which we pattern-match to tell missing-bucket from bad credentials."
  [err]
  (let [s (str/lower-case (or err ""))]
    (cond
      (or (str/includes? s "(404)")
          (str/includes? s "not found")
          (str/includes? s "nosuchbucket"))
      :missing-bucket

      (or (str/includes? s "(401)")
          (str/includes? s "(403)")
          (str/includes? s "forbidden")
          (str/includes? s "unauthorized")
          (str/includes? s "invalidaccesskey")
          (str/includes? s "signaturedoesnotmatch"))
      :bad-credentials

      :else :unknown)))

(defn- r2-errors
  "Defense in depth: schema rejects empty or placeholder R2 keys, but if they
  slip through surface a loud credential error instead of silently skipping the check.

  `:r2-endpoint` is supplied directly (e.g. `https://<acct>.eu.r2.cloudflarestorage.com`
  for the EU jurisdiction) so we don't need to know which jurisdiction the
  bucket lives in. Uses a single `aws s3api head-bucket` call (which works
  with Object-scoped R2 tokens, unlike list-buckets) and classifies the
  error to tell the user whether the bucket is missing or the credentials
  are wrong."
  [{:keys [r2-bucket r2-endpoint r2-access-key-id r2-secret-access-key]}]
  (let [missing (cond-> []
                  (blank-or-placeholder? r2-endpoint)          (conj :r2-endpoint)
                  (blank-or-placeholder? r2-bucket)            (conj :r2-bucket)
                  (blank-or-placeholder? r2-access-key-id)     (conj :r2-access-key-id)
                  (blank-or-placeholder? r2-secret-access-key) (conj :r2-secret-access-key))]
    (cond
      (seq missing)
      [(format "R2: missing or placeholder credentials: %s" (str/join ", " (map name missing)))]

      (not (which? "aws"))
      []

      :else
      (let [env-map  {"AWS_ACCESS_KEY_ID"     r2-access-key-id
                      "AWS_SECRET_ACCESS_KEY" r2-secret-access-key
                      "AWS_DEFAULT_REGION"    "auto"}
            {:keys [ok? err]} (run ["aws" "s3api" "head-bucket"
                                    "--bucket" r2-bucket
                                    "--endpoint-url" r2-endpoint]
                                   env-map)]
        (if ok?
          []
          (let [snippet (or (trim-snippet err) "head-bucket failed")]
            (case (classify-head-bucket-error err)
              :missing-bucket
              [(format "R2 (bucket): %s not found at %s — %s"
                       r2-bucket r2-endpoint snippet)]

              :bad-credentials
              [(format "R2 (auth): credentials rejected at %s — %s"
                       r2-endpoint snippet)]

              :unknown
              [(format "R2: head-bucket on %s at %s failed — %s"
                       r2-bucket r2-endpoint snippet)])))))))

(def ^:private cloud-compute-providers #{"oci" "hcloud" "digitalocean"})

(defn- cloud-compute?
  [{:keys [provider-compute]}]
  (contains? cloud-compute-providers provider-compute))

(defn- ssh-pubkey-identity
  [s]
  (let [[key-type key-body] (some-> s str/trim (str/split #"\s+" 3))]
    (when (and (not (str/blank? key-type))
               (not (str/blank? key-body)))
      [key-type key-body])))

(defn- ssh-agent-errors
  [{:keys [compute-pubkey] :as params} env]
  (if-not (cloud-compute? params)
    []
    (let [sock (some-> (get env "SSH_AUTH_SOCK") str/trim)]
      (cond
        (placeholder? compute-pubkey)
        ["SSH agent: :compute-pubkey still contains REPLACE_ME"]

        (str/blank? sock)
        ["SSH agent: SSH_AUTH_SOCK is not set; start ssh-agent and run ssh-add for :compute-pubkey"]

        :else
        (let [{:keys [ok? exit out err]} (run ["ssh-add" "-L"] {"SSH_AUTH_SOCK" sock})
              wanted (ssh-pubkey-identity compute-pubkey)
              agent-msg (str err "\n" out)]
          (cond
            (nil? wanted)
            ["SSH agent: :compute-pubkey is not a parseable SSH public key"]

            ok?
            (let [loaded (set (keep ssh-pubkey-identity (str/split-lines (or out ""))))]
              (if (contains? loaded wanted)
                []
                [(format "SSH agent: :compute-pubkey is not loaded in ssh-agent at SSH_AUTH_SOCK=%s" sock)]))

            (str/includes? (str/lower-case agent-msg) "no identities")
            [(format "SSH agent: :compute-pubkey is not loaded; the agent at SSH_AUTH_SOCK=%s has no identities" sock)]

            :else
            (let [snippet (trim-snippet err)]
              [(format "SSH agent: ssh-add -L failed for SSH_AUTH_SOCK=%s (exit %d)%s"
                       sock
                       exit
                       (if snippet (str " — " snippet) ""))])))))))

(defn- credential-errors
  ([params] (credential-errors params (System/getenv)))
  ([params env]
   (let [{:keys [provider-smtp provider-dns provider-compute provider-backend
                 domain resend-api-key cloudflare-api-token hcloud-token do-token]} params
         single (->> [(when (and (= provider-smtp "resend") (real-value? resend-api-key))
                        (bearer-check "Resend API"
                                      "https://api.resend.com/api-keys"
                                      resend-api-key))
                      (when (and (= provider-dns "cloudflare") (real-value? domain) (real-value? cloudflare-api-token))
                        (cloudflare-zone-check domain cloudflare-api-token))
                      (when (and (= provider-compute "hcloud") (real-value? hcloud-token))
                        (bearer-check "Hetzner Cloud API"
                                      "https://api.hetzner.cloud/v1/server_types"
                                      hcloud-token))
                      (when (and (= provider-compute "digitalocean") (real-value? do-token))
                        (bearer-check "DigitalOcean API"
                                      "https://api.digitalocean.com/v2/account"
                                      do-token))
                      (when (and (= provider-compute "oci") (which? "oci"))
                        (or (oci-config-error)
                            (cli-check "OCI" ["oci" "iam" "region" "list" "--output" "json"])))
                      (when (and (= provider-backend "s3") (which? "aws"))
                        (cli-check "AWS (S3 backend)" ["aws" "sts" "get-caller-identity"]))]
                     (keep identity))
         multi  (concat (when (= provider-backend "r2") (r2-errors params))
                        (ssh-agent-errors params env))]
     (->> (concat single multi)
          (mapv (fn [m] {:check :credential :detail m}))))))

;;; -------------------------------------------------------------- image checks

(defn- image-errors
  [params]
  (when (which? "skopeo")
    (->> (get-in params [:once :applications])
         (keep (fn [{:keys [image]}]
                 (when (real-value? image)
                   (let [{:keys [ok? err]} (run ["skopeo" "inspect" "--no-tags" "--override-os" "linux" (str "docker://" image)])]
                     (when-not ok?
                       {:check :image
                        :detail (format "%s — %s" image (or (trim-snippet err) "manifest unknown"))}))))))))

;;; -------------------------------------------------------------- top-level

(defn validate-report
  "Validate the merged active profile.

  `env` defaults to the process env. Returns
  `{:ok? boolean :errors [{:check kw :detail string}]}`."
  ([opts] (validate-report opts (System/getenv)))
  ([opts env]
   (let [opts'  (workflow/read-bc-pars opts env)
         params (::workflow/params opts')
         errors (vec (concat
                      (schema-errors opts')
                      (tool-errors params)
                      (credential-errors params env)
                      (image-errors params)))]
     {:ok? (empty? errors)
      :errors errors})))

(defn- group-name [k]
  (case k
    :schema     "Schema"
    :tool       "Tools"
    :credential "Credentials"
    :image      "Images"
    (str k)))

(defn- print-report
  [{:keys [ok? errors]}]
  (if ok?
    (println "All checks passed.")
    (do
      (println (format "Validation failed (%d issue%s):"
                       (count errors)
                       (if (= 1 (count errors)) "" "s")))
      (doseq [k [:schema :tool :credential :image]
              :let [es (filter #(= k (:check %)) errors)]
              :when (seq es)]
        (println)
        (println (str "  " (group-name k) ":"))
        (doseq [{:keys [detail]} es]
          (println (str "    - " detail)))))))

(defn validate
  "big-config workflow step for `bb run once package validate`."
  [_step-fns opts]
  (let [result (validate-report opts)]
    (print-report result)
    (merge opts
           {::result result}
           (if (:ok? result)
             (core/ok)
             {::bc/exit 1
              ::bc/err "validation failed"}))))

(comment
  (debug tap-values
    (validate-report {}))
  (-> tap-values))
