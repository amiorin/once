(ns io.github.bigconfig-ai.once.utils
  (:require
   [clojure.string :as str]))

(defn strip-ansi [s]
  (-> s
      (str/replace #"\x1b\]8;[^\x07]*\x07" "")
      (str/replace #"\x1b\[[0-9;]*m" "")))
