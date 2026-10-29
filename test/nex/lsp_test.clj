(ns nex.lsp-test
  "Tests for `nex lsp`: diagnostics, the outline, formatting, and the
   JSON-RPC framing they travel in."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.lsp :as lsp])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]))

(defn- lines [& ls] (str (str/join "\n" ls) "\n"))

(defn- check [text]
  (lsp/check-text (str (io/file (System/getProperty "java.io.tmpdir") "lsp_test.nex")) text))

(deftest type-errors-are-located
  (let [diags (check (lines "class A"
                            "  feature"
                            "    f(n: Integer): Integer do"
                            "      result := n + \"a\""
                            "    end"
                            "end"
                            "let y: Integer := \"hi\""))]
    (is (= [[4 7] [7 1]] (map (juxt :line #(some-> (:col %) inc)) diags)))
    (is (every? #(str/includes? (:message %) "Cannot assign String") diags))))

(deftest syntax-errors-are-located
  (let [[d & more] (check (lines "class A"
                                 "  feature"
                                 "    f() do"
                                 "      print(\"hi\""
                                 "    end"
                                 "end"))]
    (is (empty? more))
    (is (= 5 (:line d)))
    (is (str/starts-with? (:message d) "Syntax error: "))))

(deftest a-correct-program-has-no-diagnostics
  (is (empty? (check (lines "let x: Integer := 1" "print(x + 1)")))))

(deftest warnings-point-at-their-class
  (let [[d] (check (lines "print(1)"
                          "class P"
                          "  feature"
                          "    equals(other: Any): Boolean do"
                          "      result := true"
                          "    end"
                          "end"))]
    (is (= 2 (:severity d)))
    (is (= 2 (:line d)))))

(deftest a-missing-intern-is-reported-on-its-line
  (let [[d] (check (lines "-- a program" "intern nowhere/Nothing" "print(1)"))]
    (is (= 2 (:line d)))
    (is (str/includes? (:message d) "Cannot find intern file"))))

(defn- outline [syms]
  (mapv (fn [s] (cond-> [(:name s) (:kind s) (get-in s [:range :start :line]) (get-in s [:range :end :line])]
                  (:children s) (conj (outline (:children s)))))
        syms))

(deftest the-outline-lists-declarations
  (let [text (lines "declare type Id = Integer"
                    "union Shape"
                    "  Circle(r: Real)"
                    "  Square(s: Real)"
                    "end"
                    "class A"
                    "  create"
                    "    make(n: Integer) do"
                    "      x := n"
                    "    end"
                    "  feature"
                    "    x: Integer"
                    "    limit = 10"
                    "    area(): Real do"
                    "      result := 1.0"
                    "    end"
                    "end"
                    "function twice(n: Integer): Integer do"
                    "  result := n * 2"
                    "end"
                    "let g := 5")
        syms (lsp/document-symbols (str/split text #"\n" -1) (nex.parser/ast text))]
    (is (= [["Id" 26 0 0]
            ["Shape" 10 1 4 [["Circle" 22 2 2] ["Square" 22 3 3]]]
            ["A" 5 5 16 [["make" 9 7 9] ["x" 8 11 11] ["limit" 14 12 12] ["area" 6 13 15]]]
            ["twice" 12 17 19]
            ["g" 13 20 20]]
           (outline syms)))
    (testing "the selection range is the name"
      (is (= {:start {:line 17 :character 9} :end {:line 17 :character 14}}
             (:selectionRange (nth syms 3)))))))

;;
;; The protocol, end to end
;;

(defn- frame [msg]
  (let [body (.getBytes ^String (json/write-str (assoc msg :jsonrpc "2.0")) StandardCharsets/UTF_8)]
    (concat (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") StandardCharsets/US_ASCII)
            body)))

(defn- unframe [^bytes bs]
  (loop [s (String. bs StandardCharsets/UTF_8) out []]
    (if-let [[header n] (re-find #"^Content-Length: (\d+)\r\n\r\n" s)]
      (let [rest-bytes (.getBytes (subs s (count header)) StandardCharsets/UTF_8)
            n (parse-long n)
            body (String. rest-bytes 0 n StandardCharsets/UTF_8)]
        (recur (String. rest-bytes n (- (alength rest-bytes) n) StandardCharsets/UTF_8)
               (conj out (json/read-str body :key-fn keyword))))
      out)))

(deftest a-session
  (let [uri "file:///tmp/nex_lsp_session_test.nex"
        ugly (lines "class P" "feature" "x: Integer" "end")
        in (ByteArrayInputStream.
            (byte-array
             (mapcat frame
                     [{:id 1 :method "initialize" :params {:capabilities {}}}
                      {:method "initialized" :params {}}
                      {:method "textDocument/didOpen"
                       :params {:textDocument {:uri uri :languageId "nex" :version 1 :text ugly}}}
                      {:id 2 :method "textDocument/formatting"
                       :params {:textDocument {:uri uri} :options {:tabSize 2 :insertSpaces true}}}
                      {:id 3 :method "textDocument/documentSymbol" :params {:textDocument {:uri uri}}}
                      {:id 4 :method "textDocument/rename"
                       :params {:textDocument {:uri uri} :position {:line 0 :character 0}}}
                      {:id 5 :method "shutdown"}
                      {:method "exit"}])))
        out (ByteArrayOutputStream.)
        old @lsp/protocol-out]
    (reset! lsp/protocol-out out)
    (try
      (is (= 0 (lsp/serve in)))
      (lsp/await-idle)
      (finally (reset! lsp/protocol-out old)))
    (let [msgs (unframe (.toByteArray out))
          by-id (into {} (keep (fn [m] (when (:id m) [(:id m) m]))) msgs)]
      (is (true? (get-in by-id [1 :result :capabilities :documentFormattingProvider])))
      (is (= "class P\nfeature\n  x: Integer\nend\n"
             (get-in by-id [2 :result 0 :newText])))
      (is (= ["P"] (map :name (get-in by-id [3 :result]))))
      (is (= -32601 (get-in by-id [4 :error :code])))
      (is (contains? (get by-id 5) :result))
      (is (some #(and (= "textDocument/publishDiagnostics" (:method %))
                      (= [] (get-in % [:params :diagnostics])))
                msgs)))))

;;
;; Hover and go-to-definition
;;

(defn- run-session
  "Serve MSGS (requests get ids 1, 2, ...) and return the responses, in
   order, and every notification."
  [msgs]
  (let [numbered (map-indexed (fn [i m] (if (:method m) (if (:notify? m) (dissoc m :notify?) (assoc m :id (inc i))) m)) msgs)
        in (ByteArrayInputStream.
            (byte-array (mapcat frame (concat numbered [{:id 9999 :method "shutdown"} {:method "exit"}]))))
        out (ByteArrayOutputStream.)
        old @lsp/protocol-out]
    (reset! lsp/protocol-out out)
    (try
      (lsp/serve in)
      (lsp/await-idle)
      (finally (reset! lsp/protocol-out old)))
    (let [msgs (unframe (.toByteArray out))]
      (into {} (keep (fn [m] (when (:id m) [(:id m) (:result m)]))) msgs))))

(defn- position-of
  "{:line :character} (0-based) of the Nth (1-based) whole-word WORD on the
   1-based line LINE of TEXT, one character into it."
  ([text line word] (position-of text line word 1))
  ([text line word n]
   (let [l (nth (str/split text #"\n" -1) (dec line))
         m (re-matcher (re-pattern (str "(?<![A-Za-z0-9_])" word "(?![A-Za-z0-9_])")) l)]
     (dotimes [_ n] (.find m))
     {:line (dec line) :character (inc (.start m))})))

(deftest hover-and-definition
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "nex-lsp-test-" (System/nanoTime)))
              (.mkdirs))
        lib (doto (io/file dir "lib" "geo") (.mkdirs))
        point (io/file lib "Point.nex")
        main (io/file dir "main.nex")
        main-text (lines "intern geo/Point"
                         ""
                         "class Account"
                         "  note \"A bank account.\""
                         "  create"
                         "    make(start: Integer) do"
                         "      balance := start"
                         "    end"
                         "  feature"
                         "    balance: Integer"
                         "    deposit(n: Integer)"
                         "      require"
                         "        positive: n > 0"
                         "      do"
                         "        let before := balance"
                         "        balance := before + n"
                         "      end"
                         "  invariant"
                         "    non_negative: balance >= 0"
                         "end"
                         ""
                         "function twice(n: Integer): Integer do"
                         "  result := n * 2"
                         "end"
                         ""
                         "let acct := create Account.make(10)"
                         "acct.deposit(5)"
                         "let p := create Point.make(1, 2)"
                         "let label: String := \"total\""
                         "print(label.length + twice(p.x) + acct.balance)")
        _ (spit point (lines "class Point"
                             "  create"
                             "    make(ax, ay: Integer) do"
                             "      x := ax"
                             "      y := ay"
                             "    end"
                             "  feature"
                             "    x: Integer"
                             "    y: Integer"
                             "end"))
        _ (spit main main-text)
        uri (str (.toUri (.toPath main)))
        point-uri (str (.toUri (.toPath (.getCanonicalFile point))))
        probes [[27 "deposit"] [30 "x"] [30 "length"] [30 "twice"] [16 "before"] [16 "n"]
                [7 "balance"] [26 "Account"] [28 "make"] [30 "label"] [27 "acct"] [23 "result"]]
        requests (mapcat (fn [[line word]]
                           (let [pos (position-of main-text line word)]
                             [{:method "textDocument/hover" :params {:textDocument {:uri uri} :position pos}}
                              {:method "textDocument/definition" :params {:textDocument {:uri uri} :position pos}}]))
                         probes)
        responses (run-session (concat [{:method "initialize" :params {:capabilities {}}}
                                        {:notify? true :method "textDocument/didOpen"
                                         :params {:textDocument {:uri uri :languageId "nex" :version 1 :text main-text}}}]
                                       requests))
        ;; requests are numbered from 3: initialize is 1, didOpen took 2
        result-for (fn [i] (let [base (+ 3 (* 2 i))] [(get responses base) (get responses (inc base))]))
        hover-text (fn [i] (get-in (first (result-for i)) [:contents :value]))
        def-at (fn [i] (let [d (second (result-for i))]
                         (when d [(if (= uri (:uri d)) :main (if (= point-uri (:uri d)) :point (:uri d)))
                                  (inc (get-in d [:range :start :line]))])))]
    (testing "a call: the feature's signature and contracts, and its declaration"
      (is (str/includes? (hover-text 0) "Account.deposit(n: Integer)"))
      (is (str/includes? (hover-text 0) "positive: `n > 0`"))
      (is (= [:main 11] (def-at 0))))
    (testing "a feature of an interned class lives in its own file"
      (is (str/includes? (hover-text 1) "Point.x: Integer"))
      (is (= [:point 8] (def-at 1))))
    (testing "a built-in feature has a signature but no declaration"
      (is (str/includes? (hover-text 2) "String.length(): Integer"))
      (is (nil? (def-at 2))))
    (testing "a free function"
      (is (str/includes? (hover-text 3) "twice(n: Integer): Integer"))
      (is (= [:main 22] (def-at 3))))
    (testing "locals and parameters"
      (is (str/includes? (hover-text 4) "before: Integer"))
      (is (= [:main 15] (def-at 4)))
      (is (str/includes? (hover-text 5) "parameter"))
      (is (= [:main 11] (def-at 5))))
    (testing "an assignment target that is a field"
      (is (str/includes? (hover-text 6) "Account.balance: Integer"))
      (is (= [:main 10] (def-at 6))))
    (testing "a class, with its note and invariant"
      (is (str/includes? (hover-text 7) "class Account"))
      (is (str/includes? (hover-text 7) "A bank account."))
      (is (str/includes? (hover-text 7) "non_negative: `balance >= 0`"))
      (is (= [:main 3] (def-at 7))))
    (testing "a constructor of an interned class"
      (is (str/includes? (hover-text 8) "create Point.make(ax: Integer, ay: Integer)"))
      (is (= [:point 3] (def-at 8))))
    (testing "globals, with their declared or inferred type"
      (is (str/includes? (hover-text 9) "label: String"))
      (is (= [:main 29] (def-at 9)))
      (is (str/includes? (hover-text 10) "acct: Account"))
      (is (= [:main 26] (def-at 10))))
    (testing "result"
      (is (str/includes? (hover-text 11) "result: Integer")))))
