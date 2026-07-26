(ns io.github.bigconfig-ai.once.utils
  "The compatibility contract, and the DNS zone derivation every stage shares."
  (:require
   [clojure.string :as str]))

(def contract
  "Compatibility number for the launcher that consumes these namespaces and the
  templates under src/resources. Bump it on any change a launcher pinned to an
  older commit could not survive; the launcher refuses to run against a lower
  number and tells the user to repin.

  2: tools/backend-credential-env, which the launcher calls to read Tofu state.
  3: desired state drops :domain and :package. DNS zones are derived from the
     application hosts, and :profile alone names the stack.
  4: applications may span DNS zones. utils/apps-domains replaces the singular
     apps-domain contract used by the SMTP and DNS stages.
  5: validation and the workflow graph move out of the launcher and into this
     library, as once.validate and once.workflow. The launcher no longer
     defines its own steps — it calls workflow/workflow, describe/describe-file,
     and green.cli/read-pars, and `pin` is a maintainer bb task rather than a
     launcher subcommand."
  5)

(defn registrable-domain
  "The DNS zone `host` belongs to: its last two labels. Multi-label suffixes
  such as co.uk are not recognised — a host under one has to sit in a zone
  Cloudflare would report by its last two labels anyway."
  [host]
  (let [labels (str/split (str host) #"\.")]
    (when (<= 2 (count labels))
      (str/join "." (take-last 2 labels)))))

(defn apps-domains
  "The sorted DNS zones used by the applications. Desired state carries no
  :domain: each application's DNS records, Resend sending domain, and From
  address derive from that application's host."
  [opts]
  (->> (get-in opts [:once :applications])
       (keep (comp registrable-domain :host))
       distinct
       sort
       vec))
