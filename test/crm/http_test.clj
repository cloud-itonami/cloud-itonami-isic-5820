(ns crm.http-test
  "Exercises `crm.http` as a REAL running process — starts the actual
  http-kit server on an ephemeral port inside the test JVM and makes
  REAL HTTP requests against it with `java.net.http` (JDK built-in, zero
  extra test dependency — same client this fleet's `kenchi.http` already
  uses, see its ns docstring). No mocked handler, no in-process Ring
  `handler` invocation shortcut.

  Scenarios reuse the exact governed cases `crm.sim` already exercises
  (op1: clean stage transition -> commit; op3: discount-authority
  exceeded -> hard hold) rather than inventing new ones."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [org.httpkit.server :as httpkit]
            [crm.http :as http]
            [crm.store :as store])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

;; Test-only token, set explicitly for this process — never a real
;; secret, never committed anywhere as a default/fallback in crm.http
;; itself (crm.http has NO built-in token; this is the value THIS test
;; happens to pass to `start-server!`).
(def ^:private test-token "http-test-only-token-9f3a7c2e")

(def ^:private client (HttpClient/newHttpClient))

(def ^:private ^:dynamic *base-url* nil)
(def ^:private server (atom nil))

(defn- with-server [f]
  (let [srv (http/start-server! {:store (store/seed-db) :port 0 :token test-token})]
    (reset! server srv)
    (try
      (binding [*base-url* (str "http://127.0.0.1:" (httpkit/server-port srv))]
        (f))
      (finally
        @(httpkit/server-stop! srv)
        (reset! server nil)))))

(use-fixtures :once with-server)

;; ───────────────────────── HTTP helpers ─────────────────────────

(defn- req!
  [method path {:keys [body headers]}]
  (let [b (HttpRequest/newBuilder (URI/create (str *base-url* path)))]
    (doseq [[k v] headers] (.header b k v))
    (case method
      :get  (.GET b)
      :post (do (.header b "Content-Type" "application/json")
                (.POST b (HttpRequest$BodyPublishers/ofString (or body "")))))
    (let [resp (.send client (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp)
       :body   (.body resp)})))

(defn- json-req!
  ([method path opts] (json-req! method path opts nil))
  ([method path opts token]
   (let [headers (cond-> (:headers opts {})
                   token (assoc "Authorization" (str "Bearer " token)))
         resp (req! method path (assoc opts :headers headers))]
     (assoc resp :json (when (seq (:body resp))
                          (try (json/read-str (:body resp) :key-fn keyword)
                               (catch Exception _ ::unparseable)))))))

;; ───────────────────────── /health, / ─────────────────────────

(deftest health-check-no-auth-required
  (let [{:keys [status json]} (json-req! :get "/health" {})]
    (is (= 200 status))
    (is (= "ok" (:status json)))))

(deftest root-info-no-auth-required
  (let [{:keys [status json]} (json-req! :get "/" {})]
    (is (= 200 status))
    (is (= "cloud-itonami-isic-5820" (:actor json)))
    (is (= "5820" (:isic-code json)))))

;; ───────────────────────── /propose ─────────────────────────

(def ^:private op1-body
  (json/write-str
   {:op "opportunity/transition-stage" :subject "opp-100" :opportunity-id "opp-100"
    :to-stage "qualification" :rep-id "rep-100"
    :source {:class "crm-activity-log" :ref "op1"}
    :context {:actor-id "rep-1" :actor-role "rep" :phase 3}}))

(def ^:private op3-body
  (json/write-str
   {:op "opportunity/transition-stage" :subject "opp-300" :opportunity-id "opp-300"
    :to-stage "closed-won" :rep-id "rep-100" :discount-pct 25
    :source {:class "crm-activity-log" :ref "op3"}
    :context {:actor-id "rep-1" :actor-role "rep" :phase 3}}))

(deftest propose-without-auth-token-is-unauthorized
  (let [{:keys [status json]} (json-req! :post "/propose" {:body op1-body})]
    (is (= 401 status))
    (is (= "unauthorized" (:error json)))))

;; Regression coverage for the constant-time token comparison
;; (`crm.http/constant-time-string=`, ADR-2607124600): a naive `=`-based
;; rewrite bug (e.g. comparing lengths and short-circuiting, or dropping
;; the byte-array conversion) would most plausibly surface as one of
;; these two behavioral cases going wrong, so both are asserted
;; explicitly rather than just re-testing the already-covered "no
;; token" and "correct token" paths above. This is a behavioral
;; assertion (still rejected -> 401), NOT a timing measurement.
(deftest propose-with-wrong-length-token-is-unauthorized
  (let [wrong-length-token (str test-token "-extra-suffix")
        {:keys [status json]} (json-req! :post "/propose" {:body op1-body} wrong-length-token)]
    (is (= 401 status))
    (is (= "unauthorized" (:error json)))))

(deftest propose-with-same-length-wrong-token-is-unauthorized
  (let [same-length-wrong-token (str (subs test-token 0 (dec (count test-token))) "X")]
    (is (= (count test-token) (count same-length-wrong-token)))
    (let [{:keys [status json]} (json-req! :post "/propose" {:body op1-body} same-length-wrong-token)]
      (is (= 401 status))
      (is (= "unauthorized" (:error json))))))

(deftest propose-with-valid-token-and-clean-transition-commits
  (testing "op1 from crm.sim: opp-100 :prospecting -> :qualification, sourced -> commit"
    (let [{:keys [status json]} (json-req! :post "/propose" {:body op1-body} test-token)]
      (is (= 200 status))
      (is (= "committed" (:decision json)))
      (is (= "opportunity/transition-stage" (:op json)))
      (is (= "opp-100" (:subject json))))))

(deftest propose-with-hard-blocked-discount-is-held-with-violations
  (testing "op3 from crm.sim: rep-100 (:tier/rep, max 10%) requests 25% discount -> discount-authority-gate REJECT"
    (let [{:keys [status json]} (json-req! :post "/propose" {:body op3-body} test-token)]
      (is (= 200 status))
      (is (= "held" (:decision json)))
      (is (some #(= "discount-authority-gate" (:rule %)) (:violations json))))))

;; ───────────────────────── /approve (escalation resume) ─────────────────────────

;; op7 from crm.sim: a dispute/request ALWAYS escalates for human review
;; (any phase), making it the deterministic case to exercise the
;; /propose -> 202 escalated -> /approve resume round-trip end-to-end.
;; Uses the same manager context crm.sim's op7 uses.
(def ^:private op7-dispute-body
  (json/write-str
   {:op "dispute/request" :subject "opp-100" :disputed-field "stage" :claim "qualification"
    :context {:actor-id "mg-1" :actor-role "sales-manager" :phase 3}}))

(deftest propose-dispute-escalates-with-thread-id
  (testing "op7 dispute/request -> 202 escalated with a thread-id (precondition for /approve)"
    (let [{:keys [status json]} (json-req! :post "/propose" {:body op7-dispute-body} test-token)]
      (is (= 202 status))
      (is (= "escalated" (:decision json)))
      (is (string? (:thread-id json)))
      (is (= "dispute/request" (:op json))))))

(deftest approve-resumes-an-escalated-thread-to-a-final-disposition
  (testing "POST /approve with the escalated thread-id resumes the graph to :done"
    (let [{prop-json :json} (json-req! :post "/propose" {:body op7-dispute-body} test-token)
          thread-id (:thread-id prop-json)]
      (is (string? thread-id))
      (let [{:keys [status json]}
            (json-req! :post "/approve"
                       {:body (json/write-str {:thread-id thread-id
                                                :decision  "approve"
                                                :by        "test-approver"})}
                       test-token)]
        (is (= 200 status))
        (is (contains? #{"committed" "held"} (:decision json)))
        (is (= thread-id (:thread-id json)))
        (is (= "approved" (:approval json)))))))

(deftest reject-resumes-an-escalated-thread-to-held
  (testing "POST /approve with decision \"reject\" -> governor holds"
    (let [{prop-json :json} (json-req! :post "/propose" {:body op7-dispute-body} test-token)
          thread-id (:thread-id prop-json)]
      (is (string? thread-id))
      (let [{:keys [status json]}
            (json-req! :post "/approve"
                       {:body (json/write-str {:thread-id thread-id :decision "reject"})}
                       test-token)]
        (is (= 200 status))
        (is (= "held" (:decision json)))
        (is (= "rejected" (:approval json)))))))

(deftest approve-rejects-malformed-bodies
  (testing "decision must be approve|reject; thread-id must be present"
    (let [{s1 :status} (json-req! :post "/approve"
                                  {:body (json/write-str {:thread-id "x" :decision "maybe"})}
                                  test-token)]
      (is (= 400 s1)))
    (let [{s2 :status} (json-req! :post "/approve"
                                  {:body (json/write-str {:decision "approve"})}
                                  test-token)]
      (is (= 400 s2)))))

(deftest approve-without-auth-token-is-unauthorized
  (let [{:keys [status json]} (json-req! :post "/approve"
                                         {:body (json/write-str {:thread-id "x" :decision "approve"})})]
    (is (= 401 status))
    (is (= "unauthorized" (:error json)))))

;; ───────────────────────── /dashboard ─────────────────────────

(deftest dashboard-without-right-role-is-forbidden
  (let [{:keys [status json]}
        (json-req! :get "/dashboard?role=rep&year=2026&month=7" {} test-token)]
    (is (= 403 status))
    (is (= "forbidden" (:error json)))))

(deftest dashboard-with-sales-manager-returns-real-data
  (let [{:keys [status json]}
        (json-req! :get "/dashboard?role=sales-manager&year=2026&month=7" {} test-token)]
    (is (= 200 status))
    (is (contains? json :stage-counts))
    (is (contains? json :conversion-rates))
    (is (contains? (:revenue json) :recognized-revenue-usd))))

(deftest dashboard-without-auth-token-is-unauthorized
  (let [{:keys [status]} (json-req! :get "/dashboard?role=sales-manager&year=2026&month=7" {})]
    (is (= 401 status))))
