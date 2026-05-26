(ns io.github.bigconig-ai.once.package
  (:require
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.run :as run]
   [big-config.step-fns :as step-fns]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [io.github.bigconig-ai.once.describe :as describe]
   [io.github.bigconig-ai.once.options :as options]
   [io.github.bigconig-ai.once.params :as params]
   [io.github.bigconig-ai.once.tools :as tools]
   [io.github.bigconig-ai.once.validation :as validation]))

(def step-fns [workflow/print-step-fn
               (step-fns/->exit-step-fn ::end)
               (step-fns/->print-error-step-fn ::end)])

(def create
  (workflow/->workflow* {:first-step ::start-create-or-delete
                         :last-step :end-create-or-delete
                         :pipeline [::tools/tofu ["render tofu:init tofu:apply:-auto-approve" params/opts-fn]
                                    ::tools/tofu-smtp ["render tofu:init tofu:apply:-auto-approve" params/opts-fn]
                                    ::tools/tofu-dns ["render tofu:init tofu:apply:-auto-approve" params/opts-fn]
                                    ::tools/tofu-smtp-post ["render tofu:init tofu:apply:-auto-approve" params/opts-fn]
                                    ::tools/ansible-local ["render ansible-playbook:main.yml" params/opts-fn]
                                    ::tools/ansible ["render ansible-playbook:main.yml" params/opts-fn]]}))

(def build
  (workflow/->workflow* {:first-step ::start-create-or-delete
                         :last-step :end-create-or-delete
                         :pipeline [::tools/tofu ["render" params/opts-fn]
                                    ::tools/tofu-smtp ["render" params/opts-fn]
                                    ::tools/tofu-dns ["render" params/opts-fn]
                                    ::tools/tofu-smtp-post ["render" params/opts-fn]
                                    ::tools/ansible-local ["render" params/opts-fn]
                                    ::tools/ansible ["render" params/opts-fn]]}))

(def ^:private tool-workflows
  {::tools/tofu tools/tofu
   ::tools/tofu-smtp tools/tofu-smtp
   ::tools/tofu-dns tools/tofu-dns
   ::tools/tofu-smtp-post tools/tofu-smtp-post
   ::tools/ansible-local tools/ansible-local
   ::tools/ansible tools/ansible})

(when-let [register-workflow (ns-resolve 'big-config.workflow 'register-workflow)]
  (run! (fn [[step f]]
          (register-workflow step f))
        tool-workflows))

(comment
  (debug tap-values
    (create [(fn [f step opts]
               (tap> [step opts])
               (f step opts))] {::bc/env :repl
                                ::run/shell-opts {:err *err*
                                                  :out *out*}
                                ::tools/tofu-opts (workflow/parse-args "render")
                                ::tools/tofu-smtp-opts (workflow/parse-args "render")
                                ::tools/tofu-dns-opts (workflow/parse-args "render")
                                ::tools/tofu-smtp-post-opts (workflow/parse-args "render")
                                ::tools/ansible-local-opts (workflow/parse-args "render")
                                ::tools/ansible-opts (workflow/parse-args "render")}))
  (-> tap-values))

(def delete
  (workflow/->workflow* {:first-step ::start-create-or-delete
                         :last-step ::end-create-or-delete
                         :pipeline [::tools/tofu-smtp-post ["render tofu:init tofu:destroy:-auto-approve" params/opts-fn]
                                    ::tools/tofu-dns ["render tofu:init tofu:destroy:-auto-approve" params/opts-fn]
                                    ::tools/tofu-smtp ["render tofu:init tofu:destroy:-auto-approve" params/opts-fn]
                                    ::tools/tofu ["render tofu:init tofu:destroy:-auto-approve" params/opts-fn]]}))

(defn once
  [step-fns {:keys [::workflow/params] :as opts}]
  (let [opts (->> opts
                  (merge {::workflow/create-fn create
                          ::workflow/build-fn build
                          ::workflow/delete-fn delete
                          ::workflow/validate-fn validation/validate
                          ::workflow/describe-fn describe/describe})
                  (workflow/merge-params [::tools/tofu-opts ::tools/tofu-smtp-opts ::tools/tofu-dns-opts ::tools/tofu-smtp-post-opts ::tools/ansible-local-opts ::tools/ansible-opts] params))
        wf (core/->workflow {:first-step ::start
                             :wire-fn (fn [step step-fns]
                                        (case step
                                          ::start [(partial workflow/run-steps step-fns) ::end]
                                          ::end [identity]))})]
    (wf step-fns opts)))

(comment
  (debug tap-values
    (once [(fn [f step opts]
             (tap> [step opts])
             (f step opts))]
          (merge options/bb
                 {::bc/env :repl
                  ::run/shell-opts {:err *err*
                                    :out *out*}
                  ::workflow/steps [:create]})))
  (-> tap-values))

(defn once*
  [args & [opts]]
  (let [opts (merge (workflow/parse-args args)
                    {::bc/env :shell}
                    opts)]
    (once step-fns opts)))

(comment
  (debug tap-values
    (once* "create" (merge options/bb
                           {::bc/env :repl
                            ::run/shell-opts {:err *err*
                                              :out *out*}})))
  (-> tap-values))
