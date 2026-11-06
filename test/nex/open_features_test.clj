(ns nex.open-features-test
  "Open features: `extend Target feature ... end` (docs/proposals/open-features.md).

   1. An extension adds routines to a class from outside its declaration; they
      are ordinary features on both backends: dynamic dispatch, defaults,
      redefinition in heirs, attributes answering an extended query, calls
      through a constrained generic.
   2. Contracts declared in an extension are inherited by every
      implementation, and the target's invariant is checked around extended
      routines.
   3. A deferred extended feature must be implemented by every concrete heir in
      the program; the error names the extension.
   4. Conflicts, restrictions and visibility: an extension cannot replace a
      class's own feature, add a feature another extension added, add state,
      or see the target's private features.
   5. Across modules: a program combining a module that adds an operation with
      one that adds a variant must supply the missing implementation.
   6. In the REPL, an `extend` input redefines its target class with the
      features added, on both backends, and a module interned by an input is
      checked with the session.
   7. Built-in types: an extension's features become generated functions,
      reached from calls on the type's values on both backends, `this` and
      the type's own features usable in their bodies; extensions of Any and
      Comparable reach user objects too."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.repl :as repl]))

(defn- run-file [path opts]
  (str/split-lines (str/trim-newline (with-out-str (e/eval-file path opts)))))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "open_features" ".nex")]
    (try
      (spit f code)
      (run-file (.getPath f) opts)
      (finally (.delete f)))))

(defn- both
  "Printed output of CODE, asserted identical on both backends."
  [code]
  (let [compiled (run code {})]
    (is (= (run code {:interpret? true}) compiled) "compiled and interpreted output must agree")
    compiled))

(defn- error-message [code]
  (try (run code {}) nil
       (catch Exception ex (.getMessage ex))))

(def ^:private shapes
  "deferred class Shape
end

class Circle
  inherit Shape
  create make(r: Real) do radius := r end
  feature
    radius: Real
  private feature
    secret: Integer do result := 7 end
end

class Rect
  inherit Shape
  create make(w, h: Real) do width := w height := h end
  feature
    width: Real
    height: Real
end
")

(def ^:private area-extensions
  "extend Shape
feature
  area(): Real
    deferred
    ensure non_negative: result >= 0.0
    end

  describe: String do
    result := \"shape with area \" + area.to_string
  end
end

extend Circle
feature
  area(): Real do result := 3.0 * radius * radius end
end

extend Rect
feature
  area(): Real do result := width * height end
  describe: String do result := \"rectangle\" end
end
")

(deftest extended-features-are-ordinary-features-test
  (testing "dynamic dispatch, a default body, and a redefinition in an heir"
    (is (= ["3.0" "\"shape with area 3.0\"" "6.0" "\"rectangle\""]
           (both (str shapes area-extensions
                      "let xs: Array[Shape] := [create Circle.make(1.0), create Rect.make(2.0, 3.0)]
across xs as s do
  print(s.area)
  print(s.describe)
end
")))))
  (testing "an attribute answers an extended deferred query"
    (is (= ["4.0"]
           (both (str shapes area-extensions
                      "class Tile
  inherit Shape
  create make(a: Real) do area := a end
  feature area: Real
end

let s: Shape := create Tile.make(4.0)
print(s.area)
")))))
  (testing "a call through a constrained generic reaches the extended feature"
    (is (= ["9.0"]
           (both (str shapes area-extensions
                      "function total[T -> Shape](xs: Array[T]): Real do
  result := 0.0
  across xs as x do result := result + x.area end
end

let xs: Array[Shape] := [create Circle.make(1.0), create Rect.make(2.0, 3.0)]
print(total(xs))
")))))
  (testing "an extension of a generic class restates its parameters"
    (is (= ["true" "false"]
           (both "class Box [T]
  create make(v: T) do value := v end
  feature value: T
end

extend Box [T]
feature
  same(other: Box [T]): Boolean do result := value = other.value end
end

let a: Box[Integer] := create Box[Integer].make(1)
print(a.same(create Box[Integer].make(1)))
print(a.same(create Box[Integer].make(2)))
"))))
  (testing "`extend` is still an ordinary name"
    (is (= ["3"] (both "let extend := 3\nprint(extend)\n")))))

(deftest extended-contracts-and-invariants-test
  (testing "an implementation inherits the extension's postcondition"
    (let [out (both (str shapes area-extensions
                         "class Hole
  inherit Shape
  create make do end
end

extend Hole
feature
  area(): Real do result := -1.0 end
end

do
  print((create Hole.make).area)
rescue
  print(exception.to_string)
end
"))]
      (is (= 1 (count out)))
      (is (str/includes? (first out) "non_negative"))))
  (testing "the target's invariant is checked when an extended routine returns"
    (let [out (both "class Counter
  create make do count := 1 end
  feature
    count: Integer
    set_count(n: Integer) do count := n end
  invariant
    non_negative: count >= 0
end

extend Counter
feature
  overdraw do set_count(-1) end
end

do
  let c := create Counter.make
  c.overdraw
  print(\"not reached\")
rescue
  print(exception.to_string)
end
")]
      (is (= 1 (count out)))
      (is (str/includes? (first out) "non_negative")))))

(deftest coverage-test
  (testing "a concrete heir without the deferred feature is rejected, naming the extension"
    (let [msg (error-message (str shapes area-extensions
                                  "class Triangle
  inherit Shape
  create make do end
end
"))]
      (is (some? msg))
      (is (str/includes? msg "Class 'Triangle' does not implement deferred feature 'area'"))
      (is (str/includes? msg "declared by 'extend Shape'"))))
  (testing "a deferred heir is exempt"
    (is (= ["\"ok\""]
           (both (str shapes area-extensions
                      "deferred class Polygon
  inherit Shape
end
print(\"ok\")
")))))
  (testing "a class's own routine adopts an extension's deferred feature and its contract"
    (let [out (both "deferred class Shape
end

class Dot
  inherit Shape
  create make do end
  feature
    area(): Real do result := -2.0 end
end

extend Shape
feature
  area(): Real
    deferred
    ensure non_negative: result >= 0.0
    end
end

do
  let s: Shape := create Dot.make
  print(s.area)
rescue
  print(exception.to_string)
end
")]
      (is (str/includes? (first out) "non_negative")))))

(deftest conflicts-and-restrictions-test
  (testing "an extension cannot replace a class's own feature"
    (is (str/includes? (error-message (str shapes "extend Circle feature radius(): Real do result := 1.0 end end\n"))
                       "Class 'Circle' already declares feature 'radius'")))
  (testing "two extensions cannot add the same feature"
    (is (str/includes? (error-message (str shapes
                                           "extend Circle feature size(): Integer do result := 1 end end\n"
                                           "extend Circle feature size(): Integer do result := 2 end end\n"))
                       "already added by 'extend Circle'")))
  (testing "an unknown target"
    (is (str/includes? (error-message "extend Nope feature f(): Integer do result := 1 end end\n")
                       "Cannot extend unknown class 'Nope'")))
  (testing "a built-in type that cannot be extended"
    (is (str/includes? (error-message "extend Cursor feature f(): Integer do result := 1 end end\n")
                       "Extending built-in type 'Cursor' is not supported")))
  (testing "a generic target needs its generic parameters"
    (is (str/includes? (error-message "class Box [T]
  create make(v: T) do value := v end
  feature value: T
end
extend Box feature f(): Integer do result := 1 end end
")
                       "Write its generic parameters")))
  (testing "fields, once features and private sections are rejected when parsed"
    (is (str/includes? (error-message (str shapes "extend Circle feature extra: Integer end\n"))
                       "Field 'extra' is not allowed in an 'extend Circle' block"))
    (is (str/includes? (error-message (str shapes "extend Circle private feature f(): Integer do result := 1 end end\n"))
                       "A private feature section is not allowed"))))

(deftest client-visibility-test
  (testing "an extension does not see its target's private features"
    (doseq [body ["result := secret" "result := this.secret"]]
      (is (str/includes? (error-message (str shapes "extend Circle feature f(): Integer do " body " end end\n"))
                         "`secret` is private to Circle"))))
  (testing "an extension cannot assign its target's fields"
    (is (str/includes? (error-message (str shapes "extend Circle feature grow do radius := radius + 1.0 end end\n"))
                       "Cannot assign to field radius in an 'extend Circle' block"))))

(defn- write-module! [dir file-name text]
  (spit (io/file dir file-name) text)
  (.getPath (io/file dir file-name)))

(deftest modules-test
  (let [dir (doto (.toFile (java.nio.file.Files/createTempDirectory
                            "open_features"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
              (.deleteOnExit))]
    (write-module! dir "Geometry.nex" shapes)
    (write-module! dir "Areas.nex" (str "intern Geometry\n\n" area-extensions))
    (write-module! dir "Triangles.nex" "intern Geometry

class Triangle
  inherit Shape
  create make(b, h: Real) do base := b height := h end
  feature
    base: Real
    height: Real
end
")
    (testing "combining a new operation with a new variant needs glue"
      (let [main (write-module! dir "main.nex" "intern Areas
intern Triangles

print((create Triangle.make(2.0, 3.0)).base)
")
            msg (try (run-file main {}) nil (catch Exception ex (.getMessage ex)))]
        (is (some? msg))
        (is (str/includes? msg "Class 'Triangle' does not implement deferred feature 'area'"))
        (is (str/includes? msg "Areas.nex"))))
    (testing "with the glue extension the program runs on both backends"
      (let [main (write-module! dir "main.nex" "intern Areas
intern Triangles

extend Triangle
feature
  area(): Real do result := base * height / 2.0 end
end

let xs: Array[Shape] := [create Circle.make(1.0), create Triangle.make(2.0, 3.0)]
across xs as s do print(s.area) end
")
            compiled (run-file main {})]
        (is (= ["3.0" "3.0"] compiled))
        (is (= compiled (run-file main {:interpret? true})))))
    (testing "an interned module may extend a class the interning file declares"
      (write-module! dir "Greeting.nex" "extend Person
feature
  greeting: String do result := \"hello, \" + name end
end
")
      (let [main (write-module! dir "people.nex" "intern Greeting

class Person
  create make(n: String) do name := n end
  feature name: String
end

print((create Person.make(\"Ada\")).greeting)
")
            compiled (run-file main {})]
        (is (= ["\"hello, Ada\""] compiled))
        (is (= compiled (run-file main {:interpret? true})))))))

(deftest parse-shape-test
  (let [ast (p/ast (str shapes area-extensions))]
    (is (= ["Shape" "Circle" "Rect"] (mapv :target (:extensions ast))))
    (is (empty? (filter #(= :extension (:type %)) (:statements ast))))))

(defn- repl-session
  "What each of INPUTS prints, run in order in one fresh REPL session on
   BACKEND, type checking on."
  [backend inputs]
  (binding [repl/*type-checking-enabled* (atom true)
            repl/*repl-var-types* (atom {})
            repl/*repl-type-aliases* (atom {})
            repl/*repl-backend* (atom backend)
            repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
    (let [ctx (repl/init-repl-context)]
      (mapv #(str/trim (with-out-str (binding [*err* *out*] (repl/eval-code ctx %))))
            inputs))))

(defn- in-both-repls
  "Run INPUTS in a compiled and in an interpreted REPL session, and call
   CHECK with what each input printed, for each."
  [inputs check]
  (doseq [backend [:compiled :interpreter]]
    (testing (name backend)
      (check (repl-session backend inputs)))))

(deftest repl-extensions-test
  (testing "an extension of an earlier input's class, and of the class's parent"
    (in-both-repls
     ["deferred class Shape end"
      "class Dot inherit Shape create make do end feature x: Integer do result := 1 end private feature secret: Integer do result := 9 end end"
      "extend Dot feature twice(): Integer do result := x * 2 end end"
      "print((create Dot.make).twice)"
      "extend Dot feature twice(): Integer do result := 3 end end"
      "extend Shape feature area(): Real deferred end"
      "extend Dot feature area(): Real do result := 2.5 end end"
      "let s: Shape := create Dot.make"
      "print(s.area)"
      "extend Dot feature peek(): Integer do result := secret end end"]
     (fn [out]
       (is (= "2" (nth out 3)))
       (is (str/includes? (nth out 4) "already added by 'extend Dot'"))
       (is (= "2.5" (nth out 8)))
       (is (str/includes? (nth out 9) "`secret` is private to Dot")))))
  (testing "a class and its extension in one input"
    (in-both-repls
     ["class Pt create make do end end extend Pt feature y(): Integer do result := 5 end end print((create Pt.make).y)"]
     (fn [out]
       (is (= "5" (last (str/split-lines (first out))))))))
  (testing "an extension of a class not yet known"
    (in-both-repls
     ["extend Nowhere feature f(): Integer do result := 1 end end"]
     (fn [out]
       (is (str/includes? (first out) "Cannot extend unknown class 'Nowhere'"))))))

(deftest repl-module-extensions-test
  (let [dir (doto (.toFile (java.nio.file.Files/createTempDirectory
                            "open_features_repl"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
              (.deleteOnExit))
        original (System/getProperty "nex.user.dir")]
    (write-module! dir "Geometry.nex" shapes)
    (write-module! dir "Areas.nex" (str "intern Geometry\n\n" area-extensions))
    (write-module! dir "Triangles.nex" "intern Geometry

class Triangle
  inherit Shape
  create make(b, h: Real) do base := b height := h end
  feature
    base: Real
    height: Real
end
")
    (write-module! dir "Greeting.nex" "extend Person
feature
  greeting: String do result := \"hello, \" + name end
end
")
    (try
      (System/setProperty "nex.user.dir" (.getPath dir))
      (in-both-repls
       ["class Person create make(n: String) do name := n end feature name: String end"
        "intern Greeting"
        "print((create Person.make(\"Ada\")).greeting)"
        "intern Areas"
        "intern Areas"
        "print((create Circle.make(2.0)).area)"
        "intern Triangles"
        "intern Triangles\nextend Triangle feature area(): Real do result := base * height / 2.0 end end"
        "print((create Triangle.make(2.0, 3.0)).area)"]
       (fn [out]
         (testing "a module extends a class an earlier input declared"
           (is (= "\"hello, Ada\"" (nth out 2))))
         (testing "interning a module with extensions again changes nothing"
           (is (= "" (nth out 4)))
           (is (= "12.0" (nth out 5))))
         (testing "a module's class missing an extended deferred feature is rejected when interned"
           (is (str/includes? (nth out 6) "Class 'Triangle' does not implement deferred feature 'area'"))
           (is (str/includes? (nth out 6) "Areas.nex")))
         (testing "the same input can intern it and supply the feature"
           (is (= "3.0" (nth out 8))))))
      (finally
        (if original
          (System/setProperty "nex.user.dir" original)
          (System/clearProperty "nex.user.dir"))))))

(def ^:private builtin-extensions
  "extend String
feature
  is_palindrome: Boolean do
    let s := to_lower
    result := true
    from let i := 0 until i >= length / 2 do
      if s.char_at(i) /= s.char_at(length - 1 - i) then result := false end
      i := i + 1
    end
  end

  shout(n: Integer): String
    require positive: n > 0
    do
      result := to_upper
      from let i := 1 until i >= n do
        result := result + \"!\"
        i := i + 1
      end
    end
end

extend Integer
feature
  squared: Integer do result := this * this end
  is_even_square: Boolean do result := squared % 2 = 0 end
end

extend Array [T -> Comparable]
feature
  maximum: T
    require non_empty: not is_empty
    do
      result := get(0)
      across this as x do
        if x > result then result := x end
      end
    end
end
")

(deftest builtin-extensions-test
  (testing "features of String, Integer and a constrained Array"
    (is (= ["true" "false" "\"HEY!!\"" "49" "true" "9" "\"c\""]
           (both (str builtin-extensions
                      "print(\"Level\".is_palindrome)
print(\"abc\".is_palindrome)
print(\"hey\".shout(3))
print(7.squared)
print(4.is_even_square)
print([3, 9, 4].maximum)
print([\"b\", \"c\", \"a\"].maximum)
")))))
  (testing "a precondition of a built-in extension's feature"
    (is (= ["\"Precondition violation: positive\""]
           (both (str builtin-extensions
                      "do print(\"x\".shout(0)) rescue print(exception.to_string) end\n")))))
  (testing "Any and Comparable reach built-in values and user objects"
    (is (= ["\"[5]\"" "\"[Dog Rex]\"" "\"[Dog Fido]\"" "true" "false" "true"]
           (both "class Dog
  create make(n: String) do name := n end
  feature
    name: String
    to_string: String do result := \"Dog \" + name end
end

class Money
  inherit Comparable
  create make(c: Integer) do cents := c end
  feature
    cents: Integer
    compare(a: Any): Integer do
      if convert a to m: Money then result := cents - m.cents else result := 0 end
    end
end

extend Any
feature
  bracketed: String do result := \"[\" + to_string + \"]\" end
end

extend Comparable
feature
  is_between(lo, hi: Any): Boolean do result := compare(lo) >= 0 and compare(hi) <= 0 end
end

print(5.bracketed)
print((create Dog.make(\"Rex\")).bracketed)
let x: Any := create Dog.make(\"Fido\")
print(x.bracketed)
print(5.is_between(1, 10))
print(\"m\".is_between(\"a\", \"f\"))
print((create Money.make(50)).is_between(create Money.make(10), create Money.make(100)))
"))))
  (testing "errors"
    (doseq [[code expected]
            [["extend String feature length: Integer do result := 1 end end\n"
              "'String' already has feature 'length'"]
             ["extend String feature f(): Integer deferred end\n"
              "cannot be deferred"]
             ["extend Array feature f: Integer do result := 1 end end\n"
              "Write its generic parameters in the extension: 'extend Array [T]'"]
             ["extend Array [E] feature f: Integer do result := 1 end end\n"
              "write 'extend Array [T]'"]
             ["class Dog create make do end end
extend Array [T -> Comparable] feature first_one: T do result := get(0) end end
let ds: Array[Dog] := [create Dog.make]
print(ds.first_one)
" "'first_one' is a feature of Array [T -> Comparable] only, and Dog does not satisfy Comparable"]
             ["extend String feature f: Integer do let length := 1 result := length end end\n"
              "would hide feature 'length' of String"]
             ["class Dog create make do end feature tag: String do result := \"d\" end end
extend Any feature tag: String do result := \"a\" end end
" "Class 'Dog' declares feature 'tag', which 'extend Any' also adds"]
             ["extend String feature f: Integer do result := 1 end end
extend String feature f: Integer do result := 2 end end
" "already added by another extension"]]]
      (is (str/includes? (str (error-message code)) expected) code))))

(deftest system-type-extensions-test
  (testing "Console, Program and Process, self and an unstarted child"
    (is (= ["== hi ==" "true" "\"self\"" "\"child\""]
           (both "extend Console
feature
  banner(s: String) do print_line(\"== \" + s + \" ==\") end
end

extend Program
feature
  arg_count: Integer do result := arguments.length end
end

extend Process
feature
  kind: String do
    if is_self then result := \"self\" else result := \"child\" end
  end
end

console.banner(\"hi\")
print(program.arg_count >= 0)
print((create Process.self).kind)
print((create Process.command(\"ls\")).kind)
")))))

(deftest builtin-extensions-in-modules-test
  (let [dir (doto (.toFile (java.nio.file.Files/createTempDirectory
                            "open_features_builtin"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
              (.deleteOnExit))]
    (write-module! dir "Strings.nex" "extend String
feature
  exclaim: String do result := this + \"!\" end
end
")
    (write-module! dir "Loud.nex" "intern Strings
extend String
feature
  loud: String do result := to_upper.exclaim end
end
")
    (let [main (write-module! dir "main.nex" "intern Loud
intern Strings
print(\"hi\".loud)
print(\"ok\".exclaim)
")
          compiled (run-file main {})]
      (is (= ["\"HI!\"" "\"ok!\""] compiled))
      (is (= compiled (run-file main {:interpret? true}))))))

(deftest repl-builtin-extensions-test
  (in-both-repls
   ["extend Integer feature squared: Integer do result := this * this end end"
    "print(7.squared)"
    "let n := 5"
    "print(n.squared + 1)"
    "extend String feature twice: String do result := this + this end end print(\"ab\".twice)"
    "extend Integer feature cubed: Integer do result := squared * this end end"
    "print(3.cubed)"]
   (fn [out]
     (is (= "49" (nth out 1)))
     (is (= "26" (nth out 3)))
     (is (= "\"abab\"" (last (str/split-lines (nth out 4)))))
     (is (= "27" (nth out 6))))))

(deftest comparable-receiver-test
  (testing "`compare` through a Comparable-typed receiver reaches a user object on the compiled backend"
    (is (= ["2" "-1"]
           (both "class Money
  inherit Comparable
  create make(c: Integer) do cents := c end
  feature
    cents: Integer
    compare(a: Any): Integer do
      if convert a to m: Money then result := cents - m.cents else result := 0 end
    end
end
function cmp(x: Comparable, y: Any): Integer do result := x.compare(y) end
print(cmp(create Money.make(5), create Money.make(3)))
print(cmp(4, 9))
")))))
