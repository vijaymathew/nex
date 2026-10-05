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
