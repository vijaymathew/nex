(ns nex.grammar-cache
  "A prebuilt form of the grammar's lexer and parser, so a Nex process need not
   run the ANTLR tool on nexlang.g4 at every start.

   clj-antlr's interpreted parser has the ANTLR tool parse and analyse the
   grammar each time it is built: about 0.25 s, a third of `nex` startup. What
   that produces, the lexer's and parser's ATNs and their rule, token, channel
   and mode names, is all ANTLR's runtime-only LexerInterpreter and
   ParserInterpreter need, and it is fixed for a given grammar. `write!` saves
   it (the release build puts it in nex.jar as grammar/nexlang.atn) and
   `parser` builds a clj-antlr parser from it, which gives the same parse trees
   and errors as clj-antlr's own (see test/nex/grammar_cache_test.clj). The
   file records the length and CRC32 of the grammar it was made from, so one
   that doesn't match the grammar shipped beside it is ignored."
  (:require [clojure.java.io :as io]
            [clj-antlr.common :as common]
            [clj-antlr.core :as antlr]
            [clj-antlr.interpreted :as interpreted]
            [clj-antlr.proto :as proto])
  (:import [java.io DataInputStream DataOutputStream]
           [java.util.zip CRC32]
           [org.antlr.v4.runtime LexerInterpreter ParserInterpreter Vocabulary VocabularyImpl]
           [org.antlr.v4.runtime.atn ATNDeserializer ATNSerializer]))

(def ^:private magic "nex-grammar-atn-1")

(defn grammar-stamp
  "[length crc32] of the grammar text, which a cache file must match."
  [^String grammar-text]
  (let [bytes (.getBytes grammar-text "UTF-8")
        crc (doto (CRC32.) (.update bytes))]
    [(alength bytes) (.getValue crc)]))

;; ---------------------------------------------------------------------------
;; Writing

(defn- vocabulary-data [^Vocabulary v]
  (let [n (inc (.getMaxTokenType v))]
    {:literal (mapv #(.getLiteralName v %) (range n))
     :symbolic (mapv #(.getSymbolicName v %) (range n))
     :display (mapv #(.getDisplayName v %) (range n))}))

(defn grammar-data
  "What the lexer and parser interpreters for the grammar at GRAMMAR-PATH are
   built from, by running the ANTLR tool on it as clj-antlr does."
  [grammar-path]
  (let [grammar (interpreted/grammar grammar-path)
        ^LexerInterpreter lexer (interpreted/lexer-interpreter (.getImplicitLexer grammar))
        ^ParserInterpreter parser (interpreted/parser-interpreter grammar lexer)]
    {:lexer {:file (.getGrammarFileName lexer)
             :vocabulary (vocabulary-data (.getVocabulary lexer))
             :rules (vec (.getRuleNames lexer))
             :channels (vec (.getChannelNames lexer))
             :modes (vec (.getModeNames lexer))
             :atn (.toArray (ATNSerializer/getSerialized (.getATN lexer)))}
     :parser {:file (.getGrammarFileName parser)
              :vocabulary (vocabulary-data (.getVocabulary parser))
              :rules (vec (.getRuleNames parser))
              :atn (.toArray (ATNSerializer/getSerialized (.getATN parser)))}}))

(defn- write-string [^DataOutputStream out s]
  (.writeBoolean out (some? s))
  (when (some? s) (.writeUTF out ^String s)))

(defn- write-strings [^DataOutputStream out xs]
  (.writeInt out (count xs))
  (doseq [x xs] (write-string out x)))

(defn- write-ints [^DataOutputStream out ^ints xs]
  (.writeInt out (alength xs))
  (dotimes [i (alength xs)] (.writeInt out (aget xs i))))

(defn- write-section [^DataOutputStream out {:keys [file vocabulary rules channels modes atn]}]
  (write-string out file)
  (write-strings out (:literal vocabulary))
  (write-strings out (:symbolic vocabulary))
  (write-strings out (:display vocabulary))
  (write-strings out rules)
  (write-strings out (or channels []))
  (write-strings out (or modes []))
  (write-ints out atn))

(defn write!
  "Write the prebuilt grammar for the grammar file GRAMMAR-PATH to OUT, a file
   or output stream."
  [grammar-path out]
  (let [[length crc] (grammar-stamp (slurp grammar-path :encoding "UTF-8"))
        {:keys [lexer parser]} (grammar-data grammar-path)]
    (with-open [o (DataOutputStream. (io/output-stream out))]
      (.writeUTF o ^String magic)
      (.writeInt o (int length))
      (.writeLong o (long crc))
      (write-section o lexer)
      (write-section o parser))))

;; ---------------------------------------------------------------------------
;; Reading

(defn- read-string* [^DataInputStream in]
  (when (.readBoolean in) (.readUTF in)))

(defn- read-strings [^DataInputStream in]
  (let [n (.readInt in)]
    (loop [i 0 out (transient [])]
      (if (< i n) (recur (inc i) (conj! out (read-string* in))) (persistent! out)))))

(defn- read-ints ^ints [^DataInputStream in]
  (let [n (.readInt in)
        xs (int-array n)]
    (dotimes [i n] (aset xs i (.readInt in)))
    xs))

(defn- read-section [^DataInputStream in lexer?]
  (let [file (read-string* in)
        literal (read-strings in)
        symbolic (read-strings in)
        display (read-strings in)
        rules (read-strings in)
        channels (read-strings in)
        modes (read-strings in)
        atn (read-ints in)]
    (cond-> {:file file
             :vocabulary {:literal literal :symbolic symbolic :display display}
             :rules rules
             :atn atn}
      lexer? (assoc :channels channels :modes modes))))

(defn read-data
  "The prebuilt grammar in IN (a file, resource URL or input stream) when it
   was made from a grammar whose text is GRAMMAR-TEXT, else nil."
  [in ^String grammar-text]
  (with-open [i (DataInputStream. (io/input-stream in))]
    (when (= magic (.readUTF i))
      (let [length (.readInt i)
            crc (.readLong i)]
        (when (= [length crc] (grammar-stamp grammar-text))
          {:lexer (read-section i true)
           :parser (read-section i false)})))))

;; ---------------------------------------------------------------------------
;; A clj-antlr parser over the prebuilt grammar
;;
;; These mirror clj-antlr.interpreted's SinglethreadedParser and
;; ThreadLocalParser, building ANTLR's runtime interpreters from the prebuilt
;; data instead of from a Grammar the ANTLR tool made.

(defrecord ^:private SinglethreadedParser [^LexerInterpreter lexer ^ParserInterpreter parser rule-indexes]
  proto/Parser
  (parse [p opts text]
    (locking p
      (let [error-listener (common/error-listener)
            char-stream (common/char-stream text opts)]
        (doto lexer
          (.setInputStream char-stream)
          (.reset)
          (.removeErrorListeners)
          (.addErrorListener error-listener))
        (let [tokens (common/tokens lexer)]
          (doto parser
            (.setTokenStream tokens)
            (.reset)
            (.removeErrorListeners)
            (.addErrorListener error-listener))
          (let [rule (if-let [root (:root opts)]
                       (or (get rule-indexes (name root))
                           (throw (IllegalArgumentException. (str "No rule " root))))
                       0)
                tree (.parse parser (int rule))]
            (when-let [errors (and (get opts :throw? true) @error-listener)]
              (throw (common/parse-error errors tree)))
            {:tree tree
             :tokens tokens
             :errors @error-listener
             :parser parser
             :grammar nil
             :opts opts}))))))

(defrecord ^:private ThreadLocalParser [^ThreadLocal local make]
  proto/Parser
  (parse [_ opts text]
    (let [p (or (.get local)
                (let [p (make)] (.set local p) p))]
      (proto/parse p opts text))))

(defn- vocabulary [{:keys [literal symbolic display]}]
  (VocabularyImpl. (into-array String literal) (into-array String symbolic) (into-array String display)))

(defn- atn [^ints xs]
  (.deserialize (ATNDeserializer.) xs))

(defn parser
  "A clj-antlr parser (for clj-antlr.core/parse) built from DATA, as read-data
   returns it. Each thread gets its own lexer and parser, as with clj-antlr's."
  [{:keys [lexer parser]}]
  (let [lexer-atn (atn (:atn lexer))
        parser-atn (atn (:atn parser))
        lexer-vocabulary (vocabulary (:vocabulary lexer))
        parser-vocabulary (vocabulary (:vocabulary parser))
        rule-indexes (zipmap (:rules parser) (range))
        make (fn []
               (let [l (LexerInterpreter. ^String (:file lexer) lexer-vocabulary
                                          ^java.util.Collection (:rules lexer)
                                          ^java.util.Collection (:channels lexer)
                                          ^java.util.Collection (:modes lexer)
                                          lexer-atn (common/char-stream ""))
                     p (ParserInterpreter. ^String (:file parser) parser-vocabulary
                                           ^java.util.Collection (:rules parser)
                                           parser-atn (common/tokens l))]
                 (->SinglethreadedParser l p rule-indexes)))]
    (antlr/->ParserWrapper (->ThreadLocalParser (ThreadLocal.) make) {})))
