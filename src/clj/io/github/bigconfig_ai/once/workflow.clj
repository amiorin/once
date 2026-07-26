(ns io.github.bigconfig-ai.once.workflow
  "The DAG the launcher runs, and the two steps that are not a tool.

  Create and build fork compute and SMTP, join them at DNS, and fork again
  into the two Ansible stages:

      start ─┬─ tofu-compute ─┐                          ┌─ ansible-local
             └─ tofu-smtp ────┴─ tofu-dns ─ smtp-post ───┴─ ansible-remote

  Delete runs the same stages in reverse, dropping the managed SSH config
  before anything is destroyed."
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [green.cli :as green-cli]
   [green.dry-run :as dry-run]
   [green.progress :as progress]
   [green.tofu :as tofu]
   [green.workflow :as wf]
   [io.github.bigconfig-ai.once.tools :as tools]
   [io.github.bigconfig-ai.once.validate :as validate]))

;; ---------------------------------------------------------------------------
;; start

(defn- state-output
  "Read a previously applied stage's `params` output, or nil when the stage has
  no state yet."
  [opts tool]
  (try
    (some-> (tofu/outputs (tools/tool-dir opts tool)
                          (tools/backend-credential-env opts))
            :params walk/keywordize-keys)
    (catch Exception _ nil)))

(defn- adopt-existing-state
  "Delete renders the same templates as create, so a destroy needs the params
  earlier stages produced (compute ip, smtp domain id and records)."
  [opts]
  (let [compute (state-output opts "tofu-compute")
        smtp (state-output opts "tofu-smtp")]
    (cond-> opts
      compute (-> (merge compute) (assoc :once/compute-params compute))
      smtp (-> (merge smtp) (assoc :once/smtp-params smtp)))))

(defn start-step
  "Overlay `GREEN_PAR_*`, validate, and — for a real delete — read back what
  the earlier stages left in OpenTofu state.

  Credentials are only required for a lifecycle event that actually reaches a
  provider: `build` and `--dry-run` render from desired state alone, so they
  stay usable without any secret in the environment.

  The two-argument arity takes the environment to overlay, so a test does not
  inherit whatever `GREEN_PAR_*` variables the developer happens to have set."
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (let [opts (green-cli/read-pars (merge {:compute-prevent-destroy true} opts) env)
         event (:green/event opts)
         real? (not (:green/dry-run opts))
         lifecycle? (contains? #{:create :delete} event)
         errors (vec (concat (validate/state-errors opts)
                             (when (and real? lifecycle?) (validate/secret-errors opts))
                             (when (and real? (= :delete event)
                                        (:compute-prevent-destroy opts))
                               [(str "compute destruction is protected; set "
                                     (green-cli/par-name :compute-prevent-destroy)
                                     "=false to delete")])))]
     (cond
       (seq errors) (assoc opts :green/exit 2 :green/err (str/join "\n" errors))
       (and real? (= :delete event)) (assoc (adopt-existing-state opts) :green/exit 0)
       :else (assoc opts :green/exit 0)))))

(defn ansible-cleanup-step
  "Undo what the Ansible stages applied, then remove their rendered trees.
  ansible-local runs its playbook once more to drop the managed ~/.ssh/config
  block; both steps then scaffold against :green/event :delete, which deletes
  their targets."
  [opts]
  (-> opts tools/ansible-local-step tools/ansible-remote-step))

;; ---------------------------------------------------------------------------
;; wiring

(def tofu-steps
  [:once/tofu-compute :once/tofu-smtp :once/tofu-dns :once/tofu-smtp-post])

(def side-effecting-steps
  (into tofu-steps [:once/ansible-local :once/ansible-remote
                    :once/ansible-cleanup]))

(defn wire-fn
  [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :once/start           [start-step :once/ansible-cleanup]
      :once/ansible-cleanup [ansible-cleanup-step :once/tofu-smtp-post]
      :once/tofu-smtp-post  [tools/tofu-smtp-post-step :once/tofu-dns]
      :once/tofu-dns        [tools/tofu-dns-step :once/tofu-smtp :once/tofu-compute]
      :once/tofu-smtp       [tools/tofu-smtp-step]
      :once/tofu-compute    [tools/tofu-compute-step])
    (case step
      :once/start           [start-step :once/tofu-compute :once/tofu-smtp]
      :once/tofu-compute    [tools/tofu-compute-step :once/tofu-dns]
      :once/tofu-smtp       [tools/tofu-smtp-step :once/tofu-dns]
      :once/tofu-dns        [tools/tofu-dns-step :once/tofu-smtp-post]
      :once/tofu-smtp-post  [tools/tofu-smtp-post-step :once/ansible-local :once/ansible-remote]
      :once/ansible-local   [tools/ansible-local-step]
      :once/ansible-remote  [tools/ansible-remote-step])))

;; ---------------------------------------------------------------------------
;; backends

(defn backend-advice
  "The `:before` advice that writes backend.tf.json for one stage. Remote state
  is keyed by profile and stage, so two profiles never share a state file."
  [tool]
  (let [dir-fn #(tools/tool-dir % tool)
        state-key #(str (or (:profile %) "default") "/" tool ".tfstate")]
    (tofu/backends
     #(or (:provider-backend %) "local")
     {"local" (tofu/local-backend-advice dir-fn)
      "s3" (tofu/s3-backend-advice dir-fn
                                   (fn [opts]
                                     {:bucket (:s3-bucket opts)
                                      :key (state-key opts)
                                      :region (:s3-region opts)}))
      "r2" (tofu/r2-backend-advice dir-fn
                                   (fn [opts]
                                     {:bucket (:r2-bucket opts)
                                      :key (state-key opts)
                                      :endpoint (:r2-endpoint opts)}))})))

(def workflow
  (-> (wf/workflow {:start :once/start :wire-fn wire-fn})
      (wf/advice-add :once/tofu-compute :before ::backend
                     (backend-advice "tofu-compute"))
      (wf/advice-add :once/tofu-smtp :before ::backend
                     (backend-advice "tofu-smtp"))
      (wf/advice-add :once/tofu-dns :before ::backend
                     (backend-advice "tofu-dns"))
      (wf/advice-add :once/tofu-smtp-post :before ::backend
                     (backend-advice "tofu-smtp-post"))
      progress/advise
      (dry-run/advise side-effecting-steps)))
