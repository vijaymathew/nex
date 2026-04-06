(ns nex.compiler.jvm.descriptor
  "Low-level JVM descriptor helpers for the future bytecode compiler."
  (:require [clojure.string :as str]
            [nex.ir :as ir]))

(defn internal-class-name
  "Convert dotted or path-like class names to JVM internal form."
  [name]
  (-> name
      (str/replace "." "/")
      (str/replace #"^/+" "")))

(defn binary-class-name
  "Convert dotted or path-like class names to JVM binary form."
  [name]
  (-> name
      internal-class-name
      (str/replace "/" ".")))

(defn nex-type->jvm-type
  "Map a Nex type to a simple JVM type shape.

  This is intentionally conservative for the first compiler milestone."
  [nex-type]
  (cond
    (map? nex-type)
    (case (:base-type nex-type)
      "Array" (ir/object-jvm-type "java/util/ArrayList")
      "Map" (ir/object-jvm-type "java/util/HashMap")
      "Set" (ir/object-jvm-type "java/util/LinkedHashSet")
      "Min_Heap" (ir/object-jvm-type "java/lang/Object")
      "Task" (ir/object-jvm-type "java/lang/Object")
      "Channel" (ir/object-jvm-type "java/lang/Object")
      (ir/object-jvm-type "java/lang/Object"))

    (string? nex-type)
    (case nex-type
      "Integer" :int
      "Integer64" :long
      "Real" :double
      "Decimal" :double
      "Boolean" :boolean
      "Char" :char
      "Void" :void
      "String" (ir/object-jvm-type "java/lang/String")
      "Array" (ir/object-jvm-type "java/util/ArrayList")
      "Map" (ir/object-jvm-type "java/util/HashMap")
      "Set" (ir/object-jvm-type "java/util/LinkedHashSet")
      "Min_Heap" (ir/object-jvm-type "java/lang/Object")
      "Any" (ir/object-jvm-type "java/lang/Object")
      "Function" (ir/object-jvm-type "java/lang/Object")
      "Cursor" (ir/object-jvm-type "java/lang/Object")
      "Console" (ir/object-jvm-type "java/lang/Object")
      "Process" (ir/object-jvm-type "java/lang/Object")
      "Task" (ir/object-jvm-type "java/lang/Object")
      "Channel" (ir/object-jvm-type "java/lang/Object")
      (ir/object-jvm-type (internal-class-name nex-type)))

    :else
    (ir/object-jvm-type "java/lang/Object")))

(defn jvm-type->descriptor
  [jvm-type]
  (cond
    (= jvm-type :int) "I"
    (= jvm-type :long) "J"
    (= jvm-type :double) "D"
    (= jvm-type :boolean) "Z"
    (= jvm-type :char) "C"
    (= jvm-type :void) "V"
    (ir/object-jvm-type? jvm-type) (str "L" (second jvm-type) ";")
    :else (throw (ex-info "Unknown JVM type" {:jvm-type jvm-type}))))

(defn method-descriptor
  [arg-types return-type]
  (str "("
       (apply str (map jvm-type->descriptor arg-types))
       ")"
       (jvm-type->descriptor return-type)))

(defn constructor-descriptor
  [arg-types]
  (method-descriptor arg-types :void))

(defn repl-instance-method-descriptor
  []
  "(Lnex/compiler/jvm/runtime/NexReplState;[Ljava/lang/Object;)Ljava/lang/Object;")

(defn boxing-owner
  [jvm-type]
  (case jvm-type
    :int "java/lang/Integer"
    :long "java/lang/Long"
    :double "java/lang/Double"
    :boolean "java/lang/Boolean"
    :char "java/lang/Character"
    nil))

(defn boxing-descriptor
  [jvm-type]
  (when-let [owner (boxing-owner jvm-type)]
    (method-descriptor [jvm-type] (ir/object-jvm-type owner))))

(defn unboxing-method
  [jvm-type]
  (case jvm-type
    :int {:owner "java/lang/Integer" :name "intValue" :descriptor "()I"}
    :long {:owner "java/lang/Long" :name "longValue" :descriptor "()J"}
    :double {:owner "java/lang/Double" :name "doubleValue" :descriptor "()D"}
    :boolean {:owner "java/lang/Boolean" :name "booleanValue" :descriptor "()Z"}
    :char {:owner "java/lang/Character" :name "charValue" :descriptor "()C"}
    nil))
