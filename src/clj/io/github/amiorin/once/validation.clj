(ns io.github.amiorin.once.validation
  "Validate the active profile (`options/bb`) before running `bb once create`.

  Four phases run in a single pass and their errors are collected into a flat
  list:

    1. Schema    — malli validates required keys, value formats, and a
                   cross-field rule (every application :host must match :domain).
    2. Tools     — required CLIs (tofu, ansible-playbook, ssh, curl, skopeo,
                   plus per-provider CLIs) are on PATH.
    3. Credentials — tokens / cloud configs authenticate against their APIs via
                   curl or the provider CLI.
    4. Images    — every image referenced by :once :applications resolves on
                   its registry via `skopeo inspect`.

  `validate*` is the `bb validate` entry point: prints a grouped report and
  exits non-zero on failure."
  (:require
   [babashka.process :as p]
   [big-config.render :as render]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [clojure.string :as str]
   [io.github.amiorin.once.options :as options]
   [malli.core :as m]
   [malli.error :as me]))

;;; -------------------------------------------------------------- regexes

(def ^:private domain-rx
  #"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

(def ^:private hostname-rx domain-rx)

(def ^:private image-rx
  #"^[a-z0-9.-]+/[a-z0-9._-]+(/[a-z0-9._-]+)*(:[a-zA-Z0-9._-]+)?$")

(def ^:private ssh-pubkey-rx
  #"^ssh-(ed25519|rsa|dss|ecdsa) [A-Za-z0-9+/=]+( .*)?$")

(defn- re-schema [rx msg]
  [:and :string [:re {:error/message msg} rx]])

;;; -------------------------------------------------------------- sub-profile schemas

(def ^:private schema:resend
  [:map
   [:provider-smtp [:= "resend"]]
   [:resend-server :string]
   [:resend-port :int]
   [:resend-username :string]
   [:resend-api-key :string]
   [:resend-password :string]])

(def ^:private schema:no-infra-smtp
  [:map
   [:provider-smtp [:= "no-infra"]]
   [:no-infra-smtp-server :string]
   [:no-infra-smtp-port :int]
   [:no-infra-smtp-username :string]
   [:no-infra-smtp-password :string]])

(def ^:private schema:smtp
  [:multi {:dispatch :provider-smtp}
   ["resend" schema:resend]
   ["no-infra" schema:no-infra-smtp]])

(def ^:private schema:cloudflare
  [:map
   [:provider-dns [:= "cloudflare"]]
   [:cloudflare-api-token :string]])

(def ^:private schema:no-infra-dns
  [:map [:provider-dns [:= "no-infra"]]])

(def ^:private schema:dns
  [:multi {:dispatch :provider-dns}
   ["cloudflare" schema:cloudflare]
   ["no-infra" schema:no-infra-dns]])

(def ^:private schema:s3
  [:map
   [:provider-backend [:= "s3"]]
   [:s3-bucket :string]
   [:s3-region :string]])

(def ^:private schema:local
  [:map [:provider-backend [:= "local"]]])

(def ^:private schema:backend
  [:multi {:dispatch :provider-backend}
   ["s3" schema:s3]
   ["local" schema:local]])

(def ^:private schema:oci
  [:map
   [:provider-compute [:= "oci"]]
   [:oci-config-file-profile :string]
   [:oci-subnet-id :string]
   [:oci-compartment-id :string]
   [:oci-availability-domain :string]
   [:oci-display-name :string]
   [:oci-shape :string]
   [:oci-ocpus :int]
   [:oci-memory-in-gbs :int]
   [:oci-boot-volume-size-in-gbs :int]
   [:oci-boot-volume-vpus-per-gb :int]
   [:oci-ssh-authorized-keys :string]])

(def ^:private schema:hcloud
  [:map
   [:provider-compute [:= "hcloud"]]
   [:hcloud-name :string]
   [:hcloud-image :string]
   [:hcloud-server-type :string]
   [:hcloud-location :string]
   [:hcloud-ssh-keys :string]
   [:hcloud-token :string]])

(def ^:private schema:digitalocean
  [:map
   [:provider-compute [:= "digitalocean"]]
   [:digitalocean-name :string]
   [:digitalocean-region :string]
   [:digitalocean-size :string]
   [:digitalocean-image :string]
   [:digitalocean-vpc-uuid :string]
   [:digitalocean-ssh-keys :string]
   [:do-token :string]])

(def ^:private schema:no-infra-compute
  [:map
   [:provider-compute [:= "no-infra"]]
   [:no-infra-compute-ip :string]
   [:no-infra-compute-user :string]
   [:no-infra-compute-sudoer :string]
   [:no-infra-compute-uid :string]
   [:no-infra-compute-name :string]])

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
   [:env {:optional true} [:vector :string]]])

(def ^:private schema:base-params
  [:map
   [:domain (re-schema domain-rx "must be a valid domain")]
   [:package [:and :string [:fn {:error/message "must be a non-empty string"} seq]]]
   [:once [:map [:applications [:vector schema:application]]]]
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
   [::render/profile :string]
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
    (= provider-backend "s3")           (conj {:cmd "aws"    :name "AWS CLI" :hint "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"})))

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

(defn- run [args]
  (try
    (let [proc   (p/process args {:out :string :err :string})
          result (deref proc run-timeout-ms ::timeout)]
      (if (= ::timeout result)
        (do
          (p/destroy-tree proc)
          {:ok? false :exit -1 :out ""
           :err (format "command timed out after %dms" run-timeout-ms)})
        (let [{:keys [exit out err]} result]
          {:ok? (zero? exit) :exit exit :out out :err err})))
    (catch Exception e
      {:ok? false :exit -1 :out "" :err (.getMessage e)})))

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

(defn- cli-check
  [label args]
  (let [{:keys [ok? err]} (run args)]
    (when-not ok?
      (format "%s: %s" label (or (trim-snippet err) "command failed")))))

(defn- credential-errors
  [params]
  (let [{:keys [provider-smtp provider-dns provider-compute provider-backend
                resend-api-key cloudflare-api-token hcloud-token do-token]} params]
    (->> [(when (and (= provider-smtp "resend") resend-api-key)
            (bearer-check "Resend API"
                          "https://api.resend.com/api-keys"
                          resend-api-key))
          (when (and (= provider-dns "cloudflare") cloudflare-api-token)
            (bearer-check "Cloudflare API"
                          "https://api.cloudflare.com/client/v4/zones?per_page=1"
                          cloudflare-api-token))
          (when (and (= provider-compute "hcloud") hcloud-token)
            (bearer-check "Hetzner Cloud API"
                          "https://api.hetzner.cloud/v1/server_types"
                          hcloud-token))
          (when (and (= provider-compute "digitalocean") do-token)
            (bearer-check "DigitalOcean API"
                          "https://api.digitalocean.com/v2/account"
                          do-token))
          (when (and (= provider-compute "oci") (which? "oci"))
            (cli-check "OCI" ["oci" "iam" "region" "list" "--output" "json"]))
          (when (and (= provider-backend "s3") (which? "aws"))
            (cli-check "AWS (S3 backend)" ["aws" "sts" "get-caller-identity"]))]
         (keep identity)
         (mapv (fn [m] {:check :credential :detail m})))))

;;; -------------------------------------------------------------- image checks

(defn- image-errors
  [params]
  (when (which? "skopeo")
    (->> (get-in params [:once :applications])
         (keep (fn [{:keys [image]}]
                 (when image
                   (let [{:keys [ok? err]} (run ["skopeo" "inspect" "--no-tags" "--override-os" "linux" (str "docker://" image)])]
                     (when-not ok?
                       {:check :image
                        :detail (format "%s — %s" image (or (trim-snippet err) "manifest unknown"))}))))))))

;;; -------------------------------------------------------------- top-level

(defn validate
  "Validate the merged active profile.

  `env` defaults to the process env. Returns
  `{:ok? boolean :errors [{:check kw :detail string}]}`."
  ([opts] (validate opts (System/getenv)))
  ([opts env]
   (let [opts'  (workflow/read-bc-pars opts env)
         params (::workflow/params opts')
         errors (vec (concat
                      (schema-errors opts')
                      (tool-errors params)
                      (credential-errors params)
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

(defn validate*
  "CLI entry point. Validates `opts` (defaulting to `options/bb`), prints a
  grouped report, and exits non-zero on failure."
  [_args & [opts]]
  (let [result (validate (or opts options/bb))]
    (print-report result)
    (when-not (:ok? result)
      (System/exit 1))
    result))

(comment
  (debug tap-values
    (validate options/bb))
  (-> tap-values))
