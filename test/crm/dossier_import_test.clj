(ns crm.dossier-import-test
  "crm.dossier-import exercised entirely offline with hand-typed fixture
  maps matching `dossier.store`'s real Company shape (`{:id :legal-name
  :jurisdiction :registration-no :status ...}`) — no dependency on
  `cloud-itonami-isic-8291`'s code at all, same decoupling the ns
  docstring itself describes."
  (:require [clojure.test :refer [deftest is testing]]
            [crm.dossier-import :as di]
            [crm.store :as store]))

(def yamato-dossier-company
  "Shape-identical to what `dossier.houjin-bangou/->company` or
  `dossier.store/company` would return for a real, active JPN company."
  {:id "jpn-5050005005266"
   :legal-name "大和職業紹介株式会社(demo)"
   :jurisdiction :jpn
   :registration-no "5050005005266"
   :status :active
   :source {:class :official-registry :ref "https://api.houjin-bangou.nta.go.jp/4/num?number=5050005005266"}
   :flags {}})

(def dissolved-dossier-company
  (assoc yamato-dossier-company :id "jpn-1234567890123" :status :dissolved))

(def gbr-dossier-company
  {:id "gbr-GBDEMO0002" :legal-name "Northwind Capital Holdings Ltd (demo)"
   :jurisdiction :gbr :registration-no "GBDEMO0002" :status :active
   :source {:class :official-registry :ref "https://api.company-information.service.gov.uk/company/GBDEMO0002"}
   :flags {}})

(deftest registry-id?-recognizes-every-known-dossier-prefix
  (is (true? (di/registry-id? "jpn-5050005005266")))
  (is (true? (di/registry-id? "gbr-GBDEMO0002")))
  (is (true? (di/registry-id? "usa-0000445566")))
  (is (true? (di/registry-id? "lei-969500DEMO0ZEN00001A")))
  (is (false? (di/registry-id? "acct-acme")) "a purely local ad-hoc id is not a registry id")
  (is (false? (di/registry-id? nil)))
  (is (false? (di/registry-id? 12345))))

(deftest dossier-company->account-keeps-the-canonical-id-verbatim
  (let [acct (di/dossier-company->account yamato-dossier-company)]
    (is (= "jpn-5050005005266" (:id acct))
        "the account id IS the dossier canonical id -- no new local id minted")
    (is (= "大和職業紹介株式会社(demo)" (:name acct)))))

(deftest dossier-company->account-never-fabricates-a-subscription
  (testing "a freshly-imported prospect account has NO subscription tier and is NOT active --
           :active? on a 5820 account means 'has an active subscription', not 'is the legal
           entity operating', and conflating the two would fabricate customer status"
    (let [acct (di/dossier-company->account yamato-dossier-company)]
      (is (nil? (:subscription-tier acct)))
      (is (false? (:active? acct))))))

(deftest dossier-company->account-refuses-a-dissolved-company
  (is (nil? (di/dossier-company->account dissolved-dossier-company))
      "a legally dissolved company is never imported as a live prospect"))

(deftest dossier-company->account-refuses-malformed-or-unrecognized-input
  (is (nil? (di/dossier-company->account nil)))
  (is (nil? (di/dossier-company->account {})))
  (is (nil? (di/dossier-company->account {:id "acct-acme" :legal-name "x" :status :active}))
      "a non-registry id (no known prefix) is refused, never silently accepted")
  (is (nil? (di/dossier-company->account (assoc yamato-dossier-company :legal-name "")))
      "a blank legal name is refused"))

(deftest dossier-company->account-works-for-any-recognized-jurisdiction-not-just-jpn
  (let [acct (di/dossier-company->account gbr-dossier-company)]
    (is (= "gbr-GBDEMO0002" (:id acct)))
    (is (= "Northwind Capital Holdings Ltd (demo)" (:name acct)))))

(deftest dossier-company->account-result-is-a-valid-crm-store-account
  (testing "the mapped account round-trips through crm.store like any other seeded account"
    (let [acct (di/dossier-company->account yamato-dossier-company)
          s (store/seed-db)]
      (store/with-accounts s (assoc (into {} (map (juxt :id identity) (store/all-accounts s)))
                                     (:id acct) acct))
      (is (= "大和職業紹介株式会社(demo)" (:name (store/account s "jpn-5050005005266")))))))

(deftest dossier-company->lead-account-matches-to-the-canonical-id
  (let [lead (di/dossier-company->lead yamato-dossier-company
                                       {:lead-id "lead-jpn-1" :owner-rep-id "rep-100"
                                        :source :official-registry-lookup
                                        :name nil :email nil})]
    (is (= "jpn-5050005005266" (:account-id lead)))
    (is (= "大和職業紹介株式会社(demo)" (:company lead)))
    (is (= :new (:status lead)) "an imported company record is not itself evidence anyone qualified this lead")
    (is (= :official-registry-lookup (:source lead)))))

(deftest dossier-company->lead-never-invents-a-contact-person
  (testing "dossier is a COMPANY registry, never a contact-person directory -- name/email are
           whatever the caller honestly supplies, nil if not yet known, never derived from the
           company record"
    (let [lead (di/dossier-company->lead yamato-dossier-company
                                         {:lead-id "lead-jpn-2" :owner-rep-id "rep-100"
                                          :source :official-registry-lookup})]
      (is (nil? (:name lead)))
      (is (nil? (:email lead))))))

(deftest dossier-company->lead-refuses-under-the-same-conditions-as-account
  (is (nil? (di/dossier-company->lead dissolved-dossier-company
                                      {:lead-id "x" :owner-rep-id "rep-100" :source :official-registry-lookup})))
  (is (nil? (di/dossier-company->lead nil {:lead-id "x" :owner-rep-id "rep-100" :source :official-registry-lookup}))))
