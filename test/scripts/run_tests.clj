#!/usr/bin/env clojure

(require '[clojure.test :as test])

;; Load all test namespaces
(require 'nex.loops-test)
(require 'nex.if-conditions-test)
(require 'nex.scoped-blocks-test)
(require 'nex.param-syntax-test)
(require 'nex.inheritance-test)
(require 'nex.inheritance-runtime-test)
(require 'nex.generator.java-test)
(require 'nex.generator.javascript_test)
(require 'nex.typed-let-test)
(require 'nex.visibility-test)
(require 'nex.types-test)
(require 'nex.create-test)
(require 'nex.generics-test)
(require 'nex.parameterless-call-test)
(require 'nex.arrays-maps-test)
(require 'nex.exception-test)
(require 'nex.cursor-test)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                    RUNNING ALL TESTS                       ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Run all tests
(let [results (test/run-tests 'nex.loops-test
                               'nex.if-conditions-test
                               'nex.scoped-blocks-test
                               'nex.param-syntax-test
                               'nex.inheritance-test
                               'nex.inheritance-runtime-test
                               'nex.generator.java-test
                               'nex.generator.javascript_test
                               'nex.typed-let-test
                               'nex.visibility-test
                               'nex.types-test
                               'nex.create-test
                               'nex.generics-test
                               'nex.parameterless-call-test
                               'nex.arrays-maps-test
                               'nex.exception-test
                               'nex.cursor-test)]
  (println)
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║                    TEST SUMMARY                            ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println)
  (println "Total tests:" (:test results))
  (println "Passed:" (:pass results))
  (println "Failed:" (:fail results))
  (println "Errors:" (:error results))
  (println)
  (when (or (pos? (:fail results)) (pos? (:error results)))
    (System/exit 1)))
