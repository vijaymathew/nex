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
                      {:id 4 :method "textDocument/hover"
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
