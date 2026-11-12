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
      "Map_Entry" (ir/object-jvm-type "java/lang/Object")
      "Atomic_Integer" (ir/object-jvm-type "java/lang/Object")
      "Atomic_Integer64" (ir/object-jvm-type "java/lang/Object")
      "Atomic_Boolean" (ir/object-jvm-type "java/lang/Object")
      "Atomic_Reference" (ir/object-jvm-type "java/lang/Object")
      "Task" (ir/object-jvm-type "java/lang/Object")
      "Channel" (ir/object-jvm-type "java/lang/Object")
      (ir/object-jvm-type "java/lang/Object"))

    (string? nex-type)
    (case nex-type
      ;; Integer is 64-bit (Int64), matching the interpreter and the numeric-tower
      ;; model (NUMERIC_TOWER.md).
      "Integer" :long
      ;; Byte, Integer16 and Integer32 are JVM ints (ir/sized-jvm-types),
      ;; boxed to their tagged runtime values (a Short for a Byte, a wrapper
      ;; for the other two, rt/nex-int16?) only where an object is needed.
      ;; Arithmetic on one widens it to a long first (see lower-expr-binary).
      "Byte" :u8
      "Integer16" :i16
      "Integer32" :i32
      "Real" :double
      "Boolean" :boolean
      "Char" :char
      "Void" :void
      ;; A Nex String is a java.lang.String or, when it holds a character
      ;; outside the BMP, a nex.types.runtime.NexWideString (see
      ;; nex.types.runtime "Strings"), so its JVM type is their common one.
      "String" (ir/object-jvm-type "java/lang/Object")
      "Array" (ir/object-jvm-type "java/util/ArrayList")
      "Map" (ir/object-jvm-type "java/util/HashMap")
      "Set" (ir/object-jvm-type "java/util/LinkedHashSet")
      "Min_Heap" (ir/object-jvm-type "java/lang/Object")
      "Map_Entry" (ir/object-jvm-type "java/lang/Object")
      "Atomic_Integer" (ir/object-jvm-type "java/lang/Object")
      "Atomic_Integer64" (ir/object-jvm-type "java/lang/Object")
      "Atomic_Boolean" (ir/object-jvm-type "java/lang/Object")
      "Atomic_Reference" (ir/object-jvm-type "java/lang/Object")
      "Any" (ir/object-jvm-type "java/lang/Object")
      "Comparable" (ir/object-jvm-type "java/lang/Object")
      "Function" (ir/object-jvm-type "java/lang/Object")
      "Cursor" (ir/object-jvm-type "java/lang/Object")
      "Console" (ir/object-jvm-type "java/lang/Object")
      "Program" (ir/object-jvm-type "java/lang/Object")
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
    ;; In a field each sized type has its own JVM type, so a field read by
    ;; reflection comes back as a Byte, Short or Integer the runtime can tell
    ;; apart (nex.types.runtime/field-value->nex). A Byte (0..255) is stored
    ;; as a signed byte and masked back on load.
    (= jvm-type :u8) "B"
    (= jvm-type :i16) "S"
    (= jvm-type :i32) "I"
    (= jvm-type :long) "J"
    (= jvm-type :double) "D"
    (= jvm-type :boolean) "Z"
    ;; A Char is a Unicode code point (up to 0x10FFFF), so a JVM int, not a
    ;; JVM char; it is boxed to a nex.types.runtime.NexChar.
    (= jvm-type :char) "I"
    (= jvm-type :void) "V"
    (ir/object-jvm-type? jvm-type) (str "L" (second jvm-type) ";")
    :else (throw (ex-info "Unknown JVM type" {:jvm-type jvm-type}))))

(defn method-descriptor
  [arg-types return-type]
  (str "("
       (apply str (map jvm-type->descriptor arg-types))
       ")"
       (jvm-type->descriptor return-type)))

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
    nil))
