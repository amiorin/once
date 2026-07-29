;; Print which container green resolves for every host in the parity corpus, one
;; `host=image` line per entry. Red and blue print the same shape, so parity.sh
;; can diff them directly.
(require '[cheshire.core :as json]
         '[io.github.getcolors.once.describe :as describe])

(let [path (first *command-line-args*)
      {:keys [containers hosts]} (json/parse-string (slurp path) keyword)]
  (doseq [host hosts]
    (let [container (#'describe/find-container-for-host containers host)]
      (println (str host "=" (or (get-in container [:Config :Image]) ""))))))
