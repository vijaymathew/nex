(ns nex.compiler.jvm.interpreter-unlink-test
  "The compiled path must not need the tree-walking interpreter (see
   docs/md/BACKEND_ALIGNMENT.md, D3c). Checked in a fresh JVM, since this test
   suite itself has long since loaded nex.interpreter."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- run-clojure-in-fresh-jvm
  [form]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        pb (ProcessBuilder. ^java.util.List [java "-cp" (System/getProperty "java.class.path")
                                             "clojure.main" "-e" (pr-str form)])]
    (.redirectErrorStream pb true)
    (let [proc (.start pb)
          output (slurp (.getInputStream proc))]
      (.waitFor proc)
      {:exit (.exitValue proc) :out output})))

(deftest compiling-a-program-does-not-load-the-interpreter-test
  (testing "type-checking and compiling a whole file (with an intern) leaves nex.interpreter unloaded"
    (let [dir (java.io.File/createTempFile "nex-unlink" "")
          _ (.delete dir)
          _ (.mkdirs dir)
          helper (java.io.File. dir "helper.nex")
          app (java.io.File. dir "app.nex")]
      (try
        (spit helper "class Helper\nfeature\n  twice(n: Integer): Integer do result := n * 2 end\nend\n")
        (spit app "intern Helper\nlet h := create Helper\nprint(h.twice(21))\n")
        (let [{:keys [exit out]}
              (run-clojure-in-fresh-jvm
               `(do (require 'nex.eval 'nex.compiler.jvm.file 'nex.parser)
                    (let [path# ~(.getCanonicalPath app)
                          compiled# ((resolve 'nex.compiler.jvm.file/compile-ast)
                                     path# ((resolve 'nex.parser/ast) (slurp path#)))]
                      (println "classes" (pos? (count (:classes compiled#))))
                      (println "interpreter-loaded" (boolean (find-ns 'nex.interpreter))))
                    (shutdown-agents)))]
          (is (= 0 exit) out)
          (is (str/includes? out "classes true") out)
          (is (str/includes? out "interpreter-loaded false") out))
        (finally
          (doseq [f (reverse (file-seq dir))] (.delete f)))))))

(deftest running-a-program-does-not-load-the-interpreter-test
  (testing "running a whole file compiled — closures that capture, spawn, builtin
            callbacks into Nex code, to_string — leaves nex.interpreter unloaded"
    (let [app (java.io.File/createTempFile "nex-unlink-run" ".nex")]
      (try
        (spit app "class Money
create
  make(c: Integer) do cents := c end
feature
  cents: Integer
  to_string(): String do result := \"$\" + cents.to_string end
end
let bonus: Integer := 5
let add_bonus := fn (m: Money): Money do result := create Money.make(m.cents + bonus) end
let wallet: Array[Money] := [create Money.make(30), create Money.make(10)]
let sorted := wallet.sort(fn (a, b: Money): Integer do result := a.cents.compare(b.cents) end)
let t: Task[Integer] := spawn do result := bonus * 2 end
print(add_bonus(sorted.get(0)))
print(\"total: \" + sorted.get(1))
print(t.await)
")
        (let [{:keys [exit out]}
              (run-clojure-in-fresh-jvm
               `(do (require 'nex.eval)
                    ((resolve 'nex.eval/eval-file) ~(.getCanonicalPath app) {})
                    (println "interpreter-loaded" (boolean (find-ns 'nex.interpreter)))
                    (shutdown-agents)))]
          (is (= 0 exit) out)
          (is (str/includes? out "$15\n") out)
          (is (str/includes? out "\"total: $30\"") out)
          (is (str/includes? out "10") out)
          (is (str/includes? out "interpreter-loaded false") out))
        (finally (.delete app))))))
