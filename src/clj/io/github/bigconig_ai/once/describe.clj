(ns io.github.bigconig-ai.once.describe
  "Describe the active profile after provisioning.

  `describe` is the big-config workflow step behind `bb run once package describe`. It
  prints a human-readable report with configured provider names, SSH
  reachability for the computed host, and deployed ONCE applications discovered
  from the remote server. Most live checks are soft failures; a missing remote
  `once` command marks the step as failed."
  (:require
   [babashka.process :as p]
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.render :as render]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [clojure.string :as str]
   [io.github.bigconig-ai.once.params :as params]))

;;; -------------------------------------------------------------- command helpers

(def ^:private run-timeout-ms 30000)
(def ^:private ssh-probe-timeout-ms 10000)
(def ^:private registry-timeout-ms 30000)

(defn- run
  ([args] (run args {}))
  ([args {:keys [timeout-ms extra-env]
          :or   {timeout-ms run-timeout-ms}}]
   (try
     (let [proc   (p/process args (cond-> {:in  (java.io.ByteArrayInputStream. (byte-array 0))
                                           :out :string
                                           :err :string}
                                    (seq extra-env) (assoc :extra-env extra-env)))
           result (deref proc timeout-ms ::timeout)]
       (if (= ::timeout result)
         (do
           (p/destroy-tree proc)
           {:ok? false :exit -1 :out ""
            :err (format "command timed out after %dms" timeout-ms)})
         (let [{:keys [exit out err]} result]
           {:ok? (zero? exit) :exit exit :out out :err err})))
     (catch Exception e
       {:ok? false :exit -1 :out "" :err (.getMessage e)}))))

(defn- trim-snippet [s]
  (let [s (some-> s str/trim)]
    (when-not (str/blank? s)
      (if (> (count s) 200) (str (subs s 0 200) "…") s))))

(defn- result-detail
  [label {:keys [exit out err]}]
  (let [snippet (or (trim-snippet err) (trim-snippet out))]
    (format "%s failed (exit %d)%s"
            label
            (or exit -1)
            (if snippet (str " — " snippet) ""))))

(defn- once-command-not-found?
  [{:keys [exit out err]}]
  (let [text (str/lower-case (str (or err "") "\n" (or out "")))]
    (or (= 127 exit)
        (str/includes? text "once: command not found")
        (str/includes? text "once: not found")
        (str/includes? text "command not found: once"))))

(defn- ssh-base-args
  [{:keys [ip user]}]
  ["ssh"
   "-o" "BatchMode=yes"
   "-o" "ConnectTimeout=5"
   "-o" "StrictHostKeyChecking=accept-new"
   (str user "@" ip)])

(defn- ssh-run
  ([run-fn compute remote-args]
   (ssh-run run-fn compute remote-args run-timeout-ms))
  ([run-fn compute remote-args timeout-ms]
   (run-fn (into (ssh-base-args compute) remote-args)
           {:timeout-ms timeout-ms})))

(def ^:private once-command-check-args
  ["command" "-v" "once" ">/dev/null" "2>&1"
   "||" "test" "-x" "/usr/local/bin/once"
   "||" "{" "echo" "once:" "command" "not" "found" ">&2" ";" "exit" "127" ";" "}"])

;;; -------------------------------------------------------------- providers + compute

(defn provider-summary
  "Return configured provider names from merged params."
  [params]
  {:compute (:provider-compute params)
   :backend (:provider-backend params)
   :smtp    (:provider-smtp params)
   :dns     (:provider-dns params)})

(defn- compute-target
  [{:keys [provider-compute ip user sudoer no-infra-compute-ip
           no-infra-compute-user no-infra-compute-sudoer]}]
  (let [ip (if (and (= provider-compute "no-infra")
                    (or (str/blank? ip) (= "192.168.0.1" ip))
                    (not (str/blank? no-infra-compute-ip)))
             no-infra-compute-ip
             ip)]
    {:ip ip
     :user (or (not-empty user)
               (not-empty no-infra-compute-user)
               (not-empty sudoer)
               (not-empty no-infra-compute-sudoer)
               "root")}))

(defn- compute-status
  [run-fn params]
  (let [{:keys [ip user] :as target} (compute-target params)]
    (cond
      (str/blank? ip)
      (assoc target :running? false :detail "missing IP address")

      :else
      (let [{:keys [ok?] :as result} (ssh-run run-fn target ["true"] ssh-probe-timeout-ms)]
        (assoc target
               :running? (boolean ok?)
               :detail (if ok?
                         "ssh ok"
                         (str (result-detail "ssh" result)
                              (when (= "192.168.0.1" ip)
                                "; no Tofu output found or host is down"))))))))

;;; -------------------------------------------------------------- once list parsing

(defn strip-ansi
  [s]
  (-> (or s "")
      (str/replace #"\x1b\]8;[^\x07]*\x07" "")
      (str/replace #"\x1b\[[0-9;?]*[ -/]*[@-~]" "")))

(def ^:private host-status-rx
  #"([A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?\.[A-Za-z]{2,})(?:\s+\(([^)]*)\))?")

(defn parse-once-list
  [output]
  (->> (str/split-lines (strip-ansi output))
       (keep (fn [line]
               (when-let [[_ host status] (re-find host-status-rx line)]
                 (cond-> {:host host}
                   (not (str/blank? status)) (assoc :status status)))))
       vec))

;;; -------------------------------------------------------------- image helpers

(defn image->repository+tag
  "Parse a Docker image reference into repository, tag and normalized image.

  References without an explicit tag default to `latest`. Registry ports are
  handled by looking for a tag separator only after the final slash."
  [image]
  (let [image (some-> image str str/trim)]
    (when-not (or (str/blank? image)
                  (re-matches #"^sha256:[A-Fa-f0-9]+$" image))
      (let [without-digest (first (str/split image #"@" 2))
            last-slash     (.lastIndexOf ^String without-digest "/")
            last-colon     (.lastIndexOf ^String without-digest ":")
            has-tag?       (> last-colon last-slash)
            repository     (if has-tag?
                             (subs without-digest 0 last-colon)
                             without-digest)
            tag            (if has-tag?
                             (subs without-digest (inc last-colon))
                             "latest")]
        {:repository repository
         :tag tag
         :image (str repository ":" tag)}))))

(defn matching-repo-digest
  "Return the digest (e.g. `sha256:...`) from `repo-digests` for repository."
  [repository repo-digests]
  (some (fn [repo-digest]
          (let [[repo digest] (str/split (str repo-digest) #"@" 2)]
            (when (= repository repo) digest)))
        repo-digests))

(defn- update-available?
  [running-digest registry-digest]
  (when (and (not (str/blank? running-digest))
             (not (str/blank? registry-digest)))
    (not= running-digest registry-digest)))

(defn- registry-digest
  [run-fn image os arch]
  (let [args   (cond-> ["skopeo" "inspect" "--no-tags"]
                 (not (str/blank? os))   (into ["--override-os" os])
                 (not (str/blank? arch)) (into ["--override-arch" arch])
                 true                    (conj (str "docker://" image)))
        result (run-fn args {:timeout-ms registry-timeout-ms})]
    (if (:ok? result)
      (try
        {:digest (:Digest (json/parse-string (:out result) keyword))}
        (catch Exception e
          {:digest nil
           :detail (str "registry response was not valid JSON: " (.getMessage e))}))
      {:digest nil
       :detail (result-detail "skopeo inspect" result)})))

;;; -------------------------------------------------------------- docker parsing + matching

(defn- parse-json-vector
  [s]
  (try
    (let [v (json/parse-string (if (str/blank? s) "[]" s) keyword)]
      (if (sequential? v) (vec v) []))
    (catch Exception _
      [])))

(defn- string-leaves
  [x]
  (cond
    (nil? x) []
    (string? x) [x]
    (keyword? x) [(cond-> (name x)
                    (namespace x) (str "/" (namespace x)))]
    (map? x) (mapcat (fn [[k v]] (concat (string-leaves k) (string-leaves v))) x)
    (sequential? x) (mapcat string-leaves x)
    :else [(str x)]))

(defn- host-variants
  [host]
  (let [host (str/lower-case host)]
    (distinct [host
               (str/replace host "." "-")
               (str/replace host "." "_")])))

(defn- container-search-text
  [container]
  (->> (select-keys container [:Name :Config :NetworkSettings])
       string-leaves
       (str/join "\n")
       str/lower-case))

(defn- container-for-host?
  [host container]
  (let [text (container-search-text container)]
    (some #(str/includes? text %) (host-variants host))))

(defn- find-container-for-host
  [containers host]
  (some #(when (container-for-host? host %) %) containers))

(defn- image-identifiers
  [containers]
  (->> containers
       (mapcat (fn [container]
                 [(:Image container)
                  (get-in container [:Config :Image])]))
       (remove str/blank?)
       distinct
       vec))

(defn- image-info-for-container
  [image-infos container parsed-image]
  (let [image-id    (:Image container)
        image-ref   (get-in container [:Config :Image])
        normalized  (:image parsed-image)
        repository  (:repository parsed-image)]
    (or (some #(when (= image-id (:Id %)) %) image-infos)
        (some #(when (contains? (set (:RepoTags %)) image-ref) %) image-infos)
        (some #(when (and normalized (contains? (set (:RepoTags %)) normalized)) %) image-infos)
        (some #(when (and repository (matching-repo-digest repository (:RepoDigests %))) %) image-infos))))

(defn- application-report
  [run-fn containers image-infos {:keys [host status]}]
  (let [container         (find-container-for-host containers host)
        image-ref         (some-> container (get-in [:Config :Image]))
        parsed-image      (image->repository+tag image-ref)
        image-info        (when container (image-info-for-container image-infos container parsed-image))
        os                (or (:Os image-info) "linux")
        arch              (or (:Architecture image-info) "amd64")
        running-digest    (when parsed-image
                            (matching-repo-digest (:repository parsed-image)
                                                  (:RepoDigests image-info)))
        fallback-digest   (or (:Id image-info) (:Image container))
        registry          (when parsed-image
                            (registry-digest run-fn (:image parsed-image) os arch))
        registry-digest'  (:digest registry)]
    (cond-> {:host host
             :status (or (some-> container (get-in [:State :Status]))
                         status
                         "unknown")
             :image (or (:image parsed-image) image-ref)
             :version (:tag parsed-image)
             :digest (or running-digest fallback-digest)
             :digest-source (cond
                              running-digest :repo-digest
                              fallback-digest :image-id)
             :registry-digest registry-digest'
             :new-version? (update-available? running-digest registry-digest')}
      (:detail registry) (assoc :registry-detail (:detail registry)))))

;;; -------------------------------------------------------------- remote discovery

(defn- remote-applications
  [run-fn compute]
  (let [{check-ok? :ok? :as once-check} (ssh-run run-fn compute once-command-check-args)]
    (if-not check-ok?
      {:ok? false
       :fatal? true
       :detail (result-detail "once command check" once-check)}
      (let [{:keys [ok? out] :as once-result}
            (ssh-run run-fn compute ["sudo" "-n" "once" "list"])]
        (if-not ok?
          (cond-> {:ok? false
                   :detail (result-detail "once list" once-result)}
            (once-command-not-found? once-result) (assoc :fatal? true))
          (let [once-apps (parse-once-list out)]
            (if (empty? once-apps)
              {:ok? true :applications []}
              (let [{:keys [ok? out] :as ps-result}
                    (ssh-run run-fn compute ["sudo" "-n" "docker" "ps" "-q"])]
                (if-not ok?
                  {:ok? false :detail (result-detail "docker ps" ps-result)}
                  (let [ids (->> (str/split-lines (or out ""))
                                 (map str/trim)
                                 (remove str/blank?)
                                 vec)]
                    (if (empty? ids)
                      {:ok? true
                       :applications (mapv #(assoc %
                                                    :status (or (:status %) "unknown")
                                                    :image nil
                                                    :version nil
                                                    :digest nil
                                                    :registry-digest nil
                                                    :new-version? nil)
                                          once-apps)}
                      (let [{:keys [ok? out] :as container-result}
                            (ssh-run run-fn compute
                                     (into ["sudo" "-n" "docker" "inspect" "--type" "container"] ids))]
                        (if-not ok?
                          {:ok? false :detail (result-detail "docker inspect" container-result)}
                          (let [containers (parse-json-vector out)
                                image-ids  (image-identifiers containers)]
                            (if (empty? image-ids)
                              {:ok? true
                               :applications (mapv #(application-report run-fn containers [] %) once-apps)}
                              (let [{:keys [ok? out] :as image-result}
                                    (ssh-run run-fn compute
                                             (into ["sudo" "-n" "docker" "image" "inspect"] image-ids))]
                                (if-not ok?
                                  {:ok? false :detail (result-detail "docker image inspect" image-result)}
                                  (let [image-infos (parse-json-vector out)]
                                    {:ok? true
                                     :applications (mapv #(application-report run-fn containers image-infos %)
                                                         once-apps)}))))))))))))))))))

;;; -------------------------------------------------------------- top-level

(defn- resolve-once-opts
  [opts once-opts-fn]
  (try
    {:opts (once-opts-fn opts)}
    (catch Exception e
      {:opts opts
       :detail (str "could not resolve OpenTofu parameters: " (.getMessage e))})))

(defn describe-report
  "Build a describe report from `opts`.

  Returns a map with provider names, compute reachability, and deployed remote
  applications. Live failures are represented in the return value instead of
  thrown. Optional arities allow tests to inject a command runner and params
  resolver."
  ([opts] (describe-report opts run params/once-opts))
  ([opts run-fn once-opts-fn]
   (let [{opts' :opts resolve-detail :detail} (resolve-once-opts opts once-opts-fn)
         profile       (::render/profile opts')
         params'       (::workflow/params opts')
         providers     (provider-summary params')
         compute       (cond-> (compute-status run-fn params')
                         resolve-detail (update :detail #(str % "; " resolve-detail)))
         app-result    (if (:running? compute)
                         (remote-applications run-fn compute)
                         {:ok? false
                          :detail "not checked because compute is not reachable"})]
     {:profile profile
      :providers providers
      :compute compute
      :applications (vec (:applications app-result))
      :applications-error (when-not (:ok? app-result) (:detail app-result))
      :fatal-error? (boolean (:fatal? app-result))})))

;;; -------------------------------------------------------------- reporting

(defn- present
  [x]
  (if (str/blank? (str x)) "unknown" (str x)))

(defn- update-label
  [x]
  (case x
    true "yes"
    false "no"
    nil "unknown"))

(defn- print-report
  [{:keys [profile providers compute applications applications-error]}]
  (println (format "Profile: %s" (present profile)))
  (println)
  (println "Providers:")
  (println (format "  Compute: %s" (present (:compute providers))))
  (println (format "  Backend: %s" (present (:backend providers))))
  (println (format "  SMTP: %s" (present (:smtp providers))))
  (println (format "  DNS: %s" (present (:dns providers))))
  (println)
  (println "Compute:")
  (println (format "  IP: %s" (present (:ip compute))))
  (println (format "  SSH user: %s" (present (:user compute))))
  (println (format "  Status: %s%s"
                   (if (:running? compute) "running" "not reachable")
                   (if-let [detail (not-empty (:detail compute))]
                     (format " (%s)" detail)
                     "")))
  (println)
  (cond
    applications-error
    (println (format "Applications: %s." applications-error))

    (empty? applications)
    (println "Applications: none found.")

    :else
    (do
      (println "Applications:")
      (doseq [{:keys [host status image version digest digest-source registry-digest
                      new-version? registry-detail]} applications]
        (println (format "  - %s" (present host)))
        (println (format "    status: %s" (present status)))
        (println (format "    image: %s" (present image)))
        (println (format "    version: %s" (present version)))
        (println (format "    digest: %s%s"
                         (present digest)
                         (if (= digest-source :image-id) " (image id; digest comparison unknown)" "")))
        (println (format "    registry digest: %s" (present registry-digest)))
        (println (format "    update available: %s" (update-label new-version?)))
        (when registry-detail
          (println (format "    registry check: %s" registry-detail)))))))

(defn describe
  "big-config workflow step for `bb run once package describe`.

  Unreachable infrastructure is reported without failing the step, but a missing
  remote `once` command returns a non-zero workflow exit."
  [_step-fns opts]
  (let [result (describe-report opts)]
    (print-report result)
    (merge opts
           {::result result}
           (if (:fatal-error? result)
             {::bc/exit 1
              ::bc/err (or (:applications-error result) "describe failed")}
             (core/ok)))))

(comment
  (debug tap-values
    (describe-report {}))
  (-> tap-values))
