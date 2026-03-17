(ns nex.compiler.jvm.repl-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.repl :as repl]))

(deftest repl-compiled-backend-command-and-fallback-test
  (testing "experimental compiled backend can be enabled and falls back for unsupported forms"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :interpreter)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str
                (repl/handle-command ctx0 ":backend compiled"))
            ctx1 (binding [*out* (java.io.StringWriter.)]
                   (repl/eval-code ctx0 "let x: Integer := 40"))
            output (with-out-str
                     (repl/eval-code ctx1 "x + 2"))]
        (is (= :compiled @repl/*repl-backend*))
        (is (str/includes? output "42"))))))

(deftest repl-compiled-backend-status-command-test
  (testing "backend status command reports the current backend"
    (binding [repl/*repl-backend* (atom :compiled)]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/handle-command ctx ":backend status"))]
        (is (str/includes? output "COMPILED"))))))
