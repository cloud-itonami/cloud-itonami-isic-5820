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
