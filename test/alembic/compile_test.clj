; SPDX-License-Identifier: EPL-2.0
(ns alembic.compile-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [alembic.compile :refer [check-faust! faust-source validate
                                     compile-to-cpp compile-to-clap]]
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

;; ---------------------------------------------------------------------------
;; compile-to-clap — gated on cpp build being configured
;;
;; These tests are skipped when the cmake build directory does not exist,
;; keeping `lein test` fast. Run `cmake -S cpp -B cpp/build` once to enable.
;; ---------------------------------------------------------------------------

(defn- cpp-build-ready? []
  (.exists (io/file (System/getProperty "user.dir") "cpp" "build" "CMakeCache.txt")))

(deftest compile-to-clap-simple-test
  (when (cpp-build-ready?)
    (let [clap-path (compile-to-clap simple-patch :name "alembic-test-phasor")]
      (testing "returns a string path"
        (is (string? clap-path)))
      (testing "path ends with .clap"
        (is (str/ends-with? clap-path ".clap")))
      (testing ".clap bundle exists on disk"
        (is (.exists (io/file clap-path))))
      (testing "macOS bundle has Contents/MacOS structure"
        (is (.exists (io/file clap-path "Contents" "MacOS" "alembic-test-phasor")))))))

(deftest compile-to-clap-with-params-test
  (when (cpp-build-ready?)
    (let [clap-path (compile-to-clap fm-patch
                                     :name    "alembic-test-fm"
                                     :vendor  "cljseq-test"
                                     :version "0.1.0")]
      (testing "FM patch with params compiles to .clap"
        (is (.exists (io/file clap-path))))
      (testing "bundle structure is correct"
        (is (.exists (io/file clap-path "Contents" "MacOS" "alembic-test-fm")))))))
