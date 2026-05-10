; SPDX-License-Identifier: EPL-2.0
(ns alembic.compile
  "Faust toolchain integration for alembic patch graphs.

  Requires `faust` >= 2.50 on PATH (brew install faust).

  (check-faust!)         — verify installation, throw if missing/old
  (validate graph)       — confirm DSP source compiles, throw on error
  (compile-to-cpp graph) — return Faust-generated C++ source string
  (faust-source graph)   — return the emitted .dsp source string (no compiler)"
  (:require [alembic.emit :refer [emit-faust]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Version check
;; ---------------------------------------------------------------------------

(def ^:private minimum-version [2 50 0])

(defn- parse-version [s]
  (when-let [[_ v] (re-find #"FAUST Version (\S+)" s)]
    (mapv #(try (Integer/parseInt %) (catch Exception _ 0))
          (str/split v #"\."))))

(defn- version>= [[a b c & _] [x y z & _]]
  (or (> (or a 0) x)
      (and (= (or a 0) x)
           (or (> (or b 0) y)
               (and (= (or b 0) y)
                    (>= (or c 0) z))))))

(defn check-faust!
  "Verify faust is on PATH and meets the minimum version requirement.
  Throws ex-info on failure; returns nil on success."
  []
  (let [{:keys [exit out err]} (sh "faust" "--version")]
    (when (not= 0 exit)
      (throw (ex-info "faust not found — install via: brew install faust"
                      {:stderr err})))
    (if-let [v (parse-version out)]
      (when-not (version>= v minimum-version)
        (throw (ex-info (format "faust %s is below minimum %s — upgrade via: brew upgrade faust"
                                (str/join "." v)
                                (str/join "." minimum-version))
                        {:version v :minimum minimum-version})))
      (throw (ex-info (str "Could not parse faust version from output: " out) {}))))
  nil)

;; ---------------------------------------------------------------------------
;; Temp-file helpers
;; ---------------------------------------------------------------------------

(defn- make-temp [suffix]
  (doto (File/createTempFile "alembic-" suffix)
    (.deleteOnExit)))

(defmacro ^:private with-dsp-file
  "Bind `sym` to a temp .dsp File written with `src`, then execute body.
  File is deleted on exit regardless of outcome."
  [[sym src] & body]
  `(let [f# (make-temp ".dsp")]
     (try
       (spit f# ~src)
       (let [~sym f#] ~@body)
       (finally (.delete f#)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn faust-source
  "Return the Faust .dsp source string for `graph` without invoking the compiler."
  [graph]
  (emit-faust graph))

(defn validate
  "Confirm that `graph` produces valid Faust DSP.
  Runs `faust -lang cpp` on the emitted source and discards the output.
  Returns nil on success; throws ex-info with :errors and :source on failure."
  [graph]
  (let [src    (emit-faust graph)
        out-f  (make-temp ".cpp")]
    (try
      (with-dsp-file [in-f src]
        (let [{:keys [exit err]} (sh "faust" "-lang" "cpp"
                                     (.getAbsolutePath in-f)
                                     "-o" (.getAbsolutePath out-f))]
          (when (not= 0 exit)
            (throw (ex-info "Faust compilation failed"
                            {:errors err :source src})))))
      (finally (.delete out-f))))
  nil)

(defn compile-to-cpp
  "Compile `graph` to a C++ source string via `faust -lang cpp`.
  Returns the C++ source on success; throws ex-info with :errors and :source on failure."
  [graph]
  (let [src   (emit-faust graph)
        out-f (make-temp ".cpp")]
    (try
      (with-dsp-file [in-f src]
        (let [{:keys [exit err]} (sh "faust" "-lang" "cpp"
                                     (.getAbsolutePath in-f)
                                     "-o" (.getAbsolutePath out-f))]
          (if (= 0 exit)
            (slurp out-f)
            (throw (ex-info "Faust compilation failed"
                            {:errors err :source src})))))
      (finally (.delete out-f)))))
