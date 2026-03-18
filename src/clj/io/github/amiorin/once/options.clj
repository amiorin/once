(ns io.github.amiorin.once.options
  (:require
   [big-config.workflow :as workflow]))

(def defaults {::workflow/params {:hyperscaler "oci"
                                  :package "once"}})
