(ns nex.void-safety-inheritance-test
  "Void safety for *inherited* attachable fields.

   A field of an attached (non-detachable) class type may never be void, and the
   checker enforces that by requiring every constructor to initialize one. It
   built that requirement from the class's own feature sections only, so a
   subclass was never asked about the fields it inherits:

       class B  feature a: A  create make(a: A) do this.a := a end  end
       class C inherit B  create make() do end  end
       print(create C.make.a)   -- nil, from a field typed `A`, not `?A`

   B's own constructor initializes `a`, so B passed; C declared no fields, so the
   check skipped C entirely and nothing ever ran B's constructor.

   A subclass cannot assign an inherited field directly (\"Cannot assign to field
   a outside of class B\"), so calling the parent's constructor is the only way it
   can initialize one — that is what is now required."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- type-errors
  "The type errors CODE produces, or nil when it type-checks."
  [code]
  (let [f (java.io.File/createTempFile "void_safety" ".nex")]
    (try
      (spit f code)
      (try (with-out-str (e/eval-file (.getPath f) {}))
           nil
           (catch clojure.lang.ExceptionInfo ex
             (or (seq (:errors (ex-data ex))) [(ex-message ex)])))
      (finally (.delete f)))))

(defn- output
  "Printed output of CODE on both backends, asserted equal, and returned."
  [code]
  (letfn [(run [interpret?]
            (let [f (java.io.File/createTempFile "void_safety" ".nex")]
              (try
                (spit f code)
                (->> (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))
                     str/trim-newline
                     str/split-lines
                     (remove #(str/starts-with? % "Warning:"))
                     vec)
                (finally (.delete f)))))]
    (let [compiled (run false)
          interpreted (run true)]
      (is (= interpreted compiled) "compiled and interpreted output must agree")
      compiled)))

(def ^:private base
  "class A
feature
  i: Integer
create
  make(v: Integer) do i := v end
end

class B
feature
  a: A
create
  make(a: A)
  do
    this.a := a
  end
end
")

(deftest subclass-ctor-must-initialize-inherited-attachable-field
  (testing "a constructor that never reaches the parent's is rejected"
    (let [errs (type-errors (str base "class C inherit B
create
  make()
  do end
end
let c := create C.make
print(c.a)"))]
      (is (some #(re-find #"must call a constructor of B" %) errs)
          (pr-str errs))
      ;; The message has to name the field: the constructor looks complete, and
      ;; what is missing is inherited from somewhere else in the file.
      (is (some #(re-find #"\ba\b" %) errs) (pr-str errs)))))

(deftest subclass-ctor-chaining-to-parent-is-accepted
  (testing "calling the parent's constructor initializes the inherited field"
    (is (= ["7"] (output (str base "class C inherit B
create
  make(v: Integer) do B.make(create A.make(v)) end
end
let c := create C.make(7)
print(c.a.i)"))))))

(deftest subclass-without-own-constructors-is-accepted
  (testing "a subclass that declares none inherits the parent's, which initialize"
    (is (= ["1"] (output (str base "class C inherit B
end
let c := create C.make(create A.make(1))
print(c.a.i)"))))))

(deftest grandchild-ctor-must-chain
  (testing "the requirement holds down a chain, and one link is enough"
    ;; D calling C's constructor is enough: C's own check already guarantees C's
    ;; constructors reach B's, so checking direct parents covers the chain.
    (is (= ["9"] (output (str base "class C inherit B
create
  make(v: Integer) do B.make(create A.make(v)) end
end
class D inherit C
create
  make() do C.make(9) end
end
let d := create D.make
print(d.a.i)"))))
    (is (some #(re-find #"must call a constructor of C" %)
              (type-errors (str base "class C inherit B
create
  make(v: Integer) do B.make(create A.make(v)) end
end
class D inherit C
create
  make() do end
end
let d := create D.make
print(d.a.i)"))))))

(deftest detachable-inherited-field-needs-no-chaining
  (testing "a `?A` field may be void, so a subclass need not initialize it"
    (is (= ["nil"] (output "class A
feature
  i: Integer
end

class B
feature
  a: ?A
create
  make() do end
end

class C inherit B
create
  make() do end
end
let c := create C.make
print(c.a)")))))

(deftest builtin-typed-inherited-field-needs-no-chaining
  (testing "a scalar field has a zero value, so it does not force chaining"
    (is (= ["0"] (output "class B
feature
  n: Integer
create
  make() do end
end

class C inherit B
create
  make() do end
end
let c := create C.make
print(c.n)")))))

(deftest parent-call-inside-control-flow-counts
  (testing "the parent constructor call is found wherever the body may run it"
    (is (= ["3"] (output (str base "class C inherit B
create
  make(v: Integer)
  do
    if v > 0 then
      B.make(create A.make(v))
    else
      B.make(create A.make(0))
    end
  end
end
let c := create C.make(3)
print(c.a.i)"))))))

(deftest own-attachable-field-check-still-applies
  (testing "the pre-existing own-field requirement is unchanged"
    (is (some #(re-find #"must initialize attachable fields" %)
              (type-errors (str base "class D
feature
  a: A
create
  make() do end
end
let d := create D.make
print(d.a)"))))))
