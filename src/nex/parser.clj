(ns nex.parser
  (:require [nex.walker :as walker]
            [clj-antlr.core :as antlr]))

(def parser
  (antlr/parser "grammar/nexlang.g4"))

(defn parse [input]
  (antlr/parse parser input))

(defn ast [input]
  (-> input
      parse
      walker/walk-node))
