(ns nex.docs-examples-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- delete-tree!
  [root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (.delete f)))

(defn- run-process!
  [& args]
  (let [pb (ProcessBuilder. ^java.util.List (vec args))]
    (.directory pb (io/file "/home/vijay/Projects/nex"))
    (.redirectErrorStream pb true)
    (let [proc (.start pb)
          output (slurp (.getInputStream proc))]
      (.waitFor proc)
      {:exit (.exitValue proc)
       :output output})))

(deftest compiled-docs-check-runs-transcript-generic-stack-block
  (testing "the compiled docs checker executes unlabeled nex> transcript blocks instead of skipping them"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-docs-example-check-test")
          md-file (io/file tmp-dir "generic_stack.md")]
      (try
        (.mkdirs tmp-dir)
        (spit md-file "```text
nex> class Stack [G]
       create
         make() do
           items := []
         end
       feature
         items: Array[G]
         push(value: G) do
           items.add(value)
         end
         pop(): G do
           result := items.get(items.length - 1)
           items.remove(items.length - 1)
         end
         peek(): G do
           result := items.get(items.length - 1)
         end
         is_empty(): Boolean do
           result := items.is_empty
         end
         size(): Integer do
           result := items.length
         end
     end

nex> let s := create Stack[Integer].make
nex> s.push(10)
nex> s.push(20)
nex> s.pop
20
```")
        (let [{:keys [exit output]}
              (run-process! "clojure" "-M:test" "test/scripts/check_compiled_tutorial_examples.clj" (.getPath md-file))]
          (is (= 0 exit) output)
          (is (str/includes? output (str "RUN  1/1  " (.getPath md-file))) output)
          (is (str/includes? output (str "PASS " (.getPath md-file))) output))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))
