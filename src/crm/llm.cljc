(ns crm.llm
  "RevOps-LLM client — the *contained intelligence node*.

  It normalizes an incoming stage-transition request into a proposal
  (which opportunity, which stage, how much discount, whether it books
  revenue), drafts account disclosure column sets, and drafts dispute
  resolutions. CRITICAL: it is a smart-but-untrusted advisor. It returns
  a *proposal*, never a committed stage transition or disclosure. Every
  output is censored downstream by `crm.policy` (the SubscriptionGovernor)
  before anything touches the SSoT or leaves the actor.

  Deterministic mock so the actor graph runs offline and the governor
  contract is exercised end-to-end. In production this calls a real LLM
  (kotoba-llm) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str
     :rationale  str
     :cites      [kw|str ..]
     :source     {:class kw :ref str}|nil
     :effect     kw
     :value      map|nil
     :columns    [kw ..]|nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [crm.store :as store]))

(defn- propose-transition
  "Opportunity stage-transition proposal — the LLM only normalizes/
  validates the transition (adds no new discount-authority or
  entitlement judgment). `:unsourced?` injects the failure mode we must
  defend against: a stage transition proposed with no provenance at all."
  [_db {:keys [opportunity-id to-stage rep-id discount-pct activate-feature-tier
               booked-amount-usd as-of-date source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "stage transition: " opportunity-id " → " to-stage)
     :rationale "出典引用済み opportunity/rep データの正規化のみ。新規権限判定なし。"
     :cites     [:opportunity-id :to-stage :rep-id]
     :source    src
     :effect    :stage-transition-upsert
     :value     {:opportunity-id opportunity-id :rep-id rep-id :to-stage to-stage
                 :discount-pct (or discount-pct 0)
                 :activate-feature-tier activate-feature-tier
                 :booked-amount-usd booked-amount-usd
                 :as-of-date as-of-date
                 :closed? (boolean (#{:closed-won :closed-lost} to-stage))}
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-disclosure
  "Disclosure column-set proposal for a licensed account-holder query.
  `:greedy?` injects over-disclosure beyond a basic-tier subscription."
  [_db {:keys [account-id greedy?]}]
  (let [base [:id :account-id :stage :amount]
        greedy-extra [:discount-pct :closed?]]
    {:summary   (str "開示列提案: " account-id)
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "subscription tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :disclosure-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-lead-qualify
  "Lead-status-advance proposal — same normalization-only discipline as
  `propose-transition`: the LLM proposes the next status, `crm.policy`'s
  lead-status-gate (reusing `kotoba.crm.pipeline`) is the sole judge of
  whether that step is a valid forward move."
  [_db {:keys [lead-id to-status]}]
  {:summary   (str "lead status advance: " lead-id " → " to-status)
   :rationale "lead データの正規化のみ。status 遷移順序の妥当性判断なし。"
   :cites     [:lead-id :to-status]
   :source    nil
   :effect    :lead-status-upsert
   :value     {:lead-id lead-id :to-status to-status}
   :confidence 0.9})

(defn- propose-lead-convert
  "Lead → Contact + Opportunity conversion draft. The LLM only drafts the
  new Contact/Opportunity shape from the lead's own fields — whether the
  lead is actually `:qualified` and account-matched is
  `crm.policy`'s lead-convertible-gate's call, never this advisor's."
  [db {:keys [lead-id]}]
  (let [ld (store/lead db lead-id)]
    {:summary   (str "lead convert: " lead-id " → new Contact + Opportunity")
     :rationale "lead の name/email/company から Contact・初期 Opportunity を起票する提案のみ。変換可否の判定なし。"
     :cites     [:lead-id]
     :source    nil
     :effect    :lead-convert-upsert
     :value     {:lead-id lead-id
                 :contact {:id (str "contact-" lead-id) :name (:name ld) :email (:email ld)
                           :role "primary"}
                 :opportunity {:id (str "opp-" lead-id) :amount 0.0}}
     :confidence 0.85}))

(defn- propose-dispute
  "Dispute/reassignment draft. NEVER auto-applies — `crm.policy` and
  `crm.phase` both structurally force every `:dispute/request` to human
  review."
  [_db {:keys [disputed-field claim]}]
  {:summary   (str "opportunity の " disputed-field " について異議申立てへの解決案ドラフト")
   :rationale (str "申立て内容: " claim "。裏取りは人間レビューで行う。")
   :cites     [disputed-field]
   :source    nil
   :effect    :correction-apply
   :value     {:patch {disputed-field claim}}
   :confidence 0.5})

(defn infer
  [db {:keys [op] :as request}]
  (case op
    :opportunity/transition-stage (propose-transition db request)
    :disclosure/query             (propose-disclosure db request)
    :dispute/request              (propose-dispute db request)
    :lead/qualify                 (propose-lead-qualify db request)
    :lead/convert                 (propose-lead-convert db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはSaaS営業パイプライン/サブスクリプション管理の"
       "RevOpsアドバイザーです。与えられた事実のみに基づき、提案を1つだけ "
       "EDN マップで返します。説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary :rationale :cites :source({:class .. :ref ..}か nil) "
       ":effect(:stage-transition-upsert|:disclosure-serve|:correction-apply|"
       ":lead-status-upsert|:lead-convert-upsert) "
       ":value :confidence(0..1)。\n"
       "重要: 割引承認権限の妥当性判断、機能/シート entitlement の妥当性判断、"
       "パイプライン stage 遷移順序の妥当性判断、lead status 遷移順序の妥当性判断、"
       "lead が変換可能かどうかの判断はあなたの責務ではありません"
       "(governor が判定します)。"))

(defn- facts-for [st {:keys [op subject opportunity-id account-id lead-id]}]
  (case op
    :disclosure/query {:account (store/account st (or account-id subject))}
    (:lead/qualify :lead/convert) {:lead (store/lead st (or lead-id subject))}
    {:opportunity (store/opportunity st (or opportunity-id subject))}))

(defn- parse-proposal
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :confidence 0.0})))

(defn llm-advisor
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace [request proposal]
  {:t          :crmllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
