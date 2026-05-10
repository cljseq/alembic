; SPDX-License-Identifier: EPL-2.0
(ns alembic.patch-test
  (:require [clojure.test :refer [deftest is testing]]
            [alembic.patch :refer [defpatch!]]))

;; ---------------------------------------------------------------------------
;; Helper queries
;; ---------------------------------------------------------------------------

(defn- nodes-by-op [graph op]
  (filter #(= op (:op %)) (vals (:nodes graph))))

(defn- edges-where [graph pred]
  (filter pred (:edges graph)))

;; ---------------------------------------------------------------------------
;; Literal promotion
;; ---------------------------------------------------------------------------

(defpatch! literal-test {}
  (let [osc (phasor 440.0)]
    (output osc)))

(deftest literal-promotion-test
  (testing "numeric literal becomes :const node"
    (is (= 1 (count (nodes-by-op literal-test :const)))))
  (testing ":const node carries the literal value"
    (is (= 440.0 (:value (first (nodes-by-op literal-test :const))))))
  (testing ":const node is :sample rate"
    (is (= :sample (:rate (first (nodes-by-op literal-test :const)))))))

;; ---------------------------------------------------------------------------
;; Param deduplication
;; ---------------------------------------------------------------------------

(defpatch! param-dedup-p {}
  (let [x (add (param :freq) (param :freq))]
    (output x)))

(deftest param-dedup-test
  (testing "same param referenced twice → single :param node"
    (is (= 1 (count (nodes-by-op param-dedup-p :param)))))
  (testing "both inputs of :add reference the same node id"
    (let [plus (first (nodes-by-op param-dedup-p :add))]
      (is (= (get-in plus [:inputs :a])
             (get-in plus [:inputs :b]))))))

;; ---------------------------------------------------------------------------
;; Rate crossing
;; ---------------------------------------------------------------------------

(defpatch! rate-crossing-p {}
  (let [osc (phasor (param :freq))]
    (output osc)))

(deftest rate-crossing-test
  (testing "one crossing edge from :param to :phasor"
    (is (= 1 (count (edges-where rate-crossing-p :rate-crossing?)))))
  (testing "the crossing edge connects :block source to :sample destination"
    (let [edge  (first (edges-where rate-crossing-p :rate-crossing?))
          nodes (:nodes rate-crossing-p)]
      (is (= :param  (:op (get nodes (:from edge)))))
      (is (= :phasor (:op (get nodes (:to edge))))))))

;; ---------------------------------------------------------------------------
;; history feedback marking
;; ---------------------------------------------------------------------------

(defpatch! feedback-p {}
  (let [h (history (sine-bi (phasor 1.0)))]
    (output h)))

(deftest feedback-test
  (testing "exactly one :feedback? edge"
    (is (= 1 (count (edges-where feedback-p :feedback?)))))
  (testing "the feedback edge points into the :history node"
    (let [edge    (first (edges-where feedback-p :feedback?))
          hist    (first (nodes-by-op feedback-p :history))]
      (is (some? hist))
      (is (= (:id hist) (:to edge))))))

;; ---------------------------------------------------------------------------
;; output descriptor
;; ---------------------------------------------------------------------------

(defpatch! output-desc-test {}
  (let [x (phasor 1.0)]
    (output x)))

(deftest output-descriptor-test
  (testing "single :outputs entry"
    (is (= 1 (count (:outputs output-desc-test)))))
  (testing "channel 0, name Main"
    (let [out (first (:outputs output-desc-test))]
      (is (= 0      (:channel out)))
      (is (= "Main" (:name out)))))
  (testing "output :node is a valid node id in :nodes"
    (let [out (first (:outputs output-desc-test))]
      (is (contains? (:nodes output-desc-test) (:node out))))))

;; ---------------------------------------------------------------------------
;; Multi-channel outputs
;; ---------------------------------------------------------------------------

(defpatch! stereo-test {}
  (let [left  (phasor 440.0)
        right (phasor 441.0)]
    (output left)
    (output right)))

(deftest multi-channel-output-test
  (testing "two output calls → two entries in :outputs"
    (is (= 2 (count (:outputs stereo-test)))))
  (testing "channels are 0 and 1"
    (is (= #{0 1} (set (map :channel (:outputs stereo-test)))))))

;; ---------------------------------------------------------------------------
;; Dominant rate
;; ---------------------------------------------------------------------------

(defpatch! sample-rate-test {}
  (let [x (phasor 1.0)]
    (output x)))

(deftest dominant-rate-test
  (testing "patch with :sample nodes → :rate :sample"
    (is (= :sample (:rate sample-rate-test)))))

;; ---------------------------------------------------------------------------
;; Params schema passthrough
;; ---------------------------------------------------------------------------

(defpatch! params-schema-test
  {:params {:freq {:range [20.0 20000.0] :default 440.0 :unit :hz}}}
  (let [osc (phasor (param :freq))]
    (output osc)))

(deftest params-passthrough-test
  (testing "params schema is preserved verbatim"
    (is (= {:range [20.0 20000.0] :default 440.0 :unit :hz}
           (get-in params-schema-test [:params :freq])))))

;; ---------------------------------------------------------------------------
;; Structural smoke test — two-op FM patch
;; ---------------------------------------------------------------------------

(defpatch! fm-smoke-p
  {:params {:carrier   {:range [20.0 20000.0] :default 440.0 :unit :hz}
            :mod-ratio {:range [0.1 10.0]     :default 2.0}}}
  (let [mod-p  (phasor (mul (param :carrier) (param :mod-ratio)))
        sig    (sine-bi (add (phasor (param :carrier)) (sine-bi mod-p)))]
    (output sig)))

(deftest fm-smoke-test
  (testing "has nodes for all expected ops"
    (is (= 2 (count (nodes-by-op fm-smoke-p :param))))
    (is (= 2 (count (nodes-by-op fm-smoke-p :phasor))))
    (is (= 1 (count (nodes-by-op fm-smoke-p :mul))))
    (is (= 1 (count (nodes-by-op fm-smoke-p :add))))
    (is (= 2 (count (nodes-by-op fm-smoke-p :sine-bi)))))
  (testing "all rate-crossing edges connect :block to :sample"
    (doseq [edge (edges-where fm-smoke-p :rate-crossing?)]
      (let [nodes (:nodes fm-smoke-p)]
        (is (= :block  (:rate (get nodes (:from edge)))))
        (is (= :sample (:rate (get nodes (:to edge))))))))
  (testing "patch rate is :sample"
    (is (= :sample (:rate fm-smoke-p)))))
