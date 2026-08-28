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
      (is (.contains output "\"Directory(.") ))))

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
