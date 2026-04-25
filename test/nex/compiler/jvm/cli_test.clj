(ns nex.compiler.jvm.cli-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- delete-tree!
  [root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (.delete f)))

(defn- run-process!
  [working-dir & args]
  (let [pb (ProcessBuilder. ^java.util.List (vec args))]
    (.directory pb (io/file working-dir))
    (.redirectErrorStream pb true)
    (doto (.environment pb)
      (.put "J_OPTIONS" "-J--enable-native-access=ALL-UNNAMED"))
    (let [proc (.start pb)
          output (slurp (.getInputStream proc))]
      (.waitFor proc)
      {:exit (.exitValue proc)
       :out output})))

(def ^:private nex-bin
  (.getCanonicalPath (io/file "bin/nex")))

(defn- unique-tmp-dir
  [prefix]
  (io/file (System/getProperty "java.io.tmpdir")
           (str prefix "-" (System/nanoTime))))

(deftest cli-compile-jvm-success-smoke-test
  (testing "bin/nex compile jvm produces a jar and reports success"
    (let [tmp-dir (unique-tmp-dir "nex-cli-jvm-success-smoke")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "build")
          expected-jar (io/file out-dir "app.jar")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "print(\"cli ok\")")
        (let [{:keys [exit out]} (run-process! "." nex-bin "compile" "jvm" (.getPath nex-file) (.getPath out-dir))]
          (is (= 0 exit) out)
          (is (.exists expected-jar))
          (is (str/includes? out "Compiled"))
          (is (str/includes? out "Main class:")))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest cli-compile-jvm-supports-spawn-with-captured-channels
  (testing "bin/nex compile jvm handles spawn bodies that capture top-level channels"
    (let [tmp-dir (unique-tmp-dir "nex-cli-jvm-captured-channel")
          nex-file (io/file tmp-dir "channel.nex")
          out-dir (io/file tmp-dir "build")
          expected-jar (io/file out-dir "channel.jar")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "let input: Channel[Integer] := create Channel[Integer].with_capacity(4)
let output: Channel[Integer] := create Channel[Integer].with_capacity(4)

let worker: Task := spawn do
  let v: Integer := input.receive
  output.send(v * v)
end

input.send(9)
print(output.receive)
worker.await")
        (let [{:keys [exit out]} (run-process! "." nex-bin "compile" "jvm" (.getPath nex-file) (.getPath out-dir))]
          (is (= 0 exit) out)
          (is (.exists expected-jar))
          (let [{run-exit :exit run-out :out} (run-process! "." "java" "-jar" (.getPath expected-jar))]
            (is (= 0 run-exit) run-out)
            (is (= "81" (str/trim run-out)))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest cli-compile-jvm-type-error-diagnostics-test
  (testing "bin/nex compile jvm prints formatted type diagnostics on failure"
    (let [tmp-dir (unique-tmp-dir "nex-cli-jvm-type-error")
          nex-file (io/file tmp-dir "bad.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "let x: Integer := \"oops\"")
        (let [{:keys [exit out]} (run-process! "." nex-bin "compile" "jvm" (.getPath nex-file))]
          (is (not= 0 exit))
          (is (str/includes? out "Error: Type checking failed"))
          (is (str/includes? out "Cannot assign String to variable 'x' of type Integer")))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest cli-compile-jvm-parse-error-diagnostics-test
  (testing "bin/nex compile jvm prints parser diagnostics on invalid source"
    (let [tmp-dir (unique-tmp-dir "nex-cli-jvm-parse-error")
          nex-file (io/file tmp-dir "bad.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "let x := ")
        (let [{:keys [exit out]} (run-process! "." nex-bin "compile" "jvm" (.getPath nex-file))]
          (is (not= 0 exit))
          (is (str/includes? out "Error:"))
          (is (str/includes? out "mismatched input"))
          (is (str/includes? out "<EOF>")))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest cli-run-script-type-error-diagnostics-test
  (testing "bin/nex <file.nex> typechecks before execution"
    (let [tmp-dir (unique-tmp-dir "nex-cli-run-script-type-error")
          nex-file (io/file tmp-dir "bad.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "let x: Integer := \"oops\"\nprint(\"should not run\")")
        (let [{:keys [exit out]} (run-process! "." nex-bin (.getPath nex-file))]
          (is (not= 0 exit))
          (is (str/includes? out "Error: Type checking failed"))
          (is (str/includes? out "Cannot assign String to variable 'x' of type Integer"))
          (is (not (str/includes? out "should not run"))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))
