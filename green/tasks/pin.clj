(ns pin
  "Stamp the bundled launcher with the commits a standalone copy should
  resolve.

  This is a maintainer command for this repository, not something a user of the
  skill ever runs: it reads the HEAD of the checkout it is invoked in, so
  anywhere else it would stamp an unrelated SHA. That is why it lives in bb.edn
  rather than in the launcher — a payload copied into a stranger's project
  should not carry a command that is wrong by construction there.

  `green-sha` moves too when GREEN_LIB_ROOT points at a green checkout, because
  a change that spans both repositories has to pin both."
  (:require
   [clojure.java.shell :as sh]
   [clojure.string :as str]))

(def ^:private launcher "../skills/package-once-green/green")

(defn- git
  [dir & args]
  (apply sh/sh "git" "-C" (str dir) args))

(defn- git-out
  [dir & args]
  (let [{:keys [exit out]} (apply git dir args)]
    (when (zero? exit) (str/trim out))))

(defn- repo-head
  "HEAD of the repository containing `dir`, once it is clean and pushed.
  Returns [sha nil] or [nil error]. Pinning a dirty or unpushed commit would
  produce a launcher that resolves to something nobody else can fetch."
  [dir label]
  (if-let [top (git-out dir "rev-parse" "--show-toplevel")]
    (let [dirty (git-out top "status" "--porcelain")
          sha (git-out top "rev-parse" "HEAD")
          remotes (git-out top "branch" "-r" "--contains" (str sha))]
      (cond
        (seq dirty)
        [nil (str label " working tree is dirty; commit before pinning")]

        (not (str/includes? (str remotes) "origin/"))
        [nil (str label " HEAD " (subs sha 0 7) " is not on any remote branch; "
                  "push before pinning")]

        :else [sha nil]))
    [nil (str label " is not a git repository: " dir)]))

(defn- current-pin
  [text sym]
  (second (re-find (re-pattern (str "\\(def \\^:private " sym " \"([0-9a-f]{40})\"\\)"))
                   text)))

(defn- replace-pin
  [text sym old new]
  (str/replace text
               (str "(def ^:private " sym " \"" old "\")")
               (str "(def ^:private " sym " \"" new "\")")))

(defn pin
  "Returns green's Unix-style outcome map, so the task can be tested."
  []
  (let [text (slurp launcher)
        once-sha (current-pin text "once-sha")
        green-sha (current-pin text "green-sha")
        green-root (System/getenv "GREEN_LIB_ROOT")
        [once-head once-err] (repo-head "." "once")
        [green-head green-err] (if green-root
                                 (repo-head green-root "green")
                                 [nil nil])
        once-stale? (and once-head (not= once-head once-sha))
        green-stale? (and green-head (not= green-head green-sha))]
    (cond
      (not (and once-sha green-sha))
      {:green/exit 2 :green/err (str "could not locate the pins in " launcher)}

      once-err {:green/exit 2 :green/err once-err}
      green-err {:green/exit 2 :green/err green-err}

      (not (or once-stale? green-stale?))
      {:green/exit 0 :green/err (str "already pinned to " (subs once-sha 0 7))}

      :else
      (do
        (spit launcher
              (cond-> text
                once-stale? (replace-pin "once-sha" once-sha once-head)
                green-stale? (replace-pin "green-sha" green-sha green-head)))
        {:green/exit 0
         :green/err (str/join
                     "\n"
                     (cond-> []
                       once-stale?
                       (conj (str "pinned once to " (subs once-head 0 7)
                                  " (was " (subs once-sha 0 7) ")"))
                       green-stale?
                       (conj (str "pinned green to " (subs green-head 0 7)
                                  " (was " (subs green-sha 0 7) ")"))))}))))

(defn -main
  [& _]
  (let [{:green/keys [exit err]} (pin)]
    (when err
      (binding [*out* (if (zero? exit) *out* *err*)]
        (println err)))
    (System/exit exit)))
