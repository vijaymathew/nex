(ns nex.parser
  (:require [nex.walker :as walker]
            #?(:clj [clj-antlr.core :as antlr])))

#?(:clj
   (do
     (def parser
       (antlr/parser "grammar/nexlang.g4"))

     (defn parse [input]
       (let [parsed (antlr/parse parser input)]
         ;; Ensure genericArgs are included in the AST
         (assoc parsed :generic-args (get-in parsed [:genericArgs]))))

     (defn ast [input]
       (-> input
           parse
           walker/walk-node)))

   :cljs
   (do
     (def antlr4 (js/require "antlr4"))
     (def nexlangLexer (js/require "./parser_js/nexlangLexer.js"))
     (def nexlangParser (js/require "./parser_js/nexlangParser.js"))

     (defn parse [input]
       (let [chars (antlr4.CharStreams.fromString input)
             lexer (new (.-nexlangLexer nexlangLexer) chars)
             tokens (new antlr4.CommonTokenStream lexer)
             parser (new (.-nexlangParser nexlangParser) tokens)]
         (.program parser)))

     (defn ast [input]
       (-> input
           parse
           walker/walk-node))))
