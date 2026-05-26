(ns io.github.bigconig-ai.once.cli
  (:require
   [io.github.bigconig-ai.once.options :as options]
   [io.github.bigconig-ai.once.package :as package]
   [io.github.bigconig-ai.once.params :as params]
   [io.github.bigconig-ai.once.tools :as tools]))

(def help-text
  "Usage: bb run <command> [args...]

Commands:
  package <step>...       Build, provision, or tear down infrastructure for the active profile.
                            bb run package validate
                            bb run package describe
                            bb run package build
                            bb run package create
                            bb run package delete
  once package <step>...  Backwards-compatible nested form.
  validate                Strict shortcut for `bb run package validate`.

  Individual tools (each requires `render` first):
  tofu <args>             e.g. bb run tofu render tofu:init tofu:apply:-auto-approve
  tofu-smtp <args>
  tofu-dns <args>
  tofu-smtp-post <args>
  ansible <args>          e.g. bb run ansible render -- ansible-playbook main.yml
  ansible-local <args>

Notes:
  * The active profile is selected by `bb` in io.github.bigconig-ai.once.options.
  * Any param can be overridden with BC_PAR_* environment variables.

See: https://www.bigconfig.ai/manual")

(def package-commands #{"validate" "describe" "build" "create" "delete"})

(defn- die!
  [& lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit 1))

(defn main*
  [args]
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
          (if (seq package-args)
            (package/once* package-args options/bb)
            (die! "Missing package step."
                  "Usage: bb run once package <validate|describe|build|create|delete>..."))

          (contains? package-commands subcommand)
          (die! (str "Use `bb run once package " subcommand "`.") "" help-text)

          :else
          (die! "Usage: bb run once package <validate|describe|build|create|delete>..." "" help-text)))

      (= command "validate")
      (if (seq rest-args)
        (die! "Error: bb run validate does not accept arguments."
              "Usage: bb run validate")
        (package/once* ["validate"] options/bb))

      (= command "package")
      (if (seq rest-args)
        (package/once* rest-args options/bb)
        (die! "Missing package step."
              "Usage: bb run package <validate|describe|build|create|delete>..."))

      (contains? package-commands command)
      (die! (str "Use `bb run package " command "`.") "" help-text)

      (= command "tofu")
      (tools/tofu* rest-args (params/once-opts options/bb))

      (= command "tofu-smtp")
      (tools/tofu-smtp* rest-args (params/once-opts options/bb))

      (= command "tofu-dns")
      (tools/tofu-dns* rest-args (params/once-opts options/bb))

      (= command "tofu-smtp-post")
      (tools/tofu-smtp-post* rest-args (params/once-opts options/bb))

      (= command "ansible")
      (tools/ansible* rest-args (params/once-opts options/bb))

      (= command "ansible-local")
      (tools/ansible-local* rest-args (params/once-opts options/bb))

      :else
      (die! (str "Unknown command: " command) "" help-text))))

(defn -main
  [& args]
  (main* args))
