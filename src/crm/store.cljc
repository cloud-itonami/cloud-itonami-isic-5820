(ns crm.store
  "SSoT for the commercial CRM/subscription-commerce actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic
                       default for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible
                       EAV store. Pure `.cljc`, so it runs offline AND can
                       be pointed at a real Datomic Local or a
                       kotoba-server pod.

  Both implement the same protocol and pass the same contract
  (test/crm/store_contract_test.clj) — the actor, the SubscriptionGovernor
  and the audit ledger never know which SSoT they run on.

  Entity shapes: a rep (discount-tier), an account (subscription-tier,
  active?), an opportunity (account/stage/amount/closed?), a subscription
  (account's SaaS entitlement: product-tier/contract-value/term/start).
  There is NO field anywhere in this schema for marketing-campaign
  attribution or support-ticket SLA — this actor covers sales-pipeline +
  subscription-entitlement governance only; marketing-automation and
  customer-service are explicitly out of scope for R0 (see README).

  The ledger stays append-only on every backend."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (rep [s id])
  (all-reps [s])
  (account [s id])
  (all-accounts [s] "every account — added for crm.dashboard's book-wide
                     revenue rollup, which must enumerate every account's
                     subscription rather than assume a fixed id set")
  (opportunity [s id])
  (all-opportunities [s])
  (subscription [s account-id])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-reps [s reps]                 "replace/seed reps (map id→rep)")
  (with-accounts [s accounts]         "replace/seed accounts (map id→account)")
  (with-opportunities [s opportunities] "replace/seed opportunities (map id→opportunity)")
  (with-subscriptions [s subscriptions] "replace/seed subscriptions (map account-id→subscription)"))

;; ───────────────────────── demo data (fictitious) ─────────────────────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline.
  `opp-300` sits at `:negotiation` with a discount request purely to
  exercise the discount-authority-gate governor gate."
  []
  {:reps
   {"rep-100" {:id "rep-100" :name "田中 太郎(デモ)" :discount-tier :tier/rep}
    "rep-200" {:id "rep-200" :name "Jane Doe (demo)"  :discount-tier :tier/senior-rep}
    "rep-300" {:id "rep-300" :name "鈴木 花子(デモ)" :discount-tier :tier/director}}
   :accounts
   {"acct-acme"  {:id "acct-acme"  :name "Acme Corp (demo)"  :subscription-tier :tier/pro :active? true}
    "acct-basic" {:id "acct-basic" :name "Basic Co (demo)"    :subscription-tier :tier/basic :active? true}}
   :opportunities
   {"opp-100" {:id "opp-100" :account-id "acct-acme" :stage :prospecting :amount 12000.0
               :discount-pct 0 :closed? false}
    "opp-200" {:id "opp-200" :account-id "acct-acme" :stage :negotiation :amount 50000.0
               :discount-pct 0 :closed? false}
    "opp-300" {:id "opp-300" :account-id "acct-acme" :stage :negotiation :amount 8000.0
               :discount-pct 0 :closed? false}}
   :subscriptions
   {"acct-acme"  {:account-id "acct-acme" :product-tier :tier/pro
                  :contract-value-usd 12000.0 :term-months 12
                  :start-date {:year 2026 :month 1 :day 1} :active? true}
    "acct-basic" {:account-id "acct-basic" :product-tier :tier/basic
                  :contract-value-usd 6000.0 :term-months 12
                  :start-date {:year 2026 :month 1 :day 1} :active? true}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (rep [_ id] (get-in @a [:reps id]))
  (all-reps [_] (sort-by :id (vals (:reps @a))))
  (account [_ id] (get-in @a [:accounts id]))
  (all-accounts [_] (sort-by :id (vals (:accounts @a))))
  (opportunity [_ id] (get-in @a [:opportunities id]))
  (all-opportunities [_] (sort-by :id (vals (:opportunities @a))))
  (subscription [_ account-id] (get-in @a [:subscriptions account-id]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :stage-transition-upsert
      (swap! a update-in [:opportunities (:opportunity-id value)]
             merge {:stage (:to-stage value)
                    :discount-pct (:discount-pct value 0)
                    :closed? (boolean (:closed? value))})
      :correction-apply
      (swap! a update-in [:opportunities (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-reps [s rs]          (when (seq rs) (swap! a assoc :reps rs)) s)
  (with-accounts [s accts]   (when (seq accts) (swap! a assoc :accounts accts)) s)
  (with-opportunities [s ops] (when (seq ops) (swap! a assoc :opportunities ops)) s)
  (with-subscriptions [s subs] (when (seq subs) (swap! a assoc :subscriptions subs)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  {:rep/id             {:db/unique :db.unique/identity}
   :account/id         {:db/unique :db.unique/identity}
   :opportunity/id     {:db/unique :db.unique/identity}
   :subscription/account-id {:db/unique :db.unique/identity}
   :ledger/seq         {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- rep->tx [{:keys [id name discount-tier]}]
  (cond-> {:rep/id id}
    name          (assoc :rep/name name)
    discount-tier (assoc :rep/discount-tier discount-tier)))

(defn- pull->rep [m]
  (when (:rep/id m)
    {:id (:rep/id m) :name (:rep/name m) :discount-tier (:rep/discount-tier m)}))

(def ^:private rep-pull [:rep/id :rep/name :rep/discount-tier])

(defn- account->tx [{:keys [id name subscription-tier active?]}]
  {:account/id id :account/name name
   :account/subscription-tier subscription-tier :account/active active?})

(defn- pull->account [m]
  (when (:account/id m)
    {:id (:account/id m) :name (:account/name m)
     :subscription-tier (:account/subscription-tier m) :active? (:account/active m)}))

(def ^:private account-pull
  [:account/id :account/name :account/subscription-tier :account/active])

(defn- opportunity->tx [{:keys [id account-id stage amount discount-pct closed?]}]
  {:opportunity/id id :opportunity/account-id account-id
   :opportunity/stage stage :opportunity/amount amount
   :opportunity/discount-pct (or discount-pct 0) :opportunity/closed (boolean closed?)})

(defn- pull->opportunity [m]
  (when (:opportunity/id m)
    {:id (:opportunity/id m) :account-id (:opportunity/account-id m)
     :stage (:opportunity/stage m) :amount (:opportunity/amount m)
     :discount-pct (:opportunity/discount-pct m) :closed? (:opportunity/closed m)}))

(def ^:private opportunity-pull
  [:opportunity/id :opportunity/account-id :opportunity/stage :opportunity/amount
   :opportunity/discount-pct :opportunity/closed])

(defn- subscription->tx [{:keys [account-id product-tier contract-value-usd term-months start-date active?]}]
  {:subscription/account-id account-id :subscription/product-tier product-tier
   :subscription/contract-value-usd contract-value-usd :subscription/term-months term-months
   :subscription/start-date (enc start-date) :subscription/active active?})

(defn- pull->subscription [m]
  (when (:subscription/account-id m)
    {:account-id (:subscription/account-id m) :product-tier (:subscription/product-tier m)
     :contract-value-usd (:subscription/contract-value-usd m)
     :term-months (:subscription/term-months m)
     :start-date (dec* (:subscription/start-date m)) :active? (:subscription/active m)}))

(def ^:private subscription-pull
  [:subscription/account-id :subscription/product-tier :subscription/contract-value-usd
   :subscription/term-months :subscription/start-date :subscription/active])

(defrecord DatomicStore [conn]
  Store
  (rep [_ id] (pull->rep (d/pull (d/db conn) rep-pull [:rep/id id])))
  (all-reps [_]
    (->> (d/q '[:find [?id ...] :where [?e :rep/id ?id]] (d/db conn))
         (map #(pull->rep (d/pull (d/db conn) rep-pull [:rep/id %])))
         (sort-by :id)))
  (account [_ id] (pull->account (d/pull (d/db conn) account-pull [:account/id id])))
  (all-accounts [_]
    (->> (d/q '[:find [?id ...] :where [?e :account/id ?id]] (d/db conn))
         (map #(pull->account (d/pull (d/db conn) account-pull [:account/id %])))
         (sort-by :id)))
  (opportunity [_ id] (pull->opportunity (d/pull (d/db conn) opportunity-pull [:opportunity/id id])))
  (all-opportunities [_]
    (->> (d/q '[:find [?id ...] :where [?e :opportunity/id ?id]] (d/db conn))
         (map #(pull->opportunity (d/pull (d/db conn) opportunity-pull [:opportunity/id %])))
         (sort-by :id)))
  (subscription [_ account-id]
    (pull->subscription (d/pull (d/db conn) subscription-pull [:subscription/account-id account-id])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :stage-transition-upsert
      (d/transact! conn [(opportunity->tx (merge (opportunity s (:opportunity-id value))
                                                  {:stage (:to-stage value)
                                                   :discount-pct (:discount-pct value 0)
                                                   :closed? (boolean (:closed? value))}))])
      :correction-apply
      (d/transact! conn [(opportunity->tx (merge (opportunity s (first path)) (:patch value)))])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-reps [s rs]
    (when (seq rs) (d/transact! conn (mapv rep->tx (vals rs)))) s)
  (with-accounts [s accts]
    (when (seq accts) (d/transact! conn (mapv account->tx (vals accts)))) s)
  (with-opportunities [s ops]
    (when (seq ops) (d/transact! conn (mapv opportunity->tx (vals ops)))) s)
  (with-subscriptions [s subs]
    (when (seq subs) (d/transact! conn (mapv subscription->tx (vals subs)))) s))

(defn datomic-store
  ([] (datomic-store {}))
  ([{:keys [reps accounts opportunities subscriptions]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-reps reps) (with-accounts accounts)
         (with-opportunities opportunities) (with-subscriptions subscriptions)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — proves protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
