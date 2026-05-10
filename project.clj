; SPDX-License-Identifier: EPL-2.0
(defproject alembic "0.1.0"
  :description "alembic — homoiconic Clojure DSP DSL for the cljseq ecosystem"
  :url "https://github.com/cljseq/alembic"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [cljseq-maths "0.1.0"]]
  :source-paths ["src"]
  :test-paths   ["test"]
  :target-path  "target/%s"
  :profiles {:dev {:dependencies []}})
