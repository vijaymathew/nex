(ns nex.non-creatable-builtin-test
  "Some built-in types have no constructor: a Task comes from `spawn`, a Byte
   from a literal or `to_byte`. `create` on one of them used to typecheck and
   then fail in lowering as a construct the compiled backend \"does not
   support yet\" (pointing at --interpret), or in the REPL as \"Undefined
   class\" -- and `create Any` quietly made an object. It is now an error
   saying how to get such a value (nex.types.bootstrap/non-creatable-message).
   A bare `create Map_Entry`, whose constructor is `make(key, value)`, says so."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.eval :as e]
            [nex.repl :as repl]))

(defn- type-errors [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (:errors result))))

(defn- run-file [code interpret?]
  (let [f (java.io.File/createTempFile "non_creatable" ".nex")]
    (try
      (spit f code)
      (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))
      (catch Throwable t
        (str "THROWN " (class t) " " (.getMessage t)))
      (finally (.delete f)))))

(def ^:private cases
  [["Map_Entry[String, Integer]" "create Map_Entry needs a key and a value: create Map_Entry.make(key, value)"]
   ["Map_Entry" "create Map_Entry needs a key and a value"]
   ["Task[Integer]" "Cannot create Task; a Task comes from `spawn`"]
   ["Byte" "Cannot create Byte; write a literal such as `65u8`"]
   ["Integer16" "Cannot create Integer16;"]
   ["Integer32" "Cannot create Integer32;"]
   ["Any" "Cannot create Any;"]
   ["Void" "Cannot create Void;"]
   ["Nil" "Cannot create Nil; the only Nil value is `nil`"]])

(deftest create-of-a-non-creatable-builtin-is-a-type-error
  (doseq [[t expected] cases]
    (testing t
      (let [[err :as errs] (type-errors (str "let x := create " t "\nprint(x)\n"))]
        (is (= 1 (count errs)))
        (is (= 1 (:line err)))
        (is (str/starts-with? (str (:message err)) expected) (:message err))))))

(deftest reported-on-both-backends
  (doseq [interpret? [false true]]
    (testing (if interpret? "interpreter" "compiled")
      (doseq [[t expected] [["Map_Entry[String, Integer]" "create Map_Entry needs a key and a value"]
                            ["Task[Integer]" "Cannot create Task"]]]
        (let [out (run-file (str "let e := create " t "\nprint(e)\n") interpret?)]
          (is (str/includes? out expected) out)
          (is (not (str/includes? out "does not support yet")) out))))))

(deftest a-program-class-with-a-builtin-name-can-be-created
  (is (nil? (type-errors (str "class Task\n"
                              "  feature\n"
                              "    n: Integer\n"
                              "end\n"
                              "let t := create Task\n"
                              "print(t.n)\n"))))
  (doseq [interpret? [false true]]
    (testing (if interpret? "interpreter" "compiled")
      (is (= "0" (str/trim (run-file (str "class Task\n"
                                          "  feature\n"
                                          "    n: Integer\n"
                                          "end\n"
                                          "let t := create Task\n"
                                          "print(t.n)\n")
                                     interpret?)))))))

(deftest reported-by-the-repl
  (doseq [typecheck? [false true]]
    (testing (str "typecheck " typecheck?)
      (let [ctx (repl/init-repl-context)
            out (fn [line] (with-out-str (repl/eval-code ctx line)))]
        (reset! repl/*type-checking-enabled* typecheck?)
        (try
          (doseq [[t expected] cases]
            (testing t
              (is (str/includes? (out (str "create " t)) expected))))
          (testing "a session class with a builtin name"
            (out "class Task feature n: Integer end")
            (is (str/includes? (out "let t := create Task") "Task")))
          (finally
            (reset! repl/*type-checking-enabled* false)
            (reset! repl/*repl-var-types* {})))))))
