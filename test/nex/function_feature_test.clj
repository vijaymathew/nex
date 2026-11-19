(ns nex.function-feature-test
  "A function value has no features but the ones every value has: it is
   called. `f.map(3)` on a function `f` was typed Any, so the mistake was
   reported only where the value was used (\"Cannot assign Any\"), or, when
   nothing used it, compiled code failed on the call itself."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run [code]
  (let [f (java.io.File/createTempFile "function_feature" ".nex")
        out (java.io.StringWriter.)]
    (try
      (spit f code)
      (binding [*out* out] (e/eval-file (.getPath f) {}))
      {:out (str/split-lines (str/trim (str out)))}
      (catch Throwable t {:error (or (ex-message t) "")})
      (finally (.delete f)))))

(deftest an-unknown-feature-of-a-function-is-an-error
  (testing "used as a value"
    (let [{:keys [error]} (run "let f := fn(i: Integer): Integer => i
let x: Integer := f.map(3)")]
      (is (str/includes? (str error) "Type checking failed") error)
      (is (str/includes? (str error)
                         "`map` is not a feature of `f`, a function of type Function(i: Integer): Integer. A function is called: `f(...)`.")
          error)))
  (testing "its value unused"
    (let [{:keys [error]} (run "let f: Function(Integer): Integer := fn(i: Integer): Integer => i
print(f.map(3))")]
      (is (str/includes? (str error) "`map` is not a feature of `f`") error))))

(deftest a-function-keeps-its-features
  (is (= {:out ["3" "4" "true"]}
         (run "let f: Function(Integer): Integer := fn(i: Integer): Integer => i
print(f.call1(3))
print(f(4))
print(f.to_string.length > 0)"))))
