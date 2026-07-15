(ns nex.union-enum-test
  "An all-payload-free, non-generic `union` is enriched into an enumeration: the
   walker adds, on top of the plain sealed-class desugaring, an interned constant
   per member (`Color.Red` is the one canonical Red), declaration-order
   `Comparable` (an `ordinal` field + `compare`), and a `values` array of all
   members. A union with any payload (or a generic parameter) is a real tagged
   sum type and keeps the plain desugaring — these tests pin both."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.classloader :as loader]))

(defn- run-interpreted [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    @(:output ctx)))

(defn- run-compiled [code]
  (let [{:keys [main-class classes]} (file/compile-ast "enum_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(defn- both-backends [code]
  {:compiled (run-compiled code) :interpreted (run-interpreted code)})

(def color-enum
  "enum union Color
  Red
  Green
  Blue
end
")

(deftest enum-members-are-interned
  (testing "Color.Red is one canonical instance on both backends"
    (let [{:keys [compiled interpreted]}
          (both-backends (str color-enum
                              "let a: Color := Color.Red
print(a == Color.Red)
print(Color.Red == Color.Green)"))]
      (is (= ["true" "false"] compiled))
      (is (= ["true" "false"] interpreted)))))

(deftest enum-orders-by-declaration
  (testing "compare follows declaration order (Red < Green < Blue)"
    (let [{:keys [compiled interpreted]}
          (both-backends (str color-enum
                              "print(Color.Red < Color.Green)
print(Color.Blue > Color.Red)
print(Color.Green <= Color.Green)"))]
      (is (= ["true" "true" "true"] compiled))
      (is (= ["true" "true" "true"] interpreted)))))

(deftest enum-values-iterates-all-members
  (testing "Color.values is an Array[Color] of every member, in order"
    (let [{:keys [compiled interpreted]}
          (both-backends (str color-enum
                              "print(Color.values.length())
across Color.values as c do print(c.ordinal) end"))]
      (is (= ["3" "0" "1" "2"] compiled))
      (is (= ["3" "0" "1" "2"] interpreted)))))

(deftest enum-still-matches-exhaustively
  (testing "match over the enum dispatches on the variant, as for any union"
    (let [prog (str color-enum
                    "let a: Color := Color.Green
match a of
  when Red then print(\"red\")
  when Green then print(\"green\")
  when Blue then print(\"blue\")
end")]
      (is (= ["\"green\""] (run-compiled prog)))
      (is (= ["\"green\""] (run-interpreted prog))))))

(deftest payloaded-union-is-not-enriched
  (testing "a union with any payload keeps the plain sum-type desugaring: no
            member constants, construction via create, match on payload"
    (let [prog "union Shape
  Circle(radius: Real)
  Unit
end
let s: Shape := create Circle.make(2.0)
match s of
  when Circle as c then print(c.radius)
  when Unit then print(\"unit\")
end"]
      (is (= ["2.0"] (run-compiled prog)))
      (is (= ["2.0"] (run-interpreted prog)))))
  (testing "and it exposes no enum `values` member"
    (is (thrown? Exception
                 (run-interpreted "union Shape
  Circle(radius: Real)
  Unit
end
print(Shape.values.length())")))))

(deftest generic-union-is-not-enriched
  (testing "a generic union stays a tagged sum type even if variants look tag-like"
    (let [prog "union Box[T]
  Full(v: T)
  Empty
end
let a: Box[Integer] := create Full[Integer].make(3)
match a of
  when Full as f then print(f.v)
  when Empty then print(\"empty\")
end"]
      (is (= ["3"] (run-compiled prog)))
      (is (= ["3"] (run-interpreted prog))))))

(deftest enrichment-is-opt-in
  (testing "a plain payload-free `union` is NOT enriched — no members, no values"
    ;; The whole point of the `enum` keyword: enrichment (and its declaration-order
    ;; ordering) is a deliberate choice, never inferred from payload-free-ness.
    (is (thrown? Exception
                 (run-interpreted "union Color
  Red
  Green
end
print(Color.Red)")))
    (is (thrown? Exception
                 (run-interpreted "union Color
  Red
  Green
end
print(Color.values.length())")))))

(deftest enum-keyword-is-reserved
  (testing "`enum` is a hard keyword and cannot be used as an identifier"
    (is (thrown? Exception (p/ast "let enum := 5")))))

(deftest enum-union-rejects-payload
  (testing "a payload on an enum variant is a clear error, not a silent sum type"
    (is (thrown-with-msg?
         Exception #"cannot carry a payload"
         (p/ast "enum union Bad
  Circle(r: Real)
  Square
end")))))

(deftest enum-union-rejects-generic
  (testing "a generic enum union is rejected — members need a concrete type"
    (is (thrown-with-msg?
         Exception #"cannot be generic"
         (p/ast "enum union Box[T]
  A
  B
end")))))

(deftest enum-union-rejects-reserved-member-names
  (testing "a variant named like a generated member is a clear error"
    (doseq [reserved ["values" "ordinal" "compare"]]
      (is (thrown-with-msg?
           Exception #"reserved member name"
           (p/ast (str "enum union Bad
  Red
  " reserved "
end")))
          (str "variant named " reserved " should be rejected")))))
