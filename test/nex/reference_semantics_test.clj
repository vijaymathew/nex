(ns nex.reference-semantics-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(defn- execute-method
  [code class-name method-name]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [class-node (:classes ast)]
            (interp/register-class ctx class-node))
        class-def (first (filter #(= class-name (:name %)) (:classes ast)))
        method-def (->> (:body class-def)
                        (mapcat :members)
                        (filter #(and (= :method (:type %))
                                      (= method-name (:name %))))
                        first)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt (:body method-def)]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

(def item-class
  "class Todo_Item
  create
    make(t: String) do
      title := t
      done := false
    end
  feature
    title: String
    done: Boolean
    mark_done()
      do
        done := true
      end
    is_done(): Boolean
      do
        result := done
      end
end")

(deftest array-get-command-updates-stored-object
  (testing "Calling a command on arr.get(i) writes the updated object back to the array"
    (let [code (str item-class
                    "\n\nclass Demo\n"
                    "  feature\n"
                    "    run() do\n"
                    "      let items := []\n"
                    "      items.add(create Todo_Item.make(\"one\"))\n"
                    "      items.add(create Todo_Item.make(\"two\"))\n"
                    "      items.get(0).mark_done()\n"
                    "      print(items.get(0).is_done())\n"
                    "      print(items.get(1).is_done())\n"
                    "    end\n"
                    "end")]
      (is (= ["true" "false"] (execute-method code "Demo" "run"))))))

(deftest map-access-command-updates-stored-object
  (testing "Calling a command on m.get(key) writes the updated object back to the map"
    (let [code (str item-class
                    "\n\nclass Demo\n"
                    "  feature\n"
                    "    run() do\n"
                    "      let items := {}\n"
                    "      items.put(\"a\", create Todo_Item.make(\"one\"))\n"
                    "      items.get(\"a\").mark_done()\n"
                    "      print(items.get(\"a\").is_done())\n"
                    "    end\n"
                    "end")]
      (is (= ["true"] (execute-method code "Demo" "run"))))))

(deftest env-aliases-see-same-updated-object
  (testing "Rebinding through one alias updates other variables that referenced the same object"
    (let [code "class A
  feature
    x: Integer
    set_x(i: Integer) do
      x := i
    end
    run() do
      let a: A := create A
      a.set_x(20)
      let b: A := a
      b.set_x(30)
      print(a.x)
      print(b.x)
    end
end"]
      (is (= ["30" "30"] (execute-method code "A" "run"))))))

(deftest nested-field-aliases-see-same-updated-object
  (testing "Updating through b.a also updates other aliases of the same A object"
    (let [code "class A
  feature
    x: Integer
    set_x(i: Integer) do
      x := i
    end
end

class B
  feature
    a: A
  create
    make(a: A) do
      this.a := a
    end
end

class T
  feature
    run() do
      let a: A := create A
      let b: B := create B.make(a)
      b.a.set_x(10)
      print(b.a.x)
      print(a.x)
    end
end"]
      (is (= ["10" "10"] (execute-method code "T" "run"))))))

(deftest query-chain-updates-propagate-through-returned-fields
  (testing "Commands on objects returned through query chains write back through the full chain"
    (let [code "class C
  feature
    value: Integer
    set_value(x: Integer) do
      value := x
    end
end

class B
  create
    make(x: C) do
      c := x
    end
  feature
    c: C
    child(): C do
      result := c
    end
end

class A
  create
    make(x: B) do
      b := x
    end
  feature
    b: B
    middle(): B do
      result := b
    end
end

class Demo
  feature
    run() do
      let c: C := create C
      let b: B := create B.make(c)
      let a: A := create A.make(b)
      a.middle().child().set_value(42)
      print(a.middle().child().value)
      print(c.value)
    end
end"]
      (is (= ["42" "42"] (execute-method code "Demo" "run"))))))

(deftest query-returning-array-element-writes-back-to-stored-object
  (testing "Commands on objects returned by user-defined queries over array fields update the stored element"
    (let [code (str item-class
                    "\n\nclass Task_List\n"
                    "  create\n"
                    "    make() do\n"
                    "      tasks := []\n"
                    "    end\n"
                    "  feature\n"
                    "    tasks: Array[Todo_Item]\n"
                    "    add_task(title: String)\n"
                    "      do\n"
                    "        tasks.add(create Todo_Item.make(title))\n"
                    "      end\n"
                    "    task_at(index: Integer): Todo_Item\n"
                    "      do\n"
                    "        result := tasks.get(index)\n"
                    "      end\n"
                    "end\n"
                    "\nclass Demo\n"
                    "  feature\n"
                    "    run() do\n"
                    "      let todo := create Task_List.make\n"
                    "      todo.add_task(\"one\")\n"
                    "      todo.task_at(0).mark_done()\n"
                    "      print(todo.task_at(0).is_done())\n"
                    "    end\n"
                    "end")]
      (is (= ["true"] (execute-method code "Demo" "run"))))))
