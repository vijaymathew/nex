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
      (.put "J_OPTIONS" "-J-Xint -J--enable-native-access=ALL-UNNAMED"))
    (let [proc (.start pb)
          output (slurp (.getInputStream proc))]
      (.waitFor proc)
      {:exit (.exitValue proc)
       :out output})))

(defn- run-jar!
  [jar-path]
  (run-process! (.getParent (io/file jar-path)) "java" "-jar" jar-path))

(def ^:private nex-bin
  (.getCanonicalPath (io/file "bin/nex")))

(deftest cli-compile-jvm-with-explicit-output-dir-test
  (testing "bin/nex compile jvm writes a jar to the requested output directory"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-cli-jvm-explicit-out")
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
          (is (str/includes? out "Main class:"))
          (let [jar-run (run-jar! (.getPath expected-jar))]
            (is (= 0 (:exit jar-run)) (:out jar-run))
            (is (= "\"cli ok\"" (str/trim (:out jar-run))))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest cli-compile-jvm-defaults-jar-to-cwd-test
  (testing "bin/nex compile jvm without an output dir writes the jar into the caller working directory"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-cli-jvm-default-out")
          nex-file (io/file tmp-dir "default_out.nex")
          expected-jar (io/file tmp-dir "default_out.jar")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "print(42)")
        (let [{:keys [exit out]} (run-process! (.getPath tmp-dir) nex-bin "compile" "jvm" (.getPath nex-file))]
          (is (= 0 exit) out)
          (is (.exists expected-jar))
          (let [jar-run (run-jar! (.getPath expected-jar))]
            (is (= 0 (:exit jar-run)) (:out jar-run))
            (is (= "42" (str/trim (:out jar-run))))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest cli-compile-jvm-type-error-diagnostics-test
  (testing "bin/nex compile jvm prints formatted type diagnostics on failure"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-cli-jvm-type-error")
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
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-cli-jvm-parse-error")
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
