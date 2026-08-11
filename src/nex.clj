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

(defn -main [& args]
  (if (empty? args)
    (println "Usage: clojure -M -m nex <file.nex>")
    (let [file-path (first args)
          code (slurp file-path)]
      (eval-and-print code))))
