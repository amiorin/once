(ns io.github.bigconfig-ai.once.utils-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconfig-ai.once.utils :as sut]))

(deftest portable-parameters
  (is (= {:port 587 :compute-prevent-destroy false}
         (sut/read-pars {:port 1 :compute-prevent-destroy true}
                        {"GREEN_PAR_PORT" "1"
                         "ONCE_PAR_PORT" "587"
                         "ONCE_PAR_COMPUTE_PREVENT_DESTROY" "false"}))))

(deftest registrable-domain-test
  (testing "the zone is the last two labels"
    (is (= "example.com" (sut/registrable-domain "www.example.com")))
    (is (= "example.com" (sut/registrable-domain "a.b.example.com")))
    (is (= "example.com" (sut/registrable-domain "example.com"))))

  (testing "a single label belongs to no zone"
    (is (nil? (sut/registrable-domain "localhost")))
    (is (nil? (sut/registrable-domain nil)))))

(deftest apps-domains-test
  (testing "zones come from all application hosts, not desired state"
    (is (= ["example.com" "example.net"]
           (sut/apps-domains {:once {:applications [{:host "www.example.net"}
                                                    {:host "app.example.com"}
                                                    {:host "admin.example.net"}]}}))))

  (testing "no applications, no zones"
    (is (= [] (sut/apps-domains {})))))
