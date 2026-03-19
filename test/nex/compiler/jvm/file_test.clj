(ns nex.compiler.jvm.file-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.file :as file]))

(defn- delete-tree!
  [root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (.delete f)))

(defn- invoke-main!
  [output-dir main-class]
  (let [runtime-dir (io/file output-dir "__aot_runtime")
        _ (.mkdirs runtime-dir)
        _ (binding [*compile-path* (.getPath runtime-dir)]
            (compile 'nex.compiler.jvm.runtime))
        classpath (str (.getCanonicalPath (io/file output-dir))
                       java.io.File/pathSeparator
                       (.getCanonicalPath runtime-dir)
                       java.io.File/pathSeparator
                       (System/getProperty "java.class.path"))
        pb (ProcessBuilder. ^java.util.List
                            ["java" "-cp" classpath main-class])]
    (.redirectErrorStream pb true)
    (let [proc (.start pb)
          output (slurp (.getInputStream proc))]
      (.waitFor proc)
      (when-not (zero? (.exitValue proc))
        (throw (ex-info "Generated launcher failed"
                        {:main-class main-class
                         :output-dir output-dir
                         :exit (.exitValue proc)
                         :output output})))
      output)))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

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

(deftest compile-file-http-server-launcher-runs
  (testing "compile-file emits class files whose launcher can start and stop an HTTP server"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-http-server-file-test")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (let [port (free-port)]
          (spit nex-file (str "let handle := http_server_create(" port ")\n"
                              "let actual_port: Integer := http_server_start(handle)\n"
                              "print(actual_port)\n"
                              "sleep(1000)\n"
                              "http_server_stop(handle)"))
          (let [result (file/compile-file (.getPath nex-file) (.getPath out-dir) {})
                run-future (future (invoke-main! out-dir (:main-class result)))
                client (java.net.http.HttpClient/newHttpClient)
                request (-> (java.net.http.HttpRequest/newBuilder
                             (java.net.URI/create (str "http://127.0.0.1:" port "/hello")))
                            (.GET)
                            (.build))
                response (loop [attempts 100]
                           (let [result (try
                                          (.send client request (java.net.http.HttpResponse$BodyHandlers/ofString))
                                          (catch Exception e
                                            e))]
                             (if (instance? Exception result)
                               (if (pos? attempts)
                                 (do
                                   (Thread/sleep 100)
                                   (recur (dec attempts)))
                                 (throw result))
                               result)))
                output (deref run-future 10000 :timeout)]
            (is (= 404 (.statusCode response)))
            (is (= "Not Found" (.body response)))
            (is (not= :timeout output))
            (is (= (str port) (str/trim output)))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-file-with-java-block-runs
  (testing "compile-file keeps with \"java\" blocks on the JVM bytecode path"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-with-java-file-test")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file (str "with \"java\" do\n"
                            "  let version_length: Integer := System.getProperty(\"java.version\").length()\n"
                            "end\n"
                            "print(version_length)"))
        (let [result (file/compile-file (.getPath nex-file) (.getPath out-dir) {})
              output (str/trim (invoke-main! out-dir (:main-class result)))]
          (is (re-matches #"\d+" output)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-file-type-error-includes-formatted-diagnostics
  (testing "compile-file surfaces formatted type errors in ex-data for callers and CLI tooling"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-type-error-test")
          nex-file (io/file tmp-dir "bad.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "let x: Integer := \"oops\"")
        (try
          (file/compile-file (.getPath nex-file) nil {})
          (is false "Expected compile-file to throw on a type error")
          (catch clojure.lang.ExceptionInfo e
            (is (= "Type checking failed" (.getMessage e)))
            (is (some #(str/includes? % "Cannot assign String to variable 'x' of type Integer")
                      (:errors (ex-data e))))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-file-parse-error-propagates-parser-message
  (testing "compile-file preserves parser diagnostics for invalid source files"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-parse-error-test")
          nex-file (io/file tmp-dir "bad.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "let x := ")
        (try
          (file/compile-file (.getPath nex-file) nil {})
          (is false "Expected compile-file to throw on a parse error")
          (catch Exception e
            (is (str/includes? (.getMessage e) "mismatched input"))
            (is (str/includes? (.getMessage e) "<EOF>"))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))
