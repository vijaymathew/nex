(ns nex.intern-test
  "Tests for intern statement to load external classes"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.repl :as repl]))

(deftest intern-parsing-test
  (testing "Parse intern statement with path and alias"
    (let [code "intern math/Factorial as Fact\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          intern-node (first (:interns ast))]
      (is (= :intern (:type intern-node)))
      (is (= "math" (:path intern-node)))
      (is (= "Factorial" (:class-name intern-node)))
      (is (= "Fact" (:alias intern-node)))))

  (testing "Parse intern statement without alias"
    (let [code "intern utils/Logger\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          intern-node (first (:interns ast))]
      (is (= :intern (:type intern-node)))
      (is (= "utils" (:path intern-node)))
      (is (= "Logger" (:class-name intern-node)))
      (is (nil? (:alias intern-node)))))

  (testing "Parse intern statement with deep path"
    (let [code "intern org/example/utils/Helper\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          intern-node (first (:interns ast))]
      (is (= :intern (:type intern-node)))
      (is (= "org/example/utils" (:path intern-node)))
      (is (= "Helper" (:class-name intern-node)))
      (is (nil? (:alias intern-node)))))

  (testing "Parse multiple intern statements"
    (let [code "intern math/Factorial\nintern utils/Logger as Log\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          interns (:interns ast)]
      (is (= 2 (count interns)))
      (is (= "Factorial" (:class-name (first interns))))
      (is (= "Logger" (:class-name (second interns))))
      (is (= "Log" (:alias (second interns)))))))

(deftest repl-intern-loads-class
  (testing "REPL evaluates top-level intern declarations and registers the class"
    (let [logger-file (io/file "Logger.nex")
          ctx (repl/init-repl-context)]
      (spit logger-file "class Logger
  feature
    show() do
      print(\"ok\")
    end
end")
      (try
        (let [output (with-out-str
                       (repl/eval-code ctx "intern Logger")
                       (repl/eval-code ctx "let l := create Logger")
                       (repl/eval-code ctx "l.show()"))]
          (is (not (.contains output "Undefined class: Logger")))
          (is (.contains output "#<Logger object>"))
          (is (.contains output "\"ok\"")))
        (finally
          (.delete logger-file))))))

(deftest load-file-resolves-bare-intern-relative-to-loaded-file
  (testing ":load-style evaluation resolves bare intern relative to the loaded file location"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-intern-" (System/nanoTime)))
          logger-file (io/file tmp-dir "Logger.nex")
          main-file (io/file tmp-dir "main.nex")
          ctx (repl/init-repl-context)]
      (.mkdirs tmp-dir)
      (spit logger-file "class Logger
  feature
    show() do
      print(\"ok\")
    end
end")
      (spit main-file "intern Logger")
      (try
        (let [output (with-out-str
                       (repl/eval-code ctx (slurp main-file) (.getPath main-file))
                       (repl/eval-code ctx "let l := create Logger")
                       (repl/eval-code ctx "l.show()"))]
          (is (not (.contains output "Cannot find intern file for Logger")))
          (is (.contains output "#<Logger object>"))
          (is (.contains output "\"ok\"")))
        (finally
          (.delete logger-file)
          (.delete main-file)
          (.delete tmp-dir))))))

(deftest load-file-typechecks-bare-intern-relative-to-loaded-file
  (testing ":load-style evaluation typechecks files that use bare intern classes"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-intern-typecheck-" (System/nanoTime)))
          a-file (io/file tmp-dir "A.nex")
          main-file (io/file tmp-dir "main.nex")
          ctx (repl/init-repl-context)]
      (.mkdirs tmp-dir)
      (spit a-file "class A
feature
  greet(name: String) do
    print(\"hello \" + name)
  end
end")
      (spit main-file "intern A

class Main
create
  make(name: String) do
    let a: A := create A
    a.greet(name)
  end
end")
      (try
        (binding [repl/*type-checking-enabled* (atom true)
                  repl/*repl-var-types* (atom {})]
          (let [output (with-out-str
                         (repl/eval-code ctx (slurp main-file) (.getPath main-file)))]
            (is (not (.contains output "Type error: Undefined class: A")))
            (is (not (.contains output "Error: Type checking failed")))))
        (finally
          (.delete a-file)
          (.delete main-file)
          (.delete tmp-dir))))))

(deftest load-file-resolves-refinement-type-declared-in-interned-file
  (testing "A `declare type ... where` refinement declared in an interned file is resolvable, both inside that file's own classes and from the loading script — not just when declared directly in the root script"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-intern-refinement-" (System/nanoTime)))
          qty-file (io/file tmp-dir "Qty_Mod.nex")
          main-file (io/file tmp-dir "main.nex")
          ctx (repl/init-repl-context)]
      (.mkdirs tmp-dir)
      (spit qty-file "declare type Quantity = Integer where n: n > 0

class Qty_Mod
create
  make()
  do
  end
feature
  check(x: Integer): Quantity
  do
    result := x
  end
end")
      (spit main-file "intern Qty_Mod

let m := create Qty_Mod.make()
let q: Quantity := 5
print(q)
print(m.check(7))")
      (try
        (let [output (with-out-str
                       (repl/eval-code ctx (slurp main-file) (.getPath main-file)))]
          (is (not (.contains output "Undefined type: Quantity")))
          (is (not (.contains output "Error: Type checking failed")))
          (is (.contains output "5"))
          (is (.contains output "7")))
        (finally
          (.delete qty-file)
          (.delete main-file)
          (.delete tmp-dir))))))

(deftest repl-intern-loads-bare-class-from-home-deps
  (testing "REPL resolves bare intern names from ~/.nex/deps"
    (let [fake-home (io/file (System/getProperty "java.io.tmpdir") (str "nex-home-" (System/nanoTime)))
          deps-dir (io/file fake-home ".nex" "deps")
          logger-file (io/file deps-dir "Logger.nex")
          ctx (repl/init-repl-context)
          original-home (System/getProperty "user.home")]
      (.mkdirs fake-home)
      (.mkdirs deps-dir)
      (spit logger-file "class Logger
  feature
    show() do
      print(\"ok\")
    end
end")
      (try
        (System/setProperty "user.home" (.getAbsolutePath fake-home))
        (let [output (with-out-str
                       (repl/eval-code ctx "intern Logger")
                       (repl/eval-code ctx "let l := create Logger")
                       (repl/eval-code ctx "l.show()"))]
          (is (not (.contains output "Cannot find intern file for Logger")))
          (is (.contains output "#<Logger object>"))
          (is (.contains output "\"ok\"")))
        (finally
          (System/setProperty "user.home" original-home)
          (.delete logger-file)
          (.delete deps-dir)
          (.delete (io/file fake-home ".nex"))
          (.delete fake-home))))))

(deftest repl-intern-loads-path-qualified-class-from-home-deps
  (testing "REPL resolves path-qualified intern names from ~/.nex/deps with or without src"
    (let [fake-home (io/file (System/getProperty "java.io.tmpdir") (str "nex-home-path-" (System/nanoTime)))
          deps-dir (io/file fake-home ".nex" "deps" "utils")
          logger-file (io/file deps-dir "Logger.nex")
          ctx (repl/init-repl-context)
          original-home (System/getProperty "user.home")]
      (.mkdirs fake-home)
      (.mkdirs deps-dir)
      (spit logger-file "class Logger
  feature
    show() do
      print(\"ok\")
    end
end")
      (try
        (System/setProperty "user.home" (.getAbsolutePath fake-home))
        (let [output (with-out-str
                       (repl/eval-code ctx "intern utils/Logger")
                       (repl/eval-code ctx "let l := create Logger")
                       (repl/eval-code ctx "l.show()"))]
          (is (not (.contains output "Cannot find intern file for utils/Logger")))
          (is (.contains output "#<Logger object>"))
          (is (.contains output "\"ok\"")))
        (finally
          (System/setProperty "user.home" original-home)
          (.delete logger-file)
          (.delete deps-dir)
          (.delete (io/file fake-home ".nex" "deps"))
          (.delete (io/file fake-home ".nex"))
          (.delete fake-home))))))

(deftest repl-intern-loads-lowercase-filename-fallback
  (testing "REPL resolves Tcp_Socket from lowercase tcp_socket.nex"
    (let [fake-home (io/file (System/getProperty "java.io.tmpdir") (str "nex-home-lowercase-" (System/nanoTime)))
          deps-dir (io/file fake-home ".nex" "deps" "utils")
          socket-file (io/file deps-dir "tcp_socket.nex")
          ctx (repl/init-repl-context)
          original-home (System/getProperty "user.home")]
      (.mkdirs fake-home)
      (.mkdirs deps-dir)
      (spit socket-file "class Tcp_Socket
  feature
    show() do
      print(\"ok\")
    end
end")
      (try
        (System/setProperty "user.home" (.getAbsolutePath fake-home))
        (let [output (with-out-str
                       (repl/eval-code ctx "intern utils/Tcp_Socket")
                       (repl/eval-code ctx "let s := create Tcp_Socket")
                       (repl/eval-code ctx "s.show()"))]
          (is (not (.contains output "Cannot find intern file for utils/Tcp_Socket")))
          (is (.contains output "#<Tcp_Socket object>"))
          (is (.contains output "\"ok\"")))
        (finally
          (System/setProperty "user.home" original-home)
          (.delete socket-file)
          (.delete deps-dir)
          (.delete (io/file fake-home ".nex" "deps"))
          (.delete (io/file fake-home ".nex"))
          (.delete fake-home))))))

(deftest repl-intern-loads-from-local-lib-directory
  (testing "REPL resolves path-qualified intern names from ./lib"
    (let [net-dir (io/file "lib" "test_net")
          socket-file (io/file net-dir "tcp_socket.nex")
          ctx (repl/init-repl-context)]
      (.mkdirs net-dir)
      (spit socket-file "class Tcp_Socket
  feature
    show() do
      print(\"ok\")
    end
end")
      (try
        (let [output (with-out-str
                       (repl/eval-code ctx "intern test_net/Tcp_Socket")
                       (repl/eval-code ctx "let s := create Tcp_Socket")
                       (repl/eval-code ctx "s.show()"))]
          (is (not (.contains output "Cannot find intern file for test_net/Tcp_Socket")))
          (is (.contains output "#<Tcp_Socket object>"))
          (is (.contains output "\"ok\"")))
        (finally
          (.delete socket-file)
          (.delete net-dir))))))

(deftest repl-intern-loads-checked-in-tcp-socket-library
  (testing "REPL can load the checked-in TCP socket library and use its disconnected constructor"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern net/Tcp_Socket")
                   (repl/eval-code ctx "let s: Tcp_Socket := create Tcp_Socket.make(\"example.com\", 80)")
                   (repl/eval-code ctx "print(s.is_connected())")
                   (repl/eval-code ctx "print(s.to_string())"))]
      (is (not (.contains output "Cannot find intern file for net/Tcp_Socket")))
      (is (.contains output "#<Tcp_Socket object>"))
      (is (.contains output "false"))
      (is (.contains output "\"Tcp_Socket(example.com:80, connected=false)\"")))))

(deftest repl-intern-loads-checked-in-server-socket-library
  (testing "REPL can load the checked-in Server_Socket library and use its disconnected constructor"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern net/Server_Socket")
                   (repl/eval-code ctx "let s: Server_Socket := create Server_Socket.make(0)")
                   (repl/eval-code ctx "print(s.is_listening())")
                   (repl/eval-code ctx "print(s.to_string())"))]
      (is (not (.contains output "Cannot find intern file for net/Server_Socket")))
      (is (.contains output "#<Server_Socket object>"))
      (is (.contains output "false"))
      (is (.contains output "\"Server_Socket(port=0, listening=false)\"")))))

(deftest repl-intern-loads-checked-in-http-client-library
  (testing "REPL can load the checked-in Http_Client library and create a client object"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern net/Http_Client")
                   (repl/eval-code ctx "let client: Http_Client := create Http_Client.make()")
                   (repl/eval-code ctx "print(client.to_string())"))]
      (is (not (.contains output "Cannot find intern file for net/Http_Client")))
      (is (.contains output "#<Http_Client object>")))))

(deftest repl-intern-loads-checked-in-http-server-library
  (testing "REPL can load the checked-in Http_Server library and create a server object"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern net/Http_Server")
                   (repl/eval-code ctx "let server: Http_Server := create Http_Server.make(0)")
                   (repl/eval-code ctx "print(server.is_running())")
                   (repl/eval-code ctx "print(server.to_string())"))]
      (is (not (.contains output "Cannot find intern file for net/Http_Server")))
      (is (.contains output "#<Http_Server object>"))
      (is (.contains output "false"))
      (is (.contains output "\"Http_Server(port=0, running=false)\"")))))

(deftest repl-intern-loads-checked-in-io-libraries
  (testing "REPL can load the checked-in io libraries and create representative objects"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern io/Path")
                   (repl/eval-code ctx "intern io/Directory")
                   (repl/eval-code ctx "intern io/Text_File")
                   (repl/eval-code ctx "intern io/Binary_File")
                   (repl/eval-code ctx "let d: Directory := create Directory.make(\".\")")
                   (repl/eval-code ctx "print(d.to_string())"))]
      (is (not (.contains output "Cannot find intern file for io/Path")))
      (is (not (.contains output "Cannot find intern file for io/Directory")))
      (is (not (.contains output "Cannot find intern file for io/Text_File")))
      (is (not (.contains output "Cannot find intern file for io/Binary_File")))
      (is (.contains output "\"Directory(.")))))

(deftest repl-intern-loads-checked-in-json-library
  (testing "REPL can load the checked-in Json library and create a Json object"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern data/Json")
                   (repl/eval-code ctx "let json: Json := create Json.make()")
                   (repl/eval-code ctx "print(json.to_string())"))]
      (is (not (.contains output "Cannot find intern file for data/Json")))
      (is (.contains output "#<Json object>")))))

(deftest repl-intern-loads-checked-in-time-libraries
  (testing "REPL can load the checked-in time libraries and create representative objects"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern time/Duration")
                   (repl/eval-code ctx "intern time/Date_Time")
                   (repl/eval-code ctx "let d: Duration := create Duration.seconds(5)")
                   (repl/eval-code ctx "let t: Date_Time := create Date_Time.make(2026, 3, 13, 10, 30, 0)")
                   (repl/eval-code ctx "print(d.to_string())")
                   (repl/eval-code ctx "print(t.to_string())"))]
      (is (not (.contains output "Cannot find intern file for time/Duration")))
      (is (not (.contains output "Cannot find intern file for time/Date_Time")))
      (is (.contains output "\"Duration(5000 ms)\""))
      (is (.contains output "\"2026-03-13T10:30:00Z\"")))))

(deftest repl-intern-loads-checked-in-text-libraries
  (testing "REPL can load the checked-in Regex library and create a Regex object"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern text/Regex")
                   (repl/eval-code ctx "let rx: Regex := create Regex.compile(\"[a-z]+\")")
                   (repl/eval-code ctx "print(rx.to_string())"))]
      (is (not (.contains output "Cannot find intern file for text/Regex")))
      (is (.contains output "\"Regex(/[a-z]+/)\"")))))

(deftest file-eval-intern-alias-is-the-real-class-not-a-duplicate-test
  (testing "`intern X as Y` makes Y the same class as X, not a nominally
            distinct duplicate with an identical body — a value built via the
            alias still type-checks and runs, compiled and interpreted, where
            a second interned module expects X's real name (a diamond
            dependency: the regression seen when the alias and the real name
            are both in scope at once)"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-intern-alias-diamond-" (System/nanoTime)))
          account-file (io/file tmp-dir "Account.nex")
          report-file (io/file tmp-dir "Report.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs tmp-dir)
      (spit account-file "class Account
feature
  owner: String
create
  make(name: String) do owner := name end
end")
      (spit report-file "intern Account

class Report
feature
  print_owner(a: Account) do
    print(a.owner)
  end
end")
      (spit main-file "intern Account as Acc
intern Report

let a := create Acc.make(\"river\")
let r := create Report
r.print_owner(a)")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (not (.contains compiled "Undefined class")))
          (is (not (.contains compiled "Error")))
          (is (.contains compiled "\"river\"")))
        (finally
          (.delete account-file)
          (.delete report-file)
          (.delete main-file)
          (.delete tmp-dir))))))

(deftest repl-intern-alias-resolves-across-later-cells-test
  (testing "an `intern ... as` alias declared in one REPL cell still resolves
            in a later cell — including against a class a second interned
            module reaches unaliased (a diamond dependency). Regression test:
            the compiled-REPL session used to rebuild its class registry
            between cells from the real names only, silently dropping the
            alias a prior cell had established"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-intern-alias-repl-" (System/nanoTime)))
          account-file (io/file tmp-dir "Account.nex")
          report-file (io/file tmp-dir "Report.nex")
          ctx (repl/init-repl-context)
          source-id (.getPath (io/file tmp-dir "session.nex"))]
      (.mkdirs tmp-dir)
      (spit account-file "class Account
feature
  owner: String
create
  make(name: String) do owner := name end
end")
      (spit report-file "intern Account

class Report
feature
  print_owner(a: Account) do
    print(a.owner)
  end
end")
      (try
        (binding [repl/*type-checking-enabled* (atom true)
                  repl/*repl-var-types* (atom {})]
          (let [output (with-out-str
                         (repl/eval-code ctx "intern Account as Acc" source-id)
                         (repl/eval-code ctx "intern Report" source-id)
                         (repl/eval-code ctx "let a := create Acc.make(\"river\")" source-id)
                         (repl/eval-code ctx "let r := create Report" source-id)
                         (repl/eval-code ctx "r.print_owner(a)" source-id))]
            (is (not (.contains output "Undefined class: Acc")))
            (is (not (.contains output "Undefined class: Report")))
            (is (.contains output "\"river\""))))
        (finally
          (.delete account-file)
          (.delete report-file)
          (.delete tmp-dir))))))

(deftest repl-intern-brings-module-free-functions-into-later-cells
  (testing "REPL keeps an interned module's free functions callable in later cells"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern data/Result")
                   (repl/eval-code ctx "let r: Result[Integer, String] := create Ok[Integer, String].make(21)")
                   (repl/eval-code ctx "let d: Result[Integer, String] := result_map(r, fn (x: Integer): Integer do result := x * 2 end)")
                   (repl/eval-code ctx "print(d.unwrap_or(0))"))]
      (is (not (.contains output "Undefined function: result_map")))
      (is (.contains output "42")))))

;; --- Namespaces (docs/proposals/namespaces.md), Phase 2: ambiguity detection ---

(defn- spit-account-lib!
  "Write a minimal `Account` class into <tmp-dir>/lib/<path>/Account.nex, the
   layout `intern <path>/Account` resolves against (see find-intern-file)."
  [tmp-dir path field]
  (let [dir (io/file tmp-dir "lib" path)
        f (io/file dir "Account.nex")]
    (.mkdirs dir)
    (spit f (str "class Account\nfeature\n  " field ": Integer\ncreate\n  make(v: Integer) do "
                 field " := v end\nend"))
    f))

(deftest file-eval-intern-ambiguous-bare-class-name-is-a-compile-error-test
  (testing "two interned modules exporting the same bare class name is a
            compile-time ambiguity error, not a silent last-one-wins pick"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-ambiguous-" (System/nanoTime)))
          finance-file (spit-account-lib! tmp-dir "finance" "balance")
          billing-file (spit-account-lib! tmp-dir "billing" "id")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern finance/Account
intern billing/Account

let c := create Account.make(1)")
      (try
        (let [error (is (thrown? clojure.lang.ExceptionInfo (e/eval-file (.getPath main-file) {})))
              message (str (ex-message error) (some-> error ex-data str))]
          (is (.contains message "Ambiguous reference to 'Account'"))
          (is (.contains message "billing.Account"))
          (is (.contains message "finance.Account")))
        (finally
          (.delete finance-file)
          (.delete billing-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "finance"))
          (.delete (io/file tmp-dir "lib" "billing"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-own-class-shadows-ambiguous-interned-name-test
  (testing "a class declared directly in the entry file always wins over a
            same-named interned class — ambiguity is only ever between two
            *interned* things, never against the file's own definitions"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-own-wins-" (System/nanoTime)))
          finance-file (spit-account-lib! tmp-dir "finance" "balance")
          billing-file (spit-account-lib! tmp-dir "billing" "id")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern finance/Account
intern billing/Account

class Account
feature
  tag: Integer
create
  make(t: Integer) do tag := t end
end

let c := create Account.make(1)
print(c.tag)")
      (try
        (let [output (with-out-str (e/eval-file (.getPath main-file) {}))]
          (is (not (.contains output "Ambiguous reference")))
          (is (.contains output "1")))
        (finally
          (.delete finance-file)
          (.delete billing-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "finance"))
          (.delete (io/file tmp-dir "lib" "billing"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(defn- spit-function-lib!
  "Write a single free function `<name>` into
   <tmp-dir>/lib/<path>/<name>.nex — `intern <path>/<name>` resolves against
   this layout (see find-intern-file). BODY-EXPR is the function's `result :=
   <expr>` right-hand side, so two libs can each define a same-named function
   with a different, checkable behavior."
  [tmp-dir path name body-expr]
  (let [dir (io/file tmp-dir "lib" path)
        f (io/file dir (str name ".nex"))]
    (.mkdirs dir)
    (spit f (str "function " name "(n: Integer): Integer do result := " body-expr " end"))
    f))

;; Unlike a class, a colliding free function has no `intern ... as` escape
;; hatch — that only ever aliases a class (see process-intern and
;; resolve-interned*'s alias-type-alias, both of which match only against a
;; file's :classes). Instead, `trade.ship(x)`-style dot-qualified calls
;; (nex.walker/resolve-qualified-function-calls, nex.typechecker/check-
;; program's own ambiguous-function tracking) let two colliding interned
;; functions coexist: a *bare* reference to the colliding name is rejected
;; with a clear "Ambiguous reference" error, at the call site that actually
;; uses it, not the whole program merely for having interned both — and
;; each one is still reachable by its qualified name. This used to slip
;; straight past type-checking entirely and fail only once lowering emitted
;; two same-named, same-arity methods into one class file — an opaque JVM
;; ClassFormatError naming a mangled method, not the user's function.
(deftest file-eval-cross-file-bare-function-collision-is-an-ambiguous-reference-error-test
  (testing "a bare call to a name interned from two files is a compile-time
            \"Ambiguous reference\" error, on both backends, not a JVM
            ClassFormatError at bytecode emission"
    (doseq [interpret? [false true]]
      (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                             (str "nex-ns-fn-collision-" (System/nanoTime) "-" interpret?))
            lib1-file (spit-function-lib! tmp-dir "lib1" "foo" "n + 1")
            lib2-file (spit-function-lib! tmp-dir "lib2" "foo" "n - 1")
            main-file (io/file tmp-dir "main.nex")]
        (spit main-file "intern lib1/foo
intern lib2/foo

print(foo(10))")
        (try
          (let [ex (is (thrown? clojure.lang.ExceptionInfo
                                (e/eval-file (.getPath main-file) {:interpret? interpret?})))
                message (ex-message ex)]
            (is (.contains message "Ambiguous reference to 'foo'") message)
            (is (.contains message "lib1.foo") message)
            (is (.contains message "lib2.foo") message)
            (is (not (.contains message "ClassFormatError")) message))
          (finally
            (.delete lib1-file)
            (.delete lib2-file)
            (.delete main-file)
            (.delete (io/file tmp-dir "lib" "lib1"))
            (.delete (io/file tmp-dir "lib" "lib2"))
            (.delete (io/file tmp-dir "lib"))
            (.delete tmp-dir)))))))

(deftest file-eval-cross-file-function-collision-resolves-via-qualified-call-test
  (testing "each colliding interned function is still reachable — by its
            qualified name (`lib1.foo(x)`/`lib2.foo(x)`), on the compiled
            backend (nex.walker/resolve-qualified-function-calls is a
            compiled-backend-only rewrite; --interpret still hits the
            pre-existing, unrelated limitation of resolving a function-only
            intern target at all — see process-intern)"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-fn-qualified-call-" (System/nanoTime)))
          lib1-file (spit-function-lib! tmp-dir "lib1" "foo" "n + 1")
          lib2-file (spit-function-lib! tmp-dir "lib2" "foo" "n - 1")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern lib1/foo
intern lib2/foo

print(lib1.foo(10))
print(lib2.foo(10))")
      (try
        (let [output (with-out-str (e/eval-file (.getPath main-file)))]
          (is (= "11\n9\n" output)))
        (finally
          (.delete lib1-file)
          (.delete lib2-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "lib1"))
          (.delete (io/file tmp-dir "lib" "lib2"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-qualified-function-call-resolves-nested-path-test
  (testing "a multi-segment intern path resolves as a nested dot-chain call
            (`trade.core.ship(x)`), not just a single-segment one — even
            with no collision at all, since a qualified call works whether
            or not the bare name would have"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-fn-nested-path-" (System/nanoTime)))
          lib-file (spit-function-lib! tmp-dir "trade/core" "ship" "n * 100")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern trade/core/ship

print(trade.core.ship(3))")
      (try
        (let [output (with-out-str (e/eval-file (.getPath main-file)))]
          (is (= "300\n" output)))
        (finally
          (.delete lib-file)
          (.delete (io/file tmp-dir "lib" "trade" "core"))
          (.delete (io/file tmp-dir "lib" "trade"))
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-qualified-function-call-yields-to-a-same-named-local-test
  (testing "a bound local/param sharing the intern path's leading segment
            always wins — `trade.ship(x)` stays an ordinary (rejected as
            undefined) member-call chain on the local, never reinterpreted
            as the module path, anywhere the local's name is in scope at
            all (nex.walker/collect-possibly-bound-names is a coarse,
            whole-program check, not a precise per-scope one)"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-fn-shadow-" (System/nanoTime)))
          lib-file (spit-function-lib! tmp-dir "trade" "ship" "n + 1")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern trade/ship

function use(trade: Integer): Integer do
  result := trade + 1
end

print(trade.ship(10))")
      (try
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (e/eval-file (.getPath main-file))))]
          (is (.contains (ex-message ex) "Undefined variable: trade") (ex-message ex)))
        (finally
          (.delete lib-file)
          (.delete (io/file tmp-dir "lib" "trade"))
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-qualified-call-with-wrong-method-name-is-undefined-function-not-variable-test
  (testing "a qualified call whose base IS a real intern-path prefix but
            whose method name is wrong (typo'd or nonexistent) reports
            'Undefined function: trade.shpi' — not the misleading 'Undefined
            variable: trade' that nex.typechecker/reject-undefined-target!
            used to fall back to whenever the qualified-name rewrite
            (nex.walker/resolve-qualified-function-calls) found no exact
            match, even though 'trade' itself is a perfectly good module
            reference and the real problem is the method name"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-fn-wrong-method-" (System/nanoTime)))
          lib-file (spit-function-lib! tmp-dir "trade" "ship" "n + 1")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern trade/ship

print(trade.shpi(10))")
      (try
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (e/eval-file (.getPath main-file))))]
          (is (.contains (ex-message ex) "Undefined function: trade.shpi") (ex-message ex))
          (is (not (.contains (ex-message ex) "Undefined variable")) (ex-message ex)))
        (finally
          (.delete lib-file)
          (.delete (io/file tmp-dir "lib" "trade"))
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-cross-file-sibling-functions-unaffected-by-collision-check-test
  (testing "a non-colliding function from an interned file is unaffected —
            the collision check only ever flags a NAME that actually repeats"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-fn-no-collision-" (System/nanoTime)))
          lib1-file (spit-function-lib! tmp-dir "lib1" "foo" "n + 1")
          lib2-file (spit-function-lib! tmp-dir "lib2" "bar" "n - 1")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern lib1/foo
intern lib2/bar

print(foo(10))
print(bar(10))")
      (try
        (let [output (with-out-str (e/eval-file (.getPath main-file) {}))]
          (is (= "11\n9\n" output)))
        (finally
          (.delete lib1-file)
          (.delete lib2-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "lib1"))
          (.delete (io/file tmp-dir "lib" "lib2"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-own-function-shadows-ambiguous-interned-name-test
  (testing "a function declared directly in the entry file always wins over a
            same-named interned function — ambiguity is only ever between two
            *interned* functions, never against the file's own definitions,
            the exact function-side analog of
            file-eval-own-class-shadows-ambiguous-interned-name-test"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-fn-own-wins-" (System/nanoTime)))
          lib1-file (spit-function-lib! tmp-dir "lib1" "foo" "n + 1")
          lib2-file (spit-function-lib! tmp-dir "lib2" "foo" "n - 1")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern lib1/foo
intern lib2/foo

function foo(n: Integer): Integer do result := n * 100 end

print(foo(10))")
      (try
        (let [output (with-out-str (e/eval-file (.getPath main-file) {}))]
          (is (not (.contains output "Ambiguous reference")))
          (is (= "1000\n" output)))
        (finally
          (.delete lib1-file)
          (.delete lib2-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "lib1"))
          (.delete (io/file tmp-dir "lib" "lib2"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-function-sharing-a-file-with-an-interned-class-is-interned-too-test
  (testing "`intern path/Class` brings in the whole file, not just the class
            named after the path — a free function declared alongside that
            class in the same file arrives too, under its own bare name"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-fn-sharing-file-" (System/nanoTime)))
          dir (io/file tmp-dir "lib" "billing")
          lib-file (io/file dir "Account.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs dir)
      (spit lib-file "class Account
feature
  id: Integer
create
  make(v: Integer) do id := v end
end

function summarize(n: Integer): String do result := \"account \" + n.to_string end")
      (spit main-file "intern billing/Account

let a := create Account.make(1)
print(a.id)
print(summarize(5))")
      (try
        (let [output (with-out-str (e/eval-file (.getPath main-file) {}))]
          (is (= "1\n\"account 5\"\n" output)))
        (finally
          (.delete lib-file)
          (.delete dir)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-intern-as-does-not-alias-a-function-test
  (testing "`intern path/name as alias` only ever renames a class — a
            function is unaffected: the alias name stays undefined, and the
            bare name keeps working exactly as if `as` had never been written"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-fn-as-noop-" (System/nanoTime)))
          lib-file (spit-function-lib! tmp-dir "lib1" "foo" "n + 1")
          bare-call-file (io/file tmp-dir "bare.nex")
          alias-call-file (io/file tmp-dir "alias.nex")]
      (spit bare-call-file "intern lib1/foo as foo1

print(foo(10))")
      (spit alias-call-file "intern lib1/foo as foo1

print(foo1(10))")
      (try
        (let [bare-output (with-out-str (e/eval-file (.getPath bare-call-file) {}))]
          (is (= "11\n" bare-output)
              "the bare name still works, unaffected by the `as`"))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (e/eval-file (.getPath alias-call-file) {})))]
          (is (.contains (ex-message ex) "Undefined function: foo1") (ex-message ex)))
        (finally
          (.delete lib-file)
          (.delete (io/file tmp-dir "lib" "lib1"))
          (.delete bare-call-file)
          (.delete alias-call-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-qualified-function-call-matches-exact-dotted-name-not-a-prefix-test
  (testing "a single-segment and a multi-segment interned path can each
            define a function named `ship` without either qualified call
            reaching the wrong one — nex.walker/resolve-qualified-function-
            calls matches the whole `.`-chain exactly against one function's
            own qualified name; there is no prefix search to get confused by
            (the concern that motivated the discarded underscore-based
            design, not the `.`-chain one actually built — a chain's own
            syntax already fixes where the path ends and the call begins)"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-fn-exact-match-" (System/nanoTime)))
          top-file (spit-function-lib! tmp-dir "trade" "ship" "n + 1")
          nested-file (spit-function-lib! tmp-dir "trade/core" "ship" "n * 100")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern trade/ship
intern trade/core/ship

print(trade.ship(10))
print(trade.core.ship(10))")
      (try
        (let [output (with-out-str (e/eval-file (.getPath main-file) {}))]
          (is (= "11\n1000\n" output)))
        (finally
          (.delete top-file)
          (.delete nested-file)
          (.delete (io/file tmp-dir "lib" "trade" "core"))
          (.delete (io/file tmp-dir "lib" "trade"))
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-qualified-function-call-checks-argument-types-test
  (testing "a qualified call type-checks its arguments exactly like an
            ordinary one — nex.walker/resolve-qualified-function-calls
            rewrites the AST before type-checking runs, so a bad argument is
            still caught, not silently accepted just because the call was
            written qualified"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-fn-qualified-arg-check-" (System/nanoTime)))
          lib-file (spit-function-lib! tmp-dir "trade" "ship" "n + 1")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern trade/ship

print(trade.ship(\"not a number\"))")
      (try
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (e/eval-file (.getPath main-file) {})))]
          (is (.contains (ex-message ex) "Type checking failed") (ex-message ex)))
        (finally
          (.delete lib-file)
          (.delete (io/file tmp-dir "lib" "trade"))
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-path-qualified-intern-as-alias-resolves-collision-test
  (testing "`intern billing/Account as Billing_Account` DOES resolve a
            same-named collision, on both backends — Billing_Account's
            :type-expr points at the qualified identity \"billing.Account\"
            (nex.interpreter/resolve-interned*), not the bare, still-ambiguous
            \"Account\", so referencing only the alias never touches the bare
            name at all"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-alias-fix-" (System/nanoTime)))
          finance-file (spit-account-lib! tmp-dir "finance" "balance")
          billing-file (spit-account-lib! tmp-dir "billing" "id")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern finance/Account
intern billing/Account as Billing_Account

let b := create Billing_Account.make(2)
print(b.id)")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "2")))
        (finally
          (.delete finance-file)
          (.delete billing-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "finance"))
          (.delete (io/file tmp-dir "lib" "billing"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-both-sides-aliased-intern-resolves-collision-test
  (testing "both colliding interns aliased (`intern x/A as x_a`, `intern
            y/A as y_a`) each resolve to their OWN class independently, on
            both backends — neither alias falls through to the shared,
            ambiguous bare name `A`"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-both-aliased-" (System/nanoTime)))
          x-dir (io/file tmp-dir "lib" "x")
          y-dir (io/file tmp-dir "lib" "y")
          x-file (io/file x-dir "A.nex")
          y-file (io/file y-dir "A.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs x-dir)
      (.mkdirs y-dir)
      (spit x-file "class A
feature
  tag: String
create
  make(t: String) do this.tag := t end
end")
      (spit y-file "class A
feature
  label: String
create
  make(l: String) do this.label := l end
end")
      (spit main-file "intern x/A as x_a
intern y/A as y_a

let p := create x_a.make(\"from-x\")
let q := create y_a.make(\"from-y\")
print(p.tag)
print(q.label)")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "from-x"))
          (is (.contains compiled "from-y")))
        (finally
          (.delete x-file)
          (.delete y-file)
          (.delete main-file)
          (.delete x-dir)
          (.delete y-dir)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest interpret-nested-bare-intern-resolves-relative-to-its-own-file-test
  (testing "under --interpret, a BARE `intern Sibling` inside a file that was
            itself reached through a path-qualified intern resolves relative
            to THAT FILE's own directory, not the entry script's — the
            interpreted-mode analog of
            file-eval-diamond-dependency-is-not-ambiguous-test, which only
            ever exercised the compiled backend. Regression test:
            nex.interpreter/process-intern evaluated a just-loaded file's AST
            without ever updating ctx's :debug-source to that file's own
            path first, so intern-search-roots (which explicitly documents
            this exact nested-intern scenario as its reason to exist) kept
            resolving every nested intern relative to the ENTRY file's
            directory, however deep the nesting — 'Cannot find intern file
            for Sibling' even though Sibling.nex sits right next to the file
            that interns it. The static analysis path (resolve-interned*,
            what the compiled backend and type-checking both use) already
            rebinds :debug-source per recursive call, so this bug was
            invisible on the compiled backend; only --interpret builds a
            single shared ctx it forgets to update."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-interpret-nested-bare-" (System/nanoTime)))
          lib-dir (io/file tmp-dir "lib" "pathA")
          sibling-file (io/file lib-dir "Sibling.nex")
          wrapper-file (io/file lib-dir "Wrapper.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs lib-dir)
      (spit sibling-file "class Sibling
feature
  tag: String
create
  make() do tag := \"sibling\" end
end")
      (spit wrapper-file "intern Sibling

class Wrapper
feature
  greet(): String do result := create Sibling.make.tag end
end")
      (spit main-file "intern pathA/Wrapper

let w := create Wrapper
print(w.greet)")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (not (.contains interpreted "Cannot find intern file for Sibling")) interpreted)
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "sibling")))
        (finally
          (.delete sibling-file)
          (.delete wrapper-file)
          (.delete main-file)
          (.delete lib-dir)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-qualified-generic-inherit-conforms-to-bare-generic-param-type-test
  (testing "a class reached through a QUALIFIED generic `inherit` clause
            (`inherit flex/Spec[Integer]`, walked to a :parent string
            \"flex.Spec\") still conforms to a parameter typed with the
            SAME class's bare, non-qualified generic name (`Spec[Integer]`)
            — the generic-type analog of
            file-eval-qualified-reference-to-non-colliding-class-test,
            which only ever covered non-parameterized types. Regression
            test: nex.typechecker/ancestor-instantiation (which walks a
            generic heir's `inherit` chain looking for the target base
            class) compared class names with raw `=`, so \"flex.Spec\"
            never matched a lookup for bare \"Spec\" even though
            class-name-identity — used everywhere else two spellings of the
            same class must compare equal — already normalizes exactly
            this pair. class-subtype? needed the identical fix for
            non-generic inheritance; this is the same defect one level up,
            in the parameterized-type conformance path
            (generic-class-conforms?) that only generic classes go through."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-qualified-generic-inherit-" (System/nanoTime)))
          lib-dir (io/file tmp-dir "lib" "flex")
          lib-file (io/file lib-dir "Spec.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs lib-dir)
      (spit lib-file "deferred class Spec[T]
feature holds(item: T): Boolean deferred
end

class Over_Amount inherit flex/Spec[Integer]
feature
  threshold: Integer
create make(t: Integer) do threshold := t end
feature
  holds(item: Integer): Boolean do result := item >= threshold end
end

function check(s: Spec[Integer], x: Integer): Boolean do result := s.holds(x) end")
      (spit main-file "intern flex/Spec

print(check(create Over_Amount.make(10), 15))")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "true")))
        (finally
          (.delete lib-file)
          (.delete lib-dir)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-sealed-match-exhaustiveness-covers-qualified-inherit-heir-test
  (testing "a sealed hierarchy's variant declared with a QUALIFIED `inherit`
            clause (`inherit shapes/Shape`, walked to the :parent string
            \"shapes.Shape\") still counts toward match exhaustiveness
            against the bare sealed type name (`Shape`) — omitting it from
            a `match` with no `else` must be rejected as non-exhaustive,
            exactly as if the variant had been declared `inherit Shape`
            directly. Regression test: nex.typechecker/find-sealed-
            subclasses compared a heir's :parent with raw `=`, so
            \"shapes.Shape\" never matched a lookup for bare \"Shape\" —
            such a heir was silently absent from the known-variants set, so
            a match omitting it compiled and ran anyway instead of being
            rejected. The same identity gap class-subtype? and
            ancestor-instantiation needed fixing for ordinary and generic
            subtyping, one level up in the exhaustiveness check that only
            sealed `match` goes through."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-sealed-qualified-inherit-" (System/nanoTime)))
          lib-dir (io/file tmp-dir "lib" "shapes")
          lib-file (io/file lib-dir "Shape.nex")
          non-exhaustive-file (io/file tmp-dir "non_exhaustive.nex")
          exhaustive-file (io/file tmp-dir "exhaustive.nex")]
      (.mkdirs lib-dir)
      (spit lib-file "sealed deferred class Shape
end

class Circle inherit shapes/Shape
feature radius: Integer create make(r: Integer) do radius := r end
end

class Square inherit shapes/Shape
feature side: Integer create make(s: Integer) do side := s end
end")
      (spit non-exhaustive-file "intern shapes/Shape

function describe(s: Shape): String
do
  match s of
    Circle as c then result := \"circle\"
  end
end

print(describe(create Circle.make(5)))")
      (spit exhaustive-file "intern shapes/Shape

function describe(s: Shape): String
do
  match s of
    Circle as c then result := \"circle\"
    Square as sq then result := \"square\"
  end
end

print(describe(create Circle.make(5)))
print(describe(create Square.make(3)))")
      (try
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (e/eval-file (.getPath non-exhaustive-file) {})))]
          (is (.contains (ex-message ex) "does not cover all variants") (ex-message ex))
          (is (.contains (ex-message ex) "Square") (ex-message ex)))
        (let [compiled (with-out-str (e/eval-file (.getPath exhaustive-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath exhaustive-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "circle"))
          (is (.contains compiled "square")))
        (finally
          (.delete lib-file)
          (.delete lib-dir)
          (.delete non-exhaustive-file)
          (.delete exhaustive-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-match-bound-generic-field-resolves-through-qualified-inherit-test
  (testing "a match clause's bound variable, whose class inherits its sealed
            parent GENERICALLY through a QUALIFIED `inherit` clause
            (`inherit pathA/Box[U]`, walked to the :parent string
            \"pathA.Box\"), still gets its generic field types substituted
            from the matched subject's real type arguments — not erased to
            Any. Regression test: nex.typechecker/match-clause-binding-type
            matched a clause class's `:parent` against the subject's base
            type with raw `=`, so \"pathA.Box\" never matched a lookup for
            the bare `Box` the enclosing function actually matched on; the
            function silently fell back to the unsubstituted bare class
            name, so `f.value` (declared `value: U`) came back typed Any
            instead of Integer inside the clause body — a real type-safety
            hole (an Integer operation on Any slips past normal checking)
            hiding behind what looks like a harmless fallback path. Same
            identity gap as class-subtype?, ancestor-instantiation, and
            find-sealed-subclasses, one level up in generic binding-type
            reconstruction."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-match-generic-qualified-" (System/nanoTime)))
          lib-dir (io/file tmp-dir "lib" "pathA")
          lib-file (io/file lib-dir "Box.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs lib-dir)
      (spit lib-file "sealed deferred class Box[T]
end

class Full[U] inherit pathA/Box[U]
feature
  value: U
create make(v: U) do value := v end
end

class Empty[U] inherit pathA/Box[U]
end")
      (spit main-file "intern pathA/Box

function unwrap(b: Box[Integer]): Integer
do
  match b of
    Full as f then result := f.value + 1
    Empty as e then result := 0
  end
end

print(unwrap(create Full[Integer].make(42)))")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "43")))
        (finally
          (.delete lib-file)
          (.delete lib-dir)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-self-inheritance-via-qualified-self-reference-is-rejected-test
  (testing "`class Loop inherit p/Loop` — a class naming ITSELF as its own
            parent, but through its own qualified path rather than bare —
            is still rejected as self-inheritance. Hardening test for
            nex.typechecker/check-inheritance, which now compares PARENT
            against CLASS-NAME through class-name-identity rather than raw
            `=`, the same normalization class-subtype?, ancestor-
            instantiation, find-sealed-subclasses and match-clause-binding-
            type all needed for their own equivalent comparisons. This
            specific case was independently caught even before that fix
            (the qualified-registration pass always compares two identically
            -qualified spellings), but check-inheritance's comparisons are
            now consistent with the rest of the identity-normalized checks
            rather than relying on that coincidence."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-ns-self-inherit-qualified-" (System/nanoTime)))
          lib-dir (io/file tmp-dir "lib" "p")
          lib-file (io/file lib-dir "Loop.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs lib-dir)
      (spit lib-file "class Loop inherit p/Loop
feature x: Integer
end")
      (spit main-file "intern p/Loop

print(1)")
      (try
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (e/eval-file (.getPath main-file) {})))]
          (is (.contains (ex-message ex) "cannot inherit from itself") (ex-message ex)))
        (finally
          (.delete lib-file)
          (.delete lib-dir)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-diamond-dependency-is-not-ambiguous-test
  (testing "two different `intern` paths that resolve to the SAME canonical
            file (a diamond dependency) are not a collision — resolve-interned*
            already dedupes those by canonical path, so only one qualified
            name ever reaches the ambiguity check. `finance/Account` lives
            under a fake ~/.nex/deps so it resolves identically regardless of
            which file (main.nex or wrapper/Money_Box.nex, in different
            directories) does the interning — the diamond shape itself, not a
            coincidence of both interns sharing one directory."
    (let [fake-home (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-diamond-home-" (System/nanoTime)))
          finance-file (spit-account-lib! fake-home "finance" "balance")
          tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-diamond-" (System/nanoTime)))
          wrapper-file (io/file tmp-dir "lib" "wrapper" "Money_Box.nex")
          main-file (io/file tmp-dir "main.nex")
          original-home (System/getProperty "user.home")]
      (.mkdirs (.getParentFile wrapper-file))
      ;; spit-account-lib! writes to <root>/lib/finance/Account.nex; move it to
      ;; the ~/.nex/deps/finance layout find-intern-file also searches.
      (let [deps-dir (io/file fake-home ".nex" "deps" "finance")
            deps-file (io/file deps-dir "Account.nex")]
        (.mkdirs deps-dir)
        (io/copy finance-file deps-file)
        (.delete finance-file)
        (.delete (io/file fake-home "lib" "finance"))
        (.delete (io/file fake-home "lib")))
      (spit wrapper-file "intern finance/Account

class Money_Box
feature
  peek(a: Account): Integer do
    result := a.balance
  end
end")
      (spit main-file "intern finance/Account
intern wrapper/Money_Box

let c := create Account.make(1)
let box := create Money_Box
print(box.peek(c))")
      (try
        (System/setProperty "user.home" (.getAbsolutePath fake-home))
        (let [output (with-out-str (e/eval-file (.getPath main-file) {}))]
          (is (not (.contains output "Ambiguous reference")))
          (is (.contains output "1")))
        (finally
          (System/setProperty "user.home" original-home)
          (.delete (io/file fake-home ".nex" "deps" "finance" "Account.nex"))
          (.delete (io/file fake-home ".nex" "deps" "finance"))
          (.delete (io/file fake-home ".nex" "deps"))
          (.delete (io/file fake-home ".nex"))
          (.delete fake-home)
          (.delete wrapper-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "wrapper"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

;; --- Namespaces (docs/proposals/namespaces.md), Phase 3: qualified reference syntax ---

(deftest file-eval-qualified-reference-resolves-collision-test
  (testing "a qualified reference (`finance/Account`, walked to \"finance.Account\")
            disambiguates a genuine bare-name collision end to end — type
            annotation, `create`, and field access all resolve to the RIGHT
            one of the two same-named classes, constructing real, distinct
            objects, on both the compiled and the interpreted backend"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-qualified-" (System/nanoTime)))
          finance-file (spit-account-lib! tmp-dir "finance" "balance")
          billing-file (spit-account-lib! tmp-dir "billing" "id")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern finance/Account
intern billing/Account

let a: finance/Account := create finance/Account.make(1)
let b: billing/Account := create billing/Account.make(2)
print(a.balance)
print(b.id)")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "1"))
          (is (.contains compiled "2")))
        (finally
          (.delete finance-file)
          (.delete billing-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "finance"))
          (.delete (io/file tmp-dir "lib" "billing"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-qualified-reference-to-non-colliding-class-test
  (testing "a qualified reference to a class that ISN'T ambiguous still
            resolves correctly, and interchangeably with a bare reference to
            the SAME class — regression coverage for a real bug found while
            building this: naively preferring the qualified name for every
            interned class's own JVM/runtime identity (not just a genuinely
            colliding one) broke the common, non-colliding case, since every
            *other*, ordinary bare reference to it in the program still
            expects its plain bare identity"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-non-colliding-" (System/nanoTime)))
          account-file (spit-account-lib! tmp-dir "finance" "balance")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern finance/Account

let a: Account := create finance/Account.make(7)
let b: finance/Account := create Account.make(8)
print(a.balance)
print(b.balance)")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "7"))
          (is (.contains compiled "8")))
        (finally
          (.delete account-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "finance"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-qualified-match-clause-on-sealed-hierarchy-test
  (testing "a qualified match clause (`when shapes/Circle then ...`) dispatches
            correctly against a real object of that (non-colliding) class, on
            both backends — regression coverage for a second bug found while
            building this: runtime match/convert dispatch compares plain Nex
            type-name *strings* against the value's own embedded runtime type
            name, a separate mechanism from JVM identity that needed its own
            fix once the class-identity fix above landed"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-qualified-match-" (System/nanoTime)))
          shape-dir (io/file tmp-dir "lib" "shapes")
          shape-file (io/file shape-dir "Shape.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs shape-dir)
      (spit shape-file "sealed deferred class Shape
end

class Circle
inherit Shape
feature
  radius: Integer
create
  make(r: Integer) do this.radius := r end
end

class Square
inherit Shape
feature
  side: Integer
create
  make(s: Integer) do this.side := s end
end")
      (spit main-file "intern shapes/Shape

let s: Shape := create shapes/Circle.make(5)
match s of
  shapes/Circle then print(\"circle\")
  shapes/Square then print(\"square\")
end")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "circle"))
          (is (not (.contains compiled "square"))))
        (finally
          (.delete shape-file)
          (.delete shape-dir)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-aliased-collision-used-inside-a-constructor-body-test
  (testing "a path-qualified alias resolving a bare-name collision (two
            different libraries both named Counter, at multi-segment paths)
            works when referenced from INSIDE a class's own constructor body,
            not just at top level — regression test: visible-class-map's
            qualified-name recovery only reached the top-level lowering env;
            every nested lowering scope (a constructor, a method, ...) builds
            its own :classes list independently via its own make-lowering-env
            call, and the class that lost the bare-name collapse was simply
            absent from it, so a nested `create C1.make` failed to lower even
            though the identical top-level reference worked fine"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-nested-alias-" (System/nanoTime)))
          math-dir (io/file tmp-dir "lib" "math")
          cc-dir (io/file tmp-dir "lib" "cc" "bb")
          math-file (io/file math-dir "Counter.nex")
          cc-file (io/file cc-dir "Counter.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs math-dir)
      (.mkdirs cc-dir)
      (spit math-file "class Counter
  create
    make() do
      count := 0
    end
  feature
    count: Integer
    increment() do
      count := count + 1
    end
    value(): Integer do
      result := count
    end
end")
      (spit cc-file "class Counter
create
  make do print(\"COUNTER\") end
end")
      (spit main-file "intern math/Counter as C1
intern cc/bb/Counter as C

class Main
  create
    make() do
      let c := create C1.make
      c.increment
      c.increment
      print(c.value)
    end
  end

  create Main.make")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "2")))
        (finally
          (.delete math-file)
          (.delete cc-file)
          (.delete main-file)
          (.delete math-dir)
          (.delete cc-dir)
          (.delete (io/file tmp-dir "lib" "cc"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(defn- spit-widget-lib!
  "Write a minimal `Widget` class into <tmp-dir>/lib/<path>/Widget.nex — a
   String `tag` field set from `tag-value`, plus a `greet()` method that
   reads it. Used to exercise a colliding, aliased qualified class from
   several different nested lowering scopes (method body, class constant,
   `inherit` clause) — each builds its own lowering env independently (see
   `nex.lower/make-lowering-env` call sites), which is exactly the class of
   bug `file-eval-aliased-collision-used-inside-a-constructor-body-test`,
   above, found and fixed for the constructor-body case specifically."
  [tmp-dir path tag-value]
  (let [dir (io/file tmp-dir "lib" path)
        f (io/file dir "Widget.nex")]
    (.mkdirs dir)
    ;; tag has a default value (not attachable/must-init) so a subclass's own
    ;; constructor never needs to touch it — Nex only allows a field write from
    ;; its OWN declaring class, even for a public field, so a subclass writing
    ;; an inherited field is invalid Nex regardless of namespacing (confirmed
    ;; separately with a non-colliding, non-interned pair of classes) and
    ;; would only get in the way of the inherit-parent test below.
    (spit f (str "class Widget\nfeature\n  tag: String = \"" tag-value
                 "\"\ncreate\n  make() do end\nfeature\n  greet(): String do result := \"hi from \" + tag end\nend"))
    f))

(deftest file-eval-aliased-collision-used-inside-a-method-body-test
  (testing "a path-qualified alias resolving a bare-name collision works when
            referenced from inside an ordinary METHOD body (not a
            constructor) — a different make-lowering-env call site
            (lower-function, shared by methods and free functions) than the
            constructor-body regression above"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-method-alias-" (System/nanoTime)))
          a-file (spit-widget-lib! tmp-dir "widgets_a" "a")
          b-file (spit-widget-lib! tmp-dir "widgets_b" "b")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern widgets_a/Widget as WA
intern widgets_b/Widget as WB

class Main
create
  make() do end
feature
  run() do
    let a := create WA.make
    let b := create WB.make
    print(a.greet())
    print(b.greet())
  end
end

let m := create Main.make
m.run()")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "hi from a"))
          (is (.contains compiled "hi from b")))
        (finally
          (.delete a-file)
          (.delete b-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "widgets_a"))
          (.delete (io/file tmp-dir "lib" "widgets_b"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-aliased-collision-used-in-a-class-constant-test
  (testing "a path-qualified alias resolving a bare-name collision works when
            referenced from inside a class CONSTANT's initializer — the
            constant-env make-lowering-env call site inside lower-class-def,
            distinct from both the constructor-body and method-body cases"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-constant-alias-" (System/nanoTime)))
          a-file (spit-widget-lib! tmp-dir "widgets_a" "a")
          b-file (spit-widget-lib! tmp-dir "widgets_b" "b")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern widgets_a/Widget as WA
intern widgets_b/Widget as WB

class Holder
feature
  default_widget = create WA.make
  describe(): String do result := default_widget.greet() end
end

let h := create Holder
print(h.describe())")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "hi from a")))
        (finally
          (.delete a-file)
          (.delete b-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "widgets_a"))
          (.delete (io/file tmp-dir "lib" "widgets_b"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-aliased-collision-used-as-inherit-parent-test
  (testing "a path-qualified alias resolving a bare-name collision works as
            an `inherit` parent, with a subclass calling a method it
            inherits from that parent — exercises parent-chain metadata
            resolution (resolve-parent-metas / direct-parent-method-map),
            architecturally distinct from resolving a class's own identity"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-inherit-alias-" (System/nanoTime)))
          a-file (spit-widget-lib! tmp-dir "widgets_a" "a")
          b-file (spit-widget-lib! tmp-dir "widgets_b" "b")
          main-file (io/file tmp-dir "main.nex")]
      (spit main-file "intern widgets_a/Widget as WA
intern widgets_b/Widget as WB

class Special
inherit WA
create
  make() do end
feature
  shout(): String do result := greet() + \"!\" end
end

let s := create Special.make
print(s.shout())")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "hi from a!")))
        (finally
          (.delete a-file)
          (.delete b-file)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib" "widgets_a"))
          (.delete (io/file tmp-dir "lib" "widgets_b"))
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-aliased-generic-class-constructs-with-explicit-type-args-test
  (testing "an aliased GENERIC class constructs correctly when given explicit
            type arguments (`create BA[Integer].make(...)`) — regression test
            for a bug found while testing the collision cases above, on the
            interpreter specifically: nex.interpreter/create-user-object
            computes the specialized class's registry key from the reference
            actually used (`class-name`, e.g. \"BA\" -> \"BA[Integer]\"), but
            specialize-class computed its OWN internal name from the
            TEMPLATE's own :name (the alias target's real name, e.g. \"Box\"
            -> \"Box[Integer]\") and register-specialized-class trusted that
            instead — a key mismatch that made the freshly-registered
            specialization unfindable under the name the caller was about to
            look it up by, falling through to the Java-interop fallback and
            failing with \"Undefined class\". Not collision-specific — this
            reproduces with only ONE interned library, no bare-name collision
            at all — but found via, and fixed alongside, the namespaces work,
            so covered here for the same reason the other tests in this file
            are: it is exactly the `intern ... as` combination those changes
            made it easy to reach."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-generic-alias-" (System/nanoTime)))
          box-dir (io/file tmp-dir "lib" "boxes")
          box-file (io/file box-dir "Box.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs box-dir)
      (spit box-file "class Box[T]
feature
  value: T
create
  make(v: T) do this.value := v end
end")
      (spit main-file "intern boxes/Box as BA

let a := create BA[Integer].make(1)
print(a.value)")
      (try
        (let [compiled (with-out-str (e/eval-file (.getPath main-file) {}))
              interpreted (with-out-str (e/eval-file (.getPath main-file) {:interpret? true}))]
          (is (= interpreted compiled) "compiled and interpreted output must agree")
          (is (.contains compiled "1")))
        (finally
          (.delete box-file)
          (.delete box-dir)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-syntax-error-in-interned-file-names-that-file-test
  (testing "a syntax error in a file the entry program INTERNS (not the file
            it was actually run on) is reported against the interned file's
            own path and source — not silently misattributed to the entry
            file, which is all a bare ParseError (carrying only a line/column,
            no file identity) leaves the top-level handler able to assume once
            more than one file can be involved in a single run. Regression
            test for exactly this: `nex ds.nex` reported a syntax error 60
            lines into an interned library as \"Line 7\" of ds.nex itself (ds.nex
            is 7 lines long), pointing a caret at the END of an unrelated
            assert statement — nex.interpreter/parse-interned-file now wraps
            the ParseError with the file/source it actually came from, on
            both backends (type-checking runs first regardless), checked here
            via the raw exception rather than captured stdout, since the
            point is what the THROWN diagnostic identifies, not how a
            particular caller happens to print it (see nex.eval/-main and
            nex.repl/eval-code for the two that do)."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-intern-syntax-error-" (System/nanoTime)))
          lib-dir (io/file tmp-dir "lib" "broken_lib")
          lib-file (io/file lib-dir "Oops.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs lib-dir)
      ;; `elseif ... := ...` — `:=` (assignment) where a boolean condition
      ;; (comparison `=`) is required — a real, if easy to miss, mistake, not
      ;; a contrived one; this is what surfaced the bug (docs/proposals/
      ;; namespaces.md is unrelated — a plain, non-generic class is enough).
      (spit lib-file "class Oops
feature
  check(n: Integer): Boolean do
    if n = 0 then
      result := true
    elseif n := 1 then
      result := false
    end
  end
end")
      (spit main-file "intern broken_lib/Oops

let o := create Oops
print(o.check(0))")
      (try
        (doseq [interpret? [false true]]
          (let [ex (try (e/eval-file (.getPath main-file) {:interpret? interpret?})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (some? ex) (str "expected a syntax error, interpret?=" interpret?))
            (when ex
              (let [data (ex-data ex)]
                (is (:nex/intern-parse-error data))
                (is (= (.getCanonicalPath lib-file) (:file-path data)))
                (let [rendered (with-out-str
                                 (p/format-parse-errors (:parse-error data) (:source data) 0))]
                  (is (.contains rendered "elseif n := 1 then")))))))
        (finally
          (.delete lib-file)
          (.delete lib-dir)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-type-error-inside-interned-file-names-that-file-test
  (testing "a type error deep in an interned file's own method body (an
            undefined variable, found during check-class) is reported
            against that file's own path, on both backends — regression test
            for the same class of bug as the interned-syntax-error test
            above, one layer later: `nex ds.nex` reported \"Type error at
            line 65, column 39: Undefined variable: xs\" with no indication
            that line 65 belonged to a library ds.nex interned, not ds.nex
            itself (7 lines long). nex.interpreter/resolve-interned* now
            stamps :source-file onto every class/function it returns
            (alongside :qualified-name), and check-program wraps each one's
            processing in nex.typechecker/with-source-file, which — unlike
            the interned-syntax-error case, an exception thrown once per
            file being parsed — annotates whichever TypeError(s) escape,
            unless already stamped by an inner, more specific
            with-source-file for a DIFFERENT interned class this one's own
            body happens to reference."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-interned-type-error-" (System/nanoTime)))
          lib-dir (io/file tmp-dir "lib" "thing_lib")
          lib-file (io/file lib-dir "Thing.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs lib-dir)
      (spit lib-file "class Thing
feature
  greet(): String do
    result := \"hi \" + missing_name
  end
create
  make() do end
end")
      (spit main-file "intern thing_lib/Thing

let t := create Thing
print(t.greet())")
      (try
        (doseq [interpret? [false true]]
          (let [ex (try (e/eval-file (.getPath main-file) {:interpret? interpret?})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (some? ex) (str "expected a type error, interpret?=" interpret?))
            (when ex
              (is (.contains (.getMessage ex) (.getCanonicalPath lib-file)))
              (is (.contains (.getMessage ex) "Undefined variable: missing_name"))
              (is (not (.contains (.getMessage ex) (.getCanonicalPath main-file)))
                  "must not ALSO be misattributed to the entry file"))))
        (finally
          (.delete lib-file)
          (.delete lib-dir)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest file-eval-undefined-type-inside-interned-file-names-that-file-test
  (testing "an undefined TYPE reference (a field's declared type naming a
            class that doesn't exist) inside an interned file is reported
            against that file's own path — collect-undefined-type-errors is
            a separate, whole-program, batch pass (not one class/function at
            a time like with-source-file above), so it needed its own
            per-declaration :source-file tracking rather than reusing
            with-source-file's wrapping."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-ns-interned-undefined-type-" (System/nanoTime)))
          lib-dir (io/file tmp-dir "lib" "thing_lib")
          lib-file (io/file lib-dir "Thing.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs lib-dir)
      (spit lib-file "class Thing
feature
  bad: No_Such_Type
create
  make() do end
end")
      (spit main-file "intern thing_lib/Thing

let t := create Thing")
      (try
        (doseq [interpret? [false true]]
          (let [ex (try (e/eval-file (.getPath main-file) {:interpret? interpret?})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (some? ex) (str "expected a type error, interpret?=" interpret?))
            (when ex
              (is (.contains (.getMessage ex) (.getCanonicalPath lib-file)))
              (is (.contains (.getMessage ex) "Undefined type: No_Such_Type")))))
        (finally
          (.delete lib-file)
          (.delete lib-dir)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))

(deftest cli-run-resolves-nested-path-qualified-intern-from-a-sibling-lib-directory-test
  (testing "a `nex script.nex` run resolves a path-qualified intern reached
            transitively (script -> lib A -> lib B), where B sits in a
            *different* lib subdirectory than A, not just alongside it.

            This exercises the real `bin/nex` CLI path specifically: it
            exports the project root via the NEX_USER_DIR env var and never
            sets the `nex.user.dir` system property (that's REPL-only), so
            it has to run as a subprocess — nex.interpreter/find-intern-file
            reads System/getenv directly, which this JVM's in-process tests
            can't fake (see examples-smoke-test's run-failure-with-nex-user-dir
            for the same constraint). Before the fix, intern-search-roots only
            fell back to the `nex.user.dir` property, so a nested intern (one
            resolved while a just-interned lib file's own :debug-source, not
            the script's, was current) lost the project root entirely once it
            needed a lib/<path> outside its own directory: source-dir pointed
            at the interning file's own directory and pwd resolved to
            NEX_HOME (bin/nex cd's there before starting the JVM), so
            lib/transactions/account.nex could not reach lib/units/Money.nex."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "nex-nested-intern-" (System/nanoTime)))
          money-dir (io/file tmp-dir "lib" "units")
          money-file (io/file money-dir "Money.nex")
          account-dir (io/file tmp-dir "lib" "transactions")
          account-file (io/file account-dir "account.nex")
          main-file (io/file tmp-dir "main.nex")]
      (.mkdirs money-dir)
      (.mkdirs account-dir)
      (spit money-file "class Money
create
  make(a: Real) do
    amount := a
  end
feature
  amount: Real
end")
      (spit account-file "intern units/Money

class Account
create
  make(b: Money) do
    balance := b
  end
feature
  balance: Money
end")
      (spit main-file "intern transactions/account as Account

let a := create Account.make(create Money.make(100.0))
print(a.balance.amount)")
      (try
        (let [pb (ProcessBuilder. ^"[Ljava.lang.String;"
                  (into-array String
                              ["java" "-cp" (System/getProperty "java.class.path")
                               "clojure.main" "-m" "nex.eval" (.getPath main-file)]))]
          (.put (.environment pb) "NEX_USER_DIR" (.getPath tmp-dir))
          (.redirectErrorStream pb true)
          (let [proc (.start pb)
                output (slurp (.getInputStream proc))
                code (.waitFor proc)]
            (is (not (.contains output "Cannot find intern file")) output)
            (is (zero? code) output)
            (is (.contains output "100.0") output)))
        (finally
          (.delete money-file)
          (.delete money-dir)
          (.delete account-file)
          (.delete account-dir)
          (.delete main-file)
          (.delete (io/file tmp-dir "lib"))
          (.delete tmp-dir))))))
