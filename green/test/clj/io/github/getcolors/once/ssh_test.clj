(ns io.github.getcolors.once.ssh-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.once.ssh :as sut]))

(defn- tmp-home
  "A throwaway directory standing in for $HOME — the keypair lives in
  `~/.ssh`, and no test may touch the real one."
  []
  (str (java.nio.file.Files/createTempDirectory
        "once-ssh-test"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defmacro with-home
  "Run body with the module resolving `~` to dir."
  [dir & body]
  `(with-redefs [sut/home-dir (constantly ~dir)] ~@body))

(defn- opts-in [dir & kvs]
  (merge {:profile "prod"
          :provider-compute "vultr"
          :green/state-file (str (io/file dir "colors.yml"))}
         (apply hash-map kvs)))

(defn- fake-keygen
  "A run-fn that writes both halves the way ssh-keygen would."
  [args _ _]
  (let [path (last args)]
    (io/make-parents path)
    (spit path "PRIVATE")
    (spit (str path ".pub") "ssh-ed25519 AAAATESTKEY prod managed by Colors")
    {:exit 0}))

(defn- seed-keypair! [dir]
  (fake-keygen ["ssh-keygen" (str (io/file dir ".ssh" "prod"))] {} 0))

(deftest keygen-mode-is-selected-by-absence
  (let [dir (tmp-home)]
    (with-home dir
      (testing "no machine key in desired state means keygen mode"
        (is (sut/keygen? (opts-in dir)))
        (is (sut/keygen? (opts-in dir :provider-compute "yandex"))))
      (testing "an explicit value is opt-out, placeholder included"
        (is (not (sut/keygen? (opts-in dir :vultr-ssh-keys "key-uuid"))))
        (is (sut/keygen? (opts-in dir :vultr-ssh-keys "REPLACE_ME"))))
      (testing "no-infra provisions no machine, so it never generates"
        (is (not (sut/keygen? (opts-in dir :provider-compute "no-infra"))))))))

(deftest with-machine-key-fills-templates-deterministically
  (let [dir (tmp-home)]
    (with-home dir
      (testing "opt-out opts pass through untouched"
        (let [opts (opts-in dir :vultr-ssh-keys "key-uuid")]
          (is (= opts (sut/with-machine-key opts false)))
          (is (= opts (sut/with-machine-key opts true)))))
      (testing "path providers get the absolute public key path under ~/.ssh"
        (let [out (sut/with-machine-key (opts-in dir :provider-compute "oci") false)]
          (is (true? (:ssh-keygen out)))
          (is (= (:oci-ssh-authorized-keys out) (:ssh-public-key-path out)))
          (is (= (str (io/file dir ".ssh" "prod.pub")) (:ssh-public-key-path out)))
          (is (= (str (io/file dir ".ssh" "prod")) (:ssh-private-key-path out)))))
      (testing "the content provider gets the placeholder on build"
        (let [out (sut/with-machine-key (opts-in dir :provider-compute "yandex") false)]
          (is (= sut/placeholder-public (:compute-pubkey out)))))
      (testing "a build never reads ~/.ssh, even when the key exists"
        (io/make-parents (io/file dir ".ssh" "prod"))
        (spit (io/file dir ".ssh" "prod.pub") "ssh-ed25519 AAAAREAL prod")
        (let [out (sut/with-machine-key (opts-in dir :provider-compute "yandex") false)]
          (is (= sut/placeholder-public (:compute-pubkey out))))
        (testing "while a real event reads the generated content"
          (let [out (sut/with-machine-key (opts-in dir :provider-compute "yandex") true)]
            (is (= "ssh-ed25519 AAAAREAL prod" (:compute-pubkey out)))))))))

(deftest ensure-key-implements-the-create-matrix
  (testing "first create generates and the run proceeds"
    (let [dir (tmp-home)]
      (with-home dir
        (let [out (sut/ensure-key! (opts-in dir) (constantly nil) fake-keygen)]
          (is (not (pos? (:green/exit out 0))))
          (is (.exists (io/file dir ".ssh" "prod")))
          (is (.exists (io/file dir ".ssh" "prod.pub")))))))

  (testing "state without a key is lost access, not regeneration"
    (let [dir (tmp-home)]
      (with-home dir
        (let [out (sut/ensure-key! (opts-in dir) (constantly {:ip "1.2.3.4"})
                                   fake-keygen)]
          (is (= 1 (:green/exit out)))
          (is (str/includes? (:green/err out) "does not hold the machine key"))
          (is (not (.exists (io/file dir ".ssh" "prod"))))))))

  (testing "a key without state refuses rather than overwrites"
    (let [dir (tmp-home)]
      (with-home dir
        (seed-keypair! dir)
        (let [before (slurp (io/file dir ".ssh" "prod"))
              out (sut/ensure-key! (opts-in dir) (constantly nil)
                                   (fn [& _] (is false "must not regenerate") {:exit 1}))]
          (is (= 1 (:green/exit out)))
          (is (str/includes? (:green/err out) "previous delete may be incomplete"))
          (is (= before (slurp (io/file dir ".ssh" "prod")))
              "the existing key is untouched — it may be the only credential left")))))

  (testing "state and key together converge and re-enforce permissions"
    (let [dir (tmp-home)]
      (with-home dir
        (seed-keypair! dir)
        (let [out (sut/ensure-key! (opts-in dir) (constantly {:ip "1.2.3.4"})
                                   (fn [& _] {:exit 1}))]
          (is (not (pos? (:green/exit out 0))))))))

  (testing "half a keypair is an error either way round"
    (let [dir (tmp-home)]
      (with-home dir
        (io/make-parents (io/file dir ".ssh" "prod"))
        (spit (io/file dir ".ssh" "prod") "PRIVATE")
        (let [out (sut/ensure-key! (opts-in dir) (constantly {:ip "1.2.3.4"})
                                   (fn [& _] {:exit 1}))]
          (is (= 1 (:green/exit out)))
          (is (str/includes? (:green/err out) "half a keypair"))))))

  (testing "opt-out mode does nothing at all"
    (let [dir (tmp-home)]
      (with-home dir
        (let [opts (opts-in dir :vultr-ssh-keys "key-uuid")]
          (is (= opts (sut/ensure-key! opts (constantly nil)
                                       (fn [& _] (is false) {:exit 1})))))))))

(defn- preflight-opts [dir & kvs]
  (sut/with-machine-key (apply opts-in dir :do-token "tok"
                               :provider-compute "digitalocean" kvs)
                        true))

(deftest preflight-refuses-unowned-provider-keys
  (let [dir (tmp-home)]
    (with-home dir
      (seed-keypair! dir)

      (testing "no key at the provider proceeds"
        (let [out (sut/preflight! (preflight-opts dir) (fn [_ _] []))]
          (is (not (pos? (:green/exit out 0))))))

      (testing "the key our state owns proceeds"
        (let [out (sut/preflight!
                   (assoc (preflight-opts dir) :once/ssh-state-params {:ssh_key_id "77"})
                   (fn [_ _] [{:id "77" :name "prod" :public "ssh-ed25519 AAAATESTKEY"}]))]
          (is (not (pos? (:green/exit out 0))))))

      (testing "our leftover names the recovery, a foreign key forbids deletion"
        (let [ours (sut/preflight!
                    (preflight-opts dir)
                    (fn [_ _] [{:id "77" :name "prod"
                                :public "ssh-ed25519 AAAATESTKEY"}]))
              foreign (sut/preflight!
                       (preflight-opts dir)
                       (fn [_ _] [{:id "88" :name "prod"
                                   :public "ssh-ed25519 AAAASOMEONEELSE"}]))]
          (is (= 1 (:green/exit ours)))
          (is (str/includes? (:green/err ours) "previous delete left it behind"))
          (is (= 1 (:green/exit foreign)))
          (is (str/includes? (:green/err foreign) "Do not delete it"))))

      (testing "a listing failure stops the create instead of skipping the check"
        (let [out (sut/preflight! (preflight-opts dir)
                                  (fn [_ _] (throw (ex-info "HTTP 500" {}))))]
          (is (= 1 (:green/exit out)))
          (is (str/includes? (:green/err out) "cannot list"))))

      (testing "another deployment's key under a different name is ignored"
        (let [out (sut/preflight!
                   (preflight-opts dir)
                   (fn [_ _] [{:id "9" :name "other" :public "ssh-ed25519 X"}]))]
          (is (not (pos? (:green/exit out 0))))))

      (testing "aws and the path providers have no REST preflight"
        (let [out (sut/preflight! (sut/with-machine-key
                                   (opts-in dir :provider-compute "aws") true)
                                  (fn [_ _] (throw (ex-info "must not be called" {}))))]
          (is (not (pos? (:green/exit out 0)))))))))

(deftest cleanup-removes-the-key-only-on-a-keygen-delete
  (let [dir (tmp-home)]
    (with-home dir
      (seed-keypair! dir)
      (testing "a keygen delete removes both halves but never ~/.ssh itself"
        (let [out (sut/cleanup-step (opts-in dir :green/event :delete))]
          (is (zero? (:green/exit out)))
          (is (not (.exists (io/file dir ".ssh" "prod"))))
          (is (not (.exists (io/file dir ".ssh" "prod.pub"))))
          (is (.exists (io/file dir ".ssh"))
              "~/.ssh is the operator's directory, not the deployment's")))
      (testing "opt-out deletes leave the key alone"
        (seed-keypair! dir)
        (sut/cleanup-step (opts-in dir :green/event :delete :vultr-ssh-keys "key-uuid"))
        (is (.exists (io/file dir ".ssh" "prod"))))
      (testing "other events leave the key alone"
        (sut/cleanup-step (opts-in dir :green/event :create))
        (is (.exists (io/file dir ".ssh" "prod")))))))

(deftest identity-args-select-the-machine-key
  (is (= [] (sut/identity-args {:ip "1.2.3.4"})))
  (is (= ["-o" "IdentitiesOnly=yes" "-i" "/x/.ssh/prod"]
         (sut/identity-args {:ssh-keygen true
                             :ssh-private-key-path "/x/.ssh/prod"}))))
