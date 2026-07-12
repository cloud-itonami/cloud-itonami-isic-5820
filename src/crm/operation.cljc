(ns crm.operation
  "OperationActor — one transition/disclosure/dispute operation = one
  supervised actor run, expressed as a langgraph-clj StateGraph. The
  advisor (RevOps-LLM) is sealed into a single node (:advise); its
  proposal is ALWAYS routed through the SubscriptionGovernor (:govern)
  and the rollout phase gate (:decide) before anything commits to the
  SSoT or is disclosed.

  Everything the actor depends on is injected:
    - the Store    (MemStore | DatomicStore | kotoba-server) — `store` arg
    - the Advisor  (mock | real LLM)                          — :advisor opt
    - the Phase    (0→3 rollout)                              — :phase in ctx

  One graph run = one operation. Human-in-the-loop = real approval/
  dispute review: `interrupt-before #{:request-approval}` pauses the
  actor and hands the decision to a human reviewer (sales manager)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [crm.llm :as llm]
            [crm.policy :as policy]
            [crm.phase :as phase]
            [crm.store :as store]))

(defn- commit-fact [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :commit
   :basis      (:cites proposal)
   :source     (:source proposal)
   :summary    (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:effect  (:effect proposal)
   :value   (:value proposal)
   :path    [(:subject request)]})

(defn build
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (llm/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (llm/-advise advisor store request)]
            {:proposal p :audit [(llm/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (policy/check request context proposal store)}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (policy/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (or reason
                                    (cond (:correction? verdict) :dispute-request
                                          (:revenue-mismatch-imminent? verdict) :revenue-mismatch-imminent
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :approved-by (:by approval))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (policy/hold-fact request context
                                              (assoc verdict :violations
                                                     [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:policy-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
