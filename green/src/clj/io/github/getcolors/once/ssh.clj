(ns io.github.getcolors.once.ssh
  "The machine-access SSH keypair a deployment owns.

  Implements the SSH Keypair Standard (workspace `standards/ssh-keypair.md`):
  when the selected compute provider's machine-key configuration key is absent
  from desired state, the package generates and manages an ed25519 keypair
  named after the profile, in the operator's `~/.ssh`. When the key is
  present, everything here steps aside and the value is used exactly as
  before the standard — presence is the only switch.

  Key material is like state: losing it loses access to the machine. So the
  keypair lives in `~/.ssh` outside any checkout or workdir (the profile is
  globally unique — it already keys remote state — which is what makes the
  shared flat directory safe), an existing key without state is an error
  rather than something to overwrite, a provider-side key named after the
  profile but absent from our state is an error rather than something to
  import, and delete removes the local key only after the compute destroy
  succeeded.

  Generation shells `ssh-keygen` like `github`: three languages agreeing on
  OpenSSH private-key encoding is a parity problem, one subprocess is not.
  The private key never enters the opts map — templates receive only paths."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [green.process :as process]
   [io.github.getcolors.once.validate :as validate])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.nio.file Files Paths)
   (java.nio.file.attribute PosixFilePermissions)))

(def ^:private run-timeout-ms 30000)
(def ^:private http-timeout-ms 30000)

(def placeholder-public
  "The public key a build or dry-run renders where content (not a path) is
  interpolated. Fixed, so the artifact stays deterministic and byte-identical
  across colours whether or not the keypair exists."
  "ssh-ed25519 PLACEHOLDER managed-by-colors")

(def machine-key-keys
  "Compute provider -> the desired-state key that carries the machine key.
  Absent or placeholder value = keygen mode. `no-infra` provisions no machine,
  so it has no entry and never generates."
  {"azure" :azure-ssh-authorized-keys
   "aws" :aws-ssh-authorized-keys
   "google" :google-ssh-authorized-keys
   "digitalocean" :digitalocean-ssh-keys
   "hcloud" :hcloud-ssh-keys
   "vultr" :vultr-ssh-keys
   "yandex" :compute-pubkey
   "oci" :oci-ssh-authorized-keys})

(def preflight-providers
  "Registered-key providers with a token-bearing REST API the create preflight
  can list account keys through. AWS is exempt by design: `aws_key_pair` names
  are unique per region and the instance depends on the key pair, so a
  duplicate name fails the apply before any instance exists."
  #{"digitalocean" "hcloud" "vultr"})

(defn keygen?
  "Whether this deployment is in keygen mode: the selected compute provider
  takes a machine key and desired state does not supply one. Once
  `with-machine-key` has filled the provider key with the generated path the
  desired-state test alone would flip to opt-out, so the `:ssh-keygen` flag
  it stamps keeps the answer sticky for the rest of the run."
  [opts]
  (let [k (machine-key-keys (:provider-compute opts))]
    (boolean (or (:ssh-keygen opts)
                 (and k (validate/placeholder? (get opts k)))))))

(defn profile [opts] (or (:profile opts) "default"))

(defn home-dir
  "The operator's home directory — `~/.ssh` is where the keypair lives, per
  the standard. A function rather than a constant so tests and the parity
  driver can redirect it away from the real `~/.ssh`."
  []
  (or (System/getenv "HOME") (System/getProperty "user.home")))

(defn ssh-dir [_opts] (str (io/file (home-dir) ".ssh")))
(defn private-key-path [opts] (str (io/file (ssh-dir opts) (profile opts))))
(defn public-key-path [opts] (str (private-key-path opts) ".pub"))

(defn- fail [opts msg]
  (assoc opts :green/exit 1 :green/err msg))

(defn with-machine-key
  "Fill the template values keygen mode owns, for every event, and leave
  opt-out opts untouched. Path providers get the absolute public-key path
  ($HOME expanded here, because tofu's `file()` does not expand `~`); the
  content provider gets the key content on real events and the fixed
  placeholder otherwise, so builds never read `~/.ssh`."
  [opts real?]
  (if-not (keygen? opts)
    opts
    (let [k (machine-key-keys (:provider-compute opts))
          prv (str (.getAbsolutePath (io/file (private-key-path opts))))
          pub (str (.getAbsolutePath (io/file (public-key-path opts))))
          content (if (and real? (.exists (io/file pub)))
                    (str/trim (slurp pub))
                    placeholder-public)]
      (cond-> (assoc opts
                     :ssh-keygen true
                     :ssh-private-key-path prv
                     :ssh-public-key-path pub)
        (= k :compute-pubkey) (assoc :compute-pubkey content)
        (not= k :compute-pubkey) (assoc k pub)))))

(defn identity-args
  "ssh arguments selecting the deployment's key, empty in opt-out mode. Every
  ssh the package runs against the machine (host-key capture, describe)
  threads these, because in keygen mode nothing guarantees an agent holds the
  key."
  [opts]
  (if (:ssh-keygen opts)
    ["-o" "IdentitiesOnly=yes" "-i" (:ssh-private-key-path opts)]
    []))

;;; ------------------------------------------------------------- permissions

(defn- set-perms!
  [path perms]
  (Files/setPosixFilePermissions
   (Paths/get (str path) (make-array String 0))
   (PosixFilePermissions/fromString perms)))

(defn- enforce-perms!
  "700 on `~/.ssh`, 600 on the private key — on every real run, not only at
  generation, so a key restored with wrong permissions fails early."
  [opts]
  (try
    (set-perms! (ssh-dir opts) "rwx------")
    (when (.exists (io/file (private-key-path opts)))
      (set-perms! (private-key-path opts) "rw-------"))
    nil
    (catch Exception e
      (str "cannot enforce permissions on " (ssh-dir opts) ": " (ex-message e)))))

;;; ------------------------------------------------- the create-time matrix

(defn- keygen-args [opts path]
  ["ssh-keygen" "-q" "-t" "ed25519" "-N" ""
   "-C" (str (profile opts) " managed by Colors")
   "-f" path])

(defn ensure-key!
  "The standard's create matrix, generation, and permission enforcement, on a
  real create in keygen mode. `state-fn` reads the compute stage's applied
  `params` output best-effort (nil when no state is readable): state and key
  agreeing means converge, disagreeing means a human has to act, and neither
  existing means first create. An existing key without state is never
  overwritten — it may be the only credential to a host that is still alive.

  Threads the state params through `:once/ssh-state-params` so the provider
  preflight does not read state twice."
  ([opts state-fn] (ensure-key! opts state-fn process/run-with-timeout))
  ([opts state-fn run-fn]
   (if-not (keygen? opts)
     opts
     (let [prv (private-key-path opts)
           pub (public-key-path opts)
           prv? (.exists (io/file prv))
           pub? (.exists (io/file pub))
           state (state-fn opts)
           opts (assoc opts :once/ssh-state-params state)]
       (cond
         (and state (not (or prv? pub?)))
         (fail opts (str "compute state exists but " prv " is missing: this "
                         "workstation does not hold the machine key. Copy it "
                         "from where the deployment was created, or rebuild; "
                         "a regenerated key cannot reach the existing host."))

         (and (or prv? pub?) (not (and prv? pub?)))
         (fail opts (str "~/.ssh holds half a keypair for " (profile opts)
                         " (private " (if prv? "present" "missing")
                         ", public " (if pub? "present" "missing")
                         "): restore the missing half, or — after verifying "
                         "no host for " (profile opts) " survives — remove "
                         "both and retry."))

         (and (not state) prv?)
         (fail opts (str prv " exists but no compute state is readable: the "
                         "previous delete may be incomplete, or a first "
                         "create was interrupted. Verify at the provider "
                         "that no host for " (profile opts) " survives; if "
                         "it is confirmed gone (or the interrupted create "
                         "never made one), remove " prv " and " pub
                         " and retry."))

         prv?
         (if-let [err (enforce-perms! opts)] (fail opts err) opts)

         :else
         (let [_ (io/make-parents prv)
               result (run-fn (keygen-args opts prv) {} run-timeout-ms)]
           (if-not (zero? (:exit result -1))
             (fail opts (str "ssh-keygen failed for " (profile opts) ": "
                             (str/trim (str (:err result)))))
             (if-let [err (enforce-perms! opts)] (fail opts err) opts))))))))

;;; ------------------------------------------- the provider-side preflight

(defn- http-get-json
  [url headers]
  (let [client (HttpClient/newHttpClient)
        builder (HttpRequest/newBuilder (URI. url))
        builder (reduce (fn [b [k v]] (.header b k v)) builder headers)
        request (.build (.timeout builder (java.time.Duration/ofMillis http-timeout-ms)))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode response) 299)
      (throw (ex-info (str "HTTP " (.statusCode response) " from " url) {})))
    (json/parse-string (.body response) true)))

(defn- normalize-key
  "The comparable part of an OpenSSH public key: type and material, comment
  dropped."
  [s]
  (str/join " " (take 2 (str/split (str/trim (str s)) #"\s+"))))

(defn fetch-account-keys
  "Every SSH key registered in the provider account, as
  `[{:id :name :public}]`, following pagination. A listing failure throws:
  the preflight answers or the create does not proceed."
  [provider token]
  (case provider
    "digitalocean"
    (loop [url "https://api.digitalocean.com/v2/account/keys?per_page=200" acc []]
      (let [body (http-get-json url {"Authorization" (str "Bearer " token)})
            acc (into acc (map (fn [k] {:id (str (:id k))
                                        :name (str (:name k))
                                        :public (normalize-key (:public_key k))})
                              (:ssh_keys body)))]
        (if-let [next-url (get-in body [:links :pages :next])]
          (recur next-url acc)
          acc)))

    "hcloud"
    (loop [page 1 acc []]
      (let [body (http-get-json
                  (str "https://api.hetzner.cloud/v1/ssh_keys?per_page=50&page=" page)
                  {"Authorization" (str "Bearer " token)})
            acc (into acc (map (fn [k] {:id (str (:id k))
                                        :name (str (:name k))
                                        :public (normalize-key (:public_key k))})
                              (:ssh_keys body)))]
        (if-let [next-page (get-in body [:meta :pagination :next_page])]
          (recur next-page acc)
          acc)))

    "vultr"
    (loop [cursor nil acc []]
      (let [body (http-get-json
                  (str "https://api.vultr.com/v2/ssh-keys?per_page=100"
                       (when (seq cursor) (str "&cursor=" cursor)))
                  {"Authorization" (str "Bearer " token)})
            acc (into acc (map (fn [k] {:id (str (:id k))
                                        :name (str (:name k))
                                        :public (normalize-key (:ssh_key k))})
                              (:ssh_keys body)))
            next-cursor (get-in body [:meta :links :next])]
        (if (seq next-cursor)
          (recur next-cursor acc)
          acc)))))

(def ^:private preflight-tokens
  {"digitalocean" :do-token
   "hcloud" :hcloud-token
   "vultr" :vultr-api-key})

(defn preflight!
  "Refuse a real create when the provider account holds a key named after the
  profile that this deployment's state does not own. Ownership is the
  resource id recorded in state (surfaced through the compute stage's
  `ssh_key_id` output param) — names are conventions anyone can copy. A found
  key is never adopted: if state was lost, the instance is likely orphaned
  too, and importing the key would let create build a second machine next to
  the first. The local public key decides the message: matching material is
  our leftover, anything else is foreign and must not be deleted."
  ([opts] (preflight! opts fetch-account-keys))
  ([opts fetch-fn]
   (let [provider (:provider-compute opts)]
     (if-not (and (keygen? opts) (contains? preflight-providers provider))
       opts
       (let [token (get opts (preflight-tokens provider))
             owned-id (some-> (:once/ssh-state-params opts) :ssh_key_id str)
             keys (try [(fetch-fn provider token) nil]
                       (catch Exception e [nil (ex-message e)]))
             [account-keys err] keys]
         (cond
           err
           (fail opts (str "cannot list " provider " SSH keys for the create "
                           "preflight: " err))

           :else
           (let [found (first (filter #(= (:name %) (profile opts)) account-keys))
                 local-pub (let [f (io/file (public-key-path opts))]
                             (when (.exists f) (normalize-key (slurp f))))]
             (cond
               (nil? found) opts
               (and owned-id (= owned-id (:id found))) opts

               (and local-pub (= local-pub (:public found)))
               (fail opts (str provider " already has an SSH key named "
                               (profile opts) " (id " (:id found) ") that is "
                               "not in this deployment's state and matches "
                               (public-key-path opts) ": a previous delete "
                               "left it behind. Verify no host for "
                               (profile opts) " survives, delete that key at "
                               "the provider, and retry."))

               :else
               (fail opts (str provider " already has an SSH key named "
                               (profile opts) " (id " (:id found) ") that is "
                               "not in this deployment's state and does not "
                               "match " (public-key-path opts) ". Do not "
                               "delete it: it belongs to something else. "
                               "Investigate, or change profile."))))))))))

;;; ----------------------------------------------------------------- delete

(defn- remove-file!
  "Delete `path` when it exists, and answer the failure message when it is
  still there afterwards. Presence is the check, not `.delete`'s boolean: a
  file in a read-only directory survives without an exception, and a delete
  that reported success over a surviving key would break the invariant the
  standard exists for."
  [path]
  (let [f (io/file path)]
    (when (.exists f) (.delete f))
    (when (.exists f)
      (str "cannot remove " path " after the compute destroy; remove it by "
           "hand and retry the delete"))))

(defn cleanup-step
  "Remove the generated keypair — the delete DAG wires this after the compute
  destroy, so reaching it means the destroy succeeded and the invariant `key
  present ⇔ deployment exists` holds. A failed or interrupted delete leaves
  the key, correctly: it is still needed. Only the profile-named files are
  touched: `~/.ssh` is the operator's directory and is never removed. A key
  file that survives the removal fails the step: the operator is told which
  file, and the delete is not done until it is gone."
  [opts]
  (if-not (and (= :delete (:green/event opts)) (keygen? opts))
    (assoc opts :green/exit 0)
    (if-let [err (some remove-file! [(private-key-path opts) (public-key-path opts)])]
      (fail opts err)
      (assoc opts :green/exit 0))))
