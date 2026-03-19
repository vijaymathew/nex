(ns nex.compiler.jvm.file-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.runtime :as runtime]))

(defn- delete-tree!
  [root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (.delete f)))

(defn- invoke-main!
  [output-dir main-class]
  (let [url (.toURL (.toURI (io/file output-dir)))
        parent (.getClassLoader nex.compiler.jvm.runtime.NexReplState)]
    (with-open [loader (java.net.URLClassLoader. (into-array java.net.URL [url]) parent)]
      (let [^Class cls (.loadClass loader main-class)
            ^java.lang.reflect.Method main-method (.getMethod cls "main" (into-array Class [(class (into-array String []))]))]
        (binding [*out* (java.io.StringWriter.)]
          (.invoke main-method nil (object-array [(into-array String [])]))
          (str *out*))))))

(deftest compile-file-writes-class-files-and-launcher-runs
  (testing "compile-file emits .class files and the generated launcher runs the program"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-compile-test")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Greeter
feature
  greet(): String
  do
    result := \"hello from bytecode\"
  end
end

let g: Greeter := create Greeter
print(g.greet())")
        (let [result (file/compile-file (.getPath nex-file) (.getPath out-dir) {})]
          (is (.exists (io/file (get-in result [:class-files (:main-class result)]))))
          (is (.exists (io/file (get-in result [:class-files (:program-class result)]))))
          (is (some #(str/ends-with? % "Greeter.class") (vals (:class-files result))))
          (is (= "\"hello from bytecode\""
                 (str/trim (invoke-main! out-dir (:main-class result))))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-file-resolves-interned-classes
  (testing "compile-file resolves local interned classes before bytecode emission"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-intern-test")
          nex-file (io/file tmp-dir "main.nex")
          intern-file (io/file tmp-dir "A.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit intern-file "class A
feature
  name(): String
  do
    result := \"interned\"
  end
end")
        (spit nex-file "intern A

let a: A := create A
print(a.name())")
        (let [result (file/compile-file (.getPath nex-file) (.getPath out-dir) {})]
          (is (some #(str/ends-with? % "A.class") (vals (:class-files result))))
          (is (= "\"interned\""
                 (str/trim (invoke-main! out-dir (:main-class result))))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-produces-standalone-runnable-jar
  (testing "compile-jar builds a standalone runnable jar"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-test")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class App
feature
  greet(): String
  do
    result := \"hello standalone\"
  end
end

let app: App := create App
print(app.greet())")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              proc (.exec (Runtime/getRuntime)
                          (into-array String ["java" "-jar" (:jar result)]))]
          (.waitFor proc)
          (let [output (slurp (.getInputStream proc))
                error-output (slurp (.getErrorStream proc))]
            (is (= 0 (.exitValue proc)) error-output)
            (is (= "\"hello standalone\"" (str/trim output)))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))
