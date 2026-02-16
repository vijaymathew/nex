(ns nex
  (:require [nex.parser :as p]
            [nex.interpreter :as interp]))

(defn eval-code
  "Parse and interpret nex code, returning the context."
  [code]
  (-> code p/ast interp/interpret))

(defn eval-and-print
  "Parse, interpret, and print output from nex code."
  [code]
  (-> code p/ast interp/run))

(defn run [opts]
  (println "╔═══════════════════════════════════════════╗")
  (println "║           NEX Language v0.0.1             ║")
  (println "║  A software modeling and implementation   ║")
  (println "║            language                       ║")
  (println "╚═══════════════════════════════════════════╝")
  (println)

  (println "Example usage:")
  (println)
  (println "1. Expressions:")
  (println "   print(3 + 4 * 2)")
  (eval-and-print "print(3 + 4 * 2)")
  (println)

  (println "2. Comparisons:")
  (println "   print(5 > 3, 10 = 10)")
  (eval-and-print "print(5 > 3, 10 = 10)")
  (println)

  (println "3. Class definitions:")
  (println "   class Point")
  (println "     feature x: Integer y: Integer")
  (println "   end")
  (let [ctx (eval-code "class Point feature x: Integer y: Integer end")]
    (println "   Classes registered:" (keys @(:classes ctx))))
  (println)

  (println "For more examples, run: clojure -M demo.clj"))
