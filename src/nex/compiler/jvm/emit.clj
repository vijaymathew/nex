(ns nex.compiler.jvm.emit
  "ASM-backed JVM bytecode emission for Nex.

  Emits lowered Nex IR and class specs as JVM bytecode for:

  - compiled REPL cells with `eval(NexReplState)`
  - user-defined classes, constructors, methods, and constants
  - launcher classes with `main(String[])`

  The emitter handles control flow, runtime helper calls, object-model support,
  and debug metadata such as source files, line tables, and local variables."
  (:require [clojure.string :as str]
            [nex.compiler.jvm.descriptor :as desc]
            [nex.ir :as ir])
  (:import [org.objectweb.asm ClassWriter Label MethodVisitor Opcodes Type]))

(def ^:private class-version Opcodes/V17)
(def ^:private repl-state-internal-name "nex/compiler/jvm/runtime/NexReplState")
(def ^:private atom-internal-name "clojure/lang/Atom")
(def ^:private arraylist-internal-name "java/util/ArrayList")
(def ^:private hashmap-internal-name "java/util/HashMap")
(def ^:private linkedhashset-internal-name "java/util/LinkedHashSet")
(def ^:private rt-internal-name "clojure/lang/RT")
(def ^:private var-internal-name "clojure/lang/Var")
 (def ^:private throwable-internal-name "java/lang/Throwable")
(def ^:dynamic *local-debug-ranges* nil)

(declare emit-const!)
(declare emit-runtime-call!)
(declare emit-expression!)

(defn eval-method-descriptor
  []
  (desc/method-descriptor
   [(ir/object-jvm-type "nex/compiler/jvm/runtime/NexReplState")]
   (ir/object-jvm-type "java/lang/Object")))

(defn repl-fn-method-descriptor
  []
  "(Lnex/compiler/jvm/runtime/NexReplState;[Ljava/lang/Object;)Ljava/lang/Object;")

(defn launcher-main-method-descriptor
  []
  "([Ljava/lang/String;)V")

(defn- class-default-value
  [jvm-type]
  (cond
    (= :int jvm-type) 0
    (= :long jvm-type) 0
    (= :double jvm-type) 0.0
    (= :boolean jvm-type) false
    (= :char jvm-type) 0
    :else nil))

(defn- source-file-name
  [source-file]
  (when source-file
    (last (str/split (str source-file) #"[\\/]"))))

(defn minimal-class-spec
  "Create a minimal class spec for a compiled REPL cell."
  [unit]
  {:internal-name (desc/internal-class-name (:name unit))
   :binary-name (desc/binary-class-name (:name unit))
   :source-file (:source-file unit)
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
                :locals (:locals unit)
                :body (:body unit)}]
              (map (fn [fn-node]
                     {:name (:emitted-name fn-node)
                      :descriptor (repl-fn-method-descriptor)
                      :flags (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
                      :kind :repl-fn
                      :fn-node fn-node})
                   (:functions unit))))})

(defn user-class-spec
  [class-spec]
  {:internal-name (:internal-name class-spec)
   :binary-name (desc/binary-class-name (:jvm-name class-spec))
   :source-file (:source-file class-spec)
   :super-name "java/lang/Object"
   :interfaces []
   :flags (if (:deferred? class-spec)
            (+ Opcodes/ACC_PUBLIC Opcodes/ACC_ABSTRACT)
            Opcodes/ACC_PUBLIC)
   :fields (vec
            (concat
             (map (fn [{:keys [name jvm-type]}]
                    {:name name
                     :descriptor (desc/jvm-type->descriptor jvm-type)
                     :flags Opcodes/ACC_PRIVATE
                     :jvm-type jvm-type})
                  (:composition-fields class-spec))
             (map (fn [{:keys [name jvm-type]}]
                    {:name name
                     :descriptor (desc/jvm-type->descriptor jvm-type)
                     :flags Opcodes/ACC_PUBLIC
                     :jvm-type jvm-type})
                  (:fields class-spec))))
   :static-fields (mapv (fn [{:keys [name jvm-type]}]
                          {:name name
                           :descriptor (desc/jvm-type->descriptor jvm-type)
                           :flags (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC Opcodes/ACC_FINAL)
                           :jvm-type jvm-type})
                        (:constants class-spec))
   :methods (vec
             (concat
              [{:name "<init>"
                :descriptor "()V"
                :flags Opcodes/ACC_PUBLIC
                :kind :user-default-constructor
                :owner (:internal-name class-spec)
                :super-name "java/lang/Object"
                :composition-fields (:composition-fields class-spec)
                :fields (:fields class-spec)}
               {:name "<clinit>"
                :descriptor "()V"
                :flags (+ Opcodes/ACC_STATIC)
                :kind :class-initializer
                :owner (:internal-name class-spec)
                :constants (:constants class-spec)}]
              (map (fn [fn-node]
                     {:name (:emitted-name fn-node)
                      :descriptor (repl-fn-method-descriptor)
                      :flags (+ Opcodes/ACC_PUBLIC)
                      :kind :instance-ctor-fn
                      :fn-node fn-node})
                   (:constructors class-spec))
              (map (fn [fn-node]
                     {:name (:emitted-name fn-node)
                      :descriptor (repl-fn-method-descriptor)
                      :flags (if (:deferred? fn-node)
                               (+ Opcodes/ACC_PUBLIC Opcodes/ACC_ABSTRACT)
                               Opcodes/ACC_PUBLIC)
                      :kind (if (:deferred? fn-node)
                              :abstract-instance-fn
                              :instance-fn)
                      :fn-node fn-node})
                   (:methods class-spec))))})

(defn launcher-class-spec
  [{:keys [internal-name binary-name source-file program-internal-name classes-edn imports-edn]}]
  {:internal-name internal-name
   :binary-name binary-name
   :source-file source-file
   :super-name "java/lang/Object"
   :interfaces []
   :flags Opcodes/ACC_PUBLIC
   :methods [{:name "<init>"
              :descriptor "()V"
              :flags Opcodes/ACC_PUBLIC
              :kind :default-constructor}
             {:name "main"
              :descriptor (launcher-main-method-descriptor)
              :flags (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC)
              :kind :launcher-main
              :program-internal-name program-internal-name
              :classes-edn classes-edn
              :imports-edn imports-edn}]})

(defn- emit-default-constructor!
  [^ClassWriter cw {:keys [name descriptor flags]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (.visitMethodInsn mv Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V" false)
    (.visitInsn mv Opcodes/RETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn- emit-local-variable-table!
  [^MethodVisitor mv start-label end-label locals local-ranges]
  (doseq [{:keys [name slot jvm-type descriptor]} locals
          :when (and name (some? slot) (or descriptor jvm-type))]
    (.visitLocalVariable mv
                         ^String (str name)
                         ^String (or descriptor
                                      (desc/jvm-type->descriptor jvm-type))
                         nil
                         (or (get-in local-ranges [slot :start]) start-label)
                         (or (get-in local-ranges [slot :end]) end-label)
                         (int slot))))

(defn- mark-local-debug-before!
  [^MethodVisitor mv slot]
  (when *local-debug-ranges*
    (when-not (get @*local-debug-ranges* slot)
      (let [label (Label.)]
        (.visitLabel mv label)
        (swap! *local-debug-ranges* assoc slot {:start label :end label})))))

(defn- mark-local-debug-after!
  [^MethodVisitor mv slot]
  (when *local-debug-ranges*
    (when (get @*local-debug-ranges* slot)
      (let [label (Label.)]
        (.visitLabel mv label)
        (swap! *local-debug-ranges* assoc-in [slot :end] label)))))

(defn- emit-launcher-main!
  [^ClassWriter cw {:keys [name descriptor flags program-internal-name classes-edn imports-edn]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (let [start-label (Label.)
          end-label (Label.)]
      (.visitLabel mv start-label)
      (.visitLdcInsn mv "clojure.core")
      (.visitLdcInsn mv "require")
      (.visitMethodInsn mv
                        Opcodes/INVOKESTATIC
                        rt-internal-name
                        "var"
                        "(Ljava/lang/String;Ljava/lang/String;)Lclojure/lang/Var;"
                        false)
      (.visitLdcInsn mv "nex.compiler.jvm.runtime")
      (.visitMethodInsn mv
                        Opcodes/INVOKESTATIC
                        "clojure/lang/Symbol"
                        "intern"
                        "(Ljava/lang/String;)Lclojure/lang/Symbol;"
                        false)
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        var-internal-name
                        "invoke"
                        "(Ljava/lang/Object;)Ljava/lang/Object;"
                        false)
      (.visitInsn mv Opcodes/POP)

      (emit-runtime-call! mv "make-repl-state" [])
      (.visitTypeInsn mv Opcodes/CHECKCAST repl-state-internal-name)
      (.visitVarInsn mv Opcodes/ASTORE 1)

      (emit-runtime-call! mv "bootstrap-compiled-state!"
                          [(fn [] (.visitVarInsn mv Opcodes/ALOAD 1))
                           (fn [] (.visitLdcInsn mv ^String classes-edn))
                           (fn [] (.visitLdcInsn mv ^String imports-edn))])
      (.visitInsn mv Opcodes/POP)

      (.visitVarInsn mv Opcodes/ALOAD 1)
      (.visitMethodInsn mv
                        Opcodes/INVOKESTATIC
                        program-internal-name
                        "eval"
                        (eval-method-descriptor)
                        false)
      (.visitInsn mv Opcodes/POP)

      (emit-runtime-call! mv "print-state-output!"
                          [(fn [] (.visitVarInsn mv Opcodes/ALOAD 1))])
      (.visitInsn mv Opcodes/POP)
      (.visitLabel mv end-label)
      (emit-local-variable-table! mv start-label end-label
                                  [{:name "args"
                                    :slot 0
                                    :descriptor "[Ljava/lang/String;"}
                                   {:name "state"
                                    :slot 1
                                    :jvm-type (ir/object-jvm-type repl-state-internal-name)}]
                                  {})
      (.visitInsn mv Opcodes/RETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))))

(defn- emit-user-default-constructor!
  [^ClassWriter cw {:keys [name descriptor flags fields composition-fields owner super-name]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (.visitMethodInsn mv Opcodes/INVOKESPECIAL super-name "<init>" "()V" false)
    (doseq [{:keys [name jvm-type deferred?]} composition-fields]
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (if deferred?
        (.visitInsn mv Opcodes/ACONST_NULL)
        (do
          (.visitTypeInsn mv Opcodes/NEW (second jvm-type))
          (.visitInsn mv Opcodes/DUP)
          (.visitMethodInsn mv Opcodes/INVOKESPECIAL (second jvm-type) "<init>" "()V" false)))
      (.visitFieldInsn mv
                       Opcodes/PUTFIELD
                       owner
                       name
                       (desc/jvm-type->descriptor jvm-type)))
    (doseq [{:keys [name jvm-type]} fields]
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (emit-const! mv {:value (class-default-value jvm-type) :jvm-type jvm-type})
      (.visitFieldInsn mv
                       Opcodes/PUTFIELD
                       owner
                       name
                       (desc/jvm-type->descriptor jvm-type)))
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
    (= :void jvm-type)
    nil

    (= :int jvm-type)
    (do
      (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Number")
      (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Number" "intValue" "()I" false))

    (= :long jvm-type)
    (do
      (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Number")
      (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Number" "longValue" "()J" false))

    (= :double jvm-type)
    (do
      (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Number")
      (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Number" "doubleValue" "()D" false))

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

(defn- emit-stack-coerce!
  [^MethodVisitor mv from-jvm-type to-jvm-type]
  (cond
    (= from-jvm-type to-jvm-type)
    nil

    (and (= :int from-jvm-type) (= :long to-jvm-type))
    (.visitInsn mv Opcodes/I2L)

    (and (= :int from-jvm-type) (= :double to-jvm-type))
    (.visitInsn mv Opcodes/I2D)

    (and (= :long from-jvm-type) (= :double to-jvm-type))
    (.visitInsn mv Opcodes/L2D)

    (and (contains? ir/primitive-jvm-types from-jvm-type)
         (ir/object-jvm-type? to-jvm-type))
    (emit-box! mv from-jvm-type)

    (and (ir/object-jvm-type? from-jvm-type)
         (contains? ir/primitive-jvm-types to-jvm-type))
    (emit-unbox-or-cast! mv to-jvm-type)

    (and (ir/object-jvm-type? from-jvm-type)
         (ir/object-jvm-type? to-jvm-type))
    (emit-unbox-or-cast! mv to-jvm-type)

    :else
    (throw (ex-info "Unsupported JVM stack coercion"
                    {:from-jvm-type from-jvm-type
                     :to-jvm-type to-jvm-type}))))

(defn- numeric-promotion-jvm-type
  [left-jvm-type right-jvm-type]
  (cond
    (= left-jvm-type right-jvm-type) left-jvm-type
    (or (= :double left-jvm-type) (= :double right-jvm-type)) :double
    (or (= :long left-jvm-type) (= :long right-jvm-type)) :long
    (and (= :int left-jvm-type) (= :int right-jvm-type)) :int
    :else nil))

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
    [:mod :int] Opcodes/IREM
    [:bit-shl :int] Opcodes/ISHL
    [:bit-shr :int] Opcodes/ISHR
    [:bit-ushr :int] Opcodes/IUSHR
    [:bit-and :int] Opcodes/IAND
    [:bit-or :int] Opcodes/IOR
    [:bit-xor :int] Opcodes/IXOR

    [:add :long] Opcodes/LADD
    [:sub :long] Opcodes/LSUB
    [:mul :long] Opcodes/LMUL
    [:div :long] Opcodes/LDIV
    [:mod :long] Opcodes/LREM

    [:add :double] Opcodes/DADD
    [:sub :double] Opcodes/DSUB
    [:mul :double] Opcodes/DMUL
    [:div :double] Opcodes/DDIV
    [:mod :double] Opcodes/DREM

    (throw (ex-info "Unsupported binary opcode emission"
                    {:operator operator :jvm-type jvm-type}))))

(defn- unary-opcode
  [operator jvm-type]
  (case [operator jvm-type]
    [:neg :int] Opcodes/INEG
    [:neg :long] Opcodes/LNEG
    [:neg :double] Opcodes/DNEG
    [:bit-not :int] Opcodes/ICONST_M1
    nil))

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

(declare emit-expr!)
(declare emit-stmt!)
(declare emit-boxed-expr!)
(declare emit-boxed-arg-array!)

(defn- emit-runtime-var!
  [^MethodVisitor mv fn-name]
  (.visitLdcInsn mv "nex.compiler.jvm.runtime")
  (.visitLdcInsn mv ^String fn-name)
  (.visitMethodInsn mv
                    Opcodes/INVOKESTATIC
                    rt-internal-name
                    "var"
                    "(Ljava/lang/String;Ljava/lang/String;)Lclojure/lang/Var;"
                    false))

(defn- emit-runtime-invoke-0!
  [^MethodVisitor mv fn-name]
  (emit-runtime-var! mv fn-name)
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    var-internal-name
                    "invoke"
                    "()Ljava/lang/Object;"
                    false))

(defn- emit-runtime-invoke-1!
  [^MethodVisitor mv fn-name]
  (emit-runtime-var! mv fn-name)
  (.visitInsn mv Opcodes/SWAP)
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    var-internal-name
                    "invoke"
                    "(Ljava/lang/Object;)Ljava/lang/Object;"
                    false))

(defn- emit-runtime-invoke-2!
  [^MethodVisitor mv fn-name]
  (emit-runtime-var! mv fn-name)
  (.visitInsn mv Opcodes/DUP_X2)
  (.visitInsn mv Opcodes/POP)
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    var-internal-name
                    "invoke"
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                    false))

(defn- runtime-invoke-descriptor
  [arity]
  (case arity
    0 "()Ljava/lang/Object;"
    1 "(Ljava/lang/Object;)Ljava/lang/Object;"
    2 "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    3 "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    4 "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    5 "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    6 "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    7 "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    8 "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    (throw (ex-info "Unsupported runtime invoke arity" {:arity arity}))))

(defn- emit-runtime-call!
  [^MethodVisitor mv fn-name arg-emitters]
  (emit-runtime-var! mv fn-name)
  (doseq [emit! arg-emitters]
    (emit!))
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    var-internal-name
                    "invoke"
                    (runtime-invoke-descriptor (count arg-emitters))
                    false))

(defn- direct-derived-builtin-helper-name
  [helper]
  (cond
    (re-matches #"^(regex_|datetime_|path_|text_file_|binary_file_).*$" helper)
    (str "builtin-" (str/replace helper "_" "-"))

    (str/starts-with? helper "builtin-method:")
    (let [[_ base method] (str/split helper #":" 3)]
      (str "builtin-method-"
           (str/lower-case base)
           "-"
           (-> method
               (str/replace "_" "-"))))

    :else
    nil))

(defn- emit-direct-runtime-helper-call!
  [^MethodVisitor mv expr state-slot]
  (let [helper (:helper expr)
        args (:args expr)
        emit-return (fn [jvm-type]
                      (if (= :void jvm-type)
                        (do (.visitInsn mv Opcodes/POP) :void)
                        (do (emit-unbox-or-cast! mv jvm-type)
                            jvm-type)))]
    (case helper
      "builtin-method:Cursor:start"
      (do
        (emit-runtime-call! mv "builtin-cursor-start"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "builtin-method:Cursor:cursor"
      (do
        (emit-runtime-call! mv "builtin-cursor-cursor"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "builtin-method:Cursor:item"
      (do
        (emit-runtime-call! mv "builtin-cursor-item"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "builtin-method:Cursor:next"
      (do
        (emit-runtime-call! mv "builtin-cursor-next"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "builtin-method:Cursor:at_end"
      (do
        (emit-runtime-call! mv "builtin-cursor-at-end"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "print"
      (do
        (emit-runtime-call! mv "builtin-print!"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-arg-array! mv args state-slot))])
        (emit-return (:jvm-type expr)))

      "println"
      (do
        (emit-runtime-call! mv "builtin-println!"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-arg-array! mv args state-slot))])
        (emit-return (:jvm-type expr)))

      "type_of"
      (do
        (emit-runtime-call! mv "builtin-type-of"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "type_is"
      (do
        (emit-runtime-call! mv "builtin-type-is"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))])
        (emit-return (:jvm-type expr)))

      "sleep"
      (do
        (emit-runtime-call! mv "builtin-sleep!"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "http_get"
      (do
        (emit-runtime-call! mv "builtin-http-get"
                            (into [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))]
                                  (mapv (fn [arg]
                                          (fn [] (emit-boxed-expr! mv arg state-slot)))
                                        args)))
        (emit-return (:jvm-type expr)))

      "http_post"
      (do
        (emit-runtime-call! mv "builtin-http-post"
                            (into [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))]
                                  (mapv (fn [arg]
                                          (fn [] (emit-boxed-expr! mv arg state-slot)))
                                        args)))
        (emit-return (:jvm-type expr)))

      "json_parse"
      (do
        (emit-runtime-call! mv "builtin-json-parse"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "json_stringify"
      (do
        (emit-runtime-call! mv "builtin-json-stringify"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "http_server_create"
      (do
        (emit-runtime-call! mv "builtin-http-server-create"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "http_server_get"
      (do
        (emit-runtime-call! mv "builtin-http-server-get!"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))
                             (fn [] (emit-boxed-expr! mv (nth args 2) state-slot))])
        (emit-return (:jvm-type expr)))

      "http_server_post"
      (do
        (emit-runtime-call! mv "builtin-http-server-post!"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))
                             (fn [] (emit-boxed-expr! mv (nth args 2) state-slot))])
        (emit-return (:jvm-type expr)))

      "http_server_put"
      (do
        (emit-runtime-call! mv "builtin-http-server-put!"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))
                             (fn [] (emit-boxed-expr! mv (nth args 2) state-slot))])
        (emit-return (:jvm-type expr)))

      "http_server_delete"
      (do
        (emit-runtime-call! mv "builtin-http-server-delete!"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))
                             (fn [] (emit-boxed-expr! mv (nth args 2) state-slot))])
        (emit-return (:jvm-type expr)))

      "http_server_start"
      (do
        (emit-runtime-call! mv "builtin-http-server-start!"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "http_server_stop"
      (do
        (emit-runtime-call! mv "builtin-http-server-stop!"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "http_server_is_running"
      (do
        (emit-runtime-call! mv "builtin-http-server-is-running"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "java-call-static"
      (do
        (emit-runtime-call! mv "java-call-static"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))
                             (fn [] (emit-boxed-arg-array! mv (vec (drop 2 args)) state-slot))])
        (emit-return (:jvm-type expr)))

      "java-get-static-field"
      (do
        (emit-runtime-call! mv "java-get-static-field"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))])
        (emit-return (:jvm-type expr)))

      "validate-object-state"
      (do
        (emit-runtime-call! mv "validate-object-state"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))])
        (emit-return (:jvm-type expr)))

      "op:string-concat"
      (do
        (emit-runtime-call! mv "string-concat"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-arg-array! mv args state-slot))])
        (emit-return (:jvm-type expr)))

      "op:pow-int"
      (do
        (emit-runtime-call! mv "pow-int"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))])
        (emit-return (:jvm-type expr)))

      "op:pow-long"
      (do
        (emit-runtime-call! mv "pow-long"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))])
        (emit-return (:jvm-type expr)))

      "op:pow-double"
      (do
        (emit-runtime-call! mv "pow-double"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))])
        (emit-return (:jvm-type expr)))

      "spawn-function-object"
      (do
        (emit-runtime-call! mv "spawn-function-object"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "create-channel"
      (do
        (emit-runtime-call! mv "create-channel"
                            (mapv (fn [arg]
                                    (fn [] (emit-boxed-expr! mv arg state-slot)))
                                  args))
        (emit-return (:jvm-type expr)))

      "op:await-all"
      (do
        (emit-runtime-call! mv "task-await-all"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "op:await-any"
      (do
        (emit-runtime-call! mv "task-await-any"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "select-deadline"
      (do
        (emit-runtime-call! mv "select-deadline"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "deadline-expired?"
      (do
        (emit-runtime-call! mv "deadline-expired?"
                            [(fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return (:jvm-type expr)))

      "select-sleep-step"
      (do
        (emit-runtime-call! mv "select-sleep-step!"
                            [])
        (emit-return (:jvm-type expr)))

      "datetime_make"
      (do
        (emit-runtime-call! mv "builtin-datetime-make-from-array"
                            [(fn [] (emit-boxed-arg-array! mv args state-slot))])
        (emit-return (:jvm-type expr)))

      (when-let [derived-helper (direct-derived-builtin-helper-name helper)]
        (do
          (emit-runtime-call! mv derived-helper
                              (mapv (fn [arg]
                                      (fn [] (emit-boxed-expr! mv arg state-slot)))
                                    args))
          (emit-return (:jvm-type expr)))))))

(defn- emit-boolean-short-circuit!
  [^MethodVisitor mv operator left-expr right-expr state-slot]
  (let [skip-label (Label.)
        false-label (Label.)
        end-label (Label.)
        left-type (emit-expr! mv left-expr state-slot)]
    (when-not (= :boolean left-type)
      (throw (ex-info "Logical operator requires boolean lhs"
                      {:operator operator :jvm-type left-type})))
    (case operator
      :and (.visitJumpInsn mv Opcodes/IFEQ false-label)
      :or (.visitJumpInsn mv Opcodes/IFNE skip-label)
      (throw (ex-info "Unsupported short-circuit operator"
                      {:operator operator})))
    (let [right-type (emit-expr! mv right-expr state-slot)]
      (when-not (= :boolean right-type)
        (throw (ex-info "Logical operator requires boolean rhs"
                        {:operator operator :jvm-type right-type}))))
    (case operator
      :and
      (do
        (.visitJumpInsn mv Opcodes/IFEQ false-label)
        (.visitInsn mv Opcodes/ICONST_1)
        (.visitJumpInsn mv Opcodes/GOTO end-label)
        (.visitLabel mv false-label)
        (.visitInsn mv Opcodes/ICONST_0)
        (.visitLabel mv end-label))

      :or
      (do
        (.visitJumpInsn mv Opcodes/IFNE skip-label)
        (.visitInsn mv Opcodes/ICONST_0)
        (.visitJumpInsn mv Opcodes/GOTO end-label)
        (.visitLabel mv skip-label)
        (.visitInsn mv Opcodes/ICONST_1)
        (.visitLabel mv end-label)))))

(defn- emit-bit-test!
  [^MethodVisitor mv left-expr right-expr state-slot]
  (let [true-label (Label.)
        end-label (Label.)]
    (emit-expr! mv left-expr state-slot)
    (.visitInsn mv Opcodes/ICONST_1)
    (emit-expr! mv right-expr state-slot)
    (.visitInsn mv Opcodes/ISHL)
    (.visitInsn mv Opcodes/IAND)
    (.visitJumpInsn mv Opcodes/IFNE true-label)
    (.visitInsn mv Opcodes/ICONST_0)
    (.visitJumpInsn mv Opcodes/GOTO end-label)
    (.visitLabel mv true-label)
    (.visitInsn mv Opcodes/ICONST_1)
    (.visitLabel mv end-label)))

(defn- emit-bit-set-like!
  [^MethodVisitor mv operator left-expr right-expr state-slot]
  (emit-expr! mv left-expr state-slot)
  (.visitInsn mv Opcodes/ICONST_1)
  (emit-expr! mv right-expr state-slot)
  (.visitInsn mv Opcodes/ISHL)
  (case operator
    :bit-set (.visitInsn mv Opcodes/IOR)
    :bit-unset (do
                 (.visitInsn mv Opcodes/ICONST_M1)
                 (.visitInsn mv Opcodes/IXOR)
                 (.visitInsn mv Opcodes/IAND))
    (throw (ex-info "Unsupported bit-set-like operator"
                    {:operator operator}))))

(defn- emit-load-values-map!
  [^MethodVisitor mv state-slot]
  (.visitVarInsn mv Opcodes/ALOAD state-slot)
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

(defn- emit-state-load-functions-map!
  [^MethodVisitor mv state-slot]
  (.visitVarInsn mv Opcodes/ALOAD state-slot)
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
  [^MethodVisitor mv args state-slot]
  (.visitLdcInsn mv (int (count args)))
  (.visitTypeInsn mv Opcodes/ANEWARRAY "java/lang/Object")
  (doseq [[idx arg] (map-indexed vector args)]
    (.visitInsn mv Opcodes/DUP)
    (.visitLdcInsn mv (int idx))
    (let [emitted-type (emit-expr! mv arg state-slot)
          declared-type (:jvm-type arg)]
      (when (and declared-type
                 (not= emitted-type declared-type))
        (throw (ex-info "Argument emission type did not match IR declaration"
                        {:arg arg
                         :emitted-type emitted-type
                         :declared-type declared-type})))
      (when (contains? ir/primitive-jvm-types declared-type)
        (emit-box! mv declared-type)))
    (.visitInsn mv Opcodes/AASTORE)))

(defn- emit-boxed-expr!
  [^MethodVisitor mv expr state-slot]
  (let [jvm-type (emit-expr! mv expr state-slot)]
    (when (contains? ir/primitive-jvm-types jvm-type)
      (emit-box! mv jvm-type))
    (ir/object-jvm-type "java/lang/Object")))

(defn- emit-array-literal!
  [^MethodVisitor mv expr state-slot]
  (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
  (.visitInsn mv Opcodes/DUP)
  (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "()V" false)
  (doseq [element (:elements expr)]
    (.visitInsn mv Opcodes/DUP)
    (emit-boxed-expr! mv element state-slot)
    (.visitMethodInsn mv
                      Opcodes/INVOKEVIRTUAL
                      arraylist-internal-name
                      "add"
                      "(Ljava/lang/Object;)Z"
                      false)
    (.visitInsn mv Opcodes/POP))
  (:jvm-type expr))

(defn- emit-map-literal!
  [^MethodVisitor mv expr state-slot]
  (.visitTypeInsn mv Opcodes/NEW hashmap-internal-name)
  (.visitInsn mv Opcodes/DUP)
  (.visitMethodInsn mv Opcodes/INVOKESPECIAL hashmap-internal-name "<init>" "()V" false)
  (doseq [{:keys [key value]} (:entries expr)]
    (.visitInsn mv Opcodes/DUP)
    (emit-boxed-expr! mv key state-slot)
    (emit-boxed-expr! mv value state-slot)
    (.visitMethodInsn mv
                      Opcodes/INVOKEVIRTUAL
                      hashmap-internal-name
                      "put"
                      "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                      false)
    (.visitInsn mv Opcodes/POP))
  (:jvm-type expr))

(defn- emit-set-literal!
  [^MethodVisitor mv expr state-slot]
  (.visitTypeInsn mv Opcodes/NEW linkedhashset-internal-name)
  (.visitInsn mv Opcodes/DUP)
  (.visitMethodInsn mv Opcodes/INVOKESPECIAL linkedhashset-internal-name "<init>" "()V" false)
  (doseq [element (:elements expr)]
    (.visitInsn mv Opcodes/DUP)
    (emit-boxed-expr! mv element state-slot)
    (.visitMethodInsn mv
                      Opcodes/INVOKEVIRTUAL
                      linkedhashset-internal-name
                      "add"
                      "(Ljava/lang/Object;)Z"
                      false)
    (.visitInsn mv Opcodes/POP))
  (:jvm-type expr))

(defn- emit-register-repl-fn!
  [^MethodVisitor mv state-slot owner-internal-name fn-node]
  (emit-state-load-functions-map! mv state-slot)
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

(defn- emit-convert!
  [^MethodVisitor mv {:keys [value binding target-type temp-slot]} state-slot]
  (emit-runtime-var! mv "convert-value")
  (.visitVarInsn mv Opcodes/ALOAD state-slot)
  (let [value-type (emit-expr! mv value state-slot)]
    (when (contains? ir/primitive-jvm-types value-type)
      (emit-box! mv value-type)))
  (.visitLdcInsn mv ^String (if (map? target-type) (:base-type target-type) target-type))
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    var-internal-name
                    "invoke"
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                    false)
  (.visitTypeInsn mv Opcodes/CHECKCAST "[Ljava/lang/Object;")
  (.visitVarInsn mv Opcodes/ASTORE temp-slot)

  (.visitVarInsn mv Opcodes/ALOAD temp-slot)
  (.visitInsn mv Opcodes/ICONST_1)
  (.visitInsn mv Opcodes/AALOAD)
  (case (:kind binding)
    :local
    (do
      (emit-unbox-or-cast! mv (:jvm-type binding))
      (.visitVarInsn mv (local-store-op (:jvm-type binding)) (:slot binding)))

    :top
    (do
      (emit-load-values-map! mv state-slot)
      (.visitLdcInsn mv ^String (:name binding))
      (.visitVarInsn mv Opcodes/ALOAD temp-slot)
      (.visitInsn mv Opcodes/ICONST_1)
      (.visitInsn mv Opcodes/AALOAD)
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        hashmap-internal-name
                        "put"
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                        false)
      (.visitInsn mv Opcodes/POP))

    (throw (ex-info "Unsupported convert binding kind"
                    {:binding binding})))

  (.visitVarInsn mv Opcodes/ALOAD temp-slot)
  (.visitInsn mv Opcodes/ICONST_0)
  (.visitInsn mv Opcodes/AALOAD)
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    "java/lang/Boolean"
                    "booleanValue"
                    "()Z"
                    false)
  :boolean)

(defn- emit-collection-method!
  [^MethodVisitor mv expr state-slot]
  (let [{:keys [collection-kind method target args jvm-type]} expr]
    (case [collection-kind method]
      [:array "get"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
        (emit-expr! mv (first args) state-slot)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "get" "(I)Ljava/lang/Object;" false)
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:array "add"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
        (emit-boxed-expr! mv (first args) state-slot)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "add" "(Ljava/lang/Object;)Z" false)
        (.visitInsn mv Opcodes/POP)
        :void)

      [:array "push"]
      (emit-collection-method! mv (assoc expr :method "add") state-slot)

      [:array "add_at"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
        (emit-expr! mv (first args) state-slot)
        (emit-boxed-expr! mv (second args) state-slot)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "add" "(ILjava/lang/Object;)V" false)
        :void)

      [:array "at"]
      (emit-collection-method! mv (assoc expr :method "add_at") state-slot)

      [:array "put"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
        (emit-expr! mv (first args) state-slot)
        (emit-boxed-expr! mv (second args) state-slot)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "set" "(ILjava/lang/Object;)Ljava/lang/Object;" false)
        (.visitInsn mv Opcodes/POP)
        :void)

      [:array "set"]
      (emit-collection-method! mv (assoc expr :method "put") state-slot)

      [:array "length"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "size" "()I" false)
        :int)

      [:array "size"]
      (emit-collection-method! mv (assoc expr :method "length") state-slot)

      [:array "is_empty"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "isEmpty" "()Z" false)
        :boolean)

      [:array "contains"]
      (do
        (emit-runtime-call! mv "array-contains"
                            [(fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
        :boolean)

      [:array "index_of"]
      (do
        (emit-runtime-call! mv "array-index-of"
                            [(fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Integer")
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Integer" "intValue" "()I" false)
        :int)

      [:array "remove"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
        (emit-expr! mv (first args) state-slot)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "remove" "(I)Ljava/lang/Object;" false)
        (.visitInsn mv Opcodes/POP)
        :void)

      [:array "reverse"]
      (do
        (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
        (.visitInsn mv Opcodes/DUP)
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "reversed" "()Ljava/util/List;" false)
        (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "(Ljava/util/Collection;)V" false)
        jvm-type)

      [:array "slice"]
      (do
        (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
        (.visitInsn mv Opcodes/DUP)
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
        (emit-expr! mv (first args) state-slot)
        (emit-expr! mv (second args) state-slot)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "subList" "(II)Ljava/util/List;" false)
        (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "(Ljava/util/Collection;)V" false)
        jvm-type)

      [:array "first"]
      (emit-collection-method! mv (assoc expr :method "get" :args [(ir/const-node 0 "Integer" :int)]) state-slot)

      [:array "last"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
        (.visitInsn mv Opcodes/DUP)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "size" "()I" false)
        (.visitInsn mv Opcodes/ICONST_1)
        (.visitInsn mv Opcodes/ISUB)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "get" "(I)Ljava/lang/Object;" false)
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:array "sort"]
      (do
        (emit-runtime-call! mv "array-sort"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-expr! mv target state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:array "join"]
      (do
        (emit-runtime-call! mv "array-join"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-expr! mv (first args) state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/String")
        jvm-type)

      [:array "to_string"]
      (do
        (emit-runtime-call! mv "array-to-string"
                            [(fn [] (emit-expr! mv target state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/String")
        jvm-type)

      [:array "equals"]
      (do
        (emit-runtime-call! mv "deep-equals"
                            [(fn [] (emit-boxed-expr! mv target state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
        :boolean)

      [:array "clone"]
      (do
        (emit-runtime-call! mv "clone-value"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:array "cursor"]
      (do
        (emit-runtime-call! mv "collection-cursor"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (.visitLdcInsn mv "Array"))
                             (fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:map "get"]
      (do
        (emit-runtime-call! mv "map-get"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:map "try_get"]
      (do
        (emit-runtime-call! mv "map-try-get"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))
                             (fn [] (emit-boxed-expr! mv (second args) state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:map "put"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
        (emit-boxed-expr! mv (first args) state-slot)
        (emit-boxed-expr! mv (second args) state-slot)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "put" "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" false)
        (.visitInsn mv Opcodes/POP)
        :void)

      [:map "at"]
      (emit-collection-method! mv (assoc expr :method "put") state-slot)

      [:map "set"]
      (emit-collection-method! mv (assoc expr :method "put") state-slot)

      [:map "size"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "size" "()I" false)
        :int)

      [:map "is_empty"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "isEmpty" "()Z" false)
        :boolean)

      [:map "contains_key"]
      (do
        (emit-runtime-call! mv "map-contains-key"
                            [(fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
        :boolean)

      [:map "keys"]
      (do
        (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
        (.visitInsn mv Opcodes/DUP)
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "keySet" "()Ljava/util/Set;" false)
        (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "(Ljava/util/Collection;)V" false)
        jvm-type)

      [:map "values"]
      (do
        (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
        (.visitInsn mv Opcodes/DUP)
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "values" "()Ljava/util/Collection;" false)
        (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "(Ljava/util/Collection;)V" false)
        jvm-type)

      [:map "remove"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
        (emit-boxed-expr! mv (first args) state-slot)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "remove" "(Ljava/lang/Object;)Ljava/lang/Object;" false)
        (.visitInsn mv Opcodes/POP)
        :void)

      [:map "to_string"]
      (do
        (emit-runtime-call! mv "map-to-string"
                            [(fn [] (emit-expr! mv target state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/String")
        jvm-type)

      [:map "equals"]
      (do
        (emit-runtime-call! mv "deep-equals"
                            [(fn [] (emit-boxed-expr! mv target state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
        :boolean)

      [:map "clone"]
      (do
        (emit-runtime-call! mv "clone-value"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:map "cursor"]
      (do
        (emit-runtime-call! mv "collection-cursor"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (.visitLdcInsn mv "Map"))
                             (fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:set "contains"]
      (do
        (emit-runtime-call! mv "set-contains"
                            [(fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
        :boolean)

      [:set "union"]
      (do
        (emit-runtime-call! mv "set-union"
                            [(fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-expr! mv (first args) state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:set "difference"]
      (do
        (emit-runtime-call! mv "set-difference"
                            [(fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-expr! mv (first args) state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:set "intersection"]
      (do
        (emit-runtime-call! mv "set-intersection"
                            [(fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-expr! mv (first args) state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:set "symmetric_difference"]
      (do
        (emit-runtime-call! mv "set-symmetric-difference"
                            [(fn [] (emit-expr! mv target state-slot))
                             (fn [] (emit-expr! mv (first args) state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:set "size"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST linkedhashset-internal-name)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL linkedhashset-internal-name "size" "()I" false)
        :int)

      [:set "is_empty"]
      (do
        (emit-expr! mv target state-slot)
        (.visitTypeInsn mv Opcodes/CHECKCAST linkedhashset-internal-name)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL linkedhashset-internal-name "isEmpty" "()Z" false)
        :boolean)

      [:set "to_string"]
      (do
        (emit-runtime-call! mv "set-to-string"
                            [(fn [] (emit-expr! mv target state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/String")
        jvm-type)

      [:set "equals"]
      (do
        (emit-runtime-call! mv "deep-equals"
                            [(fn [] (emit-boxed-expr! mv target state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
        :boolean)

      [:set "clone"]
      (do
        (emit-runtime-call! mv "clone-value"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      [:set "cursor"]
      (do
        (emit-runtime-call! mv "collection-cursor"
                            [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                             (fn [] (.visitLdcInsn mv "Set"))
                             (fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-unbox-or-cast! mv jvm-type)
        jvm-type)

      (throw (ex-info "Unsupported collection method emission"
                      {:expr expr})))))

(defn- emit-concurrency-method!
  [^MethodVisitor mv expr state-slot]
  (let [{:keys [concurrency-kind method target args jvm-type]} expr
        emit-return (fn [jvm-type]
                      (if (= :void jvm-type)
                        (do (.visitInsn mv Opcodes/POP) :void)
                        (do (emit-unbox-or-cast! mv jvm-type)
                            jvm-type)))]
    (case [concurrency-kind method]
      [:task "await"]
      (do
        (emit-runtime-call! mv "task-await-method"
                            (cond-> [(fn [] (emit-boxed-expr! mv target state-slot))]
                              (seq args) (conj (fn [] (emit-boxed-expr! mv (first args) state-slot)))))
        (emit-return jvm-type))

      [:task "cancel"]
      (do
        (emit-runtime-call! mv "task-cancel-method"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-return jvm-type))

      [:task "is_done"]
      (do
        (emit-runtime-call! mv "task-is-done-method"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-return jvm-type))

      [:task "is_cancelled"]
      (do
        (emit-runtime-call! mv "task-is-cancelled-method"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-return jvm-type))

      [:channel "send"]
      (do
        (emit-runtime-call! mv "channel-send-method"
                            (cond-> [(fn [] (emit-boxed-expr! mv target state-slot))
                                     (fn [] (emit-boxed-expr! mv (first args) state-slot))]
                              (= 2 (count args)) (conj (fn [] (emit-boxed-expr! mv (second args) state-slot)))))
        (emit-return jvm-type))

      [:channel "try_send"]
      (do
        (emit-runtime-call! mv "channel-try-send-method"
                            [(fn [] (emit-boxed-expr! mv target state-slot))
                             (fn [] (emit-boxed-expr! mv (first args) state-slot))])
        (emit-return jvm-type))

      [:channel "receive"]
      (do
        (emit-runtime-call! mv "channel-receive-method"
                            (cond-> [(fn [] (emit-boxed-expr! mv target state-slot))]
                              (seq args) (conj (fn [] (emit-boxed-expr! mv (first args) state-slot)))))
        (emit-return jvm-type))

      [:channel "try_receive"]
      (do
        (emit-runtime-call! mv "channel-try-receive-method"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-return jvm-type))

      [:channel "close"]
      (do
        (emit-runtime-call! mv "channel-close-method"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-return jvm-type))

      [:channel "is_closed"]
      (do
        (emit-runtime-call! mv "channel-is-closed-method"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-return jvm-type))

      [:channel "capacity"]
      (do
        (emit-runtime-call! mv "channel-capacity-method"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-return jvm-type))

      [:channel "size"]
      (do
        (emit-runtime-call! mv "channel-size-method"
                            [(fn [] (emit-boxed-expr! mv target state-slot))])
        (emit-return jvm-type))

      (throw (ex-info "Unsupported concurrency method emission"
                      {:expr expr})))))

(defn- emit-expr!
  [^MethodVisitor mv expr state-slot]
  (case (:op expr)
    :const
    (do
      (emit-const! mv expr)
      (:jvm-type expr))

    :array-literal
    (emit-array-literal! mv expr state-slot)

    :map-literal
    (emit-map-literal! mv expr state-slot)

    :set-literal
    (emit-set-literal! mv expr state-slot)

    :local
    (do
      (mark-local-debug-before! mv (:slot expr))
      (.visitVarInsn mv (local-load-op (:jvm-type expr)) (:slot expr))
      (mark-local-debug-after! mv (:slot expr))
      (:jvm-type expr))

    :this
    (do
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (:jvm-type expr))

    :new
    (do
      (.visitTypeInsn mv Opcodes/NEW (:class expr))
      (.visitInsn mv Opcodes/DUP)
      (.visitMethodInsn mv Opcodes/INVOKESPECIAL (:class expr) "<init>" "()V" false)
      (:jvm-type expr))

    :top-get
    (do
      (emit-load-values-map! mv state-slot)
      (.visitLdcInsn mv ^String (:name expr))
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        hashmap-internal-name
                        "get"
                        "(Ljava/lang/Object;)Ljava/lang/Object;"
                        false)
      (emit-unbox-or-cast! mv (:jvm-type expr))
      (:jvm-type expr))

    :field-get
    (do
      (emit-expr! mv (:target expr) state-slot)
      (.visitTypeInsn mv Opcodes/CHECKCAST (:owner expr))
      (.visitFieldInsn mv
                       Opcodes/GETFIELD
                       (:owner expr)
                       (:field expr)
                       (desc/jvm-type->descriptor (:jvm-type expr)))
      (:jvm-type expr))

    :static-field-get
    (do
      (.visitFieldInsn mv
                       Opcodes/GETSTATIC
                       (:owner expr)
                       (:field expr)
                       (desc/jvm-type->descriptor (:jvm-type expr)))
      (:jvm-type expr))

    :call-repl-fn
    (do
      (emit-state-load-functions-map! mv state-slot)
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
      (.visitVarInsn mv Opcodes/ALOAD state-slot)
      (.visitInsn mv Opcodes/AASTORE)
      (.visitInsn mv Opcodes/DUP)
      (.visitInsn mv Opcodes/ICONST_1)
      (emit-boxed-arg-array! mv (:args expr) state-slot)
      (.visitInsn mv Opcodes/AASTORE)
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        "java/lang/reflect/Method"
                        "invoke"
                        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"
                        false)
      (emit-unbox-or-cast! mv (:jvm-type expr))
      (:jvm-type expr))

    :call-function
    (do
      (emit-runtime-var! mv "invoke-function-object")
      (.visitVarInsn mv Opcodes/ALOAD state-slot)
      (emit-boxed-expr! mv (:target expr) state-slot)
      (emit-boxed-arg-array! mv (:args expr) state-slot)
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        var-internal-name
                        "invoke"
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                        false)
      (if (= :void (:jvm-type expr))
        (do (.visitInsn mv Opcodes/POP) :void)
        (do
          (emit-unbox-or-cast! mv (:jvm-type expr))
          (:jvm-type expr))))

    :call-virtual
    (do
      (emit-expr! mv (:target expr) state-slot)
      (.visitTypeInsn mv Opcodes/CHECKCAST (:owner expr))
      (.visitVarInsn mv Opcodes/ALOAD state-slot)
      (emit-boxed-arg-array! mv (:args expr) state-slot)
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        (:owner expr)
                        (:method expr)
                        (:descriptor expr)
                        false)
      (emit-unbox-or-cast! mv (:jvm-type expr))
      (:jvm-type expr))

    :call-runtime
    (or (emit-direct-runtime-helper-call! mv expr state-slot)
        (do
          (.visitLdcInsn mv "nex.compiler.jvm.runtime")
          (.visitLdcInsn mv "invoke-builtin")
          (.visitMethodInsn mv
                            Opcodes/INVOKESTATIC
                            rt-internal-name
                            "var"
                            "(Ljava/lang/String;Ljava/lang/String;)Lclojure/lang/Var;"
                            false)
          (.visitVarInsn mv Opcodes/ALOAD state-slot)
          (.visitLdcInsn mv ^String (:helper expr))
          (emit-boxed-arg-array! mv (:args expr) state-slot)
          (.visitMethodInsn mv
                            Opcodes/INVOKEVIRTUAL
                            var-internal-name
                            "invoke"
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                            false)
          (if (= :void (:jvm-type expr))
            (do (.visitInsn mv Opcodes/POP) :void)
            (do (emit-unbox-or-cast! mv (:jvm-type expr))
                (:jvm-type expr)))))

    :collection-method
    (emit-collection-method! mv expr state-slot)

    :concurrency-method
    (emit-concurrency-method! mv expr state-slot)

    :convert
    (emit-convert! mv expr state-slot)

    :unary
    (let [operand-type (emit-expr! mv (:expr expr) state-slot)]
      (case (:operator expr)
        :not
        (do
          (when-not (= :boolean operand-type)
            (throw (ex-info "Boolean not requires boolean operand"
                            {:expr expr :jvm-type operand-type})))
          (.visitInsn mv Opcodes/ICONST_1)
          (.visitInsn mv Opcodes/IXOR)
          (:jvm-type expr))

        :bit-not
        (do
          (when-not (= :int operand-type)
            (throw (ex-info "Bitwise not requires int operand"
                            {:expr expr :jvm-type operand-type})))
          (.visitInsn mv Opcodes/ICONST_M1)
          (.visitInsn mv Opcodes/IXOR)
          (:jvm-type expr))

        (do
          (when-not (= operand-type (:jvm-type expr))
            (throw (ex-info "Unary operand lowered to unexpected JVM type"
                            {:expr expr
                             :operand-jvm-type operand-type
                             :expr-jvm-type (:jvm-type expr)})))
          (.visitInsn mv (or (unary-opcode (:operator expr) (:jvm-type expr))
                             (throw (ex-info "Unsupported unary opcode emission"
                                             {:operator (:operator expr)
                                              :jvm-type (:jvm-type expr)}))))
          (:jvm-type expr))))

    :binary
    (cond
      (#{:and :or} (:operator expr))
      (do
        (emit-boolean-short-circuit! mv (:operator expr) (:left expr) (:right expr) state-slot)
        (:jvm-type expr))

      (= :bit-rotl (:operator expr))
      (do
        (emit-expr! mv (:left expr) state-slot)
        (emit-expr! mv (:right expr) state-slot)
        (.visitMethodInsn mv
                          Opcodes/INVOKESTATIC
                          "java/lang/Integer"
                          "rotateLeft"
                          "(II)I"
                          false)
        (:jvm-type expr))

      (= :bit-rotr (:operator expr))
      (do
        (emit-expr! mv (:left expr) state-slot)
        (emit-expr! mv (:right expr) state-slot)
        (.visitMethodInsn mv
                          Opcodes/INVOKESTATIC
                          "java/lang/Integer"
                          "rotateRight"
                          "(II)I"
                          false)
        (:jvm-type expr))

      (= :bit-test (:operator expr))
      (do
        (emit-bit-test! mv (:left expr) (:right expr) state-slot)
        (:jvm-type expr))

      (#{:bit-set :bit-unset} (:operator expr))
      (do
        (emit-bit-set-like! mv (:operator expr) (:left expr) (:right expr) state-slot)
        (:jvm-type expr))

      :else
      (let [left-type (emit-expr! mv (:left expr) state-slot)
            operand-type (or (numeric-promotion-jvm-type left-type (:jvm-type expr))
                             (:jvm-type expr))]
        (emit-stack-coerce! mv left-type operand-type)
        (let [right-type (emit-expr! mv (:right expr) state-slot)]
          (emit-stack-coerce! mv right-type operand-type)
          (when-not (= operand-type (:jvm-type expr))
            (throw (ex-info "Binary operand promotion disagrees with result JVM type"
                            {:expr expr
                             :operand-jvm-type operand-type
                             :result-jvm-type (:jvm-type expr)
                             :left-jvm-type left-type
                             :right-jvm-type right-type})))
          (.visitInsn mv (binary-opcode (:operator expr) operand-type)))
        (:jvm-type expr)))

    :compare
    (let [left-type (emit-expr! mv (:left expr) state-slot)
          operand-type (or (numeric-promotion-jvm-type left-type (:jvm-type (:left expr)))
                           left-type)]
      (emit-stack-coerce! mv left-type operand-type)
      (let [right-type (emit-expr! mv (:right expr) state-slot)
            compare-type (or (numeric-promotion-jvm-type operand-type right-type)
                             (when (= operand-type right-type) operand-type)
                             (when (and (ir/object-jvm-type? operand-type)
                                        (ir/object-jvm-type? right-type))
                               (ir/object-jvm-type "java/lang/Object")))]
        (when-not compare-type
          (throw (ex-info "Compare operands lowered to incompatible JVM types"
                          {:expr expr
                           :left-jvm-type left-type
                           :right-jvm-type right-type})))
        (emit-stack-coerce! mv right-type compare-type)
      (cond
        (#{:int :boolean :char} compare-type)
        (emit-numeric-compare! mv (:operator expr) compare-type)

        (#{:long :double} compare-type)
        (emit-long-or-double-compare! mv (:operator expr) compare-type)

        (ir/object-jvm-type? compare-type)
        (do
          (when-not (#{:eq :neq} (:operator expr))
            (throw (ex-info "Only eq/neq object comparisons are supported"
                            {:expr expr :jvm-type compare-type})))
          (emit-object-compare! mv (:operator expr)))

        :else
        (throw (ex-info "Unsupported compare emission type"
                        {:expr expr :jvm-type compare-type}))))
      (:jvm-type expr))

    :if
    (let [else-label (Label.)
          end-label (Label.)
          test-type (emit-expr! mv (:test expr) state-slot)
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
      (let [result-type (:jvm-type expr)
            then-type (emit-expr! mv (first then-exprs) state-slot)
            else-type (do
                        (emit-stack-coerce! mv then-type result-type)
                        (.visitJumpInsn mv Opcodes/GOTO end-label)
                        (.visitLabel mv else-label)
                        (let [emitted-else-type (emit-expr! mv (first else-exprs) state-slot)]
                          (emit-stack-coerce! mv emitted-else-type result-type)
                          emitted-else-type))]
        (.visitLabel mv end-label)
        result-type))

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
  [^MethodVisitor mv expr state-slot]
  (let [jvm-type (emit-expr! mv expr state-slot)]
    (when (contains? ir/primitive-jvm-types jvm-type)
      (emit-box! mv jvm-type))
    (.visitInsn mv Opcodes/ARETURN)))

(defn- emit-raise!
  [^MethodVisitor mv expr state-slot]
  (let [jvm-type (emit-expr! mv expr state-slot)]
    (when (contains? ir/primitive-jvm-types jvm-type)
      (emit-box! mv jvm-type))
    (emit-runtime-invoke-1! mv "make-raised-exception")
    (.visitTypeInsn mv Opcodes/CHECKCAST throwable-internal-name)
    (.visitInsn mv Opcodes/ATHROW)))

(defn- emit-retry!
  [^MethodVisitor mv]
  (emit-runtime-invoke-0! mv "make-retry-signal")
  (.visitTypeInsn mv Opcodes/CHECKCAST throwable-internal-name)
  (.visitInsn mv Opcodes/ATHROW))

(defn- emit-assert!
  [^MethodVisitor mv {:keys [kind label expr]} state-slot]
  (let [ok-label (Label.)
        expr-type (emit-expr! mv expr state-slot)
        kind-label (case kind
                     :require "Precondition"
                     :ensure "Postcondition"
                     :invariant "Loop invariant"
                     :variant "Loop variant"
                     :class-invariant "Class invariant"
                     (name kind))]
    (when-not (= :boolean expr-type)
      (throw (ex-info "Assert emission requires boolean expression"
                      {:stmt {:kind kind :label label}
                       :jvm-type expr-type})))
    (.visitJumpInsn mv Opcodes/IFNE ok-label)
    (.visitLdcInsn mv ^String kind-label)
    (.visitLdcInsn mv ^String label)
    (emit-runtime-invoke-2! mv "make-contract-violation")
    (.visitTypeInsn mv Opcodes/CHECKCAST throwable-internal-name)
    (.visitInsn mv Opcodes/ATHROW)
    (.visitLabel mv ok-label)))

(defn- emit-try!
  [^MethodVisitor mv {:keys [body rescue throwable-slot rescue-throwable-slot exception-slot]} state-slot]
  (let [loop-start (Label.)
        body-start (Label.)
        body-end (Label.)
        body-handler (Label.)
        rescue-start (Label.)
        rescue-end (Label.)
        rescue-handler (Label.)
        not-retry-label (Label.)
        rescue-not-retry-label (Label.)
        end-label (Label.)]
    (.visitTryCatchBlock mv body-start body-end body-handler throwable-internal-name)
    (.visitTryCatchBlock mv rescue-start rescue-end rescue-handler throwable-internal-name)
    (.visitLabel mv loop-start)
    (.visitLabel mv body-start)
    (doseq [stmt body]
      (emit-stmt! mv stmt state-slot))
    (.visitLabel mv body-end)
    (.visitJumpInsn mv Opcodes/GOTO end-label)

    (.visitLabel mv body-handler)
    (.visitVarInsn mv Opcodes/ASTORE throwable-slot)
    (.visitVarInsn mv Opcodes/ALOAD throwable-slot)
    (emit-runtime-invoke-1! mv "retry-signal?")
    (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
    (.visitMethodInsn mv
                      Opcodes/INVOKEVIRTUAL
                      "java/lang/Boolean"
                      "booleanValue"
                      "()Z"
                      false)
    (.visitJumpInsn mv Opcodes/IFEQ not-retry-label)
    (.visitVarInsn mv Opcodes/ALOAD throwable-slot)
    (.visitInsn mv Opcodes/ATHROW)

    (.visitLabel mv not-retry-label)
    (.visitVarInsn mv Opcodes/ALOAD throwable-slot)
    (emit-runtime-invoke-1! mv "exception-value")
    (.visitVarInsn mv Opcodes/ASTORE exception-slot)
    (.visitLabel mv rescue-start)
    (doseq [stmt rescue]
      (emit-stmt! mv stmt state-slot))
    (.visitLabel mv rescue-end)
    (.visitVarInsn mv Opcodes/ALOAD throwable-slot)
    (.visitInsn mv Opcodes/ATHROW)

    (.visitLabel mv rescue-handler)
    (.visitVarInsn mv Opcodes/ASTORE rescue-throwable-slot)
    (.visitVarInsn mv Opcodes/ALOAD rescue-throwable-slot)
    (emit-runtime-invoke-1! mv "retry-signal?")
    (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
    (.visitMethodInsn mv
                      Opcodes/INVOKEVIRTUAL
                      "java/lang/Boolean"
                      "booleanValue"
                      "()Z"
                      false)
    (.visitJumpInsn mv Opcodes/IFEQ rescue-not-retry-label)
    (.visitJumpInsn mv Opcodes/GOTO loop-start)

    (.visitLabel mv rescue-not-retry-label)
    (.visitVarInsn mv Opcodes/ALOAD rescue-throwable-slot)
    (.visitInsn mv Opcodes/ATHROW)
    (.visitLabel mv end-label)))

(defn- emit-line-number!
  [^MethodVisitor mv stmt]
  (when-let [line (:dbg/line stmt)]
    (let [label (Label.)]
      (.visitLabel mv label)
      (.visitLineNumber mv (int line) label))))

(defn- emit-stmt!
  [^MethodVisitor mv stmt state-slot]
  (emit-line-number! mv stmt)
  (case (:op stmt)
    :return
    (emit-return! mv (:expr stmt) state-slot)

    :pop
    (emit-pop! mv (emit-expr! mv (:expr stmt) state-slot))

    :set-local
    (let [expr-jvm-type (emit-expr! mv (:expr stmt) state-slot)]
      (emit-stack-coerce! mv expr-jvm-type (:jvm-type stmt))
      (mark-local-debug-before! mv (:slot stmt))
      (.visitVarInsn mv (local-store-op (:jvm-type stmt)) (:slot stmt))
      (mark-local-debug-after! mv (:slot stmt))
      expr-jvm-type)

    :top-set
    (do
      (emit-load-values-map! mv state-slot)
      (.visitLdcInsn mv ^String (:name stmt))
      (let [expr-jvm-type (emit-expr! mv (:expr stmt) state-slot)]
        (when (contains? ir/primitive-jvm-types expr-jvm-type)
          (emit-box! mv expr-jvm-type)))
      (.visitMethodInsn mv
                        Opcodes/INVOKEVIRTUAL
                        hashmap-internal-name
                        "put"
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                        false)
      (.visitInsn mv Opcodes/POP))

    :field-set
    (do
      (emit-expr! mv (:target stmt) state-slot)
      (.visitTypeInsn mv Opcodes/CHECKCAST (:owner stmt))
      (emit-stack-coerce! mv (emit-expr! mv (:expr stmt) state-slot) (:jvm-type stmt))
      (.visitFieldInsn mv
                       Opcodes/PUTFIELD
                       (:owner stmt)
                       (:field stmt)
                       (desc/jvm-type->descriptor (:jvm-type stmt))))

    :call-runtime
    (emit-pop! mv (emit-expr! mv stmt state-slot))

    :raise
    (emit-raise! mv (:expr stmt) state-slot)

    :retry
    (emit-retry! mv)

    :assert
    (emit-assert! mv stmt state-slot)

    :try
    (emit-try! mv stmt state-slot)

    :block
    (doseq [nested (:body stmt)]
      (emit-stmt! mv nested state-slot))

    :if-stmt
    (let [else-label (Label.)
          end-label (Label.)
          test-type (emit-expr! mv (:test stmt) state-slot)]
      (when-not (= :boolean test-type)
        (throw (ex-info "If statement test did not lower to boolean"
                        {:stmt stmt :test-jvm-type test-type})))
      (.visitJumpInsn mv Opcodes/IFEQ else-label)
      (doseq [then-stmt (:then stmt)]
        (emit-stmt! mv then-stmt state-slot))
      (.visitJumpInsn mv Opcodes/GOTO end-label)
      (.visitLabel mv else-label)
      (doseq [else-stmt (:else stmt)]
        (emit-stmt! mv else-stmt state-slot))
      (.visitLabel mv end-label))

    :loop
    (let [loop-label (Label.)
          end-label (Label.)]
      (doseq [init-stmt (:init stmt)]
        (emit-stmt! mv init-stmt state-slot))
      (.visitLabel mv loop-label)
      (let [test-type (emit-expr! mv (:test stmt) state-slot)]
        (.visitJumpInsn mv Opcodes/IFNE end-label))
      (doseq [body-stmt (:body stmt)]
        (emit-stmt! mv body-stmt state-slot))
      (.visitJumpInsn mv Opcodes/GOTO loop-label)
      (.visitLabel mv end-label))

    (throw (ex-info "Unsupported IR statement emission"
                    {:stmt stmt :op (:op stmt)}))))

(defn- emit-function-arg-prologue!
  [^MethodVisitor mv fn-node arg-array-slot]
  (doseq [{:keys [arg-index slot jvm-type]} (:params fn-node)]
    (.visitVarInsn mv Opcodes/ALOAD arg-array-slot)
    (.visitLdcInsn mv (int arg-index))
    (.visitInsn mv Opcodes/AALOAD)
    (emit-unbox-or-cast! mv jvm-type)
    (mark-local-debug-before! mv slot)
    (.visitVarInsn mv (local-store-op jvm-type) slot)
    (mark-local-debug-after! mv slot)))

(defn- emit-eval-method!
  [^ClassWriter cw {:keys [name descriptor flags body owner functions locals]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (let [start-label (Label.)
          end-label (Label.)
          local-ranges (atom {})]
      (binding [*local-debug-ranges* local-ranges]
        (.visitLabel mv start-label)
        (doseq [fn-node functions]
          (emit-register-repl-fn! mv 0 owner fn-node))
        (doseq [stmt body]
          (emit-stmt! mv stmt 0))
        (.visitInsn mv Opcodes/ACONST_NULL)
        (.visitLabel mv end-label)
        (emit-local-variable-table! mv start-label end-label
                                    (concat [{:name "state"
                                              :slot 0
                                              :jvm-type (ir/object-jvm-type repl-state-internal-name)}]
                                            locals)
                                    @local-ranges)
        (.visitInsn mv Opcodes/ARETURN)
        (.visitMaxs mv 0 0)
        (.visitEnd mv)))))

(defn- emit-repl-fn-method!
  [^ClassWriter cw {:keys [name descriptor flags fn-node]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (let [start-label (Label.)
          end-label (Label.)
          local-ranges (atom {})]
      (binding [*local-debug-ranges* local-ranges]
        (.visitLabel mv start-label)
        (emit-function-arg-prologue! mv fn-node 1)
        (doseq [stmt (:body fn-node)]
          (emit-stmt! mv stmt 0))
        (.visitInsn mv Opcodes/ACONST_NULL)
        (.visitLabel mv end-label)
        (emit-local-variable-table! mv start-label end-label
                                    (concat [{:name "state"
                                              :slot 0
                                              :jvm-type (ir/object-jvm-type repl-state-internal-name)}
                                             {:name "__args"
                                              :slot 1
                                              :descriptor "[Ljava/lang/Object;"}]
                                            (:locals fn-node))
                                    @local-ranges)
        (.visitInsn mv Opcodes/ARETURN)
        (.visitMaxs mv 0 0)
        (.visitEnd mv)))))

(defn- emit-instance-fn-method!
  [^ClassWriter cw {:keys [name descriptor flags fn-node]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (let [start-label (Label.)
          end-label (Label.)
          local-ranges (atom {})]
      (binding [*local-debug-ranges* local-ranges]
        (.visitLabel mv start-label)
        (emit-function-arg-prologue! mv fn-node 2)
        (doseq [stmt (:body fn-node)]
          (emit-stmt! mv stmt 1))
        (.visitInsn mv Opcodes/ACONST_NULL)
        (.visitLabel mv end-label)
        (emit-local-variable-table! mv start-label end-label
                                    (concat [{:name "this"
                                              :slot 0
                                              :jvm-type (ir/object-jvm-type (:owner fn-node))}
                                             {:name "state"
                                              :slot 1
                                              :jvm-type (ir/object-jvm-type repl-state-internal-name)}
                                             {:name "__args"
                                              :slot 2
                                              :descriptor "[Ljava/lang/Object;"}]
                                            (:locals fn-node))
                                    @local-ranges)
        (.visitInsn mv Opcodes/ARETURN)
        (.visitMaxs mv 0 0)
        (.visitEnd mv)))))

(defn- emit-abstract-instance-fn-method!
  [^ClassWriter cw {:keys [name descriptor flags]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitEnd mv)))

(defn- emit-field!
  [^ClassWriter cw {:keys [name descriptor flags]}]
  (let [fv (.visitField cw flags name descriptor nil nil)]
    (.visitEnd fv)))

(defn- emit-class-initializer!
  [^ClassWriter cw {:keys [name descriptor flags owner constants]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (doseq [{:keys [name jvm-type value]} constants]
      (emit-expr! mv value 0)
      (.visitFieldInsn mv
                       Opcodes/PUTSTATIC
                       owner
                       name
                       (desc/jvm-type->descriptor jvm-type)))
    (.visitInsn mv Opcodes/RETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn emit-method!
  [^ClassWriter cw method-spec]
  (case (:kind method-spec)
    :default-constructor (emit-default-constructor! cw method-spec)
    :launcher-main (emit-launcher-main! cw method-spec)
    :user-default-constructor (emit-user-default-constructor! cw method-spec)
    :class-initializer (emit-class-initializer! cw method-spec)
    :eval-from-ir (emit-eval-method! cw method-spec)
    :repl-fn (emit-repl-fn-method! cw method-spec)
    :instance-ctor-fn (emit-instance-fn-method! cw method-spec)
    :abstract-instance-fn (emit-abstract-instance-fn-method! cw method-spec)
    :instance-fn (emit-instance-fn-method! cw method-spec)
    (throw (ex-info "Unsupported method emission kind"
                    {:method-spec method-spec}))))

(defn emit-class
  "Emit one minimal JVM class from a class spec and return bytecode."
  [{:keys [internal-name super-name interfaces flags methods fields static-fields source-file]}]
  (let [cw (ClassWriter. (+ ClassWriter/COMPUTE_FRAMES
                            ClassWriter/COMPUTE_MAXS))]
    (.visit cw
            class-version
            flags
            internal-name
            nil
            super-name
            (when (seq interfaces) (into-array String interfaces)))
    (when-let [sf (source-file-name source-file)]
      (.visitSource cw sf nil))
    (doseq [field fields]
      (emit-field! cw field))
    (doseq [field static-fields]
      (emit-field! cw field))
    (doseq [method methods]
      (emit-method! cw method))
    (.visitEnd cw)
    (.toByteArray cw)))

(defn compile-unit->bytes
  "Compile the first minimal IR unit to JVM bytecode."
  [unit]
  (emit-class (minimal-class-spec unit)))

(defn compile-user-class->bytes
  [class-spec]
  (emit-class (user-class-spec class-spec)))

(defn compile-launcher->bytes
  [launcher-spec]
  (emit-class (launcher-class-spec launcher-spec)))
