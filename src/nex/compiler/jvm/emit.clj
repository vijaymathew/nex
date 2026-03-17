(ns nex.compiler.jvm.emit
  "Minimal ASM-backed JVM bytecode emission for Nex.

  This first emitter milestone supports one trivial REPL cell class with:

  - public default constructor
  - public static Object eval(NexReplState state)

  The initial `eval` body ignores the IR and returns `null`."
  (:require [nex.compiler.jvm.descriptor :as desc]
            [nex.ir :as ir])
  (:import [org.objectweb.asm ClassWriter Label MethodVisitor Opcodes]))

(def ^:private class-version Opcodes/V17)

(defn eval-method-descriptor
  []
  (desc/method-descriptor
   [(ir/object-jvm-type "nex/compiler/jvm/runtime/NexReplState")]
   (ir/object-jvm-type "java/lang/Object")))

(defn minimal-class-spec
  "Create a minimal class spec for a compiled REPL cell."
  [unit]
  {:internal-name (desc/internal-class-name (:name unit))
   :binary-name (desc/binary-class-name (:name unit))
   :super-name "java/lang/Object"
   :interfaces []
   :flags Opcodes/ACC_PUBLIC
   :methods [{:name "<init>"
              :descriptor "()V"
              :flags Opcodes/ACC_PUBLIC
              :kind :default-constructor}
             {:name "eval"
              :descriptor (eval-method-descriptor)
              :flags (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
              :kind :eval-from-ir
              :body (:body unit)}]})

(defn- emit-default-constructor!
  [^ClassWriter cw {:keys [name descriptor flags]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (.visitMethodInsn mv Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V" false)
    (.visitInsn mv Opcodes/RETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn- emit-box!
  [^MethodVisitor mv jvm-type]
  (when-let [owner (desc/boxing-owner jvm-type)]
    (.visitMethodInsn mv
                      Opcodes/INVOKESTATIC
                      owner
                      "valueOf"
                      (desc/boxing-descriptor jvm-type)
                      false)))

(defn- emit-const!
  [^MethodVisitor mv {:keys [value jvm-type]}]
  (cond
    (nil? value)
    (.visitInsn mv Opcodes/ACONST_NULL)

    (= :int jvm-type)
    (.visitLdcInsn mv (int value))

    (= :long jvm-type)
    (.visitLdcInsn mv (long value))

    (= :double jvm-type)
    (.visitLdcInsn mv (double value))

    (= :boolean jvm-type)
    (.visitInsn mv (if value Opcodes/ICONST_1 Opcodes/ICONST_0))

    (= :char jvm-type)
    (.visitLdcInsn mv (int value))

    (= (ir/object-jvm-type "java/lang/String") jvm-type)
    (.visitLdcInsn mv ^String value)

    :else
    (throw (ex-info "Unsupported constant emission"
                    {:value value :jvm-type jvm-type}))))

(declare emit-expr!)
(declare emit-stmt!)

(defn- emit-expr!
  [^MethodVisitor mv expr]
  (case (:op expr)
    :const
    (do
      (emit-const! mv expr)
      (:jvm-type expr))

    (throw (ex-info "Unsupported IR expression emission"
                    {:expr expr :op (:op expr)}))))

(defn- emit-pop!
  [^MethodVisitor mv jvm-type]
  (when-not (= :void jvm-type)
    (.visitInsn mv
                (if (#{:long :double} jvm-type)
                  Opcodes/POP2
                  Opcodes/POP))))

(defn- emit-return!
  [^MethodVisitor mv expr]
  (let [jvm-type (emit-expr! mv expr)]
    (when (contains? ir/primitive-jvm-types jvm-type)
      (emit-box! mv jvm-type))
    (.visitInsn mv Opcodes/ARETURN)))

(defn- emit-stmt!
  [^MethodVisitor mv stmt]
  (case (:op stmt)
    :return
    (emit-return! mv (:expr stmt))

    :pop
    (emit-pop! mv (emit-expr! mv (:expr stmt)))

    (throw (ex-info "Unsupported IR statement emission"
                    {:stmt stmt :op (:op stmt)}))))

(defn- emit-eval-method!
  [^ClassWriter cw {:keys [name descriptor flags body]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (doseq [stmt body]
      (emit-stmt! mv stmt))
    (.visitInsn mv Opcodes/ACONST_NULL)
    (.visitInsn mv Opcodes/ARETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn emit-method!
  [^ClassWriter cw method-spec]
  (case (:kind method-spec)
    :default-constructor (emit-default-constructor! cw method-spec)
    :eval-from-ir (emit-eval-method! cw method-spec)
    (throw (ex-info "Unsupported method emission kind"
                    {:method-spec method-spec}))))

(defn emit-class
  "Emit one minimal JVM class from a class spec and return bytecode."
  [{:keys [internal-name super-name interfaces flags methods]}]
  (let [cw (ClassWriter. (+ ClassWriter/COMPUTE_FRAMES
                            ClassWriter/COMPUTE_MAXS))]
    (.visit cw
            class-version
            flags
            internal-name
            nil
            super-name
            (when (seq interfaces) (into-array String interfaces)))
    (doseq [method methods]
      (emit-method! cw method))
    (.visitEnd cw)
    (.toByteArray cw)))

(defn compile-unit->bytes
  "Compile the first minimal IR unit to JVM bytecode."
  [unit]
  (emit-class (minimal-class-spec unit)))
