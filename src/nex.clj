(ns nex
  (:require [nex.parser :as p]))

(defn run [opts]
  (println "nex 0.0.1")
  ;;(clojure.pprint/pprint (p/ast "a.print(3 + 4 * 2, \"abc\")"))
  (clojure.pprint/pprint (p/ast "class Point feature x: Integer y: Integer end")))
