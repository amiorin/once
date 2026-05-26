(ns io.github.bigconig-ai.once.cli
  (:require
   [io.github.bigconig-ai.once.options :as options]
   [io.github.bigconig-ai.once.package :as package]
   [io.github.bigconig-ai.once.params :as params]
   [io.github.bigconig-ai.once.tools :as tools]))

(def help-text
  "Usage: once <command> [args...]

Commands:
  package <step>...  Build, provision, or tear down infrastructure for the active profile.
                       once package validate
                       once package describe
                       once package build
                       once package create
                       once package delete
  validate           Shortcut for `once package validate`.

  Individual tools (each requires `render` first):
  tofu <args>             e.g. once tofu render tofu:init tofu:apply:-auto-approve
  tofu-smtp <args>
  tofu-dns <args>
  tofu-smtp-post <args>
  ansible <args>          e.g. once ansible render -- ansible-playbook main.yml
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
        rest-args (subvec args 1)]
    (cond
      (or (nil? command) (#{"help" "--help" "-h"} command))
      (println help-text)

      (#{"package" "once"} command)
      (package/once* rest-args options/bb)

      (= command "validate")
      (if (seq rest-args)
        (package/once* args options/bb)
        (package/once* ["validate"] options/bb))

      (contains? package-commands command)
      (package/once* args options/bb)

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
