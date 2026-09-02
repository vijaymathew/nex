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

(deftest cli-compile-jvm-resolves-nested-path-qualified-intern-test
  (testing "bin/nex compile jvm resolves a path-qualified intern reached
            transitively (main.nex -> lib/transaction/account.nex ->
            lib/units/money.nex), where money.nex sits in a *different* lib
            subdirectory than account.nex.

            `nex <file>` (cmd_run_script) exports NEX_USER_DIR so
            nex.interpreter/find-intern-file can fall back to the project
            root once a narrowing site is reached transitively — the
            interning file's own directory (lib/transaction) is the wrong
            root for a `lib/units/...` lookup. `nex compile jvm`
            (cmd_compile) never exported it, so this same program failed
            with 'Cannot find intern file for units/Money' through compile
            even though `nex <file>` already ran it fine."
    (let [tmp-dir (unique-tmp-dir "nex-cli-jvm-nested-intern")
          units-dir (io/file tmp-dir "lib" "units")
          transaction-dir (io/file tmp-dir "lib" "transaction")
          money-file (io/file units-dir "Money.nex")
          account-file (io/file transaction-dir "account.nex")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "build")
          expected-jar (io/file out-dir "main.jar")]
      (try
        (.mkdirs units-dir)
        (.mkdirs transaction-dir)
        (spit money-file "class Money
create
  make(a: Real) do
    amount := a
  end
feature
  amount: Real
end")
        (spit account-file "intern units/Money

class Account
create
  make(b: Money) do
    balance := b
  end
feature
  balance: Money
end")
        (spit main-file "intern transaction/account as Account

let a := create Account.make(create Money.make(100.0))
print(a.balance.amount)")
        (let [{:keys [exit out]} (run-process! (.getPath tmp-dir)
                                                nex-bin "compile" "jvm"
                                                (.getPath main-file) (.getPath out-dir))]
          (is (not (str/includes? out "Cannot find intern file")) out)
          (is (= 0 exit) out)
          (is (.exists expected-jar))
          (let [{run-exit :exit run-out :out} (run-process! (.getPath tmp-dir) "java" "-jar" (.getPath expected-jar))]
            (is (= 0 run-exit) run-out)
            (is (= "100.0" (str/trim run-out)))))
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
