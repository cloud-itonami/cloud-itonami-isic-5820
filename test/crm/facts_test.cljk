(ns crm.facts-test
  (:require [clojure.test :refer [deftest is]]
            [crm.facts :as facts]))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :crm-activity-log))
  (is (facts/class-allowed? :e-signature-system))
  (is (facts/class-allowed? :billing-system-webhook))
  (is (not (facts/class-allowed? :inference)))
  (is (not (facts/class-allowed? nil))))

(deftest discount-authority-at-least?-orders-correctly
  (is (facts/discount-authority-at-least? :tier/director :tier/rep))
  (is (facts/discount-authority-at-least? :tier/senior-rep :tier/senior-rep))
  (is (not (facts/discount-authority-at-least? :tier/rep :tier/senior-rep)))
  (is (not (facts/discount-authority-at-least? :tier/senior-rep :tier/director))))

(deftest discount-authorized?-checks-max-pct
  (is (facts/discount-authorized? :tier/rep 10))
  (is (not (facts/discount-authorized? :tier/rep 11)))
  (is (facts/discount-authorized? :tier/director 40))
  (is (not (facts/discount-authorized? :tier/director 41))))

(deftest feature-tier-at-least?-orders-correctly
  (is (facts/feature-tier-at-least? :tier/enterprise :tier/pro))
  (is (facts/feature-tier-at-least? :tier/pro :tier/pro))
  (is (not (facts/feature-tier-at-least? :tier/basic :tier/pro)))
  (is (not (facts/feature-tier-at-least? :tier/pro :tier/enterprise))))

(deftest feature-entitled?-checks-catalog
  (is (facts/feature-entitled? :tier/enterprise :sso))
  (is (not (facts/feature-entitled? :tier/pro :sso)))
  (is (facts/feature-entitled? :tier/basic :opportunities))
  (is (not (facts/feature-entitled? :tier/basic :forecasting))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    (is (= 3 (count (:source-classes c))) "3 provenance classes")
    (is (= 3 (:discount-authority-tier-count c)))
    (is (= 3 (:subscription-feature-tier-count c)))
    (is (= 5 (:pipeline-stage-count c)))
    (is (= 3 (:lead-status-count c)))))

(deftest lead-convertible?-only-true-for-qualified
  (is (facts/lead-convertible? :qualified))
  (is (not (facts/lead-convertible? :new)))
  (is (not (facts/lead-convertible? :working)))
  (is (not (facts/lead-convertible? :disqualified)))
  (is (not (facts/lead-convertible? :converted))))
