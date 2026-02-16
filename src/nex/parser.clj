(ns nex.parser
  (:require [clj-antlr.core :as antlr]
            [nex.walker :as walker]))

(def parser
  (antlr/parser "grammar/nexlang.g4"))

(defn parse [input]
  (antlr/parse parser input))

(defn ast [input]
  (-> input
      parse
      walker/walk-node))
