(ns nex.once-constant-test
  "`once` applies only to fields (Definition §3.7). The grammar shares one rule
   between fields and typed constants, so `once k: T = e` used to parse and run."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]))

(defn- rejection [src]
  (try (p/ast src) nil
       (catch Exception e (.getMessage e))))

(deftest once-typed-constant-rejected
  (let [msg (rejection "class A\nfeature\n  once k: Integer = 5\nend")]
    (is (some? msg))
    (is (str/includes? msg "Constant 'k' cannot be marked 'once'"))))

(deftest once-untyped-constant-is-a-syntax-error
  (is (some? (rejection "class A\nfeature\n  once k = 5\nend"))))

(deftest once-field-and-plain-constants-still-parse
  (testing "a once field, a typed constant and an untyped constant"
    (is (nil? (rejection "class A\nfeature\n  once x: Integer\n  k: Integer = 5\n  j = 6\ncreate\n  make(v: Integer) do\n    x := v\n  end\nend")))))
