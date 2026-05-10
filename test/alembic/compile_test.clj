; SPDX-License-Identifier: EPL-2.0
(ns alembic.compile-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [alembic.compile :refer [check-faust! faust-source validate compile-to-cpp]]
            [alembic.patch :refer [defpatch!]]))

;; ---------------------------------------------------------------------------
;; Test patches
;; ---------------------------------------------------------------------------

(defpatch! simple-patch {}
  (let [osc (phasor 440.0)]
    (output osc)))

(defpatch! fm-patch
  {:params {:carrier {:range [20.0 20000.0] :default 440.0}
            :depth   {:range [0.0 1.0]      :default 0.5}}}
  (let [mod (phasor 880.0)
        sig (sine-bi (add (phasor (param :carrier))
                          (mul (sine-bi mod) (param :depth))))]
    (output sig)))

;; ---------------------------------------------------------------------------
;; check-faust!
;; ---------------------------------------------------------------------------

(deftest check-faust-present-test
  (testing "faust is installed and meets minimum version"
    (is (nil? (check-faust!)))))

;; ---------------------------------------------------------------------------
;; faust-source
;; ---------------------------------------------------------------------------

(deftest faust-source-test
  (testing "returns DSP source string without invoking faust"
    (let [src (faust-source simple-patch)]
      (is (string? src))
      (is (str/includes? src "import(\"stdfaust.lib\");"))
      (is (str/includes? src "os.phasor")))))

;; ---------------------------------------------------------------------------
;; validate
;; ---------------------------------------------------------------------------

(deftest validate-simple-test
  (testing "simple phasor patch validates successfully"
    (is (nil? (validate simple-patch)))))

(deftest validate-fm-test
  (testing "FM patch with params validates successfully"
    (is (nil? (validate fm-patch)))))

(deftest validate-error-test
  (testing "invalid DSP source throws ex-info with :errors key"
    (let [bad-graph {:nodes   {:n0 {:id :n0 :op :faust
                                    :source "THIS IS NOT VALID FAUST"
                                    :rate :sample}}
                    :edges   []
                    :params  {}
                    :outputs [{:node :n0 :channel 0 :name "Main"}]
                    :rate    :sample}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Faust compilation failed"
            (validate bad-graph)))
      (try (validate bad-graph)
           (catch clojure.lang.ExceptionInfo e
             (is (contains? (ex-data e) :errors))
             (is (contains? (ex-data e) :source)))))))

;; ---------------------------------------------------------------------------
;; compile-to-cpp
;; ---------------------------------------------------------------------------

(deftest compile-to-cpp-simple-test
  (let [cpp (compile-to-cpp simple-patch)]
    (testing "returns a non-empty string"
      (is (string? cpp))
      (is (pos? (count cpp))))
    (testing "output is C++ (has class or struct declaration)"
      (is (re-find #"class\s+\w+\s*:" cpp)))
    (testing "output references the compute method"
      (is (str/includes? cpp "compute")))))

(deftest compile-to-cpp-fm-test
  (let [cpp (compile-to-cpp fm-patch)]
    (testing "FM patch compiles to C++"
      (is (string? cpp))
      (is (pos? (count cpp))))
    (testing "hslider params appear in generated C++ UI method"
      (is (str/includes? cpp "carrier"))
      (is (str/includes? cpp "depth")))))

(deftest compile-to-cpp-error-test
  (testing "invalid DSP throws ex-info"
    (let [bad-graph {:nodes   {:n0 {:id :n0 :op :faust
                                    :source "INVALID { DSP }"
                                    :rate :sample}}
                    :edges   []
                    :params  {}
                    :outputs [{:node :n0 :channel 0 :name "Main"}]
                    :rate    :sample}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Faust compilation failed"
            (compile-to-cpp bad-graph))))))
