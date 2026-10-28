(ns nex.old-keyword-test
  "Tests for 'old' keyword in postconditions"
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp :refer [eval-node]]))

(deftest old-keyword-parsing-test
  (testing "Parse 'old' keyword in postcondition"
    (let [code "class A
  feature
    i: Integer
    increment do
      i := i + 1
      ensure
        i_incremented: old i = i - 1
      end
  end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method (-> class-def :body first :members second)
          postcondition (-> method :ensure first :condition)]
      (is (= :binary (:type postcondition)))
      (is (= "=" (:operator postcondition)))
      (is (= :old (-> postcondition :left :type)))
      (is (= "i" (-> postcondition :left :expr :name))))))

(deftest old-keyword-evaluation-test
  (testing "'old' correctly captures pre-execution field values"
    (let [code "class Counter
  feature
    i: Integer
    increment do
      i := i + 1
      ensure
        i_incremented_by_one: old i = i - 1
      end
  end"
          ctx (-> code p/ast interp/interpret)
          create-node {:type :create :class-name "Counter" :constructor nil :args []}
          obj (eval-node ctx create-node)
          _ (interp/env-define (:current-env ctx) "counter" obj)
          call-node {:type :call :target "counter" :method "increment" :args []}
          _ (eval-node ctx call-node)
          updated-obj (interp/env-lookup (:current-env ctx) "counter")]
      ;; Object should be updated from 0 to 1
      (is (= 0 (get (:fields obj) :i)))
      (is (= 1 (get (:fields updated-obj) :i))))))

(deftest old-keyword-violation-test
  (testing "Postcondition with 'old' correctly detects violations"
    (let [code "class Counter
  feature
    i: Integer
    badIncrement do
      i := i + 2
      ensure
        i_incremented_by_one: old i = i - 1
      end
  end"
          ctx (-> code p/ast interp/interpret)
          create-node {:type :create :class-name "Counter" :constructor nil :args []}
          obj (eval-node ctx create-node)
          _ (interp/env-define (:current-env ctx) "counter" obj)
          call-node {:type :call :target "counter" :method "badIncrement" :args []}]
      ;; Should throw postcondition violation
      (is (thrown-with-msg? Exception #"Postcondition violation"
                            (eval-node ctx call-node))))))

(deftest old-keyword-rollback-test
  (testing "Fields are rolled back when postcondition fails"
    (let [code "class Counter
  feature
    i: Integer
    badIncrement do
      i := i + 2
      ensure
        must_be_one: i = 1
      end
  end"
          ctx (-> code p/ast interp/interpret)
          create-node {:type :create :class-name "Counter" :constructor nil :args []}
          obj (eval-node ctx create-node)
          _ (interp/env-define (:current-env ctx) "counter" obj)
          call-node {:type :call :target "counter" :method "badIncrement" :args []}]
      ;; Try to call method (should fail)
      (try
        (eval-node ctx call-node)
        (catch Exception _e nil))
      ;; Check that field was rolled back
      (let [counter-after (interp/env-lookup (:current-env ctx) "counter")]
        (is (= 0 (get (:fields counter-after) :i)))))))

(deftest old-keyword-multiple-fields-test
  (testing "'old' works with multiple fields"
    (let [code "class Point
  feature
    x: Integer
    y: Integer
    moveRight do
      x := x + 1
      ensure
        x_incremented: old x = x - 1
        y_unchanged: old y = y
      end
  end"
          ctx (-> code p/ast interp/interpret)
          create-node {:type :create :class-name "Point" :constructor nil :args []}
          obj (eval-node ctx create-node)
          _ (interp/env-define (:current-env ctx) "point" obj)
          call-node {:type :call :target "point" :method "moveRight" :args []}
          _ (eval-node ctx call-node)
          updated-obj (interp/env-lookup (:current-env ctx) "point")]
      ;; Both postconditions should pass
      (is (= 0 (get (:fields obj) :x)))
      (is (= 1 (get (:fields updated-obj) :x)))
      (is (= 0 (get (:fields obj) :y)))
      (is (= 0 (get (:fields updated-obj) :y))))))

(deftest old-keyword-array-field-length-snapshots-value-not-reference-test
  (testing "'old' on a query over a mutable Array field (items.length) snapshots membership at method entry, not a live reference to the same ArrayList the method body then mutates in place"
    (let [code "class Basket
  feature
    items: Array[Integer]
    add_item(x: Integer) do
      items.add(x)
      ensure
        grew: items.length = old items.length + 1
      end
  end"
          ctx (-> code p/ast interp/interpret)
          create-node {:type :create :class-name "Basket" :constructor nil :args []}
          obj (eval-node ctx create-node)
          _ (interp/env-define (:current-env ctx) "b" obj)
          call-node {:type :call :target "b" :method "add_item" :args [{:type :integer :value 1}]}
          _ (eval-node ctx call-node)
          _ (eval-node ctx (assoc call-node :args [{:type :integer :value 2}]))
          updated-obj (interp/env-lookup (:current-env ctx) "b")]
      (is (= 2 (interp/nex-array-size (get (:fields updated-obj) :items)))))))

(deftest old-keyword-outside-postcondition-error-test
  (testing "'old' throws error when used outside postcondition"
    (let [code "class A
  feature
    i: Integer
    bad do
      let x := old i
    end
  end"
          ctx (-> code p/ast interp/interpret)
          create-node {:type :create :class-name "A" :constructor nil :args []}
          obj (eval-node ctx create-node)
          _ (interp/env-define (:current-env ctx) "a" obj)
          call-node {:type :call :target "a" :method "bad" :args []}]
      ;; Should throw error about 'old' only being valid in postconditions
      (is (thrown-with-msg? Exception #"'old' can only be used in postconditions"
                            (eval-node ctx call-node))))))

(def ^:private old-entry-value-program
  "class Box
create
  make() do
    n := 0
  end
feature
  n: Integer
  inc() do
    n := n + 1
  end
  doubled(): Integer do
    result := n * 2
  end
end

class Counter
create
  make() do
    count := 0
    box := create Box.make
    items := []
  end
feature
  count: Integer
  box: Box
  items: Array[Integer]
  twice(): Integer do
    result := count * 2
  end
  bump() do
    count := count + 1
    box.inc
  ensure
    via_query: twice() = old twice() + 2
    via_nested: box.n = old box.n + 1
    via_nested_query: box.doubled() = old box.doubled() + 2
  end
  bump_other(other: Box) do
    other.inc
  ensure
    moved: other.n = old other.n + 1
  end
  forget_other(other: Box) do
  ensure
    moved: other.n = old other.n + 1
  end
  keep_first()
  require
    has_first: items.length > 0
  do
  ensure
    same_first: items.get(0) = old items.get(0)
  end
end")

(deftest old-keyword-evaluates-whole-expression-on-entry-test
  (testing "`old e` is E evaluated on entry, as in Eiffel: queries, nested objects and parameters all report their entry state"
    (let [run (fn [stmts]
                (let [ctx (-> (str old-entry-value-program "\n\n" stmts) p/ast interp/interpret)]
                  (interp/env-lookup (:current-env ctx) "total")))]
      (is (= 3 (run (str "let c := create Counter.make()\n"
                         "let b := create Box.make()\n"
                         "c.bump()\n"
                         "c.bump()\n"
                         "c.bump_other(b)\n"
                         "let total := c.box.n + b.n")))))
    (is (thrown-with-msg? Exception #"Postcondition violation: moved"
                          (-> (str old-entry-value-program "\n\n"
                                   "let c := create Counter.make()\n"
                                   "c.forget_other(create Box.make())")
                              p/ast interp/interpret)))
    (testing "the precondition runs first, so it guards `old items.get(0)`"
      (is (thrown-with-msg? Exception #"Precondition violation: has_first"
                            (-> (str old-entry-value-program "\n\n"
                                     "let c := create Counter.make()\n"
                                     "c.keep_first()")
                                p/ast interp/interpret))))))
