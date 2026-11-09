(ns nex.name-clash-test
  "Ambiguous inherited names (Definition §4.9). Members inherited through two
   parents that match by name and arity are one member when they share an
   origin (a diamond), and two unrelated members otherwise. A class may
   inherit both; an unqualified use of the name on it is rejected. Inside the
   class an ancestor name picks one (`A.x`); a client uses the object as an
   `A` or a `B`, or the class exposes the one it means through a feature of
   its own."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- errors [src]
  (mapv :message (:errors (tc/type-check (p/ast src)))))

(defn- ambiguous? [src]
  (boolean (some #(str/includes? % "is ambiguous on") (errors src))))

(def ^:private ab-parents
  "class A
feature
  x: Integer
  m(): Integer do result := 1 end
  K = 1
create
  make() do x := 1 end
end
class B
feature
  x: Integer
  m(): Integer do result := 2 end
  K = 2
create
  make() do x := 2 end
end
class C
inherit A, B
create
  build() do A.make() B.make() end
")

(defn- with-c
  "AB-PARENTS, then C's FEATURES and the end of C, then top-level CODE."
  [features code]
  (str ab-parents (when features (str "feature\n" features)) "end\n" code))

(deftest inheriting-unrelated-members-is-allowed
  (is (empty? (errors (with-c nil "let c := create C.build()\n")))))

(deftest qualified-uses-are-allowed
  (testing "an ancestor name inside the class"
    (is (empty? (errors (with-c "  sum(): Integer do result := A.x + B.x + A.m() + B.m() end\n" "")))))
  (testing "a client using the object as one of the parents"
    (is (empty? (errors (with-c nil "let c := create C.build()\nlet a: A := c\nprint(a.x)\n")))))
  (testing "a feature of the class's own that exposes one of them"
    (is (empty? (errors (with-c "  a_x(): Integer do result := A.x end\n"
                                "let c := create C.build()\nprint(c.a_x)\n")))))
  (testing "a member the class declares itself"
    (is (empty? (errors (with-c "  x: Integer\n  get(): Integer do result := x end\n"
                                "let c := create C.build()\nprint(c.x)\n"))))))

(deftest unqualified-uses-are-ambiguous
  (testing "inside the class"
    (is (ambiguous? (with-c "  get(): Integer do result := x end\n" "")))
    (is (ambiguous? (with-c "  get(): Integer do result := this.x end\n" "")))
    (is (ambiguous? (with-c "  get(): Integer do result := m() end\n" ""))))
  (testing "by a client"
    (is (ambiguous? (with-c nil "let c := create C.build()\nprint(c.x)\n")))
    (is (ambiguous? (with-c nil "let c := create C.build()\nprint(c.m())\n")))
    (is (ambiguous? (with-c nil "print(C.K)\n")))
    (is (ambiguous? (with-c nil "let c := create C.make()\n"))))
  (testing "in an heir, which inherits the ambiguity"
    (let [heir (fn [body] (with-c nil (str "class D\ninherit C\ncreate\n  go() do C.build() end\nfeature\n"
                                           "  get(): Integer do result := " body " end\nend\n")))]
      (is (ambiguous? (heir "x")))
      (is (ambiguous? (heir "C.x")))
      (is (empty? (errors (heir "A.x")))))))

(deftest messages-say-how-to-pick-one
  (let [inside (first (errors (with-c "  get(): Integer do result := x end\n" "")))
        client (first (errors (with-c nil "let c := create C.build()\nprint(c.x)\n")))]
    (is (str/includes? inside "Write 'A.x' or 'B.x'"))
    (is (str/includes? client "Use the object as an 'A' or a 'B'"))))

(deftest not-ambiguous
  (testing "a shared ancestor reached twice, redefined along one path"
    (is (empty? (errors "class A\nfeature\n  m(): Integer do result := 1 end\nend
class B\ninherit A\nfeature\n  m(): Integer do result := 2 end\nend
class C\ninherit A\nend\nclass D\ninherit B, C\nend
let d := create D\nprint(d.m())\n"))))
  (testing "deferred routines join"
    (is (empty? (errors "deferred class A\nfeature\n  m(): Integer deferred\nend
deferred class B\nfeature\n  m(): Integer deferred\nend
deferred class C\ninherit A, B\nfeature\n  twice(): Integer do result := m() * 2 end\nend\n"))))
  (testing "Any's routines have one origin"
    (is (empty? (errors "class A\nfeature\n  to_string(): String do result := \"a\" end\nend
class B\nfeature\n  to_string(): String do result := \"b\" end\nend
class C\ninherit A, B\nend\nlet c := create C\nprint(c.to_string)\n"))))
  (testing "private members are not reached through an heir"
    (is (empty? (errors "class A\nprivate feature\n  n: Integer\nend
class B\nprivate feature\n  n: Integer\nend\nclass C\ninherit A, B\nend\n"))))
  (testing "routines that differ in arity"
    (is (empty? (errors "class A\nfeature\n  m() do end\nend
class B\nfeature\n  m(v: Integer) do end\nend\nclass C\ninherit A, B\nend
let c := create C\nc.m()\nc.m(1)\n")))))
