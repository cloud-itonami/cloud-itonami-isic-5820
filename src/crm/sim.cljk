(ns crm.sim
  "Demo runner: push nine representative operations through one
  OperationActor and watch the SubscriptionGovernor + approval workflow
  earn the RevOps-LLM the right to transition an opportunity's stage,
  disclose account data, or resolve a dispute.

    op1  クリーンな stage 遷移(rep-100, 出典あり)                        → commit
    op2  出典なし stage 遷移                                             → source-provenance REJECT → hold
    op3  discount-authority を超える割引提案(rep-100=:tier/rep, 25%)      → discount-authority REJECT → hold
    op4  account の subscription tier を超える feature 有効化提案         → entitlement-scope REJECT → hold
    op5  開示クエリが tier/basic 契約なのに discount-pct/closed? を要求   → hold
    op5a 開示クエリが未契約 account から                                 → hold
    op6  適格な closed-won だが計上額が ASC606 straight-line recompute と乖離 → escalate → approve → commit
    op7  opportunity への異議申立て(どの phase でも常に人間レビュー)      → escalate → approve → commit
    op8  stage をスキップした遷移提案(qualification → closed-won)        → stage-sequence REJECT → hold
    op9  既に closed 済みの opportunity への再遷移提案                    → double-close REJECT → hold

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [crm.store :as store]
            [crm.operation :as op]
            [crm.facts :as facts]
            [crm.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "manager-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        rep     {:actor-id "rep-1" :actor-role :rep :phase 3}
        manager {:actor-id "mg-1" :actor-role :sales-manager :phase 3}]

    (line "── R0 カバレッジ(正直な現状) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (RevOps-LLM sealed; SubscriptionGovernor active) ──")

    (line "\nop1  クリーンな stage 遷移: opp-100 :prospecting → :qualification")
    (run-op! actor "op1"
             {:op :opportunity/transition-stage :subject "opp-100" :opportunity-id "opp-100"
              :to-stage :qualification :rep-id "rep-100"
              :source {:class :crm-activity-log :ref "op1"}}
             rep true)

    (line "\nop2  出典なし stage 遷移: opp-100 :qualification → :proposal")
    (run-op! actor "op2"
             {:op :opportunity/transition-stage :subject "opp-100" :opportunity-id "opp-100"
              :to-stage :proposal :rep-id "rep-100"
              :source {:class :crm-activity-log :ref "op2"} :unsourced? true}
             rep true)

    (line "\nop3  discount-authority を超える割引: opp-300 → :closed-won, rep-100 (:tier/rep) が25%割引")
    (run-op! actor "op3"
             {:op :opportunity/transition-stage :subject "opp-300" :opportunity-id "opp-300"
              :to-stage :closed-won :rep-id "rep-100" :discount-pct 25
              :source {:class :crm-activity-log :ref "op3"}}
             rep true)

    (line "\nop4  entitlement-scope を超える feature 有効化: opp-200 → :closed-won, :tier/enterprise 要求(account は :tier/pro)")
    (run-op! actor "op4"
             {:op :opportunity/transition-stage :subject "opp-200" :opportunity-id "opp-200"
              :to-stage :closed-won :rep-id "rep-300" :activate-feature-tier :tier/enterprise
              :source {:class :e-signature-system :ref "op4"}}
             rep true)

    (line "\nop5  開示クエリ(tier/basic 契約なのに discount-pct/closed? まで要求)")
    (run-op! actor "op5"
             {:op :disclosure/query :subject "acct-basic" :account-id "acct-basic" :greedy? true}
             {:actor-id "sub-1" :actor-role :account-holder :account-id "acct-basic" :phase 3} true)

    (line "\nop5a 開示クエリ(登録されていない account から)")
    (run-op! actor "op5a"
             {:op :disclosure/query :subject "acct-ghost" :account-id "acct-ghost"}
             {:actor-id "sub-2" :actor-role :account-holder :account-id "acct-ghost" :phase 3} true)

    (line "\nop6  適格な closed-won だが計上額が ASC606/IFRS15 straight-line recompute と乖離(出典・割引権限・entitlement は正常でも人間承認)")
    (run-op! actor "op6"
             {:op :opportunity/transition-stage :subject "opp-300" :opportunity-id "opp-300"
              :to-stage :closed-won :rep-id "rep-300" :discount-pct 5
              :booked-amount-usd 12000.0 :as-of-date {:year 2026 :month 7}
              :source {:class :billing-system-webhook :ref "op6"}}
             manager true)

    (line "\nop7  opportunity への異議申立て(どの phase でも常に人間レビュー)")
    (run-op! actor "op7"
             {:op :dispute/request :subject "opp-100" :disputed-field :stage :claim :qualification}
             manager true)

    (line "\nop8  stage をスキップした遷移: opp-100 :qualification → :closed-won(direct)")
    (run-op! actor "op8"
             {:op :opportunity/transition-stage :subject "opp-100" :opportunity-id "opp-100"
              :to-stage :closed-won :rep-id "rep-100"
              :source {:class :crm-activity-log :ref "op8"}}
             rep true)

    (line "\nop9  既に closed 済みの opportunity への再遷移: opp-300(op6 で closed-won 済み) → :closed-lost")
    (run-op! actor "op9"
             {:op :opportunity/transition-stage :subject "opp-300" :opportunity-id "opp-300"
              :to-stage :closed-lost :rep-id "rep-300"
              :source {:class :crm-activity-log :ref "op9"}}
             rep true)

    (line "\n── 開示(governor が承認した tier/pro 列のみ) ──")
    (line (pr-str (report/render-opportunity db "opp-100" [:id :account-id :stage :amount])))

    (line "\n── 監査台帳 (append-only; 誰が・何を・どの subscription/出典で遷移/開示したか) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
