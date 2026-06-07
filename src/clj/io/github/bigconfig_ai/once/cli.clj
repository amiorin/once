(ns io.github.bigconfig-ai.once.cli
  (:require
   [io.github.bigconfig-ai.once.options :as options]
   [io.github.bigconfig-ai.once.package :as package]
   [io.github.bigconfig-ai.once.params :as params]
   [io.github.bigconfig-ai.once.tools :as tools]))

(def help-text
  "Usage: bb run <command> [args...]

Commands:
  package <step>...       Run package workflow steps for the active profile.
                            bb run package validate
                            bb run package describe
                            bb run package build
                            bb run package create
                            bb run package delete
                            bb run package git-check lock build unlock-any
  once package <step>...  Backwards-compatible nested form.

  Package steps:
    validate              Pre-flight profile, tool, credential, image, and SSH-agent checks.
    describe              Post-provisioning providers, SSH reachability, apps, and updates report.
    build                 Render all stages without applying/provisioning.
    create                Provision and configure the full ONCE stack.
    delete                Tear down the Tofu stages in reverse order.
    lock                  Acquire the BigConfig Git-tag lock.
    git-check             Verify the Git working tree/upstream state is clean.
    git-push              Run git push through the BigConfig workflow.
    unlock-any            Force-release the computed BigConfig lock tag.

  Individual tools (accept SDK workflow steps and exec commands):
  tofu <args>             e.g. bb run tofu render tofu:init tofu:apply:-auto-approve
                          e.g. bb run tofu git-check lock render tofu:init tofu:plan unlock-any
  tofu-smtp <args>
  tofu-dns <args>
  tofu-smtp-post <args>
  ansible <args>          e.g. bb run ansible render -- ansible-playbook main.yml
  ansible-local <args>

Notes:
  * When launched through `run`, the active profile comes from that script;
    otherwise it defaults to `bb` in io.github.bigconfig-ai.once.options.
  * Any param can be overridden with BC_PAR_* environment variables.

See: https://www.bigconfig.ai/manual")

(def package-commands #{"validate" "describe" "build" "create" "delete"
                        "lock" "git-check" "git-push" "unlock-any"})

(defn- die!
  [& lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit 1))

(defn main*
  ([args]
   (main* args options/bb))
  ([args opts]
   (let [args (mapv str args)
         command (first args)
         rest-args (if (seq args) (subvec args 1) [])]
    (cond
      (or (nil? command) (#{"help" "--help" "-h"} command))
      (println help-text)

      (= command "once")
      (let [subcommand (first rest-args)
            package-args (if (seq rest-args) (subvec rest-args 1) [])]
        (cond
          (#{"help" "--help" "-h"} subcommand)
          (println help-text)

          (= subcommand "package")
          (cond
            (some #{(first package-args)} ["help" "--help" "-h"])
            (println help-text)

            (seq package-args)
            (package/once* package-args opts)

            :else
            (die! "Missing package step."
                  "Usage: bb run once package <step>..."))

          (contains? package-commands subcommand)
          (die! (str "Use `bb run once package " subcommand "`.") "" help-text)

          :else
          (die! "Usage: bb run once package <step>..." "" help-text)))

      (= command "package")
      (cond
        (some #{(first rest-args)} ["help" "--help" "-h"])
        (println help-text)

        (seq rest-args)
        (package/once* rest-args opts)

        :else
        (die! "Missing package step."
              "Usage: bb run package <step>..."))

      (contains? package-commands command)
      (die! (str "Use `bb run package " command "`.") "" help-text)

      (= command "tofu")
      (tools/tofu* rest-args (params/once-opts opts))

      (= command "tofu-smtp")
      (tools/tofu-smtp* rest-args (params/once-opts opts))

      (= command "tofu-dns")
      (tools/tofu-dns* rest-args (params/once-opts opts))

      (= command "tofu-smtp-post")
      (tools/tofu-smtp-post* rest-args (params/once-opts opts))

      (= command "ansible")
      (tools/ansible* rest-args (params/once-opts opts))

      (= command "ansible-local")
      (tools/ansible-local* rest-args (params/once-opts opts))

      :else
      (die! (str "Unknown command: " command) "" help-text)))))

(defn -main
  [& args]
  (main* args))
