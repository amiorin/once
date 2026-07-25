(ns io.github.bigconfig-ai.once.utils-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconfig-ai.once.utils :as sut]))

(deftest strip-ansi-test
  (testing "strips ANSI escape sequences and OSC 8 hyperlinks"
    (let [ansi (slurp (io/resource "ansi.output"))
          normal (slurp (io/resource "normal.output"))]
      (is (= normal (sut/strip-ansi ansi))))))

(deftest registrable-domain-test
  (testing "the zone is the last two labels"
    (is (= "example.com" (sut/registrable-domain "www.example.com")))
    (is (= "example.com" (sut/registrable-domain "a.b.example.com")))
    (is (= "example.com" (sut/registrable-domain "example.com"))))

  (testing "a single label belongs to no zone"
    (is (nil? (sut/registrable-domain "localhost")))
    (is (nil? (sut/registrable-domain nil)))))

(deftest apps-domain-test
  (testing "the zone comes from the application hosts, not desired state"
    (is (= "example.com"
           (sut/apps-domain {:once {:applications [{:host "www.example.com"}
                                                   {:host "app.example.com"}]}}))))

  (testing "no applications, no zone"
    (is (nil? (sut/apps-domain {})))))
