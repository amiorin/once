;; Drive the SSH Keypair Standard's create matrix, provider preflight, and
;; delete cleanup through green with injected state, keygen, and account-key
;; functions, printing one normalized `case exit=<n> err=<message>` line per
;; scenario. Red and blue print the same shape, so parity.sh can diff them:
;; none of this logic reaches a build artifact, and the error messages are
;; user-facing contract.
(require '[clojure.string :as str]
         '[io.github.getcolors.once.ssh :as ssh])

(defn tmp-dir
  "A fresh scenario directory, installed as the module's home so the keypair
  lands under it — no scenario may touch the real ~/.ssh."
  []
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "once-ssh-parity"
                  (into-array java.nio.file.attribute.FileAttribute [])))]
    (alter-var-root #'ssh/home-dir (constantly (constantly dir)))
    dir))

(defn fake-keygen [args _ _]
  (let [path (last args)]
    (clojure.java.io/make-parents path)
    (spit path "PRIVATE")
    (spit (str path ".pub") "ssh-ed25519 AAAATESTKEY parity managed by Colors")
    {:exit 0}))

(defn seed! [dir]
  (fake-keygen ["ssh-keygen" (str dir "/.ssh/parity")] {} 0))

(defn base [dir]
  {:profile "parity"
   :provider-compute "digitalocean"
   :do-token "tok"
   :green/state-file (str dir "/colors.yml")})

(defn line [case-name dir opts]
  (println (str case-name
                " exit=" (or (:green/exit opts) 0)
                " err=" (str/replace (str (:green/err opts)) dir "<dir>"))))

(let [state-none (constantly nil)
      state-live (constantly {:ip "1.2.3.4"})
      state-owned (constantly {:ip "1.2.3.4" :ssh_key_id "77"})]

  (let [dir (tmp-dir)]
    (line "first-create" dir (ssh/ensure-key! (base dir) state-none fake-keygen)))
  (let [dir (tmp-dir)]
    (line "lost-key" dir (ssh/ensure-key! (base dir) state-live fake-keygen)))
  (let [dir (tmp-dir)]
    (seed! dir)
    (line "leftover" dir (ssh/ensure-key! (base dir) state-none fake-keygen)))
  (let [dir (tmp-dir)]
    (seed! dir)
    (line "converge" dir (ssh/ensure-key! (base dir) state-live fake-keygen)))
  (let [dir (tmp-dir)]
    (spit (doto (clojure.java.io/file dir ".ssh" "parity")
            clojure.java.io/make-parents) "PRIVATE")
    (line "half-keypair" dir (ssh/ensure-key! (base dir) state-live fake-keygen)))

  (let [pre (fn [dir state-fn fetch]
              (ssh/preflight!
               (assoc (ssh/with-machine-key (base dir) true)
                      :once/ssh-state-params (state-fn nil))
               fetch))]
    (let [dir (tmp-dir)]
      (seed! dir)
      (line "preflight-none" dir (pre dir state-none (fn [_ _] []))))
    (let [dir (tmp-dir)]
      (seed! dir)
      (line "preflight-owned" dir
            (pre dir state-owned
                 (fn [_ _] [{:id "77" :name "parity" :public "ssh-ed25519 AAAATESTKEY"}]))))
    (let [dir (tmp-dir)]
      (seed! dir)
      (line "preflight-ours" dir
            (pre dir state-none
                 (fn [_ _] [{:id "77" :name "parity" :public "ssh-ed25519 AAAATESTKEY"}]))))
    (let [dir (tmp-dir)]
      (seed! dir)
      (line "preflight-foreign" dir
            (pre dir state-none
                 (fn [_ _] [{:id "88" :name "parity" :public "ssh-ed25519 AAAAOTHER"}]))))
    (let [dir (tmp-dir)]
      (seed! dir)
      (line "preflight-api-error" dir
            (pre dir state-none (fn [_ _] (throw (ex-info "HTTP 500 from provider" {})))))))

  (let [dir (tmp-dir)]
    (seed! dir)
    (let [out (ssh/cleanup-step (assoc (base dir) :green/event :delete))]
      (println (str "cleanup exit=" (or (:green/exit out) 0)
                    " removed=" (not (.exists (clojure.java.io/file dir ".ssh" "parity"))))))))
