(ns nex.compiler.jvm.emit
  "ASM-backed JVM bytecode emission for Nex.

  Emits lowered Nex IR and class specs as JVM bytecode for:

  - compiled REPL cells with `eval(NexReplState)`
  - user-defined classes, constructors, methods, and constants
  - launcher classes with `main(String[])`

  The emitter handles control flow, runtime helper calls, object-model support,
  and debug metadata such as source files, line tables, and local variables."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [nex.compiler.jvm.descriptor :as desc]
            [nex.ir :as ir]
            [nex.types.builtins :as bi])
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
(declare emit-runtime-invoke-1!)
(declare emit-expression!)
(declare primitive-class->jvm-type)
(declare primitive-box-info)

(defn- emit-string-constant!
  "Push a String onto the stack. A plain LDC references a single CONSTANT_Utf8
   entry, which the class-file format caps at 65535 bytes — a limit large
   programs hit when the whole class table is serialized into one blob (see
   `classes-edn` in file.clj). When the string is large enough to risk the cap,
   split it into fixed char chunks and rebuild it with StringBuilder at runtime.

   Chunks are 16384 chars: modified UTF-8 uses at most 3 bytes per char, so a
   chunk is at most 49152 bytes — safely under 65535. Splitting by char index is
   exact even when a boundary falls between the halves of a surrogate pair, since
   the appends reassemble the identical string."
  [^MethodVisitor mv ^String s]
  ;; Fast path guards well below the cap so the multi-byte worst case can never
  ;; push a single-chunk string over it.
  (if (< (count s) 21000)
    (.visitLdcInsn mv s)
    (do
      (.visitTypeInsn mv Opcodes/NEW "java/lang/StringBuilder")
      (.visitInsn mv Opcodes/DUP)
      (.visitMethodInsn mv Opcodes/INVOKESPECIAL "java/lang/StringBuilder"
                        "<init>" "()V" false)
      (doseq [chunk (map (partial apply str) (partition-all 16384 s))]
        (.visitLdcInsn mv ^String chunk)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/StringBuilder"
                          "append" "(Ljava/lang/String;)Ljava/lang/StringBuilder;" false))
      (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/StringBuilder"
                        "toString" "()Ljava/lang/String;" false))))

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
  ([class-spec] (user-class-spec class-spec {}))
  ([class-spec {:keys [classes-edn imports-edn]}]
  {:internal-name (:internal-name class-spec)
   :binary-name (desc/binary-class-name (:jvm-name class-spec))
   :source-file (:source-file class-spec)
   :super-name (or (:java-super-class class-spec) "java/lang/Object")
   :interfaces (vec (:interfaces class-spec))
   :flags Opcodes/ACC_PUBLIC
   :fields (vec
            (concat
             [{:name "__outer__"
               :descriptor "Ljava/lang/Object;"
               :flags Opcodes/ACC_PUBLIC
               :jvm-type (ir/object-jvm-type "java/lang/Object")}
              ;; The state that created this object, so the emitted `equals`/
              ;; `hashCode` below can reach Nex semantics. Java hands those two
              ;; methods nothing but the receiver, yet answering them means
              ;; dispatching to a class's `equals`/`hash` override, whose lowered
              ;; form takes a NexReplState as its first argument. Set at the
              ;; `:new` site (see emit-expr! :new), which is the only place a
              ;; user object is constructed with state in scope.
              {:name "__state__"
               :descriptor (str "L" repl-state-internal-name ";")
               :flags Opcodes/ACC_PUBLIC
               :jvm-type (ir/object-jvm-type repl-state-internal-name)}]
             (map (fn [{:keys [name jvm-type]}]
                    {:name name
                     :descriptor (desc/jvm-type->descriptor jvm-type)
                     :flags Opcodes/ACC_PRIVATE
                     :jvm-type jvm-type})
                  (:composition-fields class-spec))
             (map (fn [{:keys [name jvm-type]}]
                    {:name name
                     :descriptor (desc/jvm-type->descriptor jvm-type)
                     :flags Opcodes/ACC_PRIVATE
                     :jvm-type jvm-type})
                  (:runtime-type-fields class-spec))
             (map (fn [{:keys [name jvm-type nex-type]}]
                    {:name name
                     :descriptor (desc/jvm-type->descriptor jvm-type)
                     :flags Opcodes/ACC_PUBLIC
                     :jvm-type jvm-type
                     :nex-type nex-type})
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
                :super-name (or (:java-super-class class-spec) "java/lang/Object")
                :composition-fields (:composition-fields class-spec)
                :runtime-type-fields (:runtime-type-fields class-spec)
                :fields (:fields class-spec)}
               {:name "<clinit>"
                :descriptor "()V"
                :flags (+ Opcodes/ACC_STATIC)
                :kind :class-initializer
                :owner (:internal-name class-spec)
                :constants (:constants class-spec)
                :classes-edn classes-edn
                :imports-edn imports-edn}
               ;; Java's equality, delegating to Nex's. Set and Map are a
               ;; LinkedHashSet and a HashMap on this backend, so membership,
               ;; dedup and key lookup are decided by these two methods and
               ;; nothing else — without them a class's `equals`/`hash` override
               ;; is ignored the moment its instances go into a collection, and
               ;; `#{a, b}` keeps two objects the program considers one.
               {:name "equals"
                :descriptor "(Ljava/lang/Object;)Z"
                :flags Opcodes/ACC_PUBLIC
                :kind :object-equals
                :owner (:internal-name class-spec)}
               {:name "hashCode"
                :descriptor "()I"
                :flags Opcodes/ACC_PUBLIC
                :kind :object-hash-code
                :owner (:internal-name class-spec)}]
              (map (fn [fn-node]
                     {:name (:emitted-name fn-node)
                      :descriptor (repl-fn-method-descriptor)
                      :flags (+ Opcodes/ACC_PUBLIC)
                      :kind :instance-ctor-fn
                      :fn-node fn-node})
                   (:constructors class-spec))
              (keep (fn [fn-node]
                      (when-not (:deferred? fn-node)
                        {:name (:emitted-name fn-node)
                         :descriptor (repl-fn-method-descriptor)
                         :flags Opcodes/ACC_PUBLIC
                         :kind :instance-fn
                         :fn-node fn-node}))
                    (:methods class-spec))
              (map (fn [bridge]
                     {:name (:java-name bridge)
                      :descriptor (:descriptor bridge)
                      :flags Opcodes/ACC_PUBLIC
                      :kind :interface-bridge
                      :owner (:internal-name class-spec)
                      :bridge bridge})
                   (:java-bridge-methods class-spec))))}))

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

      ;; Record the program's own argv before anything else runs, so
      ;; Process.command_line() sees it — the reflective in-process caller
      ;; (nex.eval/run-compiled) sets the same value again redundantly, but
      ;; a jar run directly (`java -jar foo.jar arg1 arg2`, no nex.eval in
      ;; the picture) depends on this call to see its args at all.
      (emit-runtime-call! mv "set-program-args!"
                          [(fn [] (.visitVarInsn mv Opcodes/ALOAD 0))])
      (.visitInsn mv Opcodes/POP)

      (emit-runtime-call! mv "make-repl-state" [])
      (.visitTypeInsn mv Opcodes/CHECKCAST repl-state-internal-name)
      (.visitVarInsn mv Opcodes/ASTORE 1)

      (emit-runtime-call! mv "bootstrap-compiled-state!"
                          [(fn [] (.visitVarInsn mv Opcodes/ALOAD 1))
                           (fn [] (emit-string-constant! mv classes-edn))
                           (fn [] (emit-string-constant! mv imports-edn))])
      (.visitInsn mv Opcodes/POP)

      (emit-runtime-call! mv "state-set-immediate-output!"
                          [(fn [] (.visitVarInsn mv Opcodes/ALOAD 1))
                           (fn []
                             (.visitInsn mv Opcodes/ICONST_1)
                             (.visitMethodInsn mv Opcodes/INVOKESTATIC
                                               "java/lang/Boolean" "valueOf"
                                               "(Z)Ljava/lang/Boolean;" false))])
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
  [^ClassWriter cw {:keys [name descriptor flags fields composition-fields runtime-type-fields owner super-name]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (.visitMethodInsn mv Opcodes/INVOKESPECIAL super-name "<init>" "()V" false)
    ;; Initialize __outer__ = this (self-reference for dynamic dispatch)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (.visitFieldInsn mv Opcodes/PUTFIELD owner "__outer__" "Ljava/lang/Object;")
    (doseq [{:keys [name jvm-type]} composition-fields]
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (.visitTypeInsn mv Opcodes/NEW (second jvm-type))
      (.visitInsn mv Opcodes/DUP)
      (.visitMethodInsn mv Opcodes/INVOKESPECIAL (second jvm-type) "<init>" "()V" false)
      (.visitFieldInsn mv
                       Opcodes/PUTFIELD
                       owner
                       name
                       (desc/jvm-type->descriptor jvm-type))
      ;; Set parent.__outer__ = this (back-pointer for dynamic dispatch)
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (.visitFieldInsn mv Opcodes/GETFIELD owner name (desc/jvm-type->descriptor jvm-type))
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (.visitFieldInsn mv Opcodes/PUTFIELD (second jvm-type) "__outer__" "Ljava/lang/Object;"))
    (doseq [{:keys [name jvm-type nex-type]} (concat runtime-type-fields fields)]
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (cond
        (= (ir/object-jvm-type "java/util/ArrayList") jvm-type)
        (do
          (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
          (.visitInsn mv Opcodes/DUP)
          (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "()V" false))

        (= (ir/object-jvm-type "java/util/HashMap") jvm-type)
        (do
          (.visitTypeInsn mv Opcodes/NEW hashmap-internal-name)
          (.visitInsn mv Opcodes/DUP)
          (.visitMethodInsn mv Opcodes/INVOKESPECIAL hashmap-internal-name "<init>" "()V" false))

        (= (ir/object-jvm-type "java/util/LinkedHashSet") jvm-type)
        (do
          (.visitTypeInsn mv Opcodes/NEW linkedhashset-internal-name)
          (.visitInsn mv Opcodes/DUP)
          (.visitMethodInsn mv Opcodes/INVOKESPECIAL linkedhashset-internal-name "<init>" "()V" false))

        (and (= (ir/object-jvm-type "java/lang/String") jvm-type)
             (= "String" nex-type))
        (.visitLdcInsn mv "")

        :else
        (emit-const! mv {:value (class-default-value jvm-type) :jvm-type jvm-type}))
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

(defn- emit-unbox-or-cast-to-collection!
  "Like emit-unbox-or-cast!, but for a HashMap/LinkedHashSet target type,
   converts a portable (tagged) value to the compiled backend's native shape
   before the cast — needed wherever a value crosses from a backend-agnostic
   helper (most commonly a builtin free function like json_parse, whose
   static Nex type is Any) into a declared Map/Set-typed local/field/param;
   nothing upstream of that boundary could have known to convert it already.

   NOT the same as unconditionally patching emit-unbox-or-cast! itself: that
   function also runs on every ordinary re-read of an *already*-Map/Set-typed
   value (a plain local/field load, a method-call target) — re-converting
   those replaces the live HashMap/LinkedHashSet reference with a fresh copy
   on every read, silently breaking a subsequent `.put`/`.add` (it mutates the
   throwaway copy, never the value actually stored in the local/field/global).
   This wrapper is only reached from emit-stack-coerce!'s from/to-type-differ
   branch, which by construction never fires when the value is already the
   declared type (an equal-type coercion short-circuits before it)."
  [^MethodVisitor mv jvm-type]
  (if (and (ir/object-jvm-type? jvm-type)
           (#{hashmap-internal-name linkedhashset-internal-name} (second jvm-type)))
    (do
      (emit-runtime-invoke-1! mv "portable-value->compiled")
      (.visitTypeInsn mv Opcodes/CHECKCAST (second jvm-type)))
    (emit-unbox-or-cast! mv jvm-type)))

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

    ;; Narrowing a 64-bit Nex Integer down to a 32-bit int — used at Java
    ;; collection boundaries (ArrayList.get(int), etc.) that take int indices.
    (and (= :long from-jvm-type) (= :int to-jvm-type))
    (.visitInsn mv Opcodes/L2I)

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

    ;; Reached only when from/to differ (the equal-type clause above already
    ;; excludes a value re-read at its own already-known type), so this is
    ;; always a genuine type-changing coercion — the boundary
    ;; emit-unbox-or-cast-to-collection! guards against a portable-vs-native
    ;; Map/Set mismatch for.
    (and (ir/object-jvm-type? from-jvm-type)
         (ir/object-jvm-type? to-jvm-type))
    (emit-unbox-or-cast-to-collection! mv to-jvm-type)

    :else
    (throw (ex-info "Unsupported JVM stack coercion"
                    {:from-jvm-type from-jvm-type
                     :to-jvm-type to-jvm-type}))))

(declare emit-expr!)

(defn- emit-as-int!
  "Emit `expr` and narrow its result to a 32-bit int, inserting L2I when it is a
   64-bit Nex Integer (:long). Used for the JVM int positions: collection indices,
   shift amounts, and the 32-bit bitwise island (the interpreter masks bitwise ops
   to int32, so the compiler matches by computing them in int and widening back)."
  [^MethodVisitor mv expr state-slot]
  (emit-stack-coerce! mv (emit-expr! mv expr state-slot) :int))

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

(defn- emit-checked-long-binary!
  "Emit a checked 64-bit add/sub/mul via java.lang.Math.*Exact so the compiled
   code raises ArithmeticException on overflow exactly as the interpreter (and
   Clojure's checked arithmetic) does, instead of silently wrapping with
   LADD/LSUB/LMUL. Returns true when it emitted the op, nil otherwise — callers
   fall back to the raw opcode for non-long types and for div/mod/neg.
   (Integer division by zero is still surfaced by LDIV/LREM; the Long.MIN_VALUE/-1
   wraparound is left as-is to match the interpreter, which also wraps there.)"
  [^MethodVisitor mv operator jvm-type]
  (when (= :long jvm-type)
    (when-let [method (case operator
                        :add "addExact"
                        :sub "subtractExact"
                        :mul "multiplyExact"
                        nil)]
      (.visitMethodInsn mv Opcodes/INVOKESTATIC "java/lang/Math" method "(JJ)J" false)
      true)))

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
    ;; NaN handling (spec §B.3): DCMPG pushes +1 on NaN so `<`/`<=` fall to
    ;; false; DCMPL pushes -1 so `>`/`>=` do — the same split javac emits.
    (.visitInsn mv (cond
                     (= :long jvm-type) Opcodes/LCMP
                     (#{:lt :lte} operator) Opcodes/DCMPG
                     :else Opcodes/DCMPL))
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
(declare emit-binary!)
(declare emit-compare!)
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

(defn- runtime-helper-emitters
  "Build the ordered emitter list for a direct runtime-helper call from a
   compact spec (see direct-runtime-helper-specs)."
  [^MethodVisitor mv args state-slot spec]
  (vec (mapcat
         (fn [tok]
           (case tok
             :state      [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))]
             :e0         [(fn [] (emit-expr! mv (first args) state-slot))]
             :b0         [(fn [] (emit-boxed-expr! mv (first args) state-slot))]
             :b1         [(fn [] (emit-boxed-expr! mv (second args) state-slot))]
             :b2         [(fn [] (emit-boxed-expr! mv (nth args 2) state-slot))]
             :args       [(fn [] (emit-boxed-arg-array! mv args state-slot))]
             :args-drop2 [(fn [] (emit-boxed-arg-array! mv (vec (drop 2 args)) state-slot))]
             :bvar       (mapv (fn [arg] (fn [] (emit-boxed-expr! mv arg state-slot))) args)))
         spec)))

(def ^:private direct-runtime-helper-specs
  "helper key -> [runtime-fn-name arg-emitter-spec]. Spec tokens (read by
   runtime-helper-emitters): :state loads the repl state; :e<n>/:b<n> emit the
   nth arg raw/boxed; :args / :args-drop2 pass all args / args after the first
   two as a boxed array; :bvar splices each arg boxed."
  {"builtin-method:Cursor:start"             ["builtin-cursor-start" [:state :e0]]
   "builtin-method:Cursor:cursor"            ["builtin-cursor-cursor" [:state :e0]]
   "builtin-method:Cursor:item"              ["builtin-cursor-item" [:state :e0]]
   "builtin-method:Cursor:next"              ["builtin-cursor-next" [:state :e0]]
   "builtin-method:Cursor:at_end"            ["builtin-cursor-at-end" [:state :e0]]
   "builtin-method:Min_Heap:insert"          ["min-heap-insert-method" [:state :b0 :b1]]
   "builtin-method:Min_Heap:extract_min"     ["min-heap-extract-min-method" [:state :b0]]
   "builtin-method:Min_Heap:try_extract_min" ["min-heap-try-extract-min-method" [:state :b0]]
   "builtin-method:Min_Heap:peek"            ["min-heap-peek-method" [:state :b0]]
   "builtin-method:Min_Heap:try_peek"        ["min-heap-try-peek-method" [:state :b0]]
   "builtin-method:Min_Heap:size"            ["min-heap-size-method" [:state :b0]]
   "builtin-method:Min_Heap:is_empty"        ["min-heap-is-empty-method" [:state :b0]]
   "print"                                   ["builtin-print!" [:state :args]]
   "println"                                 ["builtin-println!" [:state :args]]
   "type_of"                                 ["builtin-type-of" [:state :b0]]
   "type_is"                                 ["builtin-type-is" [:state :b0 :b1]]
   "sleep"                                   ["builtin-sleep!" [:b0]]
   "hint_spin"                               ["builtin-hint-spin!" []]
   "exit"                                    ["builtin-exit!" [:b0]]
   "http_get"                                ["builtin-http-get" [:state :bvar]]
   "http_post"                               ["builtin-http-post" [:state :bvar]]
   "json_parse"                              ["builtin-json-parse" [:state :b0]]
   "json_stringify"                          ["builtin-json-stringify" [:state :b0]]
   "http_server_create"                      ["builtin-http-server-create" [:b0]]
   "http_server_get"                         ["builtin-http-server-get!" [:b0 :b1 :b2]]
   "http_server_post"                        ["builtin-http-server-post!" [:b0 :b1 :b2]]
   "http_server_put"                         ["builtin-http-server-put!" [:b0 :b1 :b2]]
   "http_server_delete"                      ["builtin-http-server-delete!" [:b0 :b1 :b2]]
   "http_server_start"                       ["builtin-http-server-start!" [:state :b0]]
   "http_server_stop"                        ["builtin-http-server-stop!" [:b0]]
   "http_server_is_running"                  ["builtin-http-server-is-running" [:b0]]
   "java-call-static"                        ["java-call-static" [:state :b0 :b1 :args-drop2]]
   "java-get-static-field"                   ["java-get-static-field" [:state :b0 :b1]]
   "validate-object-state"                   ["validate-object-state" [:state :b0 :b1]]
   "op:string-concat"                        ["string-concat" [:state :args]]
   "op:div-int"                              ["div-int" [:b0 :b1]]
   "op:div-long"                             ["div-long" [:b0 :b1]]
   "op:mod-int"                              ["mod-int" [:b0 :b1]]
   "op:mod-long"                             ["mod-long" [:b0 :b1]]
   "op:pow-int"                              ["pow-int" [:b0 :b1]]
   "op:pow-long"                             ["pow-long" [:b0 :b1]]
   "op:pow-double"                           ["pow-double" [:b0 :b1]]
   "spawn-function-object"                   ["spawn-function-object" [:state :b0]]
   "create-channel"                          ["create-channel" [:bvar]]
   "create-array"                            ["create-array" []]
   "create-array-filled"                     ["create-array-filled" [:bvar]]
   "create-min-heap-empty"                   ["create-min-heap-empty" []]
   "create-min-heap-from-comparator"         ["create-min-heap-from-comparator" [:bvar]]
   "create-atomic-integer"                   ["create-atomic-integer" [:b0]]
   "create-atomic-integer64"                 ["create-atomic-integer64" [:b0]]
   "create-atomic-boolean"                   ["create-atomic-boolean" [:b0]]
   "create-atomic-reference"                 ["create-atomic-reference" [:b0]]
   "op:await-all"                            ["task-await-all" [:b0]]
   "op:await-any"                            ["task-await-any" [:b0]]
   "select-deadline"                         ["select-deadline" [:b0]]
   "deadline-expired?"                       ["deadline-expired?" [:b0]]
   "select-sleep-step"                       ["select-sleep-step!" []]
   "datetime_make"                           ["builtin-datetime-make-from-array" [:args]]})

(defn- emit-direct-runtime-helper-call!
  [^MethodVisitor mv expr state-slot]
  (let [helper (:helper expr)
        args (:args expr)
        emit-return (fn [jvm-type]
                      (if (= :void jvm-type)
                        (do (.visitInsn mv Opcodes/POP) :void)
                        (do (emit-unbox-or-cast! mv jvm-type)
                            jvm-type)))]
    (if-let [[runtime-fn spec] (get direct-runtime-helper-specs helper)]
      (do
        (emit-runtime-call! mv runtime-fn (runtime-helper-emitters mv args state-slot spec))
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
    (emit-as-int! mv left-expr state-slot)
    (.visitInsn mv Opcodes/ICONST_1)
    (emit-as-int! mv right-expr state-slot)
    (.visitInsn mv Opcodes/ISHL)
    (.visitInsn mv Opcodes/IAND)
    (.visitJumpInsn mv Opcodes/IFNE true-label)
    (.visitInsn mv Opcodes/ICONST_0)
    (.visitJumpInsn mv Opcodes/GOTO end-label)
    (.visitLabel mv true-label)
    (.visitInsn mv Opcodes/ICONST_1)
    (.visitLabel mv end-label)))

(defn- emit-bit-set-like!
  "Leaves a 32-bit int on the stack; the caller widens to the Nex Integer (:long)."
  [^MethodVisitor mv operator left-expr right-expr state-slot]
  (emit-as-int! mv left-expr state-slot)
  (.visitInsn mv Opcodes/ICONST_1)
  (emit-as-int! mv right-expr state-slot)
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

(defn- emit-boxed-expr-for-storage!
  "Like emit-boxed-expr!, but also runs the boxed value through the compiled
   backend's portable-value->compiled conversion before it is stored as an
   ArrayList element or a HashMap/LinkedHashSet key/value — i.e. everywhere a
   value is written into a compiled-native collection outside the
   emit-stack-coerce!/emit-unbox-or-cast-to-collection! path (array/map/set
   literals, and the Array/Map add/add_at/put/set methods).

   Needed because a value can reach this boundary with a static Nex type of
   Any (e.g. an element read out of an Array[Any], or the result of a
   backend-agnostic helper like json_parse) while still being a portable
   NexMap/NexSet at runtime; nothing upstream had a declared Map/Set type to
   convert against. portable-value->compiled is a no-op for every other kind
   of value, so calling it here unconditionally is safe."
  [^MethodVisitor mv expr state-slot]
  (emit-boxed-expr! mv expr state-slot)
  (emit-runtime-invoke-1! mv "portable-value->compiled"))

(defn- emit-array-literal!
  [^MethodVisitor mv expr state-slot]
  (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
  (.visitInsn mv Opcodes/DUP)
  (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "()V" false)
  (doseq [element (:elements expr)]
    (.visitInsn mv Opcodes/DUP)
    (emit-boxed-expr-for-storage! mv element state-slot)
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
    (emit-boxed-expr-for-storage! mv key state-slot)
    (emit-boxed-expr-for-storage! mv value state-slot)
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
    (emit-boxed-expr-for-storage! mv element state-slot)
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
  [^MethodVisitor mv {:keys [value binding target-type target-runtime temp-slot]} state-slot]
  (emit-runtime-var! mv "convert-value")
  (.visitVarInsn mv Opcodes/ALOAD state-slot)
  (let [value-type (emit-expr! mv value state-slot)]
    (when (contains? ir/primitive-jvm-types value-type)
      (emit-box! mv value-type)))
  (if (ir/ir-node? target-runtime)
    (do
      (let [target-type-jvm (emit-expr! mv target-runtime state-slot)]
        (when (contains? ir/primitive-jvm-types target-type-jvm)
          (emit-box! mv target-type-jvm))))
    (.visitLdcInsn mv ^String (if (map? target-type) (:base-type target-type) target-type)))
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

(declare emit-collection-method!)

(defn- emit-array-method-get!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
  (emit-as-int! mv (first args) state-slot)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "get" "(I)Ljava/lang/Object;" false)
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-array-method-add!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
  (emit-boxed-expr-for-storage! mv (first args) state-slot)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "add" "(Ljava/lang/Object;)Z" false)
  (.visitInsn mv Opcodes/POP)
  :void)

(defn- emit-array-method-add-at!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
  (emit-as-int! mv (first args) state-slot)
  (emit-boxed-expr-for-storage! mv (second args) state-slot)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "add" "(ILjava/lang/Object;)V" false)
  :void)

(defn- emit-array-method-put!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
  (emit-as-int! mv (first args) state-slot)
  (emit-boxed-expr-for-storage! mv (second args) state-slot)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "set" "(ILjava/lang/Object;)Ljava/lang/Object;" false)
  (.visitInsn mv Opcodes/POP)
  :void)

(defn- emit-array-method-length!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "size" "()I" false)
  ;; size() is a 32-bit int; widen to the Nex Integer (:long) result.
  (emit-stack-coerce! mv :int jvm-type)
  jvm-type)

(defn- emit-array-method-is-empty!
  [^MethodVisitor mv {:keys [target]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "isEmpty" "()Z" false)
  :boolean)

(defn- emit-array-method-contains!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-runtime-call! mv "array-contains"
                      [(fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
  :boolean)

(defn- emit-array-method-index-of!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "array-index-of"
                      [(fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Number")
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Number" "intValue" "()I" false)
  (emit-stack-coerce! mv :int jvm-type)
  jvm-type)

(defn- emit-array-method-remove!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
  (emit-as-int! mv (first args) state-slot)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "remove" "(I)Ljava/lang/Object;" false)
  (.visitInsn mv Opcodes/POP)
  :void)

(defn- emit-array-method-reverse!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
  (.visitInsn mv Opcodes/DUP)
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "reversed" "()Ljava/util/List;" false)
  (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "(Ljava/util/Collection;)V" false)
  jvm-type)

(defn- emit-array-method-slice!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "array-slice"
                      [(fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))
                       (fn [] (emit-boxed-expr! mv (second args) state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-array-method-take!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "array-take"
                      [(fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-array-method-drop!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "array-drop"
                      [(fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-array-method-take-last!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "array-take-last"
                      [(fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-array-method-drop-last!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "array-drop-last"
                      [(fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-array-method-concat!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
  (.visitInsn mv Opcodes/DUP)
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST arraylist-internal-name)
  (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "(Ljava/util/Collection;)V" false)
  (.visitInsn mv Opcodes/DUP)
  (emit-expr! mv (first args) state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/util/Collection")
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL arraylist-internal-name "addAll" "(Ljava/util/Collection;)Z" false)
  (.visitInsn mv Opcodes/POP)
  jvm-type)


(defn- emit-array-method-sort!
  [^MethodVisitor mv {:keys [target jvm-type] :as expr} state-slot]
  (let [arg-emitters (cond-> [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                              (fn [] (emit-expr! mv target state-slot))]
                       (first (:args expr))
                       (conj (fn [] (emit-boxed-expr! mv (first (:args expr)) state-slot))))]
    (emit-runtime-call! mv "array-sort" arg-emitters)
    (emit-unbox-or-cast! mv jvm-type)
    jvm-type))

(defn- emit-array-method-to-string!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-runtime-call! mv "array-to-string"
                      [(fn [] (emit-expr! mv target state-slot))])
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/String")
  jvm-type)

(defn- emit-array-method-equals!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-runtime-call! mv "deep-equals"
                      [(fn [] (emit-boxed-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
  :boolean)

(defn- emit-array-method-clone!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-runtime-call! mv "clone-value"
                      [(fn [] (emit-boxed-expr! mv target state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-array-method-cursor!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-runtime-call! mv "collection-cursor"
                      [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                       (fn [] (.visitLdcInsn mv "Array"))
                       (fn [] (emit-boxed-expr! mv target state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- assert-direct-methods-in-sync!
  "Fails fast at namespace load if `dispatch-methods` — the method names a
   \"direct\" JVM bytecode-emission dispatch table implements — drifts from
   the canonical method set for `builtin-type` in nex.types.builtins, the
   authoritative source of what the type actually supports. Array/Map/Set/
   Task/Channel have no generic runtime-dispatch fallback (unlike scalar and
   cursor types), so any drift here is a guaranteed crash the first time a
   user calls the missing method on the compiled backend, not just a stray
   inconsistency — this is exactly the shape of bug that motivated the
   check (push/at/size/first/last used to exist here without a matching
   nex.types.builtins entry, and were unreachable dead aliases as a result)."
  [table-name builtin-type dispatch-methods]
  (let [canonical (set (keys (get bi/builtin-type-methods builtin-type)))
        emitted (set dispatch-methods)]
    (assert (= canonical emitted)
            (str table-name " is out of sync with nex.types.builtins " builtin-type
                 (when-let [extra (seq (set/difference emitted canonical))]
                   (str " — entries with no canonical method: " (sort extra)))
                 (when-let [missing (seq (set/difference canonical emitted))]
                   (str " — canonical methods with no direct emitter: " (sort missing)))))))

(def ^:private emit-array-method-dispatch
  "Array method name -> `(fn [mv expr state-slot] -> jvm-type)`: the primary
   dispatch table for `emit-array-method!`."
  {"get"       emit-array-method-get!
   "add"       emit-array-method-add!
   "add_at"    emit-array-method-add-at!
   "put"       emit-array-method-put!
   "set"       (fn [mv expr state-slot] (emit-collection-method! mv (assoc expr :method "put") state-slot))
   "length"    emit-array-method-length!
   "is_empty"  emit-array-method-is-empty!
   "contains"  emit-array-method-contains!
   "index_of"  emit-array-method-index-of!
   "remove"    emit-array-method-remove!
   "reverse"   emit-array-method-reverse!
   "slice"     emit-array-method-slice!
   "take"      emit-array-method-take!
   "drop"      emit-array-method-drop!
   "take_last" emit-array-method-take-last!
   "drop_last" emit-array-method-drop-last!
   "concat"    emit-array-method-concat!
   "sort"      emit-array-method-sort!
   "to_string" emit-array-method-to-string!
   "equals"    emit-array-method-equals!
   "clone"     emit-array-method-clone!
   "cursor"    emit-array-method-cursor!})

(assert-direct-methods-in-sync!
 "emit-array-method-dispatch" :Array (keys emit-array-method-dispatch))

(defn- emit-array-method!
  [^MethodVisitor mv expr state-slot]
  (if-let [handler (get emit-array-method-dispatch (:method expr))]
    (handler mv expr state-slot)
    (throw (ex-info "Unsupported collection method emission"
                    {:expr expr}))))


(defn- emit-map-method-get!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "map-get"
                      [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                       (fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-map-method-try-get!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "map-try-get"
                      [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                       (fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))
                       (fn [] (emit-boxed-expr! mv (second args) state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-map-method-put!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
  (emit-boxed-expr-for-storage! mv (first args) state-slot)
  (emit-boxed-expr-for-storage! mv (second args) state-slot)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "put" "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" false)
  (.visitInsn mv Opcodes/POP)
  :void)

(defn- emit-map-method-size!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "size" "()I" false)
  (emit-stack-coerce! mv :int jvm-type)
  jvm-type)

(defn- emit-map-method-is-empty!
  [^MethodVisitor mv {:keys [target]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "isEmpty" "()Z" false)
  :boolean)

(defn- emit-map-method-contains-key!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-runtime-call! mv "map-contains-key"
                      [(fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
  :boolean)

(defn- emit-map-method-keys!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
  (.visitInsn mv Opcodes/DUP)
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "keySet" "()Ljava/util/Set;" false)
  (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "(Ljava/util/Collection;)V" false)
  jvm-type)

(defn- emit-map-method-values!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (.visitTypeInsn mv Opcodes/NEW arraylist-internal-name)
  (.visitInsn mv Opcodes/DUP)
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "values" "()Ljava/util/Collection;" false)
  (.visitMethodInsn mv Opcodes/INVOKESPECIAL arraylist-internal-name "<init>" "(Ljava/util/Collection;)V" false)
  jvm-type)

(defn- emit-map-method-remove!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST hashmap-internal-name)
  (emit-boxed-expr! mv (first args) state-slot)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL hashmap-internal-name "remove" "(Ljava/lang/Object;)Ljava/lang/Object;" false)
  (.visitInsn mv Opcodes/POP)
  :void)

(defn- emit-map-method-to-string!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-runtime-call! mv "map-to-string"
                      [(fn [] (emit-expr! mv target state-slot))])
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/String")
  jvm-type)

(defn- emit-map-method-equals!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-runtime-call! mv "deep-equals"
                      [(fn [] (emit-boxed-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
  :boolean)

(defn- emit-map-method-clone!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-runtime-call! mv "clone-value"
                      [(fn [] (emit-boxed-expr! mv target state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-map-method-cursor!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-runtime-call! mv "collection-cursor"
                      [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                       (fn [] (.visitLdcInsn mv "Map"))
                       (fn [] (emit-boxed-expr! mv target state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(def ^:private emit-map-method-dispatch
  "Map method name -> `(fn [mv expr state-slot] -> jvm-type)`. \"set\" is a
   pure alias for \"put\", so it's an inline lambda rather than a named
   function of its own — same convention as `emit-array-method-dispatch`."
  {"get"          emit-map-method-get!
   "try_get"      emit-map-method-try-get!
   "put"          emit-map-method-put!
   "set"          (fn [mv expr state-slot] (emit-collection-method! mv (assoc expr :method "put") state-slot))
   "size"         emit-map-method-size!
   "is_empty"     emit-map-method-is-empty!
   "contains_key" emit-map-method-contains-key!
   "keys"         emit-map-method-keys!
   "values"       emit-map-method-values!
   "remove"       emit-map-method-remove!
   "to_string"    emit-map-method-to-string!
   "equals"       emit-map-method-equals!
   "clone"        emit-map-method-clone!
   "cursor"       emit-map-method-cursor!})

(assert-direct-methods-in-sync!
 "emit-map-method-dispatch" :Map (keys emit-map-method-dispatch))

(defn- emit-map-method!
  [^MethodVisitor mv expr state-slot]
  (if-let [handler (get emit-map-method-dispatch (:method expr))]
    (handler mv expr state-slot)
    (throw (ex-info "Unsupported collection method emission"
                    {:expr expr}))))


(defn- emit-set-method-contains!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-runtime-call! mv "set-contains"
                      [(fn [] (emit-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
  :boolean)

;; union/difference/intersection/symmetric_difference are the same shape —
;; a two-arg runtime helper call, then unbox — differing only in which
;; helper they call.
(defn- emit-set-binary-op!
  [runtime-helper-name]
  (fn [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
    (emit-runtime-call! mv runtime-helper-name
                        [(fn [] (emit-expr! mv target state-slot))
                         (fn [] (emit-expr! mv (first args) state-slot))])
    (emit-unbox-or-cast! mv jvm-type)
    jvm-type))

(defn- emit-set-method-size!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST linkedhashset-internal-name)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL linkedhashset-internal-name "size" "()I" false)
  (emit-stack-coerce! mv :int jvm-type)
  jvm-type)

(defn- emit-set-method-is-empty!
  [^MethodVisitor mv {:keys [target]} state-slot]
  (emit-expr! mv target state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST linkedhashset-internal-name)
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL linkedhashset-internal-name "isEmpty" "()Z" false)
  :boolean)

(defn- emit-set-method-to-array!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-runtime-call! mv "set-to-array"
                      [(fn [] (emit-expr! mv target state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-set-method-to-string!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-runtime-call! mv "set-to-string"
                      [(fn [] (emit-expr! mv target state-slot))])
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/String")
  jvm-type)

(defn- emit-set-method-equals!
  [^MethodVisitor mv {:keys [target args]} state-slot]
  (emit-runtime-call! mv "deep-equals"
                      [(fn [] (emit-boxed-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
  :boolean)

(defn- emit-set-method-clone!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-runtime-call! mv "clone-value"
                      [(fn [] (emit-boxed-expr! mv target state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(defn- emit-set-method-cursor!
  [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
  (emit-runtime-call! mv "collection-cursor"
                      [(fn [] (.visitVarInsn mv Opcodes/ALOAD state-slot))
                       (fn [] (.visitLdcInsn mv "Set"))
                       (fn [] (emit-boxed-expr! mv target state-slot))])
  (emit-unbox-or-cast! mv jvm-type)
  jvm-type)

(def ^:private emit-set-method-dispatch
  "Set method name -> `(fn [mv expr state-slot] -> jvm-type)`."
  {"contains"             emit-set-method-contains!
   "union"                (emit-set-binary-op! "set-union")
   "difference"           (emit-set-binary-op! "set-difference")
   "intersection"         (emit-set-binary-op! "set-intersection")
   "symmetric_difference" (emit-set-binary-op! "set-symmetric-difference")
   "size"                 emit-set-method-size!
   "is_empty"             emit-set-method-is-empty!
   "to_array"             emit-set-method-to-array!
   "to_string"            emit-set-method-to-string!
   "equals"               emit-set-method-equals!
   "clone"                emit-set-method-clone!
   "cursor"               emit-set-method-cursor!})

(assert-direct-methods-in-sync!
 "emit-set-method-dispatch" :Set (keys emit-set-method-dispatch))

(defn- emit-set-method!
  [^MethodVisitor mv expr state-slot]
  (if-let [handler (get emit-set-method-dispatch (:method expr))]
    (handler mv expr state-slot)
    (throw (ex-info "Unsupported collection method emission"
                    {:expr expr}))))


(defn- emit-collection-method!
  [^MethodVisitor mv expr state-slot]
  (case (:collection-kind expr)
    :array (emit-array-method! mv expr state-slot)
    :map   (emit-map-method! mv expr state-slot)
    :set   (emit-set-method! mv expr state-slot)
    (throw (ex-info "Unsupported collection method emission"
                    {:expr expr}))))

(defn- emit-concurrency-return!
  [^MethodVisitor mv jvm-type]
  (if (= :void jvm-type)
    (do (.visitInsn mv Opcodes/POP) :void)
    (do (emit-unbox-or-cast! mv jvm-type)
        jvm-type)))

;; Most Task/Channel methods take the receiver only (no user args) and
;; forward straight to a same-named runtime helper — differing only in
;; which helper.
(defn- emit-concurrency-target-only-op!
  [runtime-helper-name]
  (fn [^MethodVisitor mv {:keys [target jvm-type]} state-slot]
    (emit-runtime-call! mv runtime-helper-name
                        [(fn [] (emit-boxed-expr! mv target state-slot))])
    (emit-concurrency-return! mv jvm-type)))

(defn- emit-concurrency-task-await!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "task-await-method"
                      (cond-> [(fn [] (emit-boxed-expr! mv target state-slot))]
                        (seq args) (conj (fn [] (emit-boxed-expr! mv (first args) state-slot)))))
  (emit-concurrency-return! mv jvm-type))

(defn- emit-concurrency-channel-send!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "channel-send-method"
                      (cond-> [(fn [] (emit-boxed-expr! mv target state-slot))
                               (fn [] (emit-boxed-expr! mv (first args) state-slot))]
                        (= 2 (count args)) (conj (fn [] (emit-boxed-expr! mv (second args) state-slot)))))
  (emit-concurrency-return! mv jvm-type))

(defn- emit-concurrency-channel-try-send!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "channel-try-send-method"
                      [(fn [] (emit-boxed-expr! mv target state-slot))
                       (fn [] (emit-boxed-expr! mv (first args) state-slot))])
  (emit-concurrency-return! mv jvm-type))

(defn- emit-concurrency-channel-receive!
  [^MethodVisitor mv {:keys [target args jvm-type]} state-slot]
  (emit-runtime-call! mv "channel-receive-method"
                      (cond-> [(fn [] (emit-boxed-expr! mv target state-slot))]
                        (seq args) (conj (fn [] (emit-boxed-expr! mv (first args) state-slot)))))
  (emit-concurrency-return! mv jvm-type))

(def ^:private emit-concurrency-method-dispatch
  "[concurrency-kind method] -> `(fn [mv expr state-slot] -> jvm-type)`.
   Most entries take the receiver only and forward to a same-named runtime
   helper, so `emit-concurrency-target-only-op!` builds those handlers from
   just the helper name; the four with their own argument shapes (task
   await, channel send/try_send/receive) get their own function."
  {[:task "await"]          emit-concurrency-task-await!
   [:task "cancel"]         (emit-concurrency-target-only-op! "task-cancel-method")
   [:task "is_done"]        (emit-concurrency-target-only-op! "task-is-done-method")
   [:task "is_cancelled"]   (emit-concurrency-target-only-op! "task-is-cancelled-method")
   [:channel "send"]        emit-concurrency-channel-send!
   [:channel "try_send"]    emit-concurrency-channel-try-send!
   [:channel "receive"]     emit-concurrency-channel-receive!
   [:channel "try_receive"] (emit-concurrency-target-only-op! "channel-try-receive-method")
   [:channel "close"]       (emit-concurrency-target-only-op! "channel-close-method")
   [:channel "is_closed"]   (emit-concurrency-target-only-op! "channel-is-closed-method")
   [:channel "capacity"]    (emit-concurrency-target-only-op! "channel-capacity-method")
   [:channel "size"]        (emit-concurrency-target-only-op! "channel-size-method")})

(assert-direct-methods-in-sync!
 "emit-concurrency-method-dispatch [:task ...]" :Task
 (->> emit-concurrency-method-dispatch keys (filter #(= :task (first %))) (map second)))
(assert-direct-methods-in-sync!
 "emit-concurrency-method-dispatch [:channel ...]" :Channel
 (->> emit-concurrency-method-dispatch keys (filter #(= :channel (first %))) (map second)))

(defn- emit-concurrency-method!
  [^MethodVisitor mv expr state-slot]
  (if-let [handler (get emit-concurrency-method-dispatch [(:concurrency-kind expr) (:method expr)])]
    (handler mv expr state-slot)
    (throw (ex-info "Unsupported concurrency method emission"
                    {:expr expr}))))


(defn- emit-expr-const!
  [^MethodVisitor mv expr _state-slot]
  (emit-const! mv expr)
  (:jvm-type expr))

(defn- emit-expr-local!
  [^MethodVisitor mv expr _state-slot]
  (mark-local-debug-before! mv (:slot expr))
  (.visitVarInsn mv (local-load-op (:jvm-type expr)) (:slot expr))
  (mark-local-debug-after! mv (:slot expr))
  (:jvm-type expr))

(defn- emit-expr-this!
  [^MethodVisitor mv expr _state-slot]
  (.visitVarInsn mv Opcodes/ALOAD 0)
  (:jvm-type expr))

(defn- emit-expr-new!
  [^MethodVisitor mv expr state-slot]
  (.visitTypeInsn mv Opcodes/NEW (:class expr))
  (.visitInsn mv Opcodes/DUP)
  (.visitMethodInsn mv Opcodes/INVOKESPECIAL (:class expr) "<init>" "()V" false)
  ;; Hand the object the state that made it (see the __state__ field): the
  ;; emitted equals/hashCode need it and Java gives them no way to obtain it.
  (.visitInsn mv Opcodes/DUP)
  (.visitVarInsn mv Opcodes/ALOAD state-slot)
  (.visitFieldInsn mv Opcodes/PUTFIELD (:class expr) "__state__"
                   (str "L" repl-state-internal-name ";"))
  (:jvm-type expr))

(defn- emit-expr-top-get!
  [^MethodVisitor mv expr state-slot]
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

(defn- emit-expr-field-get!
  [^MethodVisitor mv expr state-slot]
  (emit-expr! mv (:target expr) state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST (:owner expr))
  (.visitFieldInsn mv
                   Opcodes/GETFIELD
                   (:owner expr)
                   (:field expr)
                   (desc/jvm-type->descriptor (:jvm-type expr)))
  (:jvm-type expr))

(defn- emit-expr-static-field-get!
  [^MethodVisitor mv expr _state-slot]
  (.visitFieldInsn mv
                   Opcodes/GETSTATIC
                   (:owner expr)
                   (:field expr)
                   (desc/jvm-type->descriptor (:jvm-type expr)))
  (:jvm-type expr))

(defn- emit-expr-call-repl-fn!
  [^MethodVisitor mv expr state-slot]
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

(defn- emit-expr-call-function!
  [^MethodVisitor mv expr state-slot]
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

(defn- emit-expr-call-virtual!
  [^MethodVisitor mv expr state-slot]
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

(defn- emit-expr-call-super-java!
  [^MethodVisitor mv expr state-slot]
  (emit-expr! mv (:target expr) state-slot)
  (.visitMethodInsn mv Opcodes/INVOKESPECIAL (:owner expr) (:method expr) (:descriptor expr) false)
  (let [java-kind (get primitive-class->jvm-type (:java-return-class expr))]
    (cond
      (= java-kind :void)
      ;; The internal calling convention represents Void as a null Object; a
      ;; bare call statement pops it, an expression context sees the same
      ;; nil any other Void-returning Nex call would.
      (.visitInsn mv Opcodes/ACONST_NULL)

      java-kind
      (let [{:keys [box-owner box-desc]} (get primitive-box-info java-kind)]
        (.visitMethodInsn mv Opcodes/INVOKESTATIC box-owner "valueOf" box-desc false))

      ;; A reference return is already Object-shaped on the stack — no
      ;; conversion needed, matching Nex's own boxed-everything internal
      ;; representation.
      :else nil))
  (:jvm-type expr))

(defn- emit-expr-call-runtime!
  [^MethodVisitor mv expr state-slot]
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
              (:jvm-type expr))))))

(defn- emit-expr-unary!
  [^MethodVisitor mv expr state-slot]
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
        ;; 32-bit bitwise island (see emit-binary!): narrow the operand to int,
        ;; complement, then widen the result back to Nex Integer (:long).
        (emit-stack-coerce! mv operand-type :int)
        (.visitInsn mv Opcodes/ICONST_M1)
        (.visitInsn mv Opcodes/IXOR)
        (emit-stack-coerce! mv :int (:jvm-type expr))
        (:jvm-type expr))

      (do
        (when-not (= operand-type (:jvm-type expr))
          (throw (ex-info "Unary operand lowered to unexpected JVM type"
                          {:expr expr
                           :operand-jvm-type operand-type
                           :expr-jvm-type (:jvm-type expr)})))
        ;; Checked negation matches the interpreter: -Long.MIN_VALUE overflows
        ;; and must raise rather than wrap back to Long.MIN_VALUE (LNEG).
        (if (and (= :neg (:operator expr)) (= :long (:jvm-type expr)))
          (.visitMethodInsn mv Opcodes/INVOKESTATIC "java/lang/Math" "negateExact" "(J)J" false)
          (.visitInsn mv (or (unary-opcode (:operator expr) (:jvm-type expr))
                             (throw (ex-info "Unsupported unary opcode emission"
                                             {:operator (:operator expr)
                                              :jvm-type (:jvm-type expr)})))))
        (:jvm-type expr)))))

(defn- emit-expr-if!
  [^MethodVisitor mv expr state-slot]
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
      result-type)))

(def ^:private emit-expr-dispatch
  "IR node `:op` -> `(fn [mv expr state-slot] -> jvm-type)`: the primary
   dispatch table for `emit-expr!`. `emit-binary!`/`emit-compare!` are only
   forward-declared this early in the file (their own defns come later) —
   wrapped for the same reason `infer-type-dispatch`'s `:call` entry in
   lower.clj is; every other entry here is already defined above this
   point, so it's referenced bare."
  {:const              emit-expr-const!
   :array-literal      emit-array-literal!
   :map-literal        emit-map-literal!
   :set-literal        emit-set-literal!
   :local              emit-expr-local!
   :this               emit-expr-this!
   :new                emit-expr-new!
   :top-get            emit-expr-top-get!
   :field-get          emit-expr-field-get!
   :static-field-get   emit-expr-static-field-get!
   :call-repl-fn       emit-expr-call-repl-fn!
   :call-function      emit-expr-call-function!
   :call-virtual       emit-expr-call-virtual!
   :call-super-java    emit-expr-call-super-java!
   :call-runtime       emit-expr-call-runtime!
   :collection-method  emit-collection-method!
   :concurrency-method emit-concurrency-method!
   :convert            emit-convert!
   :unary              emit-expr-unary!
   :binary             (fn [mv expr state-slot] (emit-binary! mv expr state-slot))
   :compare            (fn [mv expr state-slot] (emit-compare! mv expr state-slot))
   :if                 emit-expr-if!})

(defn- emit-expr!
  [^MethodVisitor mv expr state-slot]
  (if-let [handler (get emit-expr-dispatch (:op expr))]
    (handler mv expr state-slot)
    (throw (ex-info "Unsupported IR expression emission"
                    {:expr expr :op (:op expr)}))))


(defn- emit-binary!
  [^MethodVisitor mv expr state-slot]
  (cond
    (#{:and :or} (:operator expr))
    (do
      (emit-boolean-short-circuit! mv (:operator expr) (:left expr) (:right expr) state-slot)
      (:jvm-type expr))

    ;; Bitwise ops form a 32-bit island: the interpreter masks them to int32
    ;; (types/runtime.clj), so the compiler narrows the Nex Integer (:long)
    ;; operands to int, computes in int, then widens the result back to :long.
    (= :bit-rotl (:operator expr))
    (do
      (emit-as-int! mv (:left expr) state-slot)
      (emit-as-int! mv (:right expr) state-slot)
      (.visitMethodInsn mv
                        Opcodes/INVOKESTATIC
                        "java/lang/Integer"
                        "rotateLeft"
                        "(II)I"
                        false)
      (emit-stack-coerce! mv :int (:jvm-type expr))
      (:jvm-type expr))

    (= :bit-rotr (:operator expr))
    (do
      (emit-as-int! mv (:left expr) state-slot)
      (emit-as-int! mv (:right expr) state-slot)
      (.visitMethodInsn mv
                        Opcodes/INVOKESTATIC
                        "java/lang/Integer"
                        "rotateRight"
                        "(II)I"
                        false)
      (emit-stack-coerce! mv :int (:jvm-type expr))
      (:jvm-type expr))

    (= :bit-test (:operator expr))
    (do
      (emit-bit-test! mv (:left expr) (:right expr) state-slot)
      (:jvm-type expr))

    (#{:bit-set :bit-unset} (:operator expr))
    (do
      (emit-bit-set-like! mv (:operator expr) (:left expr) (:right expr) state-slot)
      (emit-stack-coerce! mv :int (:jvm-type expr))
      (:jvm-type expr))

    (#{:bit-shl :bit-shr :bit-ushr :bit-and :bit-or :bit-xor} (:operator expr))
    (do
      (emit-as-int! mv (:left expr) state-slot)
      (emit-as-int! mv (:right expr) state-slot)
      (.visitInsn mv (binary-opcode (:operator expr) :int))
      (emit-stack-coerce! mv :int (:jvm-type expr))
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
        (or (emit-checked-long-binary! mv (:operator expr) operand-type)
            (.visitInsn mv (binary-opcode (:operator expr) operand-type))))
      (:jvm-type expr))))

(defn- emit-compare!
  [^MethodVisitor mv expr state-slot]
  (let [operator (:operator expr)]
    (if (#{:ident-eq :ident-neq} operator)
      (do
        (emit-runtime-var! mv "identity-equals")
        (emit-boxed-expr! mv (:left expr) state-slot)
        (emit-boxed-expr! mv (:right expr) state-slot)
        (.visitMethodInsn mv
                          Opcodes/INVOKEVIRTUAL
                          var-internal-name
                          "invoke"
                          "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                          false)
        (emit-unbox-or-cast! mv :boolean)
        (when (= :ident-neq operator)
          (.visitInsn mv Opcodes/ICONST_1)
          (.visitInsn mv Opcodes/IXOR)))
      (let [declared-left-type (:jvm-type (:left expr))
            declared-right-type (:jvm-type (:right expr))
            compare-type (or (numeric-promotion-jvm-type declared-left-type declared-right-type)
                             (when (and (#{:eq :neq} operator)
                                        (or (ir/object-jvm-type? declared-left-type)
                                            (ir/object-jvm-type? declared-right-type)))
                               (ir/object-jvm-type "java/lang/Object"))
                             (when (and (ir/object-jvm-type? declared-left-type)
                                        (ir/object-jvm-type? declared-right-type))
                               (ir/object-jvm-type "java/lang/Object"))
                             (when (= declared-left-type declared-right-type) declared-left-type))]
        (when-not compare-type
          (throw (ex-info "Compare operands lowered to incompatible JVM types"
                          {:expr expr
                           :left-jvm-type declared-left-type
                           :right-jvm-type declared-right-type})))
        (if (and (ir/object-jvm-type? compare-type)
                 (not (#{:eq :neq} operator)))
          (do
            (emit-runtime-var! mv "runtime-compare-values")
            (.visitVarInsn mv Opcodes/ALOAD state-slot)
            (emit-boxed-expr! mv (:left expr) state-slot)
            (emit-boxed-expr! mv (:right expr) state-slot)
            (.visitMethodInsn mv
                              Opcodes/INVOKEVIRTUAL
                              var-internal-name
                              "invoke"
                              "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                              false)
            (emit-unbox-or-cast! mv :int)
            (.visitInsn mv Opcodes/ICONST_0)
            (emit-numeric-compare! mv operator :int))
          (if (ir/object-jvm-type? compare-type)
            (do
              ;; `=`/`/=` on objects: honour a class's `equals` override, else
              ;; structural. value-equals needs the repl state to dispatch the
              ;; (reflective) user method, like runtime-compare-values above.
              (emit-runtime-var! mv "value-equals")
              (.visitVarInsn mv Opcodes/ALOAD state-slot)
              (emit-boxed-expr! mv (:left expr) state-slot)
              (emit-boxed-expr! mv (:right expr) state-slot)
              (.visitMethodInsn mv
                                Opcodes/INVOKEVIRTUAL
                                var-internal-name
                                "invoke"
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                                false)
              (emit-unbox-or-cast! mv :boolean)
              (when (= :neq operator)
                (.visitInsn mv Opcodes/ICONST_1)
                (.visitInsn mv Opcodes/IXOR)))
            (do
              (let [left-type (emit-expr! mv (:left expr) state-slot)]
                (emit-stack-coerce! mv left-type compare-type))
              (let [right-type (emit-expr! mv (:right expr) state-slot)]
                (emit-stack-coerce! mv right-type compare-type))
              (cond
                (#{:int :boolean :char} compare-type)
                (emit-numeric-compare! mv operator compare-type)

                (#{:long :double} compare-type)
                (emit-long-or-double-compare! mv operator compare-type)

                (ir/object-jvm-type? compare-type)
                (emit-object-compare! mv operator)

                :else
                (throw (ex-info "Unsupported compare emission type"
                                {:expr expr :jvm-type compare-type}))))))))
    (:jvm-type expr)))

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
  [^MethodVisitor mv {:keys [kind label expr] :as stmt} state-slot]
  (let [ok-label (Label.)
        expr-type (emit-expr! mv expr state-slot)
        kind-label (case kind
                     :require "Precondition"
                     :ensure "Postcondition"
                     :invariant "Loop invariant"
                     :variant "Loop variant"
                     :class-invariant "Class invariant"
                     :assert "Assertion"
                     (name kind))
        ;; Only a bare `assert expr` reaches here unlabelled; the runtime falls
        ;; back to the line, passed as a string so no boxing is needed.
        line (when-let [line (:dbg/line stmt)] (str line))]
    (when-not (= :boolean expr-type)
      (throw (ex-info "Assert emission requires boolean expression"
                      {:stmt {:kind kind :label label}
                       :jvm-type expr-type})))
    (.visitJumpInsn mv Opcodes/IFNE ok-label)
    (emit-runtime-call! mv "make-contract-violation"
                        [(fn [] (.visitLdcInsn mv ^String kind-label))
                         (fn [] (if label
                                  (.visitLdcInsn mv ^String label)
                                  (.visitInsn mv Opcodes/ACONST_NULL)))
                         (fn [] (if line
                                  (.visitLdcInsn mv ^String line)
                                  (.visitInsn mv Opcodes/ACONST_NULL)))])
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
    (.visitJumpInsn mv Opcodes/GOTO end-label)

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

(defn- emit-stmt-set-local!
  [^MethodVisitor mv stmt state-slot]
  (let [expr-jvm-type (emit-expr! mv (:expr stmt) state-slot)]
    (emit-stack-coerce! mv expr-jvm-type (:jvm-type stmt))
    (mark-local-debug-before! mv (:slot stmt))
    (.visitVarInsn mv (local-store-op (:jvm-type stmt)) (:slot stmt))
    (mark-local-debug-after! mv (:slot stmt))
    expr-jvm-type))

(defn- emit-stmt-top-set!
  [^MethodVisitor mv stmt state-slot]
  (emit-load-values-map! mv state-slot)
  (.visitLdcInsn mv ^String (:name stmt))
  (let [expr-jvm-type (emit-expr! mv (:expr stmt) state-slot)]
    (cond
      (contains? ir/primitive-jvm-types expr-jvm-type)
      (emit-box! mv expr-jvm-type)

      ;; An object-typed value stored into a *Map/Set-declared* global may
      ;; need the same portable-vs-native conversion emit-stack-coerce!
      ;; applies at every other write site (:set-local, :field-set) — a
      ;; top-level `let root: Map[...] := json.parse(...)` reaches only this
      ;; path, and previously stored the raw (possibly portable) value with
      ;; no conversion at all, so every later read (:top-get, itself an
      ;; ordinary CHECKCAST with no conversion of its own) failed. Gated on
      ;; the *declared* type actually being Map/Set (not just "object, and
      ;; differs from expr's own type" the way emit-stack-coerce! decides it
      ;; for a local): a top-level Integer/Real global stores its value as a
      ;; boxed Object regardless of the Nex-level declared type (unlike
      ;; :set-local's real primitive JVM local slot), so unconditionally
      ;; coercing expr-jvm-type=Object down to to-jvm-type=:long here — as
      ;; emit-stack-coerce! would for a *local* — left a raw unboxed long on
      ;; the stack where HashMap.put expects a boxed Object (hit by a `with
      ;; "java"` global sourced from a raw Java call whose static Nex type
      ;; is Any/Object, e.g. `let n: Integer :=
      ;; System.getProperty(...).length()`).
      (and (ir/object-jvm-type? (:jvm-type stmt))
           (#{hashmap-internal-name linkedhashset-internal-name} (second (:jvm-type stmt))))
      (emit-unbox-or-cast-to-collection! mv (:jvm-type stmt))

      :else nil))
  (.visitMethodInsn mv
                    Opcodes/INVOKEVIRTUAL
                    hashmap-internal-name
                    "put"
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                    false)
  (.visitInsn mv Opcodes/POP))

(defn- emit-stmt-field-set!
  [^MethodVisitor mv stmt state-slot]
  (emit-expr! mv (:target stmt) state-slot)
  (.visitTypeInsn mv Opcodes/CHECKCAST (:owner stmt))
  (emit-stack-coerce! mv (emit-expr! mv (:expr stmt) state-slot) (:jvm-type stmt))
  (.visitFieldInsn mv
                   Opcodes/PUTFIELD
                   (:owner stmt)
                   (:field stmt)
                   (desc/jvm-type->descriptor (:jvm-type stmt))))

(defn- emit-stmt-block!
  [^MethodVisitor mv stmt state-slot]
  (doseq [nested (:body stmt)]
    (emit-stmt! mv nested state-slot)))

(defn- emit-stmt-if!
  [^MethodVisitor mv stmt state-slot]
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
    (.visitLabel mv end-label)))

(defn- emit-stmt-loop!
  [^MethodVisitor mv stmt state-slot]
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
    (.visitLabel mv end-label)))

(def ^:private emit-stmt-dispatch
  "IR statement `:op` -> `(fn [mv stmt state-slot] -> ...)`: the primary
   dispatch table for `emit-stmt!`, consulted after it has already emitted
   the line number — that applies unconditionally, regardless of which
   branch runs, so it stays in `emit-stmt!` itself rather than in each
   handler."
  {:return       (fn [mv stmt state-slot] (emit-return! mv (:expr stmt) state-slot))
   :pop          (fn [mv stmt state-slot] (emit-pop! mv (emit-expr! mv (:expr stmt) state-slot)))
   :set-local    emit-stmt-set-local!
   :top-set      emit-stmt-top-set!
   :field-set    emit-stmt-field-set!
   :call-runtime (fn [mv stmt state-slot] (emit-pop! mv (emit-expr! mv stmt state-slot)))
   :raise        (fn [mv stmt state-slot] (emit-raise! mv (:expr stmt) state-slot))
   :retry        (fn [mv _stmt _state-slot] (emit-retry! mv))
   :assert       (fn [mv stmt state-slot] (emit-assert! mv stmt state-slot))
   :try          (fn [mv stmt state-slot] (emit-try! mv stmt state-slot))
   :block        emit-stmt-block!
   :if-stmt      emit-stmt-if!
   :loop         emit-stmt-loop!})

(defn- emit-stmt!
  [^MethodVisitor mv stmt state-slot]
  (emit-line-number! mv stmt)
  (if-let [handler (get emit-stmt-dispatch (:op stmt))]
    (handler mv stmt state-slot)
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
  [^ClassWriter cw {:keys [name descriptor flags owner constants classes-edn imports-edn]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)
        ;; A scalar constant lowers to a `:const` (LDC) that never touches the
        ;; state slot. An object- or collection-valued constant dispatches a
        ;; constructor/call and needs a session state. `<clinit>` is static and
        ;; receives none, so we bootstrap a throwaway one (as the launcher's main
        ;; does) into local slot 0. Because <clinit> runs exactly once per class
        ;; load, the resulting PUTSTATIC interns the value: every read of the
        ;; constant yields the same instance.
        needs-state? (some (fn [{:keys [value]}] (not= :const (:op value))) constants)]
    (when (and needs-state? (or (nil? classes-edn) (nil? imports-edn)))
      (throw (ex-info "Object-valued class constant needs class metadata to bootstrap its <clinit> state"
                      {:owner owner
                       :constants (mapv :name constants)})))
    (.visitCode mv)
    (when needs-state?
      (emit-runtime-call! mv "make-repl-state" [])
      (.visitTypeInsn mv Opcodes/CHECKCAST repl-state-internal-name)
      (.visitVarInsn mv Opcodes/ASTORE 0)
      (emit-runtime-call! mv "bootstrap-compiled-state!"
                          [(fn [] (.visitVarInsn mv Opcodes/ALOAD 0))
                           (fn [] (emit-string-constant! mv classes-edn))
                           (fn [] (emit-string-constant! mv imports-edn))])
      (.visitInsn mv Opcodes/POP))
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

(defn- emit-object-state-arg!
  "Load `this.__state__` — the argument the runtime helpers need and that Java's
   equals/hashCode contract does not provide."
  [^MethodVisitor mv owner]
  (.visitVarInsn mv Opcodes/ALOAD 0)
  (.visitFieldInsn mv Opcodes/GETFIELD owner "__state__"
                   (str "L" repl-state-internal-name ";")))

(defn- emit-object-equals!
  "public boolean equals(Object other) { return Runtime.object_equals(__state__, this, other); }"
  [^ClassWriter cw {:keys [name descriptor flags owner]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (emit-runtime-call! mv "object-equals"
                        [(fn [] (emit-object-state-arg! mv owner))
                         (fn [] (.visitVarInsn mv Opcodes/ALOAD 0))
                         (fn [] (.visitVarInsn mv Opcodes/ALOAD 1))])
    (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Boolean")
    (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Boolean" "booleanValue" "()Z" false)
    (.visitInsn mv Opcodes/IRETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn- emit-object-hash-code!
  "public int hashCode() { return Runtime.object_hash_code(__state__, this); }"
  [^ClassWriter cw {:keys [name descriptor flags owner]}]
  (let [^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)]
    (.visitCode mv)
    (emit-runtime-call! mv "object-hash-code"
                        [(fn [] (emit-object-state-arg! mv owner))
                         (fn [] (.visitVarInsn mv Opcodes/ALOAD 0))])
    (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/Integer")
    (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/Integer" "intValue" "()I" false)
    (.visitInsn mv Opcodes/IRETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(def ^:private primitive-class->jvm-type
  {Integer/TYPE :int, Long/TYPE :long, Double/TYPE :double, Boolean/TYPE :boolean
   Character/TYPE :char, Float/TYPE :float, Byte/TYPE :byte, Short/TYPE :short
   Void/TYPE :void})

(def ^:private primitive-box-info
  "Per-primitive boxing/unboxing/load/return info for interface-bridge codegen.
   Broader than descriptor.clj's boxing-owner/unboxing-method (int/long/double/
   boolean/char only, the five Nex's own type system needs): a reflected Java
   interface method's descriptor can use any of the eight JVM primitives, so
   this table covers all of them."
  {:int     {:box-owner "java/lang/Integer"   :box-desc "(I)Ljava/lang/Integer;"   :unbox-name "intValue"     :unbox-desc "()I" :load Opcodes/ILOAD :return Opcodes/IRETURN}
   :long    {:box-owner "java/lang/Long"      :box-desc "(J)Ljava/lang/Long;"      :unbox-name "longValue"    :unbox-desc "()J" :load Opcodes/LLOAD :return Opcodes/LRETURN}
   :double  {:box-owner "java/lang/Double"    :box-desc "(D)Ljava/lang/Double;"    :unbox-name "doubleValue"  :unbox-desc "()D" :load Opcodes/DLOAD :return Opcodes/DRETURN}
   :float   {:box-owner "java/lang/Float"     :box-desc "(F)Ljava/lang/Float;"     :unbox-name "floatValue"   :unbox-desc "()F" :load Opcodes/FLOAD :return Opcodes/FRETURN}
   :boolean {:box-owner "java/lang/Boolean"   :box-desc "(Z)Ljava/lang/Boolean;"   :unbox-name "booleanValue" :unbox-desc "()Z" :load Opcodes/ILOAD :return Opcodes/IRETURN}
   :char    {:box-owner "java/lang/Character" :box-desc "(C)Ljava/lang/Character;" :unbox-name "charValue"    :unbox-desc "()C" :load Opcodes/ILOAD :return Opcodes/IRETURN}
   :byte    {:box-owner "java/lang/Byte"      :box-desc "(B)Ljava/lang/Byte;"      :unbox-name "byteValue"    :unbox-desc "()B" :load Opcodes/ILOAD :return Opcodes/IRETURN}
   :short   {:box-owner "java/lang/Short"     :box-desc "(S)Ljava/lang/Short;"     :unbox-name "shortValue"   :unbox-desc "()S" :load Opcodes/ILOAD :return Opcodes/IRETURN}})

(defn- reference-type-internal-name
  [^Class klass]
  (.getInternalName (Type/getType klass)))

(defn- param-slot-offsets
  "Local-variable slot for each of an instance method's real params, starting
   at slot 1 (slot 0 is `this`); long/double each occupy two slots."
  [param-classes]
  (loop [cs (seq param-classes) slot 1 acc []]
    (if (empty? cs)
      acc
      (let [width (if (#{Long/TYPE Double/TYPE} (first cs)) 2 1)]
        (recur (next cs) (+ slot width) (conj acc slot))))))

(defn- numeric-conversion-kind
  "byte/short share int's JVM computational category (no separate load/return
   opcodes exist for them), so a conversion opcode lookup treats them as int."
  [k]
  (case k (:byte :short) :int k))

(defn- numeric-widen-narrow-opcode
  "The JVM primitive-conversion opcode from FROM to TO (both one of :int
   :long :float :double, after normalizing byte/short to :int), or nil when
   no conversion is needed — same kind, or a non-numeric primitive
   (:boolean/:char) where the only sensible case is identity."
  [from to]
  (let [from (numeric-conversion-kind from)
        to (numeric-conversion-kind to)]
    (when (and (not= from to)
               (#{:int :long :float :double} from)
               (#{:int :long :float :double} to))
      (get {[:int :long] Opcodes/I2L, [:int :float] Opcodes/I2F, [:int :double] Opcodes/I2D
            [:long :int] Opcodes/L2I, [:long :float] Opcodes/L2F, [:long :double] Opcodes/L2D
            [:float :int] Opcodes/F2I, [:float :long] Opcodes/F2L, [:float :double] Opcodes/F2D
            [:double :int] Opcodes/D2I, [:double :long] Opcodes/D2L, [:double :float] Opcodes/D2F}
           [from to]))))

(defn- emit-interface-bridge-method!
  "Bridges a Java interface method's real descriptor to the compiled class's
   own internal Nex method, which always uses the uniform
   (NexReplState, Object[])->Object descriptor (repl-fn-method-descriptor) —
   so a real Java caller (a JVM collection sort, a Thread, a Swing listener
   list) reaches the Nex method exactly as if it had been called from Nex.

   The internal method needs a NexReplState, which a real Java caller has no
   way to supply. Solved the same way emit-object-equals!/emit-object-
   hash-code! already solve it for equals/hashCode — another pair of methods
   Java calls with no state in scope — by reading `this.__state__`, the
   object's own field set at construction (emit-object-state-arg!), rather
   than threading a param through."
  [^ClassWriter cw {:keys [name descriptor flags owner bridge]}]
  (let [{:keys [target-method-name param-classes return-class param-nex-kinds return-nex-kind]} bridge
        ^MethodVisitor mv (.visitMethod cw flags name descriptor nil nil)
        slots (param-slot-offsets param-classes)]
    (.visitCode mv)
    ;; `this`, kept on the stack under `state` and `args` as the receiver for
    ;; the INVOKEVIRTUAL below.
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (emit-object-state-arg! mv owner)
    (.visitIntInsn mv Opcodes/BIPUSH (count param-classes))
    (.visitTypeInsn mv Opcodes/ANEWARRAY "java/lang/Object")
    (doseq [[idx ^Class param-class slot nex-kind] (map vector (range) param-classes slots param-nex-kinds)]
      (.visitInsn mv Opcodes/DUP)
      (.visitIntInsn mv Opcodes/BIPUSH idx)
      (if-let [java-kind (get primitive-class->jvm-type param-class)]
        ;; A primitive Java param: load it, convert to the Nex-side primitive
        ;; kind when it differs (a genuinely `int`-typed Java param landing in
        ;; a Nex `Integer` (long) parameter, say), then box with whichever
        ;; wrapper the already-emitted Nex method body actually expects to
        ;; read back out of the args array — falling back to Java's own
        ;; wrapper only when the Nex side isn't itself primitive-shaped (a
        ;; loosely typed `Any` parameter).
        (let [box-kind (if (contains? primitive-box-info nex-kind) nex-kind java-kind)
              {:keys [load]} (get primitive-box-info java-kind)
              {:keys [box-owner box-desc]} (get primitive-box-info box-kind)]
          (.visitVarInsn mv load slot)
          (when-let [conv (numeric-widen-narrow-opcode java-kind box-kind)]
            (.visitInsn mv conv))
          (.visitMethodInsn mv Opcodes/INVOKESTATIC box-owner "valueOf" box-desc false))
        (.visitVarInsn mv Opcodes/ALOAD slot))
      (.visitInsn mv Opcodes/AASTORE))
    (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL owner target-method-name (repl-fn-method-descriptor) false)
    (let [java-kind (get primitive-class->jvm-type return-class)]
      (cond
        (= java-kind :void)
        (do (.visitInsn mv Opcodes/POP)
            (.visitInsn mv Opcodes/RETURN))

        ;; A primitive Java return: unbox using whichever wrapper the Nex
        ;; method body actually returned (Nex Integer is a boxed Long
        ;; regardless of the Java interface's own primitive return type),
        ;; then convert to the Java-expected primitive kind if they differ.
        java-kind
        (let [unbox-kind (if (contains? primitive-box-info return-nex-kind) return-nex-kind java-kind)
              {:keys [box-owner unbox-name unbox-desc]} (get primitive-box-info unbox-kind)
              {:keys [return]} (get primitive-box-info java-kind)]
          (.visitTypeInsn mv Opcodes/CHECKCAST box-owner)
          (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL box-owner unbox-name unbox-desc false)
          (when-let [conv (numeric-widen-narrow-opcode unbox-kind java-kind)]
            (.visitInsn mv conv))
          (.visitInsn mv return))

        (.isArray ^Class return-class)
        (.visitInsn mv Opcodes/ARETURN)

        :else
        (do
          (.visitTypeInsn mv Opcodes/CHECKCAST (reference-type-internal-name return-class))
          (.visitInsn mv Opcodes/ARETURN))))
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

(defn emit-method!
  [^ClassWriter cw method-spec]
  (case (:kind method-spec)
    :object-equals (emit-object-equals! cw method-spec)
    :object-hash-code (emit-object-hash-code! cw method-spec)
    :default-constructor (emit-default-constructor! cw method-spec)
    :launcher-main (emit-launcher-main! cw method-spec)
    :user-default-constructor (emit-user-default-constructor! cw method-spec)
    :class-initializer (emit-class-initializer! cw method-spec)
    :eval-from-ir (emit-eval-method! cw method-spec)
    :repl-fn (emit-repl-fn-method! cw method-spec)
    :instance-ctor-fn (emit-instance-fn-method! cw method-spec)
    :abstract-instance-fn (emit-abstract-instance-fn-method! cw method-spec)
    :instance-fn (emit-instance-fn-method! cw method-spec)
    :interface-bridge (emit-interface-bridge-method! cw method-spec)
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
  ([class-spec] (compile-user-class->bytes class-spec {}))
  ([class-spec bootstrap-edn]
   (emit-class (user-class-spec class-spec bootstrap-edn))))

(defn compile-launcher->bytes
  [launcher-spec]
  (emit-class (launcher-class-spec launcher-spec)))
