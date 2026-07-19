(ns crm.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (ADR-2607189300 at com-junkawasaki/root)
  for cloud-itonami-isic-5820: the wave5 fleet demo-generation sweep covers
  the 290-repo cluster sharing the {facts,phase,sim,governor,operation,
  advisor,store} actor shape, but explicitly DEFERRED 5820 ('its actor shape
  has no HTML-render entrypoint at all'). This namespace IS that entrypoint.

  It runs the SAME governed CRM scenario `crm.sim` already exercises
  (op1 clean transition -> commit; op3 discount-authority exceeded -> hard
  hold; op7 dispute -> escalate -> human-approve -> commit) through the REAL
  actor stack (crm.operation -> crm.policy governor -> crm.store), then
  renders the resulting dashboard (pipeline funnel + conversion rates +
  ground-truth ASC 606 revenue rollup) + opportunities table to HTML.

  No invented numbers, no timestamps in the page content -- reruns against
  the same fresh seed produce byte-identical output, so regeneration only
  commits when the actor's real behavior actually changes (the same
  commit-only-on-change discipline isic-2910/automotive.render-html +
  isic-6399/web/generate.cljs established for this fleet).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [crm.store :as store]
            [crm.operation :as operation]
            [crm.dashboard :as dashboard]
            [langgraph.graph :as g]))

(def ^:private rep-ctx  {:actor-id "rep-1" :actor-role :rep :phase 3})
(def ^:private mgr-ctx  {:actor-id "mg-1" :actor-role :sales-manager :phase 3})
(def ^:private as-of-date {:year 2026 :month 7})

(defn- exec! [actor tid request ctx]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "manager-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through the governed scenario this page
  describes: opp-100 advances prospecting->qualification (commit);
  opp-300 HARD-holds at closed-won on a 25% discount over rep-100's
  10% authority (discount-authority-gate, never reaches a human);
  opp-100 then has its stage disputed and the dispute is human-approved
  (escalate->resume->commit). Returns the resulting store -- every
  dashboard field below is real governor/store output, not a hand copy."
  []
  (let [db (store/seed-db)
        actor (operation/build db)]
    (exec! actor "op1" {:op :opportunity/transition-stage :subject "opp-100"
                        :opportunity-id "opp-100" :to-stage :qualification
                        :rep-id "rep-100"
                        :source {:class :crm-activity-log :ref "op1"}}
           rep-ctx)
    (exec! actor "op3" {:op :opportunity/transition-stage :subject "opp-300"
                        :opportunity-id "opp-300" :to-stage :closed-won
                        :rep-id "rep-100" :discount-pct 25
                        :source {:class :crm-activity-log :ref "op3"}}
           rep-ctx)
    (let [r7 (exec! actor "op7" {:op :dispute/request :subject "opp-100"
                                 :disputed-field :stage :claim :qualification}
                    mgr-ctx)]
      (when (= :interrupted (:status r7))
        (approve! actor "op7")))
    db))

(defn- stage-counts-rows [stage-counts]
  (str/join "\n"
            (for [[stage n] (sort-by first stage-counts)]
              (format "        <tr><td>%s</td><td class=\"num\">%d</td></tr>"
                      (name stage) (int n)))))

(defn- conversion-rows [conversion-rates]
  (str/join "\n"
            (for [[[from to] rate] (sort-by (comp first first) conversion-rates)
                  :let [r (or rate "—")]]
              (format "        <tr><td>%s → %s</td><td class=\"num\">%s</td></tr>"
                      (name from) (name to) (if (number? r) (str (Math/round (* 100 (double r))) "%") r)))))

(defn- opp-rows [db]
  (str/join "\n"
            (for [o (sort-by :id (store/all-opportunities db))]
              (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td class=\"num\">%s</td><td>%s</td></tr>"
                      (:id o)
                      (:account-id o)
                      (name (:stage o))
                      (str (:amount o))
                      (if (:closed? o) "closed" "open")))))

(defn render-html
  "Renders the post-scenario dashboard + opportunities as a deterministic
  HTML document (no timestamps). Byte-identical across reruns against the
  same seed."
  [db]
  (let [d (dashboard/render db as-of-date)
        rev (:revenue d)]
    (format
      (str "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
           "<meta name=\"generator\" content=\"crm.render-html (clojure -M:dev:render-html)\">\n"
           "<title>cloud-itonami-isic-5820 — operator console (build-time generated)</title>\n"
           "<style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:900px}"
           "h1{font-size:1.4rem}h2{font-size:1.1rem;margin-top:1.5rem}"
           "table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:4px 8px;text-align:left}"
           ".num{text-align:right}.note{color:#666;font-size:0.85rem}</style></head><body>\n"
           "<h1>cloud-itonami-isic-5820 — CRM operator console</h1>\n"
           "<p class=\"note\">Build-time generated by <code>crm.render-html</code> "
           "(<code>clojure -M:dev:render-html</code>) running the governed op1/op3/op7 "
           "scenario through the real <code>crm.operation</code>→<code>crm.policy</code>→"
           "<code>crm.store</code> stack. Not a hand-pasted snapshot; regenerates "
           "byte-identically. Revenue is ground-truth ASC 606/IFRS 15 as of %d-%02d.</p>\n\n"
           "<h2>Pipeline funnel — stage counts</h2>\n<table><tr><th>stage</th><th>opportunities</th></tr>\n%s\n</table>\n\n"
           "<h2>Conversion rates</h2>\n<table><tr><th>transition</th><th>rate</th></tr>\n%s\n</table>\n\n"
           "<h2>Revenue rollup (ASC 606, as-of %d-%02d)</h2>\n"
           "<p>active subscriptions: %d &middot; recognized revenue: $%.2f &middot; excluded: %d</p>\n\n"
           "<h2>Opportunities</h2>\n<table><tr><th>id</th><th>account</th><th>stage</th><th>amount</th><th>state</th></tr>\n%s\n</table>\n"
           "</body></html>\n")
      (:year as-of-date) (:month as-of-date)
      (stage-counts-rows (:stage-counts d))
      (conversion-rows (:conversion-rates d))
      (:year as-of-date) (:month as-of-date)
      (int (:active-subscription-count rev))
      (double (:recognized-revenue-usd rev))
      (int (:subscriptions-excluded rev))
      (opp-rows db))))

(defn -main
  "Runs the demo scenario + writes the rendered HTML to `out-file`
  (default docs/samples/operator-console.html). Prints the path."
  [& [out-file]]
  (let [out (or out-file "docs/samples/operator-console.html")
        db (run-demo!)
        html (render-html db)
        tmp (str out ".tmp")]
    (clojure.java.io/make-parents out)
    (spit tmp html)
    ;; commit-only-on-change: only overwrite if the rendered HTML differs
    (let [prev (try (slurp out) (catch Exception _ nil))]
      (if (= prev html)
        (println (str "unchanged: " out))
        (do (clojure.java.io/copy (clojure.java.io/file tmp)
                                  (clojure.java.io/file out))
            (println (str "wrote: " out)))))
    (clojure.java.io/delete-file tmp :silently)))
