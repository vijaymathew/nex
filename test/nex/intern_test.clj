(ns nex.intern-test
  "Tests for intern statement to load external classes"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
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
