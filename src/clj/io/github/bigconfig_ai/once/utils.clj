(ns io.github.bigconfig-ai.once.utils
  (:require
   [clojure.string :as str])
  (:import
   [java.util.concurrent TimeUnit]))

(def contract
  "Compatibility number for the launcher that consumes these namespaces and the
  templates under src/resources. Bump it on any change a launcher pinned to an
  older commit could not survive; the launcher refuses to run against a lower
  number and tells the user to repin.

  2: tools/backend-credential-env, which the launcher calls to read Tofu state."
  2)

(defn strip-ansi [s]
  (-> s
      (str/replace #"\x1b\]8;[^\x07]*\x07" "")
      (str/replace #"\x1b\[[0-9;]*m" "")))

(defn- process-builder
  [args {:keys [dir extra-env]}]
  (let [builder (ProcessBuilder. ^java.util.List (mapv str args))]
    (when dir
      (.directory builder (java.io.File. (str dir))))
    (when (seq extra-env)
      (.putAll (.environment builder)
               (into {} (map (fn [[k v]] [(str k) (str v)])) extra-env)))
    builder))

(defn- start-process
  [args opts]
  (let [process (.start (process-builder args opts))
        out (future (slurp (.getInputStream process)))
        err (future (slurp (.getErrorStream process)))]
    (.close (.getOutputStream process))
    {:process process :out out :err err}))

(defn- process-result
  [process out err]
  {:exit (.exitValue process)
   :out @out
   :err @err})

(defn shell-cmd
  "Run `args` with ProcessBuilder and return `{:exit :out :err}`.

  `opts` supports `:dir` and `:extra-env`. Command start failures are returned
  with exit -1 rather than thrown."
  [args opts]
  (try
    (let [{:keys [process out err]} (start-process args opts)]
      (.waitFor process)
      (process-result process out err))
    (catch Exception e
      {:exit -1 :out "" :err (or (.getMessage e) (str (class e)))})))

(defn- destroy-process-tree!
  [^Process process]
  (with-open [descendants (.descendants (.toHandle process))]
    (doseq [handle (iterator-seq (.iterator descendants))]
      (.destroyForcibly handle)))
  (.destroyForcibly process))

(defn run-with-timeout
  "Run `args`, forcibly stop it after `timeout-ms`, and return
  `{:ok? :exit :out :err}`. Supports the same options as `shell-cmd`."
  [args opts timeout-ms]
  (try
    (let [{:keys [process out err]} (start-process args opts)
          finished? (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)]
      (if finished?
        (assoc (process-result process out err) :ok? (zero? (.exitValue process)))
        (do
          (destroy-process-tree! process)
          (.waitFor process)
          {:ok? false
           :exit -1
           :out @out
           :err (let [captured @err
                      timeout (format "command timed out after %dms" timeout-ms)]
                  (if (str/blank? captured) timeout (str captured "\n" timeout)))})))
    (catch Exception e
      {:ok? false :exit -1 :out "" :err (or (.getMessage e) (str (class e)))})))

(defn- green-par-key
  [env-name]
  (-> env-name
      (subs (count "GREEN_PAR_"))
      str/lower-case
      (str/replace "_" "-")
      keyword))

(defn- coerce-override
  "Environment variables are strings; match the type of the value already in
  `opts` so a boolean key stays a boolean. Without this,
  `GREEN_PAR_COMPUTE_PREVENT_DESTROY=false` would overlay the truthy string
  \"false\"."
  [old value]
  (cond
    (boolean? old) (case (str/lower-case value)
                     "true" true
                     "false" false
                     value)
    (integer? old) (or (parse-long value) value)
    :else value))

(defn read-green-pars
  "Overlay `GREEN_PAR_*` environment variables onto flat keys in `opts`.

  For example, `GREEN_PAR_DO_TOKEN=xxx` becomes `{:do-token \"xxx\"}`. This is
  how secrets and tokens reach the workflow — they are never read from
  green.edn. Overrides are coerced to the type of the value they replace."
  ([opts]
   (read-green-pars opts (System/getenv)))
  ([opts env]
   (reduce-kv (fn [result k v]
                (let [k (str k)]
                  (if (and (str/starts-with? k "GREEN_PAR_")
                           (< (count "GREEN_PAR_") (count k)))
                    (let [key (green-par-key k)]
                      (assoc result key (coerce-override (get result key) v)))
                    result)))
              opts
              env)))

(defn- yaml-key
  [k]
  (if (keyword? k)
    (name k)
    (str k)))

(defn- yaml-scalar?
  [x]
  (or (nil? x)
      (string? x)
      (keyword? x)
      (boolean? x)
      (number? x)
      (and (map? x) (empty? x))
      (and (sequential? x) (empty? x))))

(defn- yaml-scalar
  [x]
  (cond
    (nil? x) "null"
    (string? x) (pr-str x)
    (keyword? x) (pr-str (name x))
    (true? x) "true"
    (false? x) "false"
    (number? x) (str x)
    (map? x) "{}"
    (sequential? x) "[]"
    :else (pr-str (str x))))

(declare yaml-lines)

(defn- map-lines
  [m indent]
  (mapcat (fn [[k v]]
            (let [prefix (str (apply str (repeat indent \space)) (yaml-key k) ":")]
              (if (yaml-scalar? v)
                [(str prefix " " (yaml-scalar v))]
                (cons prefix (yaml-lines v (+ indent 2))))))
          m))

(defn- sequence-item-lines
  [x indent]
  (let [prefix (str (apply str (repeat indent \space)) "-")]
    (if (yaml-scalar? x)
      [(str prefix " " (yaml-scalar x))]
      (let [child-indent (+ indent 2)
            child-prefix (apply str (repeat child-indent \space))
            lines (vec (yaml-lines x child-indent))]
        (if (empty? lines)
          [prefix]
          (into [(str prefix " " (subs (first lines) (count child-prefix)))]
                (subvec lines 1)))))))

(defn- yaml-lines
  [x indent]
  (cond
    (map? x) (map-lines x indent)
    (sequential? x) (mapcat #(sequence-item-lines % indent) x)
    :else [(str (apply str (repeat indent \space)) (yaml-scalar x))]))

(defn generate-yaml
  "Serialize maps, sequences, and scalar values to the small YAML subset used
  by the generated ONCE Ansible task file."
  [data]
  (str (str/join "\n" (yaml-lines data 0)) "\n"))
