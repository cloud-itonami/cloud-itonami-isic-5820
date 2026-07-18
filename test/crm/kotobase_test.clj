(ns crm.kotobase-test
  "crm.kotobase's pure functions (graph CID derivation, identity, CACAO
  mint), plus an end-to-end wiring test against a mock host-caps. The
  actual live-network round-trip against kotobase.net was verified
  manually (2026-07-18) — see this session's ADR — not re-run here (this
  suite has no network access and must stay hermetic)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [crm.kotobase :as kb]
            [crm.store :as store]))

;; A fixed, arbitrary test seed — NOT a real actor's key.
(def ^:private test-seed-hex
  "0000000000000000000000000000000000000000000000000000000000000001")

;; ─── graph CID derivation ────────────────────────────────────────────────

(deftest graph-cid-from-name-matches-the-tsumugu-and-net-kotobase-edge-port
  (testing "byte-identical to tsumugu.cacao/graph-cid-from-name and
           kotobase.graph/graph-cid-from-name (net-kotobase clj-edge) —
           cross-checked directly against both, 2026-07-18"
    (is (= "bafyreif7il5qx7qzksmkk65rtneseimvdq55qhca7dshayju57kru4nk5y"
           (kb/graph-cid-from-name "kotobase/db/did:key:zTest/notes")))))

(deftest canonical-graph-confines-the-same-db-name-to-different-graphs-per-did
  (testing "the actual ownership confinement the edge relies on: two
           different DIDs querying the SAME db-name must never resolve to
           the same graph"
    (is (not= (kb/canonical-graph "did:key:zAlice" "notes")
              (kb/canonical-graph "did:key:zBob" "notes")))))

;; ─── identity ────────────────────────────────────────────────────────────

(deftest identity-from-seed-hex-derives-a-stable-did-and-graph
  (let [id (kb/identity-from-seed-hex test-seed-hex)]
    (testing "did:key derivation is deterministic"
      (is (= (:did id) (:did (kb/identity-from-seed-hex test-seed-hex)))))
    (testing "graph is canonical-graph(did, db-name)"
      (is (= (kb/canonical-graph (:did id) kb/default-db-name) (:graph id))))
    (testing "seed-hex round-trips unchanged (never derived-and-discarded)"
      (is (= test-seed-hex (:seed-hex id))))))

(deftest identity-from-seed-hex-respects-an-explicit-db-name
  (let [id (kb/identity-from-seed-hex test-seed-hex "other-db")]
    (is (= (kb/canonical-graph (:did id) "other-db") (:graph id)))
    (is (not= (:graph id) (:graph (kb/identity-from-seed-hex test-seed-hex))))))

;; ─── load-seed-hex (env-var only, no generation) ──────────────────────────

(deftest load-seed-hex-reads-the-env-var-or-returns-nil
  (testing "this fn never generates/persists a seed itself — provisioning
           is an operator action (kagi mint), not something this actor
           does to itself on boot"
    ;; No portable way to unset/set process env from within a JVM test
    ;; process — this only exercises the "unset" branch, which is the
    ;; safe default a CI/test run always has.
    (when-not (System/getenv "ISIC5820_KOTOBASE_SEED_HEX")
      (is (nil? (kb/load-seed-hex))))))

;; ─── CACAO mint ──────────────────────────────────────────────────────────

(deftest mint-cacao-produces-a-non-blank-base64-string
  (let [id (kb/identity-from-seed-hex test-seed-hex)
        cacao-b64 (kb/mint-cacao id "https://kotobase.net")]
    (is (string? cacao-b64))
    (is (pos? (count cacao-b64)))))

(deftest mint-cacao-is-deterministic-only-in-signer-not-in-wire-bytes
  (testing "two mints for the same identity/aud produce DIFFERENT CACAOs
           (fresh nonce + iat/exp each time) -- this is the whole point of
           short-lived, per-construction minting rather than a cached
           CACAO"
    (let [id (kb/identity-from-seed-hex test-seed-hex)]
      (is (not= (kb/mint-cacao id "https://kotobase.net")
                (kb/mint-cacao id "https://kotobase.net"))))))

;; ─── kotobase-store wiring (mock host-caps, no network) ───────────────────

(defn- mock-http-fn
  "Captures every request; responds 200 with a canned per-nsid body."
  [captured respond-fn]
  (fn [{:keys [url body]}]
    (let [nsid (last (str/split url #"/xrpc/"))
          resp (respond-fn nsid body)]
      (swap! captured conj {:nsid nsid :body body})
      {:status 200 :body resp})))

(deftest kotobase-store-requires-a-seed
  (testing "fail-closed: this Store must never silently fall back to an
           unauthenticated or misconfigured connection"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no seed"
                          (kb/kotobase-store {:seed-hex nil :json-write pr-str
                                             :json-read read-string})))))

(deftest kotobase-store-writes-through-the-injected-http-fn
  (let [captured (atom [])
        http-fn (mock-http-fn captured
                              (fn [nsid _body]
                                (case nsid
                                  "ai.gftd.apps.kotobase.datomic.transact"
                                  (pr-str {:ok true :graph "g" :commit "c" :datom_count 1})
                                  (pr-str {:ok true :graph "g" :rows_edn []}))))
        s (kb/kotobase-store {:seed-hex test-seed-hex :db-name "test-db"
                              :json-write pr-str :json-read read-string
                              :http-fn http-fn})]
    (store/with-reps s {"r1" {:id "r1" :name "Test Rep" :discount-tier :tier/rep}})
    (testing "posted to datomic.transact"
      (is (= "ai.gftd.apps.kotobase.datomic.transact" (:nsid (first @captured)))))
    (testing "request carries db_name (tenant-write scope) and a CACAO"
      (let [body (read-string (:body (first @captured)))]
        (is (= "test-db" (:db_name body)))
        (is (string? (:cacao_b64 body)))))))

(deftest kotobase-store-mints-a-fresh-cacao-for-every-write
  (testing "kotobase-server's nonce-replay protection rejects a second
           datomic.transact presenting the same CACAO's nonce -- a Store
           reusing one static CACAO across multiple writes would 401 on
           the second write. Confirmed directly against the live edge,
           2026-07-18."
    (let [captured (atom [])
          http-fn (mock-http-fn captured
                                (fn [nsid _body]
                                  (case nsid
                                    "ai.gftd.apps.kotobase.datomic.transact"
                                    (pr-str {:ok true :graph "g" :commit "c" :datom_count 1})
                                    (pr-str {:ok true :graph "g" :rows_edn []}))))
          s (kb/kotobase-store {:seed-hex test-seed-hex :db-name "test-db"
                                :json-write pr-str :json-read read-string
                                :http-fn http-fn})]
      (store/with-reps s {"r1" {:id "r1" :name "Rep One" :discount-tier :tier/rep}})
      (store/with-accounts s {"a1" {:id "a1" :name "Account One" :subscription-tier :tier/basic :active? true}})
      (let [transacts (filter #(= "ai.gftd.apps.kotobase.datomic.transact" (:nsid %)) @captured)
            ;; :body is the raw wire string (pr-str'd, matching the
            ;; :json-write pr-str passed to kotobase-store above) --
            ;; must be parsed back before :cacao_b64 is reachable.
            cacaos (map #(:cacao_b64 (read-string (:body %))) transacts)]
        (is (= 2 (count transacts)))
        (is (every? string? cacaos))
        (is (= 2 (count (distinct cacaos))))))))
