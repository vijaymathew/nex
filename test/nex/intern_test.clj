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
