(ns nex.parser
  (:require [nex.walker :as walker]
            ["antlr4" :as antlr4]
            ["./parser_js/grammar/nexlangLexer.js" :as nexlangLexer]
            ["./parser_js/grammar/nexlangParser.js" :as nexlangParser]))

(defn- js-undefined? [v]
  (identical? js/undefined v))

(defn- resolve-module [m name]
  (cond
    (or (nil? m) (js-undefined? m))
    (throw (js/Error. (str "Parser module missing: " name
                           ". Rebuild browser bundle and hard-refresh.")))

    (and (not (js-undefined? (.-default m))) (some? (.-default m)))
    (.-default m)

    :else
    m))

(defn- try-shadow-require [module-id]
  (try
    (let [shadow-js (.-js js/shadow)]
      (when (and (some? shadow-js) (not (js-undefined? shadow-js)))
        (.require shadow-js module-id #js {})))
    (catch :default _
      nil)))

(defn- parse-tree->sexpr [parser node]
  (cond
    (or (nil? node) (js-undefined? node))
    nil

    ;; Rule node (ParserRuleContext) -> [:ruleName child1 child2 ...]
    (and (some? (.-ruleIndex node)) (not (js-undefined? (.-ruleIndex node))))
    (let [rule-name (aget (.-ruleNames parser) (.-ruleIndex node))
          child-count (.getChildCount node)
          children (->> (range child-count)
                        (map (fn [i] (parse-tree->sexpr parser (.getChild node i))))
                        (remove nil?)
                        vec)]
      (into [(keyword rule-name)] children))

    ;; Terminal node -> token text
    :else
    (.getText node)))

(defn parse [input]
  (let [antlr (resolve-module antlr4 "antlr4")
        ;; Keep direct JSImport references first so Shadow includes and wires
        ;; these modules for browser runtime.
        lexer-mod (or (.-__nexlangLexer js/window)
                      nexlangLexer
                      (aget (or js/$CLJS #js {}) "module$nex$parser_js$grammar$nexlangLexer")
                      (aget (or js/$CLJS #js {}) "module$nex$parser_js$grammar$nexlangLexer.js")
                      (try-shadow-require "module$nex$parser_js$grammar$nexlangLexer")
                      (try-shadow-require "module$nex$parser_js$grammar$nexlangLexer.js"))
        parser-mod (or (.-__nexlangParser js/window)
                       nexlangParser
                       (aget (or js/$CLJS #js {}) "module$nex$parser_js$grammar$nexlangParser")
                       (aget (or js/$CLJS #js {}) "module$nex$parser_js$grammar$nexlangParser.js")
                       (try-shadow-require "module$nex$parser_js$grammar$nexlangParser")
                       (try-shadow-require "module$nex$parser_js$grammar$nexlangParser.js"))
        lexer-ctor (resolve-module lexer-mod "nexlangLexer")
        parser-ctor (resolve-module parser-mod "nexlangParser")
        chars (.fromString (.-CharStreams antlr) input)
        lexer (new lexer-ctor chars)
        tokens (new (.-CommonTokenStream antlr) lexer)
        parser (new parser-ctor tokens)
        parse-tree (.program parser)]
    (parse-tree->sexpr parser parse-tree)))

(defn ast [input]
  (-> input
      parse
      walker/walk-node))
