(ns nex.compiler.jvm.emit
  "Minimal ASM-backed JVM bytecode emission for Nex.

  This first emitter milestone supports one trivial REPL cell class with:

  - public default constructor
  - public static Object eval(NexReplState state)

  The initial `eval` body ignores the IR and returns `null`."
  (:require [nex.compiler.jvm.descriptor :as desc]
            [nex.ir :as ir])
  (:import [org.objectweb.asm ClassWriter Label MethodVisitor Opcodes Type]))

(def ^:private class-version Opcodes/V17)
(def ^:private repl-state-internal-name "nex/compiler/jvm/runtime/NexReplState")
(def ^:private atom-internal-name "clojure/lang/Atom")
(def ^:private hashmap-internal-name "java/util/HashMap")

(defn eval-method-descriptor
  []
  (desc/method-descriptor
   [(ir/object-jvm-type "nex/compiler/jvm/runtime/NexReplState")]
   (ir/object-jvm-type "java/lang/Object")))

(defn repl-fn-method-descriptor
  []
  "(Lnex/compiler/jvm/runtime/NexReplState;[Ljava/lang/Object;)Ljava/lang/Object;")

(defn minimal-class-spec
  "Create a minimal class spec for a compiled REPL cell."
  [unit]
  {:internal-name (desc/internal-class-name (:name unit))
   :binary-name (desc/binary-class-name (:name unit))
   :super-name "java/lang/Object"
   :interfaces []
   :flags Opcodes/ACC_PUBLIC
   :methods (vec
             (concat
              [{:name "<init>"
                :descriptor "()V"
                :flags Opcodes/ACC_PUBLIC
                :kind :default-constructor}
               {:name "eval"
                :descriptor (eval-method-descriptor)
                :flags (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                :kind :eval-from-ir
                :owner (:name unit)
                :functions (:functions unit)
                :body (:body unit)}]
              (map (fn [fn-node]
                     {:name (:emitted-name fn-node)
                      :descriptor (repl-fn-method-descriptor)
                      :flags (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                      :kind :repl-fn
                      :fn-node fn-node})
                   (:functions unit))))})

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

(defn- emit-unbox-or-cast!
  [^MethodVisitor mv jvm-type]
  (cond
    (contains? ir/primitive-jvm-types jvm-type)
    (let [{:keys [owner name descriptor]} (desc/unboxing-method jvm-type)]
      (.visitTypeInsn mv Opcodes/CHECKCAST owner)
      (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL owner name descriptor false))

    (and (ir/object-jvm-type? jvm-type)
         (not= (ir/object-jvm-type "java/lang/Object") jvm-type))
    (.visitTypeInsn mv Opcodes/CHECKCAST (second jvm-type))

    :else
    nil))

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

(defn- local-load-op
  [jvm-type]
  (cond
    (#{:int :boolean :char} jvm-type) Opcodes/ILOAD
    (= :long jvm-type) Opcodes/LLOAD
    (= :double jvm-type) Opcodes/DLOAD
    (ir/object-jvm-type? jvm-type) Opcodes/ALOAD
    :else (throw (ex-info "Unsupported local load type"
                          {:jvm-type jvm-type}))))

(defn- local-store-op
  [jvm-type]
  (cond
    (#{:int :boolean :char} jvm-type) Opcodes/ISTORE
    (= :long jvm-type) Opcodes/LSTORE
    (= :double jvm-type) Opcodes/DSTORE
    (ir/object-jvm-type? jvm-type) Opcodes/ASTORE
    :else (throw (ex-info "Unsupported local store type"
                          {:jvm-type jvm-type}))))

(defn- binary-opcode
  [operator jvm-type]
  (case [operator jvm-type]
    [:add :int] Opcodes/IADD
    [:sub :int] Opcodes/ISUB
    [:mul :int] Opcodes/IMUL
    [:div :int] Opcodes/IDIV

    [:add :long] Opcodes/LADD
    [:sub :long] Opcodes/LSUB
    [:mul :long] Opcodes/LMUL
    [:div :long] Opcodes/LDIV

    [:add :double] Opcodes/DADD
    [:sub :double] Opcodes/DSUB
    [:mul :double] Opcodes/DMUL
    [:div :double] Opcodes/DDIV

    (throw (ex-info "Unsupported binary opcode emission"
                    {:operator operator :jvm-type jvm-type}))))

(defn- compare-branch-opcode
  [operator jvm-type]
  (case [operator jvm-type]
    [:gt :int] Opcodes/IF_ICMPGT
    [:gte :int] Opcodes/IF_ICMPGE
    [:lt :int] Opcodes/IF_ICMPLT
    [:lte :int] Opcodes/IF_ICMPLE
    [:eq :int] Opcodes/IF_ICMPEQ
    [:neq :int] Opcodes/IF_ICMPNE

    [:gt :boolean] Opcodes/IF_ICMPGT
    [:gte :boolean] Opcodes/IF_ICMPGE
    [:lt :boolean] Opcodes/IF_ICMPLT
    [:lte :boolean] Opcodes/IF_ICMPLE
    [:eq :boolean] Opcodes/IF_ICMPEQ
    [:neq :boolean] Opcodes/IF_ICMPNE

    [:gt :char] Opcodes/IF_ICMPGT
    [:gte :char] Opcodes/IF_ICMPGE
    [:lt :char] Opcodes/IF_ICMPLT
    [:lte :char] Opcodes/IF_ICMPLE
    [:eq :char] Opcodes/IF_ICMPEQ
    [:neq :char] Opcodes/IF_ICMPNE

    [:eq [:object "java/lang/Object"]] Opcodes/IF_ACMPEQ
    [:neq [:object "java/lang/Object"]] Opcodes/IF_ACMPNE

    nil))

(defn- emit-numeric-compare!
  [^MethodVisitor mv operator jvm-type]
  (let [true-label (Label.)
        end-label (Label.)
        branch-op (compare-branch-opcode operator jvm-type)]
    (.visitJumpInsn mv branch-op true-label)
    (.visitInsn mv Opcodes/ICONST_0)
    (.visitJumpInsn mv Opcodes/GOTO end-label)
    (.visitLabel mv true-label)
    (.visitInsn mv Opcodes/ICONST_1)
    (.visitLabel mv end-label)))

(defn- emit-long-or-double-compare!
  [^MethodVisitor mv operator jvm-type]
  (let [true-label (Label.)
        end-label (Label.)
        branch-op (case operator
                    :gt Opcodes/IFGT
                    :gte Opcodes/IFGE
                    :lt Opcodes/IFLT
                    :lte Opcodes/IFLE
                    :eq Opcodes/IFEQ
                    :neq Opcodes/IFNE
                    (throw (ex-info "Unsupported compare operator"
                                    {:operator operator :jvm-type jvm-type})))]
    (.visitInsn mv (if (= :long jvm-type) Opcodes/LCMP Opcodes/DCMPL))
    (.visitJumpInsn mv branch-op true-label)
    (.visitInsn mv Opcodes/ICONST_0)
    (.visitJumpInsn mv Opcodes/GOTO end-label)
    (.visitLabel mv true-label)
    (.visitInsn mv Opcodes/ICONST_1)
    (.visitLabel mv end-label)))

(defn- emit-object-compare!
  [^MethodVisitor mv operator]
  (let [true-label (Label.)
        end-label (Label.)
        branch-op (compare-branch-opcode operator (ir/object-jvm-type "java/lang/Object"))]
    (.visitJumpInsn mv branch-op true-label)
    (.visitInsn mv Opcodes/ICONST_0)
    (.visitJumpInsn mv Opcodes/GOTO end-label)
    (.visitLabel mv true-label)
    (.visitInsn mv Opcodes/ICONST_1)
    (.visitLabel mv end-label)))

(defn- emit-load-values-map!
  [^MethodVisitor mv]
  (.visitVarInsn mv Opcodes/ALOAD 0)
  (.visitFieldInsn mv
                   Opcodes/GETFIELD
                   repl-state-internal-name
                   "values"
                   "Ljava/lang/Object;")
  (.visitTypeInsn mv Opcodes/CHECKCAST atom-internal-name)
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    atom-internal-name
                    "deref"
                    "()Ljava/lang/Object;"
                    false)
  (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name))

(declare emit-expr!)
(declare emit-stmt!)

(defn- emit-state-load-functions-map!
  [^MethodVisitor mv]
  (.visitVarInsn mv Opcodes/ALOAD 0)
  (.visitFieldInsn mv
                   Opcodes/GETFIELD
                   repl-state-internal-name
                   "functions"
                   "Ljava/lang/Object;")
  (.visitTypeInsn mv Opcodes/CHECKCAST atom-internal-name)
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    atom-internal-name
                    "deref"
                    "()Ljava/lang/Object;"
                    false)
  (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name))

(defn- emit-boxed-arg-array!
  [^MethodVisitor mv args]
  (.visitLdcInsn mv (int (count args)))
  (.visitTypeInsn mv Opcodes/ANEWARRAY "java/lang/Object")
  (doseq [[idx arg] (map-indexed vector args)]
    (.visitInsn mv Opcodes/DUP)
    (.visitLdcInsn mv (int idx))
    (let [arg-type (emit-expr! mv arg)]
      (when (contains? ir/primitive-jvm-types arg-type)
        (emit-box! mv arg-type)))
    (.visitInsn mv Opcodes/AASTORE)))

(defn- emit-register-repl-fn!
  [^MethodVisitor mv owner-internal-name fn-node]
  (emit-state-load-functions-map! mv)
  (.visitLdcInsn mv ^String (:name fn-node))
  (.visitLdcInsn mv (Type/getObjectType owner-internal-name))
  (.visitLdcInsn mv ^String (:emitted-name fn-node))
  (.visitInsn mv Opcodes/ICONST_2)
  (.visitTypeInsn mv Opcodes/ANEWARRAY "java/lang/Class")
  (.visitInsn mv Opcodes/DUP)
  (.visitInsn mv Opcodes/ICONST_0)
  (.visitLdcInsn mv (Type/getObjectType repl-state-internal-name))
  (.visitInsn mv Opcodes/AASTORE)
  (.visitInsn mv Opcodes/DUP)
  (.visitInsn mv Opcodes/ICONST_1)
  (.visitLdcInsn mv (Type/getType "[Ljava/lang/Object;"))
  (.visitInsn mv Opcodes/AASTORE)
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    "java/lang/Class"
                    "getDeclaredMethod"
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"
                    false)
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    hashmap-internal-name
                    "put"
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                    false)
  (.visitInsn mv Opcodes/POP))

(defn- emit-expr!
  [^MethodVisitor mv expr]
  (case (:op expr)
    :const
    (do
      (emit-const! mv expr)
      (:jvm-type expr))

    :local
    (do
      (.visitVarInsn mv (local-load-op (:jvm-type expr)) (:slot expr))
      (:jvm-type expr))

    :top-get
    (do
      (emit-load-values-map! mv)
      (.visitLdcInsn mv ^String (:name expr))
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        hashmap-internal-name
                        "get"
                        "(Ljava/lang/Object;)Ljava/lang/Object;"
                        false)
      (emit-unbox-or-cast! mv (:jvm-type expr))
      (:jvm-type expr))

    :call-repl-fn
    (do
      (emit-state-load-functions-map! mv)
      (.visitLdcInsn mv ^String (:name expr))
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        hashmap-internal-name
                        "get"
                        "(Ljava/lang/Object;)Ljava/lang/Object;"
                        false)
      (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/reflect/Method")
      (.visitInsn mv Opcodes/ACONST_NULL)
      (.visitInsn mv Opcodes/ICONST_2)
      (.visitTypeInsn mv Opcodes/ANEWARRAY "java/lang/Object")
      (.visitInsn mv Opcodes/DUP)
      (.visitInsn mv Opcodes/ICONST_0)
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (.visitInsn mv Opcodes/AASTORE)
      (.visitInsn mv Opcodes/DUP)
      (.visitInsn mv Opcodes/ICONST_1)
      (emit-boxed-arg-array! mv (:args expr))
      (.visitInsn mv Opcodes/AASTORE)
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        "java/lang/reflect/Method"
                        "invoke"
                        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"
                        false)
      (emit-unbox-or-cast! mv (:jvm-type expr))
      (:jvm-type expr))

    :binary
    (let [left-type (emit-expr! mv (:left expr))
          right-type (emit-expr! mv (:right expr))]
      (when (not= left-type right-type)
        (throw (ex-info "Binary operands lowered to different JVM types"
                        {:expr expr
                         :left-jvm-type left-type
                         :right-jvm-type right-type})))
      (.visitInsn mv (binary-opcode (:operator expr) (:jvm-type expr)))
      (:jvm-type expr))

    :compare
    (let [left-type (emit-expr! mv (:left expr))
          right-type (emit-expr! mv (:right expr))]
      (when (not= left-type right-type)
        (throw (ex-info "Compare operands lowered to different JVM types"
                        {:expr expr
                         :left-jvm-type left-type
                         :right-jvm-type right-type})))
      (cond
        (#{:int :boolean :char} left-type)
        (emit-numeric-compare! mv (:operator expr) left-type)

        (#{:long :double} left-type)
        (emit-long-or-double-compare! mv (:operator expr) left-type)

        (ir/object-jvm-type? left-type)
        (do
          (when-not (#{:eq :neq} (:operator expr))
            (throw (ex-info "Only eq/neq object comparisons are supported"
                            {:expr expr :jvm-type left-type})))
          (emit-object-compare! mv (:operator expr)))

        :else
        (throw (ex-info "Unsupported compare emission type"
                        {:expr expr :jvm-type left-type})))
      (:jvm-type expr))

    :if
    (let [else-label (Label.)
          end-label (Label.)
          test-type (emit-expr! mv (:test expr))
          then-exprs (:then expr)
          else-exprs (:else expr)]
      (when-not (= :boolean test-type)
        (throw (ex-info "If test did not lower to boolean"
                        {:expr expr :test-jvm-type test-type})))
      (when (or (not= 1 (count then-exprs))
                (not= 1 (count else-exprs)))
        (throw (ex-info "If emission expects one expression per branch"
                        {:expr expr})))
      (.visitJumpInsn mv Opcodes/IFEQ else-label)
      (let [then-type (emit-expr! mv (first then-exprs))
            else-type (do
                        (.visitJumpInsn mv Opcodes/GOTO end-label)
                        (.visitLabel mv else-label)
                        (emit-expr! mv (first else-exprs)))]
        (when (not= then-type else-type)
          (throw (ex-info "If branches lowered to different JVM types"
                          {:expr expr
                           :then-jvm-type then-type
                           :else-jvm-type else-type})))
        (.visitLabel mv end-label)
        then-type))

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

    :set-local
    (let [expr-jvm-type (emit-expr! mv (:expr stmt))]
      (.visitVarInsn mv (local-store-op (:jvm-type stmt)) (:slot stmt))
      expr-jvm-type)

    :top-set
    (do
      (emit-load-values-map! mv)
      (.visitLdcInsn mv ^String (:name stmt))
      (let [expr-jvm-type (emit-expr! mv (:expr stmt))]
        (when (contains? ir/primitive-jvm-types expr-jvm-type)
          (emit-box! mv expr-jvm-type)))
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        hashmap-internal-name
                        "put"
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                        false)
      (.visitInsn mv Opcodes/POP))

    (throw (ex-info "Unsupported IR statement emission"
                    {:stmt stmt :op (:op stmt)}))))

(defn- emit-function-arg-prologue!
  [^MethodVisitor mv fn-node]
  (doseq [{:keys [arg-index slot jvm-type]} (:params fn-node)]
    (.visitVarInsn mv Opcodes/ALOAD 1)
    (.visitLdcInsn mv (int arg-index))
    (.visitInsn mv Opcodes/AALOAD)
    (emit-unbox-or-cast! mv jvm-type)
    (.visitVarInsn mv (local-store-op jvm-type) slot)))

(defn- emit-eval-method!
  [^ClassWriter cw {:keys [name descriptor flags body owner functions]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (doseq [fn-node functions]
      (emit-register-repl-fn! mv owner fn-node))
    (doseq [stmt body]
      (emit-stmt! mv stmt))
    (.visitInsn mv Opcodes/ACONST_NULL)
    (.visitInsn mv Opcodes/ARETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn- emit-repl-fn-method!
  [^ClassWriter cw {:keys [name descriptor flags fn-node]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (emit-function-arg-prologue! mv fn-node)
    (doseq [stmt (:body fn-node)]
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
    :repl-fn (emit-repl-fn-method! cw method-spec)
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
