(ns nex.compiler.jvm.emit
  "Minimal ASM-backed JVM bytecode emission for Nex.

  This first emitter milestone supports one trivial REPL cell class with:

  - public default constructor
  - public static Object eval(NexReplState state)

  The initial `eval` body ignores the IR and returns `null`."
  (:require [nex.compiler.jvm.descriptor :as desc]
            [nex.ir :as ir])
  (:import [org.objectweb.asm ClassWriter MethodVisitor Opcodes]))

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
              :kind :trivial-eval}]})

(defn- emit-default-constructor!
  [^ClassWriter cw {:keys [name descriptor flags]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (.visitMethodInsn mv Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V" false)
    (.visitInsn mv Opcodes/RETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn- emit-trivial-eval-method!
  [^ClassWriter cw {:keys [name descriptor flags]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (.visitInsn mv Opcodes/ACONST_NULL)
    (.visitInsn mv Opcodes/ARETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn emit-method!
  [^ClassWriter cw method-spec]
  (case (:kind method-spec)
    :default-constructor (emit-default-constructor! cw method-spec)
    :trivial-eval (emit-trivial-eval-method! cw method-spec)
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
