(ns io.github.bigconig-ai.once.utils-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconig-ai.once.utils :as sut]))

(deftest strip-ansi-test
  (testing "strips ANSI escape sequences and OSC 8 hyperlinks"
    (let [ansi (slurp (io/resource "ansi.output"))
          normal (slurp (io/resource "normal.output"))]
      (is (= normal (sut/strip-ansi ansi))))))
