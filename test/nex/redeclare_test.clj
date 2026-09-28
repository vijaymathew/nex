(ns nex.redeclare-test
  "Redeclaring an inherited zero-argument query as an attribute (allowed,
   desugared by nex.redeclare) and an inherited attribute as a routine
   (rejected by the type checker)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.redeclare :as redeclare]
            [nex.typechecker :as tc]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.repl :as repl]))

(defn- class-named [classes n]
  (first (filter #(= n (:name %)) classes)))

(defn- routine-names [class-def]
  (->> (:body class-def)
       (filter #(= :feature-section (:type %)))
       (mapcat :members)
       (filter #(= :method (:type %)))
       (map :name)))

(def ^:private shape-src
  "class Shape
feature
  area: Real
  do
    result := 1.0
  end
  describe: String
  do
    result := \"area=\" + area.to_string
  end
end

class Square
inherit Shape
feature
  area: Real
  side: Real
create
  make(a: Real) do area := a side := a end
end")

;; ─── The desugaring pass ─────────────────────────────────────────────────────

(deftest desugar-adds-getter-for-redeclared-query
  (let [classes (redeclare/desugar-classes (:classes (p/ast shape-src)))
        square (class-named classes "Square")
        getter (->> (:body square) (mapcat :members)
                    (filter :synthesized-getter?) first)]
    (is (= ["area"] (routine-names square)) "only the redeclared field gets a getter")
    (is (= "Real" (:return-type getter)))
    (is (empty? (:params getter)))
    (is (= (routine-names (class-named classes "Shape"))
           ["area" "describe"]) "the parent is untouched")))

(deftest desugar-is-idempotent
  (let [once (redeclare/desugar-classes (:classes (p/ast shape-src)))]
    (is (= once (redeclare/desugar-classes once)))))

(deftest desugar-follows-multi-level-and-external-parents
  (let [base (:classes (p/ast "class A feature v: Integer do result := 1 end end"))
        rest-classes (:classes (p/ast "class B inherit A end
class C inherit B feature v: Integer create make do v := 3 end end"))
        lookup (redeclare/class-lookup base)
        c (class-named (redeclare/desugar-classes rest-classes lookup) "C")]
    (is (= ["v"] (routine-names c)))))

(deftest desugar-skips-routines-with-arguments-and-existing-queries
  (let [classes (redeclare/desugar-classes
                 (:classes (p/ast "class A
feature
  f(x: Integer): Integer do result := x end
  g: Integer do result := 1 end
end
class B inherit A
feature
  f: Integer
  g: Integer
  g(): Integer do result := g end
create
  make do f := 1 g := 2 end
end")))]
    (is (= ["g"] (routine-names (class-named classes "B"))))))

;; ─── Type checking ───────────────────────────────────────────────────────────

(defn- type-errors [src]
  (let [result (tc/type-check (p/ast src))]
    (when-not (:success result)
      (str/join "\n" (map tc/format-type-error (:errors result))))))

(deftest attribute-redeclared-as-routine-is-rejected
  (testing "zero-argument routine over an inherited attribute"
    (is (re-find #"Routine 'balance' in class 'Checking' redeclares the attribute 'balance' inherited from 'Account'"
                 (str (type-errors "class Account
feature
  balance: Integer
end
class Checking inherit Account
feature
  balance: Integer do result := 0 end
end")))))
  (testing "routine with arguments over an inherited attribute"
    (is (re-find #"redeclares the attribute 'balance'"
                 (str (type-errors "class Account
feature
  balance: Integer
end
class Checking inherit Account
feature
  balance(x: Integer): Integer do result := x end
end")))))
  (testing "routine over an attribute that itself redeclared a query"
    (is (re-find #"Routine 'area' in class 'C' redeclares the attribute 'area' inherited from 'B'"
                 (str (type-errors "class A feature area: Real do result := 1.0 end end
class B inherit A feature area: Real create make do area := 2.0 end end
class C inherit B feature area: Real do result := 3.0 end create make do super.make end end"))))))

(deftest query-redeclared-as-attribute-typechecks
  (is (nil? (type-errors shape-src)))
  (testing "an attribute effects a deferred query"
    (is (nil? (type-errors "deferred class Shape
feature
  area(): Real deferred
end
class Square inherit Shape
feature
  area: Real
create
  make(a: Real) do area := a end
end")))))

(deftest invalid-query-redeclarations-are-rejected
  (testing "a routine that takes arguments"
    (is (re-find #"Attribute 'scale' in class 'B' redeclares an inherited routine 'scale' that takes arguments"
                 (str (type-errors "class A feature scale(k: Real): Real do result := k end end
class B inherit A feature scale: Real create make do scale := 2.0 end end")))))
  (testing "a non-conforming attribute type"
    (is (re-find #"Attribute 'area' in class 'B' redeclares the inherited query 'area': Real with type String"
                 (str (type-errors "class A feature area: Real do result := 1.0 end end
class B inherit A feature area: String create make do area := \"x\" end end"))))))

;; ─── Runtime, both backends ──────────────────────────────────────────────────

(defmacro ^:private with-repl [backend ctx-sym & body]
  `(binding [repl/*type-checking-enabled* (atom true)
             repl/*repl-var-types* (atom {})
             repl/*repl-backend* (atom ~backend)
             repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
     (let [~ctx-sym (repl/init-repl-context)]
       ~@body)))

(defn- run-inputs
  "Evaluate each input in turn; return the output of the last one."
  [ctx inputs]
  (doseq [input (butlast inputs)]
    (let [out (with-out-str (repl/eval-code ctx input))]
      (is (not (str/includes? out "Error")) out)))
  (with-out-str (repl/eval-code ctx (last inputs))))

(deftest query-redeclared-as-attribute-dispatches
  (doseq [backend [:compiled :interpreter]]
    (testing (str backend)
      (with-repl backend ctx
        (let [out (run-inputs ctx [shape-src
                                   "let s := create Square.make(9.0)"
                                   "let sh: Shape := s"
                                   "print(s.area.to_string + \" \" + s.describe + \" \" + sh.area.to_string + \" \" + sh.describe)"])]
          (is (str/includes? out "9.0 area=9.0 9.0 area=9.0") out))))))

(deftest parent-and-heir-in-separate-inputs
  (doseq [backend [:compiled :interpreter]]
    (testing (str backend)
      (with-repl backend ctx
        (let [out (run-inputs ctx ["deferred class Shape
feature
  area(): Real deferred
  describe: String do result := \"area=\" + area.to_string end
end"
                                   "class Square inherit Shape
feature
  area: Real
create
  make(a: Real) do area := a end
end"
                                   "let sh: Shape := create Square.make(4.0)"
                                   "print(sh.describe)"])]
          (is (str/includes? out "area=4.0") out))))))

(deftest super-reaches-the-redeclared-query
  (doseq [backend [:compiled :interpreter]]
    (testing (str backend)
      (with-repl backend ctx
        (let [out (run-inputs ctx ["class Shape feature area: Real do result := 1.0 end end"
                                   "class Square inherit Shape
feature
  area: Real
  parent_area: Real do result := super.area end
create
  make(a: Real) do area := a end
end"
                                   "let s := create Square.make(9.0)"
                                   "print(s.parent_area)"])]
          (is (str/includes? out "1.0") out))))))

(deftest inherited-postcondition-applies-to-the-attribute
  (doseq [backend [:compiled :interpreter]]
    (testing (str backend)
      (with-repl backend ctx
        (let [out (run-inputs ctx ["class Shape
feature
  area: Real
  do
    result := 1.0
  ensure
    non_negative: result >= 0.0
  end
end"
                                   "class Square inherit Shape
feature
  area: Real
create
  make(a: Real) do area := a end
end"
                                   "let sh: Shape := create Square.make(-1.0)"
                                   "print(sh.area)"])]
          (is (str/includes? out "Postcondition violation: non_negative") out))))))

;; ─── Multiple inheritance ────────────────────────────────────────────────────

(def ^:private second-parent-src
  "class Named
feature
  label: String do result := \"named\" end
end

class Shape
feature
  area: Real do result := 1.0 end
  describe: String do result := \"area=\" + area.to_string end
end

class Square
inherit Named, Shape
feature
  area: Real
create
  make(a: Real) do area := a end
end")

(def ^:private diamond-src
  "deferred class Base
feature
  area(): Real deferred
  describe: String do result := \"area=\" + area.to_string end
end

deferred class Left inherit Base
feature
  left: String do result := \"L\" end
end

deferred class Right inherit Base
feature
  right: String do result := \"R\" end
end

class Both
inherit Left, Right
feature
  area: Real
create
  make(a: Real) do area := a end
end")

(def ^:private two-queries-src
  "class A
feature
  area: Real do result := 1.0 end
  from_a: String do result := \"a=\" + area.to_string end
end

class B
feature
  area: Real do result := 2.0 end
  from_b: String do result := \"b=\" + area.to_string end
end

class C
inherit A, B
feature
  area: Real
create
  make(v: Real) do area := v end
end")

(deftest desugar-with-multiple-parents
  (testing "the query is declared by the second parent"
    (let [classes (redeclare/desugar-classes (:classes (p/ast second-parent-src)))]
      (is (= ["area"] (routine-names (class-named classes "Square"))))))
  (testing "the query reaches the heir along both sides of a diamond"
    (let [classes (redeclare/desugar-classes (:classes (p/ast diamond-src)))]
      (is (= ["area"] (routine-names (class-named classes "Both"))))
      (is (= ["left"] (routine-names (class-named classes "Left")))
          "intermediate deferred classes without the field are untouched")))
  (testing "both parents declare the query: one getter answers both"
    (let [classes (redeclare/desugar-classes (:classes (p/ast two-queries-src)))]
      (is (= ["area"] (routine-names (class-named classes "C")))))))

(deftest multiple-inheritance-typechecks
  (is (nil? (type-errors second-parent-src)))
  (is (nil? (type-errors diamond-src)))
  (is (nil? (type-errors two-queries-src)))
  (testing "a query in one parent and a routine with arguments in the other"
    (is (nil? (type-errors "class A feature size: Integer do result := 1 end end
class B feature size(k: Integer): Integer do result := k end end
class C inherit A, B feature size: Integer create make do size := 4 end end")))))

(deftest multiple-inheritance-invalid-redeclarations
  (testing "a routine over an attribute inherited from the second parent"
    (is (re-find #"Routine 'balance' in class 'Checking' redeclares the attribute 'balance' inherited from 'Account'"
                 (str (type-errors "class Named feature label: String do result := \"x\" end end
class Account feature balance: Integer end
class Checking inherit Named, Account
feature
  balance: Integer do result := 0 end
end")))))
  (testing "a routine over an attribute that one side of a diamond redeclared"
    (is (re-find #"redeclares the attribute 'area' inherited from 'Left'"
                 (str (type-errors "deferred class Base feature area(): Real deferred end
class Left inherit Base feature area: Real create make do area := 1.0 end end
deferred class Right inherit Base end
class Both inherit Left, Right
feature
  area: Real do result := 2.0 end
create
  make do Left.make end
end")))))
  (testing "an attribute over a routine with arguments from the second parent"
    (is (re-find #"Attribute 'scale' in class 'C' redeclares an inherited routine 'scale' that takes arguments"
                 (str (type-errors "class A feature label: String do result := \"a\" end end
class B feature scale(k: Real): Real do result := k end end
class C inherit A, B feature scale: Real create make do scale := 2.0 end end")))))
  (testing "an attribute type that conforms to neither parent's query"
    (is (re-find #"Attribute 'area' in class 'C' redeclares the inherited query 'area'"
                 (str (type-errors "class A feature area: Real do result := 1.0 end end
class B feature label: String do result := \"b\" end end
class C inherit B, A feature area: String create make do area := \"x\" end end"))))))

(deftest multiple-inheritance-dispatch
  (doseq [backend [:compiled :interpreter]]
    (testing (str backend " — query from the second parent")
      (with-repl backend ctx
        (let [out (run-inputs ctx [second-parent-src
                                   "let s := create Square.make(9.0)"
                                   "let sh: Shape := s"
                                   "let n: Named := s"
                                   "print(sh.area.to_string + \" \" + sh.describe + \" \" + n.label)"])]
          (is (str/includes? out "9.0 area=9.0 named") out))))
    (testing (str backend " — diamond over a deferred query")
      (with-repl backend ctx
        (let [out (run-inputs ctx [diamond-src
                                   "let b := create Both.make(3.0)"
                                   "let l: Left := b"
                                   "let r: Right := b"
                                   "print(l.describe + \" \" + r.area.to_string + \" \" + b.left + b.right)"])]
          (is (str/includes? out "area=3.0 3.0 LR") out))))
    (testing (str backend " — both parents' code sees the one attribute")
      (with-repl backend ctx
        (let [out (run-inputs ctx [two-queries-src
                                   "let c := create C.make(5.0)"
                                   "let a: A := c"
                                   "let b: B := c"
                                   "print(c.from_a + \" \" + c.from_b + \" \" + a.area.to_string + \" \" + b.area.to_string)"])]
          (is (str/includes? out "a=5.0 b=5.0 5.0 5.0") out))))))
