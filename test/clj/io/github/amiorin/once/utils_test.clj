(ns io.github.amiorin.once.utils-test
  (:require
   [clojure.java.io :as io]
   [io.github.amiorin.once.utils :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest strip-ansi-test
  (testing "strips ANSI escape sequences and OSC 8 hyperlinks"
    (let [ansi (slurp (io/resource "ansi.output"))
          normal (slurp (io/resource "normal.output"))]
      (is (= normal (sut/strip-ansi ansi))))))
