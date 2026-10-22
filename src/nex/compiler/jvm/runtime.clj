(ns nex.compiler.jvm.runtime
  "Small runtime support for the future JVM bytecode compiler."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [nex.types.builtins :as bi]
            [nex.types.concurrency :as conc]
            [nex.types.bootstrap :as bootstrap]
            [nex.types.datetime :as dt]
            [nex.types.http :as http]
            [nex.types.json :as json-types]
            [nex.types.regex :as regex-types]
            [nex.types.runtime :as rt]
            [nex.types.value :as value]
            [nex.types.typeinfo :as typeinfo]
            [nex.types.reflect :as reflect])
  (:import [clojure.lang DynamicClassLoader]
           [java.lang.reflect Field Method InvocationTargetException]
           [java.util HashMap]
           [java.util.concurrent CompletableFuture TimeUnit TimeoutException ExecutionException CancellationException]
           [java.util.concurrent.atomic AtomicBoolean AtomicInteger AtomicLong AtomicReference]))

(declare lowered-instance-method-name)
(declare reflected-field)
(declare runtime-type-name)
(declare runtime-compatible-with?)
(declare invoke-user-method)
(declare runtime-compare-values)

;; ---------------------------------------------------------------------------
;; Interpreter objects.
;;
;; Compiled code never creates one: every Nex class, closures included, is a
;; generated JVM class. They reach compiled code only from the REPL, which
;; hands over values from a cell it evaluated on the tree-walker (e.g. under
;; `:debug on`). Recognising one is a shape test (an object map, which the
;; interpreter's NexObject record also is) and needs no interpreter. Running Nex code on one does: that goes through the
;; adapter the REPL installs (nex.compiler.jvm.interp-bridge), so this
;; namespace never loads nex.interpreter.
;; ---------------------------------------------------------------------------

(defn- interp-object?
  [v]
  (and (map? v) (contains? v :class-name) (contains? v :fields)))

(defonce ^:private interpreter-adapter (atom nil))

(defn install-interpreter-adapter!
  "Install the operations that run Nex code on an interpreter object:
   :call-method (fn [state target method-name args has-parens?]),
   :concat-string (fn [state value]) — its string-concatenation form, and
   :equals-override (fn [state a b]) — its user `equals` against B, or nil."
  [adapter]
  (reset! interpreter-adapter adapter)
  nil)

(defn- interpreter-op
  [k target]
  (or (get @interpreter-adapter k)
      (throw (ex-info (str "Cannot run Nex code on an interpreted "
                           (or (:class-name target) "value")
                           " object: no interpreter is attached to this program")
                      {:op k :class-name (:class-name target)}))))

(defn- invoke-interpreter-object-method
  ([state target method-name args]
   (invoke-interpreter-object-method state target method-name args true))
  ([state target method-name args has-parens?]
   ((interpreter-op :call-method target) state target method-name (vec args) has-parens?)))


(def ^:private builtin-wrapper-receivers
  "Receiver type of a `builtin-method-<type>-<method>` helper, by the <type>
   part of its name: [the type's key in bi/builtin-type-methods, a predicate
   true of exactly that type's values]. Types missing here (Any, Cursor,
   Comparable) have no one representation, so their helpers always dispatch
   on the value."
  {"string"    [:String `string?]
   "real"      [:Real `(fn [v#] (instance? Double v#))]
   "integer"   [:Integer `(fn [v#] (instance? Long v#))]
   "integer32" [:Integer32 `rt/nex-int32?]
   "integer16" [:Integer16 `rt/nex-int16?]
   "byte"      [:Byte `rt/nex-byte?]
   "char"      [:Char `char?]
   "boolean"   [:Boolean `boolean?]
   "console"   [:Console `rt/nex-console?]
   "process"   [:Process `rt/nex-process?]
   "program"   [:Program `rt/nex-program?]
   "map_entry" [:Map_Entry `rt/nex-map-entry?]})

(defmacro ^:private def-builtin-method-wrapper
  "Define FN-NAME (`builtin-method-<type>-<method>`), the helper compiled code
   calls for METHOD-NAME on a receiver of static type <type>. The method is
   looked up once, when this namespace loads, and called directly when the
   receiver is a value of that type, rather than found by the value's type and
   the method's name on every call (bi/call-builtin-method), which costs far
   more than most of these methods do. Any other receiver, or a method the
   type's own table lacks (so Any's applies), takes call-builtin-method."
  [fn-name method-name]
  (let [method-part (str/replace method-name "_" "-")
        type-part (let [n (subs (name fn-name) (count "builtin-method-"))]
                    (subs n 0 (- (count n) (count method-part) 1)))
        [type-key pred] (get builtin-wrapper-receivers type-part)
        generic (fn [t args] `(bi/call-builtin-method nil ~t ~t ~method-name ~args))]
    (if-not type-key
      (let [t (gensym "target")
            args (gensym "args")]
        `(defn ~fn-name
           [~t & ~args]
           ~(generic t `(vec ~args))))
      (let [f (gensym "method-fn")
            ok (gensym "receiver?")
            t (gensym "target")
            a (gensym "a")
            b (gensym "b")
            more (gensym "more")
            direct? `(and ~f (~ok ~t))]
        `(let [~f (get-in bi/builtin-type-methods [~type-key ~method-name])
               ~ok ~pred]
           (defn ~fn-name
             ([~t] (if ~direct? (~f ~t) ~(generic t [])))
             ([~t ~a] (if ~direct? (~f ~t ~a) ~(generic t [a])))
             ([~t ~a ~b] (if ~direct? (~f ~t ~a ~b) ~(generic t [a b])))
             ([~t ~a ~b & ~more]
              (if ~direct?
                (apply ~f ~t ~a ~b ~more)
                ~(generic t `(into [~a ~b] ~more))))))))))

(defn- ->java-array-list
  [value]
  (if (instance? clojure.lang.IPersistentVector value)
    (java.util.ArrayList. ^java.util.Collection value)
    value))

(defrecord NexReplState [^clojure.lang.Atom values
                         ^clojure.lang.Atom types
                         ^clojure.lang.Atom functions
                         ^clojure.lang.Atom output
                         ^clojure.lang.Atom classes
                         ^clojure.lang.Atom imports
                         ^clojure.lang.Atom counter
                         ^DynamicClassLoader loader])

(definterface NexInvariant
  ;; Implemented by every compiled class with an invariant, whose synthetic
  ;; invariant method this is (nex.lower/invariant-method-def), so the
  ;; runtime can call it directly rather than by reflection (run-invariant!).
  ;; A fixed interface, unlike the per-loader dispatch interfaces, since this
  ;; namespace's own code calls it.
  (^Object __method_$invariant$arity0 [^nex.compiler.jvm.runtime.NexReplState state
                                       ^"[Ljava.lang.Object;" args]))

(definterface NexCarrier
  ;; Implemented by every compiled user class: its `__container__` (the object
  ;; it is a composition carrier of, one level up, or nil) and `__outer__`
  ;; (the heir's object it lives in), read directly rather than by reflection
  ;; when a path call looks for the object that runs a routine.
  (^Object __container [])
  (^Object __outer []))

(defn make-repl-state
  ([] (make-repl-state nil))
  ([loader]
   (->NexReplState (atom (HashMap.))
                   (atom (HashMap.))
                   (atom (HashMap.))
                   (atom [])
                   (atom (HashMap.))
                   (atom [])
                   (atom 0)
                   loader)))

(defn state-get-value
  [state name]
  (.get ^HashMap @(:values state) name))

(defn state-set-value!
  [state name value]
  (swap! (:values state)
         (fn [^HashMap m]
           (doto m
             (.put name value))))
  value)

(defn state-get-type
  [state name]
  (.get ^HashMap @(:types state) name))

(defn state-set-type!
  [state name nex-type]
  (swap! (:types state)
         (fn [^HashMap m]
           (doto m
             (.put name nex-type))))
  nex-type)

(defn state-get-fn
  [state name]
  (.get ^HashMap @(:functions state) name))

(defn state-set-fn!
  [state name fn-wrapper]
  (swap! (:functions state)
         (fn [^HashMap m]
           (doto m
             (.put name fn-wrapper))))
  fn-wrapper)

(defn repl-fn-arity-error
  "Thrown by a top-level function called with the wrong number of arguments,
   which only happens when the caller was compiled against an earlier
   definition of it (in the REPL; see emit-repl-fn-arity-check!)."
  [name expected ^objects args]
  (let [given (alength args)
        plural (fn [n] (str n (if (= 1 n) " argument" " arguments")))]
    (throw (ex-info (str "`" name "` takes " (plural expected) ", but this call passes " given
                         ". `" name "` was redefined after the code calling it was written;"
                         " define that code again so it calls the new `" name "`.")
                    {:function name :expected expected :given given}))))

(defn register-repl-fn!
  [state name owner-binary-name method-name]
  (state-set-fn! state name {:owner owner-binary-name
                             :method method-name}))

(defn- resolve-owner-class
  [state owner-binary-name]
  (if-let [^DynamicClassLoader loader (:loader state)]
    (.loadClass loader owner-binary-name)
    (Class/forName owner-binary-name)))

(defn- resolve-java-host-class
  [state class-name]
  (let [imported (bi/resolve-imported-java-class {:imports (:imports state)} class-name)]
    (or imported
        (reflect/class-for-name class-name)
        (reflect/class-for-name (str "java.lang." class-name))
        (throw (ex-info (str "Undefined Java class: " class-name)
                        {:class-name class-name})))))

(defn- method-args
  "The [state args] array a compiled routine takes, built without the vector
   `(object-array [state args])` would allocate first."
  ^objects [state args]
  (let [a (object-array 2)]
    (aset a 0 state)
    (aset a 1 args)
    a))

(defn- invoke-reflective!
  [^Method method target args]
  (try
    (.invoke method target args)
    (catch InvocationTargetException e
      (throw (or (.getCause e) e)))))

(defn registered-fn-callable
  "Wrap a registered top-level function as a plain callable, so that code the
   interpreter runs (see nex.compiler.jvm.interp-bridge) can invoke it by
   name. The registry stores either a reflective `Method` or an
   `{:owner :method}` descriptor."
  [state entry]
  (cond
    (instance? Method entry)
    (fn [& args]
      (invoke-reflective! entry nil (method-args state (object-array args))))

    (map? entry)
    (let [{:keys [owner method]} entry]
      (fn [& args]
        (let [^Class cls (resolve-owner-class state owner)
              ^Method m (.getDeclaredMethod
                         cls method
                         (into-array Class [nex.compiler.jvm.runtime.NexReplState
                                            (class (object-array 0))]))]
          (invoke-reflective! m nil (method-args state (object-array args))))))

    :else nil))

(defn function-value-for-name
  "The value a bare reference to a top-level `function`'s own name (no call
   parens) lowers to — passing it where a `Function(...)`-typed value is
   expected, e.g. `filter_items(is_rare_or_legendary)`. Reuses the same
   registry (`state`'s `:functions` map, populated by `emit-register-repl-fn!`
   for every top-level function) and wrapping (`registered-fn-callable`)
   already used to make a compiled top-level function callable by name from
   code running on the interpreter — see
   nex.compiler.jvm.interp-bridge/rebuild-interpreter-ctx. Returns a plain
   Clojure fn; `invoke-function-object` below knows to just call one of those
   directly."
  [state name]
  (when-let [entry (state-get-fn state name)]
    (registered-fn-callable state entry)))

(defn invoke-function-object
  [state target args]
  (when-not target
    (throw (ex-info "Cannot invoke Void as a function"
                    {:target target
                     :args args})))
  (cond
    (interp-object? target)
    (invoke-interpreter-object-method state target (str "call" (count args)) args false)

    ;; A top-level function passed by name (`function-value-for-name` above).
    (ifn? target)
    (apply target args)

    :else
    (let [^Class cls (.getClass target)
          lowered-name (lowered-instance-method-name (str "call" (count args)) (count args))
          ^Method method (.getDeclaredMethod cls
                                             lowered-name
                                             (into-array Class [nex.compiler.jvm.runtime.NexReplState
                                                                (class (object-array 0))]))]
      (invoke-reflective! method target (method-args state (object-array args))))))

(defn- make-task
  [future]
  {:nex-builtin-type :Task
   :future future})

(defn- task-await
  ([task]
   (task-await task nil))
  ([task timeout]
   (try
     (if (nil? timeout)
       (.get ^CompletableFuture (:future task))
       (.get ^CompletableFuture (:future task) (long timeout) TimeUnit/MILLISECONDS))
     (catch TimeoutException _
       nil)
     (catch CancellationException _
       (throw (ex-info "Task cancelled" {:task task})))
     (catch ExecutionException e
       (throw (or (.getCause e) e)))
     (catch InterruptedException e
       (.interrupt (Thread/currentThread))
       (throw e)))))

(defn- task-done?
  [task]
  (.isDone ^CompletableFuture (:future task)))

(defn- task-cancel
  [task]
  (.cancel ^CompletableFuture (:future task) true))

(defn create-channel
  ([] (create-channel 0))
  ([capacity]
   (conc/make-channel (long capacity))))

(defn create-array
  []
  (rt/nex-array))

(defn create-string-from-bytes
  [bytes]
  (rt/string-from-bytes bytes))

(defn create-array-filled
  [size value]
  (when-not (integer? size)
    (throw (ex-info "Array.filled requires an Integer size"
                    {:size size})))
  (when (neg? size)
    (throw (ex-info "Array size must be non-negative"
                    {:size size})))
  (rt/nex-array-from (vec (repeat size value))))

(defn create-min-heap-empty
  []
  {:nex-builtin-type :MinHeap
   :data (atom [])
   :comparator nil})

(defn create-set-from-array
  "Build a compiled Set (LinkedHashSet, matching set literals) from a Nex array."
  [arr]
  (let [s (java.util.LinkedHashSet.)
        n (rt/nex-array-size arr)]
    (dotimes [i n]
      (.add s (rt/nex-array-get arr i)))
    s))

(defn create-min-heap-from-comparator
  [compare]
  {:nex-builtin-type :MinHeap
   :data (atom [])
   :comparator compare})

(defn create-atomic-integer
  ;; 64-bit (Int64), matching Nex Integer; AtomicInteger silently truncated >2^31.
  [initial]
  {:nex-builtin-type :AtomicInteger
   :state (AtomicLong. (long initial))})

(defn create-atomic-integer64
  [initial]
  {:nex-builtin-type :AtomicInteger64
   :state (AtomicLong. (long initial))})

(defn create-atomic-boolean
  [initial]
  {:nex-builtin-type :AtomicBoolean
   :state (AtomicBoolean. (boolean initial))})

(defn create-atomic-reference
  [initial]
  {:nex-builtin-type :AtomicReference
   :state (AtomicReference. initial)})

(def ^:private objects-class (class (object-array 0)))

(defn- java-arg-array
  "ARGS (the Object array compiled code passes, or any sequence) from index
   OFFSET on, each put through CONVERT, as the Object array a reflective call
   takes. Built in one pass: interop calls are made in tight loops."
  ^objects [convert args offset]
  (let [offset (long offset)]
    (if (instance? objects-class args)
      (let [^objects a args
            n (max 0 (- (alength a) offset))
            out (object-array n)]
        (dotimes [i n]
          (aset out i (convert (aget a (+ i offset)))))
        out)
      (to-array (map convert (drop offset args))))))

(defn java-create-object
  [state class-name args]
  ;; Resolve the host class off `state` directly (like java-call-static), rather
  ;; than rebuilding an interpreter context just to read the imports. This also
  ;; resolves fully-qualified and java.lang.* names, not only imported ones.
  (let [^Class klass (resolve-java-host-class state class-name)]
    (reflect/invoke-constructor klass (java-arg-array rt/nex->java args 0))))

(defn java-call-static
  [state class-name method-name args]
  (let [^Class klass (resolve-java-host-class state class-name)]
    (rt/java->nex (reflect/invoke-static klass method-name (java-arg-array rt/nex->java args 0)))))

(defn java-call-method
  [state method-name target args]
  (bi/java-call-method state target method-name args))

(defn java-get-static-field
  [state class-name field-name]
  (let [^Class klass (resolve-java-host-class state class-name)
        ^Field field (or (reflected-field klass field-name)
                         (throw (ex-info (str "Undefined Java static field: " field-name)
                                         {:field field-name
                                          :class (.getName klass)})))]
    (rt/java->nex (.get field nil))))

(defn java-get-field
  [field-name target]
  (let [^Field field (or (reflected-field (.getClass target) field-name)
                         (throw (ex-info (str "Undefined Java field: " field-name)
                                         {:field field-name
                                          :class (.getName (.getClass target))})))]
    (rt/java->nex (.get field target))))

(defn java-set-field!
  [field-name target value]
  (let [^Field field (or (reflected-field (.getClass target) field-name)
                         (throw (ex-info (str "Undefined Java field: " field-name)
                                         {:field field-name
                                          :class (.getName (.getClass target))})))]
    (.set field target value)
    nil))

;; ---------------------------------------------------------------------------
;; Java calls resolved at compile time (see the "Compile-time Java overload
;; resolution" section of nex.lower). java-call-method/-static and
;; java-create-object above resolve the target Method/Constructor at RUNTIME
;; via clojure.lang.Reflector, which performs no real overload resolution --
;; when a name is overloaded, it reliably prefers a wider reference-type
;; (Object) candidate over a more specific primitive/numeric one
;; (java.util.ArrayList.remove(int)/(Object) is the textbook case). The
;; *-resolved variants below are used instead whenever nex.lower already
;; picked the single applicable candidate at compile time: PARAM-CLASSES-
;; JOINED is that candidate's exact parameter-type descriptor (comma-joined
;; binary class names, primitives spelled "int"/"long"/etc.), which pins an
;; unambiguous getMethod/getConstructor lookup here and drives per-argument
;; coercion to that exact type -- fixing both the wrong-overload selection
;; and the separate reflective invoke failure a resolved-but-uncoerced call
;; would still hit (Nex's Integer is uniformly a boxed Long; Method.invoke
;; requires an exact wrapper-class match per parameter, so a boxed Long
;; reflectively invoking an `int` parameter throws IllegalArgumentException
;; even once the right Method has been found).

(defn- resolve-one-param-class
  ^Class [^String name]
  (case name
    "int" Integer/TYPE
    "long" Long/TYPE
    "short" Short/TYPE
    "byte" Byte/TYPE
    "float" Float/TYPE
    "double" Double/TYPE
    "boolean" Boolean/TYPE
    "char" Character/TYPE
    (Class/forName name)))

(defn- resolve-param-classes
  [^String joined]
  (if (or (nil? joined) (zero? (.length joined)))
    []
    (mapv resolve-one-param-class (str/split joined #","))))

(defn- coerce-arg-for-resolved-param*
  "ARG, as Nex represents it natively (a boxed Long for Integer, a boxed
   Double for Real, ...), converted to the exact wrapper/primitive class
   PARAM-CLASS declares. Reference-typed parameters need no conversion --
   ARG is already the right runtime object (a java.lang.String, a compiled
   Nex object instance implementing the target interface directly via
   bytecode `implements`, an ArrayList/LinkedHashMap/LinkedHashSet, ...) and
   reflection widens a reference argument to a wider declared parameter type
   on its own. Narrowing a numeric argument is checked, not truncated --
   raises a clear error rather than silently wrapping, matching how Nex's
   own arithmetic already treats overflow (see emit.clj's checked negation)."
  [^Class param-class arg]
  (cond
    (or (= param-class Integer/TYPE) (= param-class Integer))
    (let [l (long arg)]
      (when (or (> l Integer/MAX_VALUE) (< l Integer/MIN_VALUE))
        (throw (ex-info (str "Value " l " does not fit in a Java int parameter")
                        {:value l :target-type "int"})))
      (int l))

    (or (= param-class Short/TYPE) (= param-class Short))
    (let [l (long arg)]
      (when (or (> l Short/MAX_VALUE) (< l Short/MIN_VALUE))
        (throw (ex-info (str "Value " l " does not fit in a Java short parameter")
                        {:value l :target-type "short"})))
      (short l))

    (or (= param-class Byte/TYPE) (= param-class Byte))
    (let [l (long arg)]
      (if (rt/nex-byte? arg)
        ;; A Nex Byte is unsigned (0..255); Java's byte is the same 8 bits read
        ;; as signed, so 200 crosses over as -56 rather than being rejected.
        (unchecked-byte l)
        (do
          (when (or (> l Byte/MAX_VALUE) (< l Byte/MIN_VALUE))
            (throw (ex-info (str "Value " l " does not fit in a Java byte parameter")
                            {:value l :target-type "byte"})))
          (byte l))))

    (or (= param-class Long/TYPE) (= param-class Long))
    (long arg)

    (or (= param-class Float/TYPE) (= param-class Float))
    (float arg)

    (or (= param-class Double/TYPE) (= param-class Double))
    (double arg)

    :else
    arg))


(defn- coerce-arg-for-resolved-param
  "coerce-arg-for-resolved-param*, after unwrapping an Integer16 / Integer32
   (a wrapper object, not a Number) to the plain long a Java numeric parameter
   takes. A Byte is left alone: its exact-`byte` handling needs to see it."
  [^Class param-class arg]
  (coerce-arg-for-resolved-param* param-class
                                  (if (or (rt/nex-int16? arg) (rt/nex-int32? arg))
                                    (rt/sized->long arg)
                                    arg)))

(defn- class-named
  "The class called NAME (Class/forName's error if there is none), looked
   up once."
  ^Class [^String name]
  (or (reflect/class-for-name name) (Class/forName name)))

(defn- resolved-method
  "OWNER's public method NAME with exactly the parameter types JOINED names
   (see resolve-param-classes), looked up once."
  ^java.lang.reflect.Method [^Class owner ^String name ^String joined]
  (reflect/on-class owner [::resolved-method name joined]
                    #(reflect/unchecked
                      (.getMethod owner name (into-array Class (resolve-param-classes joined))))))

(defn- resolved-constructor
  ^java.lang.reflect.Constructor [^Class owner ^String joined]
  (reflect/on-class owner [::resolved-constructor joined]
                    #(reflect/unchecked
                      (.getConstructor owner (into-array Class (resolve-param-classes joined))))))

(defn- coerce-args-for
  "ARGS coerced to PARAM-CLASSES, as an Object array."
  ^objects [^"[Ljava.lang.Class;" param-classes args]
  (let [out (object-array (alength param-classes))]
    (loop [i 0 xs (seq args)]
      (when (and xs (< i (alength param-classes)))
        (aset out i (coerce-arg-for-resolved-param (aget param-classes i) (first xs)))
        (recur (inc i) (next xs))))
    out))

(defn java-call-method-resolved
  [_state method-name receiver-class-name param-classes-joined target args]
  (let [m (resolved-method (class-named receiver-class-name) method-name param-classes-joined)]
    (rt/java->nex (.invoke m target (coerce-args-for (.getParameterTypes m) args)))))

(defn java-call-static-resolved
  [_state class-name method-name param-classes-joined args]
  (let [m (resolved-method (class-named class-name) method-name param-classes-joined)]
    (rt/java->nex (.invoke m nil (coerce-args-for (.getParameterTypes m) args)))))

(defn java-create-object-resolved
  [_state class-name param-classes-joined args]
  (let [c (resolved-constructor (class-named class-name) param-classes-joined)]
    (.newInstance c (coerce-args-for (.getParameterTypes c) args))))

;; ---------------------------------------------------------------------------
;; Varargs (see the "Varargs" section of nex.lower). clojure.lang.Reflector
;; -- and even a resolved single-Method getMethod/invoke pair, above -- has
;; no way to call a varargs method with loose trailing arguments: Java
;; reflection requires the array to already be built and passed as the
;; final actual argument. FIXED-CLASSES-JOINED/COMPONENT-CLASS-NAME are
;; exactly what nex.lower's resolve-java-call-target already resolved at
;; compile time: the fixed leading parameters (as for a plain resolved
;; call), plus the single component type every trailing Nex argument
;; collects against.

(defn- array-class-of
  "^Class for COMPONENT-CLASS[] -- java.lang.reflect.Array is the only
   portable way to obtain this without a compile-time array-type literal,
   since COMPONENT-CLASS is only known by name at this point."
  ^Class [^Class component-class]
  (.getClass (java.lang.reflect.Array/newInstance component-class 0)))

(defn- build-varargs-array
  [^Class component-class values]
  (let [n (count values)
        arr (java.lang.reflect.Array/newInstance component-class n)]
    (dotimes [i n]
      (java.lang.reflect.Array/set arr i (coerce-arg-for-resolved-param component-class (nth values i))))
    arr))

(defn- resolve-declared-varargs-method
  "The real java.lang.reflect.Method/Constructor this call resolved to:
   FIXED-CLASSES followed by the array type itself (COMPONENT-CLASS[]) --
   getMethod/getConstructor need the *declared* signature, not the
   per-argument component type resolve-java-call-target matched trailing
   arguments against."
  [fixed-classes component-class]
  (conj (vec fixed-classes) (array-class-of component-class)))

(defn- varargs-signature
  "[fixed parameter classes, component class, declared parameter classes]
   for a varargs call, from the names nex.lower resolved."
  [fixed-classes-joined component-class-name]
  (let [fixed (resolve-param-classes fixed-classes-joined)
        component (resolve-one-param-class component-class-name)]
    [fixed component (into-array Class (resolve-declared-varargs-method fixed component))]))

(defn- varargs-args
  "The Object array for a varargs call: the fixed arguments coerced to
   FIXED, then the trailing ones collected into one COMPONENT array."
  ^objects [fixed ^Class component args]
  (let [n (count fixed)]
    (to-array (conj (mapv coerce-arg-for-resolved-param fixed (take n args))
                    (build-varargs-array component (drop n args))))))

(defn java-call-method-resolved-varargs
  [_state method-name receiver-class-name fixed-classes-joined component-class-name target args]
  (let [^Class owner (class-named receiver-class-name)
        [fixed component ^java.lang.reflect.Method m]
        (reflect/on-class owner [::varargs-method method-name fixed-classes-joined component-class-name]
                          #(let [[fixed component declared] (varargs-signature fixed-classes-joined
                                                                                component-class-name)]
                             [fixed component (reflect/unchecked
                                               (.getMethod owner ^String method-name
                                                           ^"[Ljava.lang.Class;" declared))]))]
    (rt/java->nex (.invoke m target (varargs-args fixed component args)))))

(defn java-call-static-resolved-varargs
  [_state class-name method-name fixed-classes-joined component-class-name args]
  (let [^Class owner (class-named class-name)
        [fixed component ^java.lang.reflect.Method m]
        (reflect/on-class owner [::varargs-method method-name fixed-classes-joined component-class-name]
                          #(let [[fixed component declared] (varargs-signature fixed-classes-joined
                                                                                component-class-name)]
                             [fixed component (reflect/unchecked
                                               (.getMethod owner ^String method-name
                                                           ^"[Ljava.lang.Class;" declared))]))]
    (rt/java->nex (.invoke m nil (varargs-args fixed component args)))))

(defn java-create-object-resolved-varargs
  [_state class-name fixed-classes-joined component-class-name args]
  (let [^Class klass (class-named class-name)
        [fixed component ^java.lang.reflect.Constructor c]
        (reflect/on-class klass [::varargs-constructor fixed-classes-joined component-class-name]
                          #(let [[fixed component declared] (varargs-signature fixed-classes-joined
                                                                                component-class-name)]
                             [fixed component (reflect/unchecked
                                               (.getConstructor klass ^"[Ljava.lang.Class;" declared))]))]
    (.newInstance c (varargs-args fixed component args))))

(defn spawn-function-object
  [state fn-obj]
  (make-task
   (CompletableFuture/supplyAsync
    (reify java.util.function.Supplier
      (get [_]
        (invoke-function-object state fn-obj []))))))

(defn task-await-all
  [tasks]
  (rt/nex-array-from (map task-await tasks)))

(defn task-await-any
  [tasks]
  (when (empty? tasks)
    (throw (ex-info "await_any requires at least one task" {})))
  (loop []
    (if-let [ready-task (some #(when (task-done? %) %) tasks)]
      (task-await ready-task)
      (do
        (Thread/sleep 1)
        (recur)))))

(defn task-await-method
  ([task]
   (task-await task))
  ([task timeout]
   (task-await task timeout)))

(defn task-cancel-method
  [task]
  (task-cancel task))

(defn task-is-done-method
  [task]
  (task-done? task))

(defn task-is-cancelled-method
  [task]
  (.isCancelled ^CompletableFuture (:future task)))

(defn channel-send-method
  ([ch value]
   (bi/call-builtin-method nil ch ch "send" [value]))
  ([ch value timeout]
   (bi/call-builtin-method nil ch ch "send" [value timeout])))

(defn channel-try-send-method
  [ch value]
  (bi/call-builtin-method nil ch ch "try_send" [value]))

(defn channel-receive-method
  ([ch]
   (bi/call-builtin-method nil ch ch "receive" []))
  ([ch timeout]
   (bi/call-builtin-method nil ch ch "receive" [timeout])))

(defn channel-try-receive-method
  [ch]
  (bi/call-builtin-method nil ch ch "try_receive" []))

(defn channel-close-method
  [ch]
  (bi/call-builtin-method nil ch ch "close" []))

(defn channel-is-closed-method
  [ch]
  (bi/call-builtin-method nil ch ch "is_closed" []))

(defn channel-capacity-method
  [ch]
  (bi/call-builtin-method nil ch ch "capacity" []))

(defn channel-size-method
  [ch]
  (bi/call-builtin-method nil ch ch "size" []))

(defn- min-heap-compare
  [state heap left right]
  (let [comparator (:comparator heap)]
    (if comparator
      (let [result (if (fn? comparator)
                     (comparator left right)
                     (invoke-function-object state comparator [left right]))]
        (if (integer? result)
          result
          (throw (ex-info "Min_Heap comparator must return Integer"
                          {:left left :right right :result result}))))
      (runtime-compare-values state left right))))

(defn- min-heap-sift-up
  [state heap values idx]
  (loop [items values
         child idx]
    (if (zero? child)
      items
      (let [parent (quot (dec child) 2)
            child-value (nth items child)
            parent-value (nth items parent)]
        (if (neg? (min-heap-compare state heap child-value parent-value))
          (recur (-> items
                     (assoc child parent-value)
                     (assoc parent child-value))
                 parent)
          items)))))

(defn- min-heap-sift-down
  [state heap values idx]
  (let [n (count values)]
    (loop [items values
           parent idx]
      (let [left (+ (* 2 parent) 1)
            right (+ left 1)]
        (if (>= left n)
          items
          (let [smallest-child (if (and (< right n)
                                        (neg? (min-heap-compare state
                                                                heap
                                                                (nth items right)
                                                                (nth items left))))
                                 right
                                 left)
                parent-value (nth items parent)
                child-value (nth items smallest-child)]
            (if (neg? (min-heap-compare state heap child-value parent-value))
              (recur (-> items
                         (assoc parent child-value)
                         (assoc smallest-child parent-value))
                     smallest-child)
              items)))))))

(defn min-heap-insert-method
  [state heap value]
  (swap! (:data heap)
         (fn [items]
           (let [expanded (conj items value)]
             (min-heap-sift-up state heap expanded (dec (count expanded))))))
  nil)

(defn- min-heap-peek*
  [heap]
  (let [items @(:data heap)]
    (when (seq items)
      (first items))))

(defn min-heap-peek-method
  [state heap]
  (or (min-heap-peek* heap)
      (throw (ex-info "Min_Heap is empty" {:heap heap}))))

(defn min-heap-try-peek-method
  [state heap]
  (min-heap-peek* heap))

(defn- min-heap-extract*
  [state heap]
  (let [items @(:data heap)]
    (when (seq items)
      (let [minimum (first items)
            last-value (peek items)
            remaining-count (dec (count items))
            replacement (if (zero? remaining-count)
                          []
                          (min-heap-sift-down state heap (assoc (pop items) 0 last-value) 0))]
        (reset! (:data heap) replacement)
        minimum))))

(defn min-heap-extract-min-method
  [state heap]
  (or (min-heap-extract* state heap)
      (throw (ex-info "Min_Heap is empty" {:heap heap}))))

(defn min-heap-try-extract-min-method
  [state heap]
  (min-heap-extract* state heap))

(defn min-heap-size-method
  [state heap]
  (count @(:data heap)))

(defn min-heap-is-empty-method
  [state heap]
  (empty? @(:data heap)))

(defn builtin-method-atomic_integer-load
  [target]
  (.get ^AtomicLong (:state target)))

(defn builtin-method-atomic_integer-store
  [target value]
  (.set ^AtomicLong (:state target) (long value))
  nil)

(defn builtin-method-atomic_integer-compare-and-set
  [target expected update]
  (.compareAndSet ^AtomicLong (:state target) (long expected) (long update)))

(defn builtin-method-atomic_integer-get-and-add
  [target delta]
  (.getAndAdd ^AtomicLong (:state target) (long delta)))

(defn builtin-method-atomic_integer-add-and-get
  [target delta]
  (.addAndGet ^AtomicLong (:state target) (long delta)))

(defn builtin-method-atomic_integer-increment
  [target]
  (.incrementAndGet ^AtomicLong (:state target)))

(defn builtin-method-atomic_integer-decrement
  [target]
  (.decrementAndGet ^AtomicLong (:state target)))

(defn builtin-method-atomic_integer64-load
  [target]
  (.get ^AtomicLong (:state target)))

(defn builtin-method-atomic_integer64-store
  [target value]
  (.set ^AtomicLong (:state target) (long value))
  nil)

(defn builtin-method-atomic_integer64-compare-and-set
  [target expected update]
  (.compareAndSet ^AtomicLong (:state target) (long expected) (long update)))

(defn builtin-method-atomic_integer64-get-and-add
  [target delta]
  (.getAndAdd ^AtomicLong (:state target) (long delta)))

(defn builtin-method-atomic_integer64-add-and-get
  [target delta]
  (.addAndGet ^AtomicLong (:state target) (long delta)))

(defn builtin-method-atomic_integer64-increment
  [target]
  (.incrementAndGet ^AtomicLong (:state target)))

(defn builtin-method-atomic_integer64-decrement
  [target]
  (.decrementAndGet ^AtomicLong (:state target)))

(defn builtin-method-atomic_boolean-load
  [target]
  (.get ^AtomicBoolean (:state target)))

(defn builtin-method-atomic_boolean-store
  [target value]
  (.set ^AtomicBoolean (:state target) (boolean value))
  nil)

(defn builtin-method-atomic_boolean-compare-and-set
  [target expected update]
  (.compareAndSet ^AtomicBoolean (:state target) (boolean expected) (boolean update)))

(defn builtin-method-atomic_reference-load
  [target]
  (.get ^AtomicReference (:state target)))

(defn builtin-method-atomic_reference-store
  [target value]
  (.set ^AtomicReference (:state target) value)
  nil)

(defn builtin-method-atomic_reference-compare-and-set
  [target expected update]
  (loop []
    (let [^AtomicReference state (:state target)
          current (.get state)]
      (if (value/nex-deep-equals? interp-object? current expected)
        (if (.compareAndSet state current update)
          true
          (recur))
        false))))

(defn call-compiled-user-method
  [state target method-name args]
  (invoke-user-method state target method-name (vec args)))

(defn- builtin-cursor-op
  "{cursor-type method-fn} for METHOD-NAME on the built-in cursors (an `across`
   over an Array, String, Map or Set), keyed by the cursor's :nex-builtin-type."
  [method-name]
  (into {}
        (keep (fn [type] (when-let [f (get-in bi/builtin-type-methods [type method-name])]
                           [type f])))
        [:ArrayCursor :StringCursor :MapCursor :SetCursor]))

(declare ^:private dispatch-cursor-method*)

(defn- dispatch-cursor-method
  "Call cursor method METHOD-NAME on TARGET. A built-in cursor's method is
   looked up in OP-FNS (see builtin-cursor-op) by the cursor's type and called
   directly: `across` makes three cursor calls per element, and the general
   path looks up the target's type, checks it conforms to Cursor, then finds
   the method by name."
  ([state target method-name args]
   (dispatch-cursor-method* state target method-name args))
  ([state target method-name args op-fns]
   (if-let [method-fn (when (map? target)
                        (get op-fns (:nex-builtin-type target)))]
     (method-fn target)
     (dispatch-cursor-method* state target method-name args))))

(defn- dispatch-cursor-method*
  [state target method-name args]
  (let [runtime-name (runtime-type-name state target)]
    (if (and (string? runtime-name)
             (runtime-compatible-with? state runtime-name "Cursor"))
      (if (interp-object? target)
        (invoke-interpreter-object-method state target method-name args)
        (invoke-user-method state target method-name args))
      (bi/call-builtin-method nil target target method-name args))))

(def ^:private builtin-cursor-start-fns (builtin-cursor-op "start"))

(defn builtin-cursor-start
  [state target]
  (dispatch-cursor-method state target "start" [] builtin-cursor-start-fns))

(defn builtin-cursor-cursor
  [state target]
  (dispatch-cursor-method state target "cursor" []))

(def ^:private builtin-cursor-item-fns (builtin-cursor-op "item"))

(defn builtin-cursor-item
  [state target]
  (dispatch-cursor-method state target "item" [] builtin-cursor-item-fns))

(def ^:private builtin-cursor-next-fns (builtin-cursor-op "next"))

(defn builtin-cursor-next
  [state target]
  (dispatch-cursor-method state target "next" [] builtin-cursor-next-fns))

(def ^:private builtin-cursor-at-end-fns (builtin-cursor-op "at_end"))

(defn builtin-cursor-at-end
  [state target]
  (dispatch-cursor-method state target "at_end" [] builtin-cursor-at-end-fns))

(defn current-time-ms
  []
  (System/currentTimeMillis))

(defn select-deadline
  [timeout-ms]
  (+ (current-time-ms) (long timeout-ms)))

(defn deadline-expired?
  [deadline]
  (>= (current-time-ms) deadline))

(defn select-sleep-step!
  []
  (Thread/sleep 1)
  nil)

(defn- compiled-class-binary-name
  [state class-name]
  (some-> (.get ^HashMap @(:classes state) class-name)
          :binary-name))

(defn portable-value->compiled
  "Convert a value built through nex.types.runtime's portable (tagged)
   representation into the compiled backend's own native one, recursively —
   needed wherever a value crosses from a backend-agnostic helper
   (java-http-request and kin, which build a Map/Set with rt/nex-map,
   rt/nex-set) into a real compiled object's field via reflection: the
   compiled backend's own Map/Set fields are always a real
   java.util.HashMap/LinkedHashSet (see emit-map-literal! and the comment
   above map-contains-key), never the portable tagged form, and Field/set
   rejects the mismatch outright rather than converting it. Arrays need no
   container conversion (already java.util.ArrayList on both sides, per
   nex.types.runtime/nex-array-from) — only their elements, in case one holds
   a portable map/set in turn.

   Also called directly from emitted bytecode (see emit-unbox-or-cast! in
   emit.clj) ahead of a CHECKCAST to HashMap/LinkedHashSet: the same portable
   representation reaches a declared Map/Set-typed local, field, or param
   whenever its value originates from a backend-agnostic helper — most
   commonly a builtin free function like json_parse — whose static Nex type
   is Any, so the compiled backend cannot special-case it the way it does its
   own Map/Set literals. Public (not `defn-`) so that reflective lookup by
   name from bytecode resolves it."
  [v]
  (cond
    (rt/nex-map? v)
    ;; LinkedHashMap, not HashMap: `rt/nex-map-entries` yields the portable
    ;; map's own insertion order (e.g. object-key order from json_parse), and
    ;; a plain HashMap would scramble it to hash-bucket order the moment a
    ;; portable map crosses into the compiled backend's representation.
    (let [^java.util.Map m (java.util.LinkedHashMap.)]
      (doseq [[k val] (rt/nex-map-entries v)]
        (.put m (portable-value->compiled k) (portable-value->compiled val)))
      m)

    (rt/nex-set? v)
    (let [^java.util.Set s (java.util.LinkedHashSet.)]
      (doseq [item (rt/nex-set-seq v)]
        (.add s (portable-value->compiled item)))
      s)

    (instance? java.util.ArrayList v)
    (let [^java.util.ArrayList al v]
      (dotimes [i (.size al)]
        (.set al i (portable-value->compiled (.get al i))))
      al)

    :else v))

(defn- instantiate-compiled-object
  [state class-name field-values]
  (let [binary-name (compiled-class-binary-name state class-name)]
    (when binary-name
      (let [^Class cls (resolve-owner-class state binary-name)
            ctor (.getDeclaredConstructor cls (into-array Class []))
            instance (.newInstance ctor (object-array 0))]
        ;; The other construction path (emit-expr! :new) sets this inline; this
        ;; one has to do it by hand, or the object's equals/hashCode would find
        ;; no state and degrade to identity.
        (when-let [^Field state-field (reflected-field cls "__state__")]
          (.set state-field instance state))
        (doseq [[field-name field-value] field-values]
          (when-let [^Field field (reflected-field cls (name field-name))]
            (.set field instance (portable-value->compiled field-value))))
        instance))))

(defn- make-runtime-object
  "An instance of the program's compiled CLASS-NAME, built by a builtin (the
   http client and server hand the program Http_Response/Http_Request/
   Http_Server_Response objects). A program that uses one of those builtins
   always has its library's classes compiled in (nex.walker/add-implied-
   interns), so a missing class is a compiler defect, reported as such."
  [state class-name field-values]
  (or (instantiate-compiled-object state class-name field-values)
      (throw (ex-info (str "Internal error: class " class-name
                           " is not compiled into this program")
                      {:class-name class-name}))))

(defn next-class-name!
  ([state prefix]
   (next-class-name! state "nex/repl" prefix))
  ([state package prefix]
   (let [n (swap! (:counter state) inc)]
     (format "%s/%s_%04d" package prefix n))))

(defn clear-output!
  [state]
  (reset! (:output state) [])
  state)

(defn state-output
  [state]
  @(:output state))

(defn state-set-classes!
  [state class-map]
  (reset! (:classes state)
          (let [copy (HashMap.)]
            (doseq [[k v] class-map]
              (.put copy k v))
            copy))
  state)

(defn state-set-imports!
  [state imports]
  (reset! (:imports state) (vec imports))
  state)

(defn bootstrap-compiled-state!
  [state classes-edn imports-edn]
  (let [classes (edn/read-string classes-edn)
        imports (edn/read-string imports-edn)]
    (state-set-classes! state (into {} (map (juxt :name identity) classes)))
    (state-set-imports! state imports)
    state))

(defn state-set-immediate-output!
  [state enabled]
  (swap! (:values state)
         (fn [^HashMap m]
           (doto m (.put "__immediate_output__" (boolean enabled)))))
  nil)

(defn set-program-args!
  "Thin wrapper so the compiled launcher's `main` (emit-launcher-main!) can
   reach nex.types.runtime/set-program-args! by name through emit-runtime-
   call!, which resolves runtime helper names against this namespace only."
  [args]
  (rt/set-program-args! args))

(defn- immediate-output?
  [state]
  (let [^HashMap vals @(:values state)]
    (true? (.get vals "__immediate_output__"))))

(defn print-state-output!
  [state]
  (when-not (immediate-output? state)
    (doseq [line (state-output state)]
      (println line)))
  nil)

(defn- add-output!
  [state line]
  (when (immediate-output? state)
    (println line))
  (swap! (:output state) conj line)
  nil)

(def ^:private ^java.util.concurrent.ConcurrentHashMap lowered-method-names
  "method name -> the lowered names for arities 0..15, built on first use:
   a dynamic call asks for one every time it is made."
  (java.util.concurrent.ConcurrentHashMap.))

(defn- lowered-instance-method-name
  ^String [method-name arity]
  (let [a (long arity)]
    (if (and (string? method-name) (< -1 a 16))
      (let [^objects slots (or (.get lowered-method-names method-name)
                               (let [fresh (object-array 16)]
                                 (or (.putIfAbsent lowered-method-names method-name fresh) fresh)))]
        (or (aget slots a)
            (let [n (str "__method_" method-name "$arity" arity)]
              (aset slots a n)
              n)))
      (str "__method_" method-name "$arity" arity))))

(defn- reflected-field
  "The field FIELD-NAME of CLS, public or declared by CLS itself, looked up
   once (see nex.types.reflect/field)."
  [^Class cls field-name]
  (reflect/field cls field-name))

(defn- composition-fields
  [^Class cls]
  (->> (.getDeclaredFields cls)
       (filter (fn [^Field f] (str/starts-with? (.getName f) "_parent_")))
       (map (fn [^Field f]
              (.setAccessible f true)
              f))))

(defn- deep-reflected-field
  [value field-name]
  (or (when-let [^Field f (reflected-field (.getClass ^Object value) field-name)]
        [value f])
      (some (fn [^Field parent-field]
              (when-let [parent-value (.get parent-field value)]
                (deep-reflected-field parent-value field-name)))
            (composition-fields (.getClass ^Object value)))))

(def ^:private declared-user-method-cache
  "Class -> (lowered method name -> Method, or `false` when the class declares
   no such method). Every runtime-dispatched call (`obj.f`, `this.f`, a
   self-call inside a composition carrier) looks its target up here, so a
   reflective getDeclaredMethod — or a thrown NoSuchMethodException — happens
   once per class and name rather than on every call."
  (java.util.concurrent.ConcurrentHashMap.))

(def ^:private user-method-param-types
  (into-array Class [nex.compiler.jvm.runtime.NexReplState
                     (class (object-array 0))]))

(defn- declared-user-method
  "The method LOWERED-NAME that CLS itself declares, or nil. A plain `.get`
   first: the hit path allocates nothing."
  ^Method [^Class cls ^String lowered-name]
  (let [^java.util.concurrent.ConcurrentHashMap cache declared-user-method-cache
        ^java.util.concurrent.ConcurrentHashMap by-name
        (or (.get cache cls)
            (.computeIfAbsent cache
                              cls
                              (reify java.util.function.Function
                                (apply [_ _] (java.util.concurrent.ConcurrentHashMap.)))))
        found (or (.get by-name lowered-name)
                  (.computeIfAbsent by-name
                                    lowered-name
                                    (reify java.util.function.Function
                                      (apply [_ _]
                                        (try
                                          ;; Accessible once, here: otherwise every reflective
                                          ;; call re-runs Java's access check, which compares
                                          ;; the caller's and the class's packages by name.
                                          (doto (.getDeclaredMethod cls lowered-name user-method-param-types)
                                            (.setAccessible true))
                                          (catch NoSuchMethodException _ false))))))]
    (when found found)))

(def ^:private composition-fields-cache
  (java.util.concurrent.ConcurrentHashMap.))

(defn- find-user-method
  "Find a user method on the target object, traversing composition parents if needed."
  [target lowered-name]
  (let [^Class cls (.getClass ^Object target)]
    (if-let [method (declared-user-method cls lowered-name)]
      [target method]
      (some (fn [^Field parent-field]
              (when-let [parent-value (.get parent-field target)]
                (find-user-method parent-value lowered-name)))
            (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap composition-fields-cache
                              cls
                              (reify java.util.function.Function
                                (apply [_ c] (vec (composition-fields c)))))))))

(defn- invoke-user-method
  [state target method-name args]
  (when (nil? target)
    ;; A nil receiver must surface as the language's void-call error, not as a
    ;; raw JVM NullPointerException from the reflective dispatch below.
    (throw (ex-info "Used a value that is void (nil)"
                    {:method method-name :arity (count args) :builtin-kind "Void_Access"})))
  (if (interp-object? target)
    ;; The target was produced by the interpreter (e.g. returned from a function
    ;; that fell back to the tree-walker) and stored in the compiled session. The
    ;; compiled runtime cannot reflect user methods off an interpreter object, so
    ;; dispatch it back through the interpreter — mirroring invoke-function-object.
    (invoke-interpreter-object-method state target method-name args)
    (let [^Class cls (.getClass ^Object target)
          lowered-name (lowered-instance-method-name method-name (count args))]
      (if-let [[effective-target ^Method method] (find-user-method target lowered-name)]
        (invoke-reflective! method effective-target (method-args state (object-array args)))
        (let [runtime-name (runtime-type-name state target)]
          (if (and (= method-name "cursor")
                   (empty? args)
                   (string? runtime-name)
                   (runtime-compatible-with? state runtime-name "Cursor"))
            target
            (let [builtin-result (try
                                   (if (typeinfo/get-type-name target)
                                     (bi/call-builtin-method nil target target method-name args)
                                     ::not-found)
                                   (catch Exception _ ::not-found))]
              (if (not= builtin-result ::not-found)
                builtin-result
                (throw (ex-info (str "Method not found: " method-name)
                                {:method method-name
                                 :arity (count args)
                                 :class (.getName cls)}))))))))))

(defn call-user-method
  "A qualified call `x.f(args)` on a user object, as compiled code makes it.
   The compiler passes LOWERED-NAME (`__method_f$arityN`) ready-made and ARGS
   as an array, so when the receiver's class declares the routine itself
   (the common case: a class's own and inherited routines are all emitted on
   it) this is one cached lookup and the call. Anything else — a nil or
   interpreter receiver, a routine found only on a composition parent, a
   builtin fallback — takes the general invoke-user-method path."
  [state ^java.util.concurrent.atomic.AtomicReference site ^String lowered-name
   method-name target ^objects args]
  (if (nil? target)
    (invoke-user-method state target method-name (vec args))
    (let [cls (.getClass ^Object target)
          ;; SITE remembers [class method] from this call site's last call.
          ;; A site nearly always sees one class, so this skips the lookup.
          ^objects hit (when site (.get site))]
      (if (and hit (identical? cls (aget hit 0)))
        (invoke-reflective! (aget hit 1) target (method-args state args))
        (if-let [method (and (not (interp-object? target))
                             (declared-user-method cls lowered-name))]
          (do
            (when site (.set site (object-array [cls method])))
            (invoke-reflective! method target (method-args state args)))
          (invoke-user-method state target method-name (vec args)))))))

(defn call-user-method-fallback
  "call-user-method with the target and arguments first: the order a compiled
   call site has them in when the target does not implement the routine's
   dispatch interface (see nex.compiler.jvm.emit/emit-user-method-call!)."
  [target ^objects args state site lowered-name method-name]
  (call-user-method state site lowered-name method-name target args))

(defn dispatch-self-call
  "An unqualified call made by code running on a composition carrier, so on
   behalf of the heir's object TARGET (the carrier's `__outer__`), which may
   override the routine. Runs TARGET's unchecked twin UNCHECKED-NAME (see
   nex.lower/self-call-method-name), which its class emits when its
   hierarchy declares an invariant, else the ordinary routine CHECKED-NAME —
   with nothing to check. Both names arrive as constants, and the lookup is
   cached per class, so a hot self-call allocates no more than its args."
  [state target ^String unchecked-name ^String checked-name ^objects args]
  (let [^Class cls (.getClass ^Object target)
        call-args (method-args state args)]
    (if-let [method (or (declared-user-method cls unchecked-name)
                        (declared-user-method cls checked-name))]
      (invoke-reflective! method target call-args)
      (if-let [[effective-target ^Method method] (or (find-user-method target unchecked-name)
                                                     (find-user-method target checked-name))]
        (invoke-reflective! method effective-target call-args)
        (throw (ex-info (str "Method not found: " checked-name)
                        {:method checked-name :class (.getName cls)}))))))

(def ^:private container-field-cache (java.util.concurrent.ConcurrentHashMap.))

(declare ^:private container-of-by-reflection)

(defn- container-of
  "The object CARRIER is a composition carrier of, one level up, or nil."
  [carrier]
  (if (instance? NexCarrier carrier)
    (.__container ^NexCarrier carrier)
    (container-of-by-reflection carrier)))

(defn- container-of-by-reflection
  [carrier]
  (let [^Class cls (.getClass ^Object carrier)
        ^Field field (or (.get ^java.util.concurrent.ConcurrentHashMap container-field-cache cls)
                         (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap container-field-cache
                                           cls
                                           (reify java.util.function.Function
                                             (apply [_ c] (.getField ^Class c "__container__")))))]
    (.get field carrier)))

(defn- own-user-method
  "The routine NAME that X's class declares itself, as opposed to a stub
   forwarding one it inherits (emitted synthetic), or nil."
  ^Method [x ^String name]
  (when-let [^Method m (declared-user-method (.getClass ^Object x) name)]
    (when-not (.isSynthetic m) m)))

(defn dispatch-path-call
  "A call on the current object made by code running on CARRIER, the nested
   object of the class whose text makes the call. Between CARRIER and the
   outer object there is one path of containers; the outermost of them whose
   class itself declares the routine (an override) runs it. With none, the
   call stays on CARRIER's own path, so a shared ancestor's routine acts on
   that path's copy of its fields. A routine CARRIER cannot run (deferred
   all along its path) is dispatched on the outer object.

   UNCHECKED-NAME is the routine's unchecked twin, preferred where a class
   has one, CHECKED-NAME its ordinary routine; a qualified call passes the
   checked name for both."
  [state carrier ^String unchecked-name ^String checked-name ^objects args]
  (let [call-args (method-args state args)
        overrider (loop [x (container-of carrier) found nil]
                    (if (nil? x)
                      found
                      (recur (container-of x)
                             (if-let [m (or (own-user-method x unchecked-name)
                                            (own-user-method x checked-name))]
                               [x m]
                               found))))]
    (if-let [[target ^Method method] overrider]
      (invoke-reflective! method target call-args)
      (if-let [[target ^Method method] (or (find-user-method carrier unchecked-name)
                                           (find-user-method carrier checked-name))]
        (invoke-reflective! method target call-args)
        (dispatch-self-call state (.get (.getField (.getClass ^Object carrier) "__outer__") carrier)
                            unchecked-name checked-name args)))))

(def ^:private outer-field-cache (java.util.concurrent.ConcurrentHashMap.))

(declare ^:private outer-of-by-reflection)

(defn- outer-of
  "The heir's object CARRIER lives in (its `__outer__`)."
  [carrier]
  (if (instance? NexCarrier carrier)
    (.__outer ^NexCarrier carrier)
    (outer-of-by-reflection carrier)))

(defn- outer-of-by-reflection
  [carrier]
  (let [^Class cls (.getClass ^Object carrier)
        ^Field field (or (.get ^java.util.concurrent.ConcurrentHashMap outer-field-cache cls)
                         (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap outer-field-cache
                                           cls
                                           (reify java.util.function.Function
                                             (apply [_ c] (.getField ^Class c "__outer__")))))]
    (.get field carrier)))

(defn- resolve-path-call-target
  "[target choice] for a path call on CARRIER (see dispatch-path-call, which
   this follows): the object whose own routine UNCHECKED-NAME, else
   CHECKED-NAME, runs it, and how it was found, for path-call-target's cache:
   an index into CHAIN (CARRIER, then its containers outwards), :outer for
   CARRIER's `__outer__`, or nil when it is neither (a composition parent's
   routine), which is not cached."
  [carrier chain ^String unchecked-name ^String checked-name]
  (let [overrider (loop [i 1 found nil]
                    (if (>= i (count chain))
                      found
                      (recur (inc i)
                             (let [x (nth chain i)]
                               (if (or (own-user-method x unchecked-name)
                                       (own-user-method x checked-name))
                                 i
                                 found)))))]
    (if overrider
      [(nth chain overrider) overrider]
      (if-let [[target] (or (find-user-method carrier unchecked-name)
                            (find-user-method carrier checked-name))]
        [target (when (identical? target carrier) 0)]
        (let [outer (outer-of carrier)]
          (when-let [[target] (or (find-user-method outer unchecked-name)
                                  (find-user-method outer checked-name))]
            [target (when (identical? target outer) :outer)]))))))

(defn path-call-target
  "The object whose own routine UNCHECKED-NAME, else CHECKED-NAME, a path
   call on CARRIER runs (see dispatch-path-call), or nil. A compiled call site
   asks for it, then calls the routine through a dispatch interface
   (nex.compiler.jvm.emit/emit-path-call!), instead of dispatch-path-call
   calling it by reflection.

   Which object runs it depends only on the classes of CARRIER and of its
   containers, so SITE, the call site's cache, remembers that choice for the
   classes it last saw: a hit costs a walk up the containers and no method
   lookups."
  [^java.util.concurrent.atomic.AtomicReference site carrier
   ^String unchecked-name ^String checked-name]
  (let [^objects hit (when site (.get site))
        ^"[Ljava.lang.Class;" classes (when hit (aget hit 0))
        same-classes? (and classes
                           (loop [x carrier i 0]
                             (cond
                               (nil? x) (== i (alength classes))
                               (== i (alength classes)) false
                               (identical? (aget classes i) (.getClass ^Object x))
                               (recur (container-of x) (inc i))
                               :else false)))]
    (if same-classes?
      (let [choice (aget hit 1)]
        (if (identical? :outer choice)
          (outer-of carrier)
          (loop [x carrier k (long choice)]
            (if (zero? k) x (recur (container-of x) (dec k))))))
      (let [chain (loop [x carrier acc []]
                    (if (nil? x) acc (recur (container-of x) (conj acc x))))
            [target choice] (resolve-path-call-target carrier chain unchecked-name checked-name)]
        (when (and site (some? choice))
          (.set site (object-array [(into-array Class (map #(.getClass ^Object %) chain))
                                    choice])))
        target))))

(defn dispatch-path-call-fallback
  "dispatch-path-call with the carrier and arguments first: the order a
   compiled call site has them in when the target it was given does not
   implement a dispatch interface for the routine."
  [carrier ^objects args state unchecked-name checked-name]
  (dispatch-path-call state carrier unchecked-name checked-name args))

(defn- get-user-field
  [target field-name]
  (if (interp-object? target)
    ;; The target was produced by the interpreter (e.g. a value constructed inside
    ;; a deoptimized closure) and stored in the compiled session. Its fields live in
    ;; the object's :fields map, not as reflectable JVM fields, so read them
    ;; directly — mirroring object-field-value and invoke-user-method.
    (let [fields (:fields target)]
      (or (get fields field-name)
          (get fields (keyword field-name))))
    (let [[owner ^Field field] (or (deep-reflected-field target field-name)
                                   (throw (ex-info (str "Undefined compiled field: " field-name)
                                                   {:field field-name
                                                    :class (.getName (.getClass ^Object target))})))]
      (.get field owner))))

(defn- set-user-field!
  [target field-name value]
  (let [[owner ^Field field] (or (deep-reflected-field target field-name)
                                 (throw (ex-info (str "Undefined compiled field: " field-name)
                                                 {:field field-name
                                                  :class (.getName (.getClass ^Object target))})))]
    (.set field owner value)
    nil))

(defn make-raised-exception
  [value]
  (ex-info (str value) {:type :nex-exception :value value}))

(defn make-builtin-failure
  "A failure the language raises itself through a generated `raise` (see
   nex.ir/raise-node): MESSAGE, classified by BUILTIN, \"Kind\" or
   \"Kind/label\" (nex.types.runtime/builtin-failure)."
  [message builtin]
  (let [[kind label] (str/split (str builtin) #"/" 2)]
    (ex-info (str message) {:builtin-kind kind :label label})))

(defn make-retry-signal
  []
  (ex-info "retry" {:type :nex-retry}))

(defn- unwrap-reflective-exception
  "A free function called via `:call-repl-fn` (see emit.clj) is invoked through
   `java.lang.reflect.Method/invoke`, which wraps whatever it throws in an
   `InvocationTargetException` with no message of its own — so a `do ...
   rescue ... end` around such a call, inspecting the caught throwable
   directly, saw a null message/no ex-data and printed `nil` for `exception`
   instead of the real contract-violation or `raise`d value. A method call
   (`:call-virtual`) never goes through reflection and never hit this.
   Peels away that wrapper (and a same-shaped `ExceptionInInitializerError`,
   for a static initializer that fails the same way) down to the real cause,
   exactly as `nex.repl/unwrap-user-visible-exception` and
   `nex.eval/run-compiled` already do outside the compiled program itself."
  [^Throwable t]
  (loop [t t]
    (if (and (or (instance? InvocationTargetException t)
                 (instance? ExceptionInInitializerError t))
             (.getCause t))
      (recur (.getCause t))
      t)))

(defn retry-signal?
  [throwable]
  (let [throwable (unwrap-reflective-exception throwable)]
    (and (instance? clojure.lang.ExceptionInfo throwable)
         (= :nex-retry (:type (ex-data throwable))))))

(defn exception-value
  [throwable]
  (let [throwable (unwrap-reflective-exception throwable)]
    (if (and (instance? clojure.lang.ExceptionInfo throwable)
             (= :nex-exception (:type (ex-data throwable))))
      (:value (ex-data throwable))
      ;; A host throwable's own message is what `exception` binds to. Some
      ;; carry none — `StackOverflowError` and friends have a null
      ;; getMessage — which bound `exception` to nil, so `exception.to_string`
      ;; in the rescue block rendered "nil" with nothing to say what was
      ;; caught. Fall back to the throwable's class name so `exception` is
      ;; always a real, informative string.
      (let [msg (.getMessage ^Throwable throwable)]
        (if (str/blank? msg)
          (.getName (class throwable))
          msg)))))

(defn rescue-exception-value
  "What a `rescue` block's `exception` holds for THROWABLE: a value the program
   raised is that value; a built-in failure is an instance of its
   lib/lang/exception.nex class (Definition B.7), built by that library's
   `__builtin_exception`. A program with a `rescue` has the library
   (nex.walker/add-implied-interns), so the message is only a fallback for one
   that declared a colliding class of its own instead."
  [state throwable]
  (let [throwable (unwrap-reflective-exception throwable)]
    (if (and (instance? clojure.lang.ExceptionInfo throwable)
             (= :nex-exception (:type (ex-data throwable))))
      (:value (ex-data throwable))
      (or (when-let [[kind message label] (rt/builtin-failure throwable)]
            (when-let [make (function-value-for-name state "__builtin_exception")]
              (make kind message label)))
          (rt/nex-error-message throwable)))))

(defn- generated-class-name?
  "Whether BINARY-NAME is a class the Nex compiler generated: file programs
   compile under nex.file.<stem>, REPL input under nex.repl."
  [binary-name]
  (or (str/starts-with? binary-name "nex.file.")
      (str/starts-with? binary-name "nex.repl.")))

(defn- compiled-runtime-class-name
  "The Nex class VALUE is an instance of, or nil when it is not a compiled Nex
   object. Only generated classes qualify: a Java object reached through
   interop (say a javax.sound.midi.Track) is not a Nex object merely because
   its simple name matches a Nex class (Track)."
  [state value]
  (when (and value (generated-class-name? (.getName (.getClass ^Object value))))
    ;; Asked on every structural `=`, so the answer is remembered for the
    ;; class. Only a found name: a class is never removed from `state`, so a
    ;; name found stays right, but one not found yet may be defined later.
    (let [cls (.getClass ^Object value)
          known (reflect/on-class cls ::nex-class-name (constantly (volatile! nil)))]
      (or @known
          (let [binary-name (.getName cls)
                simple-name (last (str/split binary-name #"\."))
                found (cond
                        (contains? @(:classes state) simple-name)
                        simple-name

                        :else
                        (when-let [[_ candidate] (re-matches #"(.+)_\d{4}" simple-name)]
                          (when (contains? @(:classes state) candidate)
                            candidate)))]
            (when found (vreset! known found))
            found)))))

(defn- runtime-type-name
  [state value]
  (or (compiled-runtime-class-name state value)
      (typeinfo/runtime-type-name interp-object? typeinfo/get-type-name value)))

(defn- split-target-type-name
  "\"Some[String,Integer]\" -> [\"Some\" [\"String\" \"Integer\"]]; a name with no
   type arguments -> [name nil]. nex.lower encodes a convert target's type
   arguments this way, each as its base name (\"Any\" where it is not known)."
  [target-type-name]
  (if-let [[_ base args] (and (string? target-type-name)
                              (re-matches #"([^\[]+)\[(.*)\]" target-type-name))]
    [base (mapv str/trim (str/split args #","))]
    [target-type-name nil]))

(defn- compiled-value-type-args
  "The type arguments a compiled generic object was created with, as base
   type names, read from the `__generic_type_<param>` fields its class keeps
   (\"Any\" where they were erased); nil for a non-generic value."
  [state value runtime-name]
  (when-let [class-def (and value (string? runtime-name)
                            (.get ^HashMap @(:classes state) runtime-name))]
    (when-let [params (seq (:generic-params class-def))]
      (mapv (fn [{param-name :name}]
              (or (when-let [[owner ^Field f] (deep-reflected-field value (str "__generic_type_" param-name))]
                    (some-> (.get f owner) str))
                  "Any"))
            params))))

(defn convert-value
  [state value target-type-name]
  (let [runtime-name (runtime-type-name state value)
        [target-type-name target-args] (split-target-type-name target-type-name)
        class-lookup (fn [n] (.get ^HashMap @(:classes state) n))
        ok? (and (some? value)
                 (string? target-type-name)
                 (runtime-compatible-with? state runtime-name target-type-name)
                 ;; The type arguments too, when the target names them
                 ;; (Definition 4.7: Some[Integer] is not a Some[String]).
                 (or (empty? target-args)
                     (typeinfo/type-args-compatible?
                      class-lookup
                      (typeinfo/known-type-name-fn class-lookup)
                      runtime-name
                      (compiled-value-type-args state value runtime-name)
                      target-type-name
                      target-args)))]
    ;; A converted value may originate from a backend-agnostic helper (e.g.
    ;; nex.types.json/json-value->nex) that builds Maps/Sets via
    ;; nex.types.runtime's portable tagged representation. The compiled
    ;; backend's own Map/Set-typed locals/fields are always real
    ;; java.util.HashMap/LinkedHashSet (see emit-map-literal!), so the value
    ;; must be converted to that native shape before it flows into one — same
    ;; conversion instantiate-compiled-object applies for reflective field sets.
    (object-array [(boolean ok?) (if ok? (portable-value->compiled value) nil)])))

(defn make-contract-violation
  ([kind label]
   (make-contract-violation kind label nil))
  ([kind label line]
   (ex-info (cond
              (= kind "Loop variant") "Loop variant must decrease"
              (= kind "Loop variant bound") "Loop variant must be non-negative"
              label                   (str kind " violation: " label)
              ;; Only a bare `assert expr` is unlabelled; name it by its line.
              line                    (str kind " violation (line " line ")")
              :else                   (str kind " violation"))
            (bi/on-entry-data {:type :nex-contract-violation
                               :kind kind
                               :label label}
                              kind))))

(defn- class-def-by-name
  [state class-name]
  (.get ^HashMap @(:classes state) class-name))

(def ^:private builtin-base-class-defs
  "The standard environment's base classes (Any, Function, Cursor, Comparable,
   and the scalars), for hierarchy walks that cross from program
   classes into the builtin roots — no interpreter context needed."
  (delay (into {}
               (map (fn [class-def] [(:name class-def) class-def]))
               (bootstrap/base-class-defs))))

(defn- compiled-is-parent?
  [state class-name parent-name]
  (letfn [(walk [current seen]
            (when (and current (not (contains? seen current)))
              (when-let [class-def (or (class-def-by-name state current)
                                       (get @builtin-base-class-defs current))]
                (let [seen' (conj seen current)
                      parents (:parents class-def)]
                  (or (some #(= (:parent %) parent-name) parents)
                      (some #(walk (:parent %) seen') parents))))))]
    (walk class-name #{})))

(defn- runtime-compatible-with?
  [state runtime-name target-name]
  (or (= runtime-name target-name)
      (= target-name "Any")
      (compiled-is-parent? state runtime-name target-name)))

(declare collect-effective-field-names*)

(def ^:private ^java.util.concurrent.ConcurrentHashMap effective-field-names-cache
  "A class's lineage (see class-lineage) -> its effective field names."
  (java.util.concurrent.ConcurrentHashMap.))

(defn- class-lineage
  "CLASS-DEF and the definitions of all its ancestors, as a vector: the
   fields a class has depend on exactly these. Class definitions are
   immutable maps, whose hash Clojure computes once, so this makes a cheap
   cache key that a redefinition (of the class or of any ancestor) changes."
  [^java.util.Map class-map class-def]
  (if (empty? (:parents class-def))
    [class-def]
    (let [todo (java.util.ArrayDeque.)
          seen (java.util.HashSet.)]
      (.add todo class-def)
      (loop [acc (transient [])]
        (if-let [c (.poll todo)]
          (if (.add seen (:name c))
            (do (doseq [{:keys [parent]} (:parents c)]
                  (when-let [p (.get class-map parent)]
                    (.add todo p)))
                (recur (conj! acc c)))
            (recur acc))
          (persistent! acc))))))

(defn- collect-effective-field-names
  "The names of CLASS-DEF's fields, its parents' first, each once. Asked on
   every structural `=` and hash of an object, so it is remembered for each
   class lineage."
  [state class-def]
  (let [k (class-lineage @(:classes state) class-def)]
    (or (.get effective-field-names-cache k)
        (let [names (collect-effective-field-names* state class-def)]
          ;; Each REPL redefinition makes new lineages; keep the cache small.
          (when (> (.size effective-field-names-cache) 4096)
            (.clear effective-field-names-cache))
          (.put effective-field-names-cache k names)
          names))))

(defn- collect-effective-field-names*
  [state class-def]
  (let [class-map @(:classes state)]
    (letfn [(collect [cls seen]
              (let [class-name (:name cls)
                    already? (and class-name (contains? seen class-name))
                    seen' (if class-name (conj seen class-name) seen)]
                (if already?
                  [[] seen]
                  (let [[parent-fields seen'']
                        (if-let [parents (:parents cls)]
                          (reduce (fn [[acc seen-so-far] {:keys [parent]}]
                                    (if-let [parent-def (.get ^HashMap class-map parent)]
                                      (let [[fields seen-next] (collect parent-def seen-so-far)]
                                        [(into acc fields) seen-next])
                                      [acc seen-so-far]))
                                  [[] seen']
                                  parents)
                          [[] seen'])
                        local-fields (->> (:body cls)
                                          (filter #(= :feature-section (:type %)))
                                          (mapcat :members)
                                          (filter #(and (= :field (:type %))
                                                        (not (:constant? %))))
                                          (map :name)
                                          vec)]
                    [(vec (concat parent-fields local-fields)) seen'']))))]
      (->> (first (collect class-def #{}))
           distinct
           vec))))

(def ^:private synthetic-invariant-method-name
  "Feature name of the synthetic invariant method (see lower/`invariant-method-def`).
   Contains `$`, which the grammar forbids in identifiers, so it never collides
   with a user-declared feature."
  "$invariant")

(def ^:private invariant-method-cache
  "Per-Class cache of how to run a compiled class's synthetic invariant method:
   the `Method` when the class declares it itself, ::inherited when only a
   composition parent does, ::none when there is none. The lookup runs once
   per class rather than on every check."
  (java.util.concurrent.ConcurrentHashMap.))

(def ^:private invariant-lowered-name
  (lowered-instance-method-name synthetic-invariant-method-name 0))

(defn- invariant-method
  "How VALUE's invariant runs (see invariant-method-cache), or nil when VALUE
   has none. Interpreter objects have no compiled method and validate their
   own invariants, so they are skipped."
  [value]
  (when (and (some? value) (not (interp-object? value)))
    (let [^java.util.concurrent.ConcurrentHashMap cache invariant-method-cache
          cls (.getClass ^Object value)
          ;; A plain `.get` first: this runs on entry to and exit from every
          ;; checked call, and the hit path should allocate nothing.
          found (or (.get cache cls)
                    (.computeIfAbsent cache
                                      cls
                                      (reify java.util.function.Function
                                        (apply [_ _]
                                          (or (declared-user-method cls invariant-lowered-name)
                                              (when (find-user-method value invariant-lowered-name)
                                                ::inherited)
                                              ::none)))))]
      (when-not (identical? found ::none) found))))

(def ^:private no-args (object-array 0))

(defn- run-invariant!
  "Run VALUE's invariant (METHOD from invariant-method, unused when VALUE
   implements NexInvariant), with ST, this thread's invariant-check state
   (bi/invariant-check-state), marking a check in progress, so calls the
   invariant makes check none themselves, and recording ENTRY-ROUTINE (or nil)
   for a violation's report."
  [^objects st state value method entry-routine]
  (let [outer-entry (aget st 1)]
    (aset st 0 Boolean/TRUE)
    (aset st 1 entry-routine)
    (try
      (cond
        (instance? NexInvariant value)
        (.__method_$invariant$arity0 ^NexInvariant value state no-args)

        (instance? Method method)
        (invoke-reflective! method value (method-args state no-args))

        :else
        (invoke-user-method state value synthetic-invariant-method-name []))
      (finally
        (aset st 1 outer-entry)
        (aset st 0 nil)))))

(defn- check-invariant!
  [state value entry-routine]
  (let [^objects st (.get ^ThreadLocal bi/invariant-check-state)]
    (when-not (aget st 0)
      (if (instance? NexInvariant value)
        (run-invariant! st state value nil entry-routine)
        (when-let [method (invariant-method value)]
          (run-invariant! st state value method entry-routine))))))

(defn validate-object-state
  "Validate an object's class invariant after construction or a public call.
   The invariant is compiled to a native invariant method (structural `=`,
   no interpreter round-trip); this only dispatches it, guarded so an invariant
   that calls a public method does not re-enter. Returns the object unchanged.
   The `class-name` argument is retained for the call convention but no longer
   used — dispatch is virtual off the runtime object."
  [state _class-name value]
  (check-invariant! state value nil)
  value)

(defn validate-object-state-on-entry
  "validate-object-state, on entry to ROUTINE (\"Class.name\") from a
   qualified call. The last check on exit left the invariant true, so a
   failure here means it was broken in between: a callback into an object
   still in the middle of a routine, or a change made behind its back."
  [state routine value]
  (check-invariant! state value routine)
  value)

(defn- nex-object-render
  "The generic `#<Class object>` rendering a Nex object has when its class
   defines no `to_string` (spec Any protocol), or nil when `value` is not a
   compiled object. `bi/nex-format-value` only recognizes the interpreter's
   object *maps*, so a compiled object — an instance of a generated class —
   falls through its cond to Clojure's `#object[nex.file.m.P 0x... ]` form.
   This restores the interpreter's rendering for the compiled object model."
  [state value]
  (when-let [class-name (compiled-runtime-class-name state value)]
    (str "#<" class-name " object>")))

(declare format-value-with-state)

(defn- concat-string-value
  [state value]
  (cond
    (string? value) value

    ;; An interpreter object (e.g. created inside a deoptimized closure) renders
    ;; through the interpreter so a user-defined `to_string` is honored.
    (interp-object? value)
    ((interpreter-op :concat-string value) state value)

    (nil? value) "nil"

    ;; A collection falls through to format-value-with-state (mutually
    ;; recursive with this function — see its own docstring) rather than the
    ;; generic to_string dispatch below, which has no state to invoke a
    ;; nested element's to_string with.
    (or (rt/nex-array? value) (rt/nex-map? value) (rt/nex-set? value))
    (format-value-with-state state value)

    :else
    (let [string-value
          (try
            (let [result (invoke-user-method state value "to_string" [])]
              (if (string? result)
                result
                (bi/nex-format-value result)))
            (catch Exception _
              (or (nex-object-render state value)
                  (bi/call-builtin-method nil nil value "to_string" []))))]
      (if (string? string-value)
        string-value
        (bi/nex-format-value string-value)))))

(defn format-value
  [value]
  (bi/nex-format-value value))

(defn- has-user-to-string?
  [value]
  (boolean
   (and value
        (find-user-method value (lowered-instance-method-name "to_string" 0)))))

(defn- compiled-closure-object?
  "A compiled anonymous function. Its class is synthetic and cannot be rendered
   through the interpreter, so closures keep the REPL's older `#<...>` display."
  [value]
  (and value (str/includes? (.getName (class value)) "AnonymousFunction")))

(defn compiled-object-has-to-string?
  "Whether VALUE is a compiled Nex object whose class defines `to_string`. Public
   for the interpreter, which needs it to render a compiled object that reaches
   it (the REPL mixes the two backends). Never true for a closure."
  [value]
  (and (not (compiled-closure-object? value))
       (has-user-to-string? value)))

(defn compiled-object-class-name
  "The Nex class name of a compiled Nex object (an instance of a generated JVM
   class), or nil for any other value. A generated class carries a `set_outer`
   method and a per-cell numeric suffix (`Plain_0008`), which is stripped."
  [value]
  (when (and value
             (not (compiled-closure-object? value))
             (some #(= "set_outer" (.getName ^java.lang.reflect.Method %))
                   (.getDeclaredMethods (class value))))
    (let [simple (last (str/split (.getName (class value)) #"\."))]
      (or (second (re-matches #"(.+)_\d{4}" simple)) simple))))

;; ---------------------------------------------------------------------------
;; The context the compiled runtime calls the builtin library with.
;; ---------------------------------------------------------------------------

(defn- builtin-user-to-string
  "`to_string` of an object a builtin is formatting (print, string concat,
   Array/Map/Set rendering): a compiled object's own `to_string`, else its
   `#<Class object>` placeholder; nil for anything that is not a Nex object,
   so the builtin falls back to its own formatting."
  [state value]
  (cond
    ;; An interpreter object, from a closure deoptimized onto the tree-walker.
    (interp-object? value)
    (concat-string-value state value)

    (or (nil? value) (string? value) (number? value) (boolean? value)
        (char? value) (coll? value) (map? value))
    nil

    (compiled-object-has-to-string? value)
    (let [result (invoke-user-method state value "to_string" [])]
      (if (string? result) result (bi/nex-format-value result)))

    :else
    (when-let [class-name (compiled-object-class-name value)]
      (str "#<" class-name " object>"))))

(defn- builtin-equals-override
  "A user `equals` between two objects, for a builtin's membership test. A
   compiled object's `equals` override is already its JVM `equals`, which the
   structural fallback reaches, so only interpreter objects need dispatching."
  [state a b]
  (when (and (interp-object? a) (interp-object? b))
    (when-let [equals-override (:equals-override @interpreter-adapter)]
      (equals-override state a b))))

(defn- builtin-ctx
  "Context for calling nex.types.builtins from compiled code. It carries this
   engine's own hooks (see nex.types.builtins/hook), so a builtin that calls
   back into Nex code — a sort comparator, a user `to_string` or `equals`,
   `print`, an http-server handler — dispatches on compiled objects directly,
   and writes output to STATE, instead of going through the tree-walker.
   :imports and :classes serve the builtins that resolve a Java class or
   look up a class-def."
  [state]
  {:engine-hooks {:call-object-method (fn [_ target method-name args]
                                        ;; A top-level function passed by name
                                        ;; (function-value-for-name) is a plain fn.
                                        (if (fn? target)
                                          (apply target args)
                                          (invoke-user-method state target method-name (vec args))))
                  :user-to-string (fn [_ value] (builtin-user-to-string state value))
                  :object-equals-override (fn [_ a b] (builtin-equals-override state a b))
                  :add-output (fn [_ line] (add-output! state line))
                  :is-parent? (fn [_ class-name parent-name]
                                (boolean (compiled-is-parent? state class-name parent-name)))}
   :compiled-state state
   :imports (:imports state)
   :classes (:classes state)
   :specialized-classes (atom {})})


(defn make-raised-exception-in
  "The throwable for `raise VALUE`. Its message -- what an uncaught raise is
   reported with -- is VALUE as `print` renders it, so an object reads through
   its own `to_string` (a re-raised built-in exception, a user Exception heir)
   rather than as a JVM identity; a string stays unquoted."
  [state value]
  (ex-info (if (string? value) value (bi/print-output-value (builtin-ctx state) value))
           {:type :nex-exception :value value}))

(defn- format-value-with-state
  "Like format-value, but recurses into Array/Map/Set elements with state so a
   nested object's to_string override is honored (compiled or, via
   concat-string-value's interpreter bridge, an interpreter-native object from
   a deoptimized closure) instead of falling through to Clojure's raw
   `#object[...]` rendering — format-value alone has no state to invoke a user
   method with. Mirrors print-value's own top-level logic, generalized so it
   can also run on each element; also used directly by array-to-string/
   map-to-string/set-to-string for the explicit `.to_string()` method call."
  [state value]
  (cond
    (rt/nex-set? value) (rt/nex-set-str (partial format-value-with-state state) value)
    (rt/nex-map? value) (rt/nex-map-str (partial format-value-with-state state) value)
    (rt/nex-array? value) (rt/nex-array-str (partial format-value-with-state state) value)

    (or (interp-object? value)
        (has-user-to-string? value))
    (concat-string-value state value)

    :else
    (or (nex-object-render state value)
        (format-value value))))

(defn- print-value
  [state value]
  (format-value-with-state state value))

(defn format-display-value
  "How the REPL shows a result that is a class instance, or a collection that
   may hold some: through the class's own `to_string` (the same rendering
   `print` gives), instead of the placeholder `#<Class object>` / Clojure's raw
   `#object[...]`. Returns nil for every other value, so the REPL's ordinary
   display applies to those unchanged."
  [state value]
  (when (and value
             (not (compiled-closure-object? value))
             (or (rt/nex-array? value) (rt/nex-map? value) (rt/nex-set? value)
                 (interp-object? value)
                 (has-user-to-string? value)))
    (format-value-with-state state value)))

(defn any-to-string
  "The `to_string` that the Any protocol gives every value, for a receiver whose
   *static* type declares none. Resolution is on the runtime value, not the
   static type, because the two differ under dynamic dispatch: with
   `let o: Order := create Placed.make(...)` the static `Order` may declare no
   `to_string` while the runtime `Placed` does, and the override must still win
   — which is exactly what `print` already does for the same value. A String or
   Char is the exception: print shows it quoted, but its own to_string is the
   text itself (nex.types.builtins' string and char methods)."
  [state value]
  (if (or (string? value) (char? value))
    (bi/call-builtin-method nil value value "to_string" [])
    (print-value state value)))

(defn- object-field-value
  [value field-name]
  (cond
    (interp-object? value)
    (let [fields (:fields value)]
      (or (get fields field-name)
          (get fields (keyword field-name))))

    :else
    (get-user-field value field-name)))

(defn- http-response-status
  [response]
  (or (object-field-value response "status_code")
      200))

(defn- http-response-body
  [response]
  (str (or (object-field-value response "body_text") "")))

(defn- http-response-headers
  [response]
  (or (object-field-value response "header_map")
      (rt/nex-map)))

(defn string-concat
  [state args]
  (apply str (map #(concat-string-value state %) args)))

(defn div-int
  [a b]
  (int (rt/nex-int-div (long a) (long b))))

(defn div-long
  [a b]
  (long (rt/nex-int-div (long a) (long b))))

(defn mod-int
  [a b]
  (int (rt/nex-int-mod (long a) (long b))))

(defn mod-long
  [a b]
  (long (rt/nex-int-mod (long a) (long b))))

(defn pow-int
  [a b]
  (int (rt/nex-int-pow (int a) (int b))))

(defn pow-long
  [a b]
  (long (rt/nex-int-pow (long a) (long b))))

(defn sized->integer
  "Unwrap a Nex Byte, Integer16 or Integer32 to the long arithmetic works on."
  [x]
  (long (rt/sized->long x)))

(defn make-int16 [n] (rt/->nex-int16 n))
(defn make-int32 [n] (rt/->nex-int32 n))

(defn pow-double
  [a b]
  (Math/pow (double a) (double b)))

(defn builtin-print!
  [state args]
  (let [line (str/join " " (map #(print-value state %) args))]
    (add-output! state line)))

(defn builtin-println!
  [state args]
  (let [line (str/join " " (map #(print-value state %) args))]
    (add-output! state line)))

(defn builtin-type-of
  [state value]
  (runtime-type-name state value))

(defn- runtime-type-is
  [state target-type value]
  ;; The only hierarchy-aware step is is-parent?; the compiled backend already
  ;; walks the parent chain natively off `state` (compiled-is-parent?), so there
  ;; is no need to rebuild an interpreter context for every `type_is` test.
  (typeinfo/runtime-type-is? #(runtime-type-name state %)
                             (fn [_ctx child parent] (compiled-is-parent? state child parent))
                             nil
                             target-type
                             value))

(defn builtin-type-is
  [state target-type value]
  (runtime-type-is state target-type value))

(defn builtin-sleep!
  [millis]
  (Thread/sleep (long millis))
  nil)

(defn builtin-hint-spin!
  []
  (Thread/onSpinWait)
  nil)

(defn builtin-exit!
  [status]
  (System/exit (int status)))

(defn builtin-http-get
  ([state url]
   (builtin-http-get state url nil))
  ([state url timeout-ms]
   (http/java-http-request (fn [class-name field-values]
                             (make-runtime-object state class-name field-values))
                           "GET"
                           (str url)
                           nil
                           timeout-ms)))

(defn builtin-http-post
  ([state url body]
   (builtin-http-post state url body nil))
  ([state url body timeout-ms]
   (http/java-http-request (fn [class-name field-values]
                             (make-runtime-object state class-name field-values))
                           "POST"
                           (str url)
                           (str body)
                           timeout-ms)))

(defn builtin-json-parse
  [_state text]
  (json-types/nex-json-parse text))

(defn builtin-json-stringify
  [_state value]
  (json-types/nex-json-stringify value))

(defn builtin-http-server-create
  [port]
  (http/make-http-server-handle (int port)))

(defn- http-server-register-route!
  [handle method path handler]
  (swap! (get-in handle [:routes method]) conj {:path-pattern (str path)
                                                :handler handler})
  nil)

(defn builtin-http-server-get!
  [handle path handler]
  (http-server-register-route! handle "GET" path handler))

(defn builtin-http-server-post!
  [handle path handler]
  (http-server-register-route! handle "POST" path handler))

(defn builtin-http-server-put!
  [handle path handler]
  (http-server-register-route! handle "PUT" path handler))

(defn builtin-http-server-delete!
  [handle path handler]
  (http-server-register-route! handle "DELETE" path handler))

(defn builtin-http-server-start!
  [state handle]
  (let [server (com.sun.net.httpserver.HttpServer/create
                (java.net.InetSocketAddress. "127.0.0.1" (int @(:port handle))) 0)
        dispatch
        (proxy [com.sun.net.httpserver.HttpHandler] []
          (handle [exchange]
            (try
              (let [method (.getRequestMethod exchange)
                    uri (.getRequestURI exchange)
                    path (.getPath uri)
                    query (.getRawQuery uri)
                    body (slurp (.getRequestBody exchange))
                    route (http/find-route handle method path)
                    request-obj (make-runtime-object
                                 state
                                 "Http_Request"
                                 {"method_name" method
                                  "path_value" path
                                  "body_text" body
                                  "header_map" (http/http-exchange-headers->nex-map (.getRequestHeaders exchange))
                                  "route_params" (or (:params route) (rt/nex-map))
                                  "query_map" (http/parse-query-map query)})
                    response-obj (if route
                                   (invoke-function-object state (:handler route) [request-obj])
                                   (make-runtime-object
                                    state
                                    "Http_Server_Response"
                                    {"status_code" 404
                                     "body_text" "Not Found"
                                     "header_map" (rt/nex-map)}))
                    status (int (http-response-status response-obj))
                    response-body (http-response-body response-obj)
                    response-bytes (.getBytes response-body java.nio.charset.StandardCharsets/UTF_8)
                    response-headers (http-response-headers response-obj)]
                (doseq [[k v] response-headers]
                  (.add (.getResponseHeaders exchange) (str k) (str v)))
                (.sendResponseHeaders exchange status (long (alength response-bytes)))
                (with-open [os (.getResponseBody exchange)]
                  (.write os response-bytes))
                nil)
              (catch Exception ex
                (let [bytes (.getBytes (str "Server error: " (or (.getMessage ex) "unknown"))
                                       java.nio.charset.StandardCharsets/UTF_8)]
                  (.sendResponseHeaders exchange 500 (long (alength bytes)))
                  (with-open [os (.getResponseBody exchange)]
                    (.write os bytes)))
                nil))))]
    (.createContext server "/" dispatch)
    (.start server)
    (reset! (:server handle) server)
    (reset! (:port handle) (.getPort (.getAddress server)))
    @(:port handle)))

(defn builtin-http-server-stop!
  [handle]
  (let [server @(:server handle)]
    (when server
      (.stop ^com.sun.net.httpserver.HttpServer server 0)
      (reset! (:server handle) nil))
    nil))

(defn builtin-http-server-is-running
  [handle]
  (some? @(:server handle)))

(defn builtin-regex-validate
  [pattern flags]
  (regex-types/regex-validate pattern flags))

(defn builtin-regex-matches
  [pattern flags text]
  (regex-types/regex-matches? pattern flags text))

(defn builtin-regex-find
  [pattern flags text]
  (regex-types/regex-find pattern flags text))

(defn builtin-regex-find-all
  [pattern flags text]
  (regex-types/regex-find-all pattern flags text))

(defn builtin-regex-replace
  [pattern flags text replacement]
  (regex-types/regex-replace pattern flags text replacement))

(defn builtin-regex-split
  [pattern flags text]
  (regex-types/regex-split pattern flags text))

(defn builtin-datetime-now
  []
  (dt/datetime-now))

(defn builtin-datetime-from-epoch-millis
  [ms]
  (dt/datetime-from-epoch-millis ms))

(defn builtin-datetime-parse-iso
  [text]
  (dt/datetime-parse-iso text))

(defn builtin-datetime-make
  [year month day hour minute second]
  (dt/datetime-make year month day hour minute second))

(defn builtin-datetime-make-from-array
  [args]
  (apply builtin-datetime-make args))

(defn builtin-datetime-year
  [epoch-ms]
  (dt/datetime-year epoch-ms))

(defn builtin-datetime-month
  [epoch-ms]
  (dt/datetime-month epoch-ms))

(defn builtin-datetime-day
  [epoch-ms]
  (dt/datetime-day epoch-ms))

(defn builtin-datetime-weekday
  [epoch-ms]
  (dt/datetime-weekday epoch-ms))

(defn builtin-datetime-day-of-year
  [epoch-ms]
  (dt/datetime-day-of-year epoch-ms))

(defn builtin-datetime-hour
  [epoch-ms]
  (dt/datetime-hour epoch-ms))

(defn builtin-datetime-minute
  [epoch-ms]
  (dt/datetime-minute epoch-ms))

(defn builtin-datetime-second
  [epoch-ms]
  (dt/datetime-second epoch-ms))

(defn builtin-datetime-epoch-millis
  [epoch-ms]
  (dt/datetime-epoch-millis epoch-ms))

(defn builtin-datetime-add-millis
  [epoch-ms delta-ms]
  (dt/datetime-add-millis epoch-ms delta-ms))

(defn builtin-datetime-diff-millis
  [left-ms right-ms]
  (dt/datetime-diff-millis left-ms right-ms))

(defn builtin-datetime-truncate-to-day
  [epoch-ms]
  (dt/datetime-truncate-to-day epoch-ms))

(defn builtin-datetime-truncate-to-hour
  [epoch-ms]
  (dt/datetime-truncate-to-hour epoch-ms))

(defn builtin-datetime-format-iso
  [epoch-ms]
  (dt/datetime-format-iso epoch-ms))

(defn builtin-path-exists
  [path]
  (rt/path-exists? (str path)))

(defn builtin-path-is-file
  [path]
  (rt/path-is-file? (str path)))

(defn builtin-path-is-directory
  [path]
  (rt/path-is-directory? (str path)))

(defn builtin-path-name
  [path]
  (rt/path-name (str path)))

(defn builtin-path-extension
  [path]
  (rt/path-extension (str path)))

(defn builtin-path-name-without-extension
  [path]
  (rt/path-name-without-extension (str path)))

(defn builtin-path-absolute
  [path]
  (str (rt/path-absolute (str path))))

(defn builtin-path-normalize
  [path]
  (str (rt/path-normalize (str path))))

(defn builtin-path-size
  [path]
  (rt/path-size (str path)))

(defn builtin-path-modified-time
  [path]
  (rt/path-modified-time (str path)))

(defn builtin-path-parent
  [path]
  (rt/path-parent (str path)))

(defn builtin-path-child
  [path child-name]
  (rt/path-child (str path) (str child-name)))

(defn builtin-path-create-file
  [path]
  (rt/path-create-file (str path)))

(defn builtin-path-create-directory
  [path]
  (rt/path-create-directory (str path)))

(defn builtin-path-create-directories
  [path]
  (rt/path-create-directories (str path)))

(defn builtin-path-delete
  [path]
  (rt/path-delete (str path)))

(defn builtin-path-delete-tree
  [path]
  (rt/path-delete-tree (str path)))

(defn builtin-path-copy
  [source-path target-path]
  (rt/path-copy (str source-path) (str target-path)))

(defn builtin-path-move
  [source-path target-path]
  (rt/path-move (str source-path) (str target-path)))

(defn builtin-path-read-text
  [path]
  (rt/path-read-text (str path)))

(defn builtin-path-write-text
  [path text]
  (rt/path-write-text (str path) (str text)))

(defn builtin-path-append-text
  [path text]
  (rt/path-append-text (str path) (str text)))

(defn builtin-path-list
  [path]
  (rt/path-list (str path)))

(defn builtin-text-file-open-read
  [path]
  (rt/text-file-open-read (str path)))

(defn builtin-text-file-open-write
  [path]
  (rt/text-file-open-write (str path)))

(defn builtin-text-file-open-append
  [path]
  (rt/text-file-open-append (str path)))

(defn builtin-text-file-read-line
  [handle]
  (rt/text-file-read-line handle))

(defn builtin-text-file-write
  [handle text]
  (rt/text-file-write handle (str text)))

(defn builtin-text-file-close
  [handle]
  (rt/text-file-close handle))

(defn builtin-binary-file-open-read
  [path]
  (rt/binary-file-open-read (str path)))

(defn builtin-binary-file-open-write
  [path]
  (rt/binary-file-open-write (str path)))

(defn builtin-binary-file-open-append
  [path]
  (rt/binary-file-open-append (str path)))

(defn builtin-binary-file-read-all
  [handle]
  (rt/binary-file-read-all handle))

(defn builtin-binary-file-read
  [handle count]
  (rt/binary-file-read handle count))

(defn builtin-binary-file-write
  [handle values]
  (rt/binary-file-write handle values))

(defn builtin-binary-file-position
  [handle]
  (rt/binary-file-position handle))

(defn builtin-binary-file-seek
  [handle offset]
  (rt/binary-file-seek handle offset))

(defn builtin-binary-file-close
  [handle]
  (rt/binary-file-close handle))

(defn builtin-byte-array-make
  [a]
  (rt/byte-array-make a))

(defn builtin-byte-array-from-array
  [a]
  (rt/byte-array-from-array a))

(defn builtin-byte-array-from-java
  [a]
  (rt/byte-array-from-java a))

(defn builtin-byte-array-length
  [a]
  (rt/byte-array-length a))

(defn builtin-byte-array-get
  [a b]
  (rt/byte-array-get a b))

(defn builtin-byte-array-set
  [a b c]
  (rt/byte-array-set! a b c))

(defn builtin-byte-array-slice
  [a b c]
  (rt/byte-array-slice a b c))

(defn builtin-byte-array-to-array
  [a]
  (rt/byte-array-to-array a))

(defn builtin-byte-array-equals
  [a b]
  (rt/byte-array-equals a b))

(defn builtin-byte-array-hash
  [a]
  (rt/byte-array-hash a))

(defn builtin-byte-array-fill
  [a b]
  (rt/byte-array-fill! a b))

(defn builtin-byte-array-concat
  [a b]
  (rt/byte-array-concat a b))

(defn builtin-byte-array-copy-into
  [a b c]
  (rt/byte-array-copy-into! a b c))

(defn builtin-byte-array-index-of
  [a b]
  (rt/byte-array-index-of a b))

(defn builtin-byte-array-compare
  [a b]
  (rt/byte-array-compare a b))

(defn builtin-byte-array-to-hex
  [a]
  (rt/byte-array-to-hex a))

(defn builtin-byte-array-to-utf8
  [a]
  (rt/byte-array-to-utf8 a))

(def-builtin-method-wrapper builtin-method-any-to-string "to_string")
(def-builtin-method-wrapper builtin-method-any-equals "equals")
(def-builtin-method-wrapper builtin-method-any-hash "hash")
(def-builtin-method-wrapper builtin-method-any-clone "clone")
(def-builtin-method-wrapper builtin-method-any-cursor "cursor")
(def-builtin-method-wrapper builtin-method-any-start "start")
(def-builtin-method-wrapper builtin-method-any-item "item")
(def-builtin-method-wrapper builtin-method-any-next "next")
(def-builtin-method-wrapper builtin-method-any-at-end "at_end")
(def-builtin-method-wrapper builtin-method-any-get "get")
(def-builtin-method-wrapper builtin-method-any-length "length")

(def-builtin-method-wrapper builtin-method-map_entry-key "key")
(def-builtin-method-wrapper builtin-method-map_entry-value "value")
(def-builtin-method-wrapper builtin-method-map_entry-get "get")
(def-builtin-method-wrapper builtin-method-map_entry-to-string "to_string")
(def-builtin-method-wrapper builtin-method-map_entry-equals "equals")

(def-builtin-method-wrapper builtin-method-comparable-compare "compare")

(def-builtin-method-wrapper builtin-method-integer-to-string "to_string")
(def-builtin-method-wrapper builtin-method-integer-to-integer "to_integer")
(def-builtin-method-wrapper builtin-method-integer-to-integer64 "to_integer64")
(def-builtin-method-wrapper builtin-method-integer-to-real "to_real")
(def-builtin-method-wrapper builtin-method-integer-abs "abs")
(def-builtin-method-wrapper builtin-method-integer-min "min")
(def-builtin-method-wrapper builtin-method-integer-max "max")
(def-builtin-method-wrapper builtin-method-integer-pick "pick")
(def-builtin-method-wrapper builtin-method-integer-compare "compare")
(def-builtin-method-wrapper builtin-method-integer-hash "hash")
(def-builtin-method-wrapper builtin-method-integer-plus "plus")
(def-builtin-method-wrapper builtin-method-integer-minus "minus")
(def-builtin-method-wrapper builtin-method-integer-times "times")
(def-builtin-method-wrapper builtin-method-integer-divided-by "divided_by")
(def-builtin-method-wrapper builtin-method-integer-equals "equals")
(def-builtin-method-wrapper builtin-method-integer-not-equals "not_equals")
(def-builtin-method-wrapper builtin-method-integer-less-than "less_than")
(def-builtin-method-wrapper builtin-method-integer-less-than-or-equal "less_than_or_equal")
(def-builtin-method-wrapper builtin-method-integer-greater-than "greater_than")
(def-builtin-method-wrapper builtin-method-integer-greater-than-or-equal "greater_than_or_equal")
(def-builtin-method-wrapper builtin-method-integer-to-char "to_char")
(def-builtin-method-wrapper builtin-method-integer-to-byte "to_byte")
(def-builtin-method-wrapper builtin-method-integer-to-integer16 "to_integer16")
(def-builtin-method-wrapper builtin-method-integer-to-integer32 "to_integer32")
(def-builtin-method-wrapper builtin-method-byte-to-integer16 "to_integer16")
(def-builtin-method-wrapper builtin-method-byte-to-integer32 "to_integer32")

(def-builtin-method-wrapper builtin-method-integer16-to-string "to_string")
(def-builtin-method-wrapper builtin-method-integer16-to-integer "to_integer")
(def-builtin-method-wrapper builtin-method-integer16-to-byte "to_byte")
(def-builtin-method-wrapper builtin-method-integer16-to-integer16 "to_integer16")
(def-builtin-method-wrapper builtin-method-integer16-to-integer32 "to_integer32")
(def-builtin-method-wrapper builtin-method-integer16-abs "abs")
(def-builtin-method-wrapper builtin-method-integer16-min "min")
(def-builtin-method-wrapper builtin-method-integer16-max "max")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-left-shift "bitwise_left_shift")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-right-shift "bitwise_right_shift")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-logical-right-shift "bitwise_logical_right_shift")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-rotate-left "bitwise_rotate_left")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-rotate-right "bitwise_rotate_right")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-is-set "bitwise_is_set")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-set "bitwise_set")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-unset "bitwise_unset")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-and "bitwise_and")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-or "bitwise_or")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-xor "bitwise_xor")
(def-builtin-method-wrapper builtin-method-integer16-bitwise-not "bitwise_not")
(def-builtin-method-wrapper builtin-method-integer16-equals "equals")
(def-builtin-method-wrapper builtin-method-integer16-not-equals "not_equals")
(def-builtin-method-wrapper builtin-method-integer16-compare "compare")
(def-builtin-method-wrapper builtin-method-integer16-hash "hash")

(def-builtin-method-wrapper builtin-method-integer32-to-string "to_string")
(def-builtin-method-wrapper builtin-method-integer32-to-integer "to_integer")
(def-builtin-method-wrapper builtin-method-integer32-to-byte "to_byte")
(def-builtin-method-wrapper builtin-method-integer32-to-integer16 "to_integer16")
(def-builtin-method-wrapper builtin-method-integer32-to-integer32 "to_integer32")
(def-builtin-method-wrapper builtin-method-integer32-abs "abs")
(def-builtin-method-wrapper builtin-method-integer32-min "min")
(def-builtin-method-wrapper builtin-method-integer32-max "max")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-left-shift "bitwise_left_shift")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-right-shift "bitwise_right_shift")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-logical-right-shift "bitwise_logical_right_shift")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-rotate-left "bitwise_rotate_left")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-rotate-right "bitwise_rotate_right")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-is-set "bitwise_is_set")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-set "bitwise_set")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-unset "bitwise_unset")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-and "bitwise_and")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-or "bitwise_or")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-xor "bitwise_xor")
(def-builtin-method-wrapper builtin-method-integer32-bitwise-not "bitwise_not")
(def-builtin-method-wrapper builtin-method-integer32-equals "equals")
(def-builtin-method-wrapper builtin-method-integer32-not-equals "not_equals")
(def-builtin-method-wrapper builtin-method-integer32-compare "compare")
(def-builtin-method-wrapper builtin-method-integer32-hash "hash")


(def-builtin-method-wrapper builtin-method-byte-to-string "to_string")
(def-builtin-method-wrapper builtin-method-byte-to-integer "to_integer")
(def-builtin-method-wrapper builtin-method-byte-to-char "to_char")
(def-builtin-method-wrapper builtin-method-byte-to-hex "to_hex")
(def-builtin-method-wrapper builtin-method-byte-min "min")
(def-builtin-method-wrapper builtin-method-byte-max "max")
(def-builtin-method-wrapper builtin-method-byte-bitwise-left-shift "bitwise_left_shift")
(def-builtin-method-wrapper builtin-method-byte-bitwise-right-shift "bitwise_right_shift")
(def-builtin-method-wrapper builtin-method-byte-bitwise-logical-right-shift "bitwise_logical_right_shift")
(def-builtin-method-wrapper builtin-method-byte-bitwise-rotate-left "bitwise_rotate_left")
(def-builtin-method-wrapper builtin-method-byte-bitwise-rotate-right "bitwise_rotate_right")
(def-builtin-method-wrapper builtin-method-byte-bitwise-is-set "bitwise_is_set")
(def-builtin-method-wrapper builtin-method-byte-bitwise-set "bitwise_set")
(def-builtin-method-wrapper builtin-method-byte-bitwise-unset "bitwise_unset")
(def-builtin-method-wrapper builtin-method-byte-bitwise-and "bitwise_and")
(def-builtin-method-wrapper builtin-method-byte-bitwise-or "bitwise_or")
(def-builtin-method-wrapper builtin-method-byte-bitwise-xor "bitwise_xor")
(def-builtin-method-wrapper builtin-method-byte-bitwise-not "bitwise_not")
(def-builtin-method-wrapper builtin-method-byte-equals "equals")
(def-builtin-method-wrapper builtin-method-byte-not-equals "not_equals")
(def-builtin-method-wrapper builtin-method-byte-compare "compare")
(def-builtin-method-wrapper builtin-method-byte-hash "hash")

(def-builtin-method-wrapper builtin-method-real-to-string "to_string")
(def-builtin-method-wrapper builtin-method-real-to-integer "to_integer")
(def-builtin-method-wrapper builtin-method-real-to-integer64 "to_integer64")
(def-builtin-method-wrapper builtin-method-real-to-real "to_real")
(def-builtin-method-wrapper builtin-method-real-abs "abs")
(def-builtin-method-wrapper builtin-method-real-min "min")
(def-builtin-method-wrapper builtin-method-real-max "max")
(def-builtin-method-wrapper builtin-method-real-round "round")
(def-builtin-method-wrapper builtin-method-real-floor "floor")
(def-builtin-method-wrapper builtin-method-real-ceiling "ceiling")
(def-builtin-method-wrapper builtin-method-real-truncate "truncate")
(def-builtin-method-wrapper builtin-method-real-sqrt "sqrt")
(def-builtin-method-wrapper builtin-method-real-cbrt "cbrt")
(def-builtin-method-wrapper builtin-method-real-exp "exp")
(def-builtin-method-wrapper builtin-method-real-log "log")
(def-builtin-method-wrapper builtin-method-real-log10 "log10")
(def-builtin-method-wrapper builtin-method-real-log2 "log2")
(def-builtin-method-wrapper builtin-method-real-power "power")
(def-builtin-method-wrapper builtin-method-real-sin "sin")
(def-builtin-method-wrapper builtin-method-real-cos "cos")
(def-builtin-method-wrapper builtin-method-real-tan "tan")
(def-builtin-method-wrapper builtin-method-real-asin "asin")
(def-builtin-method-wrapper builtin-method-real-acos "acos")
(def-builtin-method-wrapper builtin-method-real-atan "atan")
(def-builtin-method-wrapper builtin-method-real-atan2 "atan2")
(def-builtin-method-wrapper builtin-method-real-to-radians "to_radians")
(def-builtin-method-wrapper builtin-method-real-to-degrees "to_degrees")
(def-builtin-method-wrapper builtin-method-real-sign "sign")
(def-builtin-method-wrapper builtin-method-real-clamp "clamp")
(def-builtin-method-wrapper builtin-method-real-hypot "hypot")
(def-builtin-method-wrapper builtin-method-real-to-fixed "to_fixed")
(def-builtin-method-wrapper builtin-method-real-is-nan "is_nan")
(def-builtin-method-wrapper builtin-method-real-is-infinite "is_infinite")
(def-builtin-method-wrapper builtin-method-real-is-finite "is_finite")
(def-builtin-method-wrapper builtin-method-real-compare "compare")
(def-builtin-method-wrapper builtin-method-real-hash "hash")
(def-builtin-method-wrapper builtin-method-real-plus "plus")
(def-builtin-method-wrapper builtin-method-real-minus "minus")
(def-builtin-method-wrapper builtin-method-real-times "times")
(def-builtin-method-wrapper builtin-method-real-divided-by "divided_by")
(def-builtin-method-wrapper builtin-method-real-equals "equals")
(def-builtin-method-wrapper builtin-method-real-not-equals "not_equals")
(def-builtin-method-wrapper builtin-method-real-less-than "less_than")
(def-builtin-method-wrapper builtin-method-real-less-than-or-equal "less_than_or_equal")
(def-builtin-method-wrapper builtin-method-real-greater-than "greater_than")
(def-builtin-method-wrapper builtin-method-real-greater-than-or-equal "greater_than_or_equal")

(def-builtin-method-wrapper builtin-method-char-to-string "to_string")
(def-builtin-method-wrapper builtin-method-char-to-upper "to_upper")
(def-builtin-method-wrapper builtin-method-char-to-lower "to_lower")
(def-builtin-method-wrapper builtin-method-char-to-integer "to_integer")
(def-builtin-method-wrapper builtin-method-char-compare "compare")
(def-builtin-method-wrapper builtin-method-char-hash "hash")

(def-builtin-method-wrapper builtin-method-boolean-to-string "to_string")
(def-builtin-method-wrapper builtin-method-boolean-and "and")
(def-builtin-method-wrapper builtin-method-boolean-or "or")
(def-builtin-method-wrapper builtin-method-boolean-not "not")
(def-builtin-method-wrapper builtin-method-boolean-equals "equals")
(def-builtin-method-wrapper builtin-method-boolean-not-equals "not_equals")
(def-builtin-method-wrapper builtin-method-boolean-compare "compare")
(def-builtin-method-wrapper builtin-method-boolean-hash "hash")

(defn builtin-method-string-length
  "`s.length`, called directly rather than through call-builtin-method's
   lookup by type and name: an `across` over a String tests it per element."
  [^String s & _]
  (long (.length s)))
(def-builtin-method-wrapper builtin-method-string-index-of "index_of")
(def-builtin-method-wrapper builtin-method-string-substring "substring")
(def-builtin-method-wrapper builtin-method-string-to-upper "to_upper")
(def-builtin-method-wrapper builtin-method-string-to-lower "to_lower")
(def-builtin-method-wrapper builtin-method-string-to-integer "to_integer")
(def-builtin-method-wrapper builtin-method-string-to-integer64 "to_integer64")
(def-builtin-method-wrapper builtin-method-string-to-real "to_real")
(def-builtin-method-wrapper builtin-method-string-contains "contains")
(def-builtin-method-wrapper builtin-method-string-starts-with "starts_with")
(def-builtin-method-wrapper builtin-method-string-ends-with "ends_with")
(def-builtin-method-wrapper builtin-method-string-trim "trim")
(def-builtin-method-wrapper builtin-method-string-replace "replace")
(def-builtin-method-wrapper builtin-method-string-pad-end "pad_end")
(def-builtin-method-wrapper builtin-method-string-pad-start "pad_start")
(def-builtin-method-wrapper builtin-method-string-replicate "replicate")
(defn builtin-method-string-char-at
  "`s.char_at(i)`, called directly (see builtin-method-string-length). Out of
   range is an Index_Out_Of_Bounds error, as the builtin's is."
  [^String s idx & _]
  (let [i (long idx)
        n (.length s)]
    (if (and (<= 0 i) (< i n))
      (.charAt s (int i))
      (throw (ex-info (str "Index " i " out of bounds for length " n)
                      {:builtin-kind "Index_Out_Of_Bounds"})))))
(def-builtin-method-wrapper builtin-method-string-chars "chars")
(def-builtin-method-wrapper builtin-method-string-to-bytes "to_bytes")
(def-builtin-method-wrapper builtin-method-string-compare "compare")
(def-builtin-method-wrapper builtin-method-string-hash "hash")
(def-builtin-method-wrapper builtin-method-string-join "join")
(defn builtin-method-string-split
  [target delim]
  (->java-array-list
   (bi/call-builtin-method nil target target "split" [delim])))
(def-builtin-method-wrapper builtin-method-string-to-string "to_string")
(def-builtin-method-wrapper builtin-method-string-equals "equals")
(def-builtin-method-wrapper builtin-method-string-not-equals "not_equals")
(def-builtin-method-wrapper builtin-method-string-less-than "less_than")
(def-builtin-method-wrapper builtin-method-string-less-than-or-equal "less_than_or_equal")
(def-builtin-method-wrapper builtin-method-string-greater-than "greater_than")
(def-builtin-method-wrapper builtin-method-string-greater-than-or-equal "greater_than_or_equal")
(def-builtin-method-wrapper builtin-method-string-plus "plus")
(def-builtin-method-wrapper builtin-method-string-cursor "cursor")

(def-builtin-method-wrapper builtin-method-cursor-start "start")
(def-builtin-method-wrapper builtin-method-cursor-item "item")
(def-builtin-method-wrapper builtin-method-cursor-next "next")
(def-builtin-method-wrapper builtin-method-cursor-at-end "at_end")

(def-builtin-method-wrapper builtin-method-console-print "print")
(def-builtin-method-wrapper builtin-method-console-print-line "print_line")
(def-builtin-method-wrapper builtin-method-console-read-line "read_line")
(def-builtin-method-wrapper builtin-method-console-error "error")
(def-builtin-method-wrapper builtin-method-console-new-line "new_line")
(def-builtin-method-wrapper builtin-method-console-flush "flush")
(def-builtin-method-wrapper builtin-method-console-read-integer "read_integer")
(def-builtin-method-wrapper builtin-method-console-read-real "read_real")

(def-builtin-method-wrapper builtin-method-program-arguments "arguments")
(def-builtin-method-wrapper builtin-method-program-env "env")
(def-builtin-method-wrapper builtin-method-program-has-env "has_env")
(def-builtin-method-wrapper builtin-method-program-pid "pid")
(def-builtin-method-wrapper builtin-method-program-working-directory "working_directory")
(def-builtin-method-wrapper builtin-method-program-exit "exit")

(def-builtin-method-wrapper builtin-method-process-getenv "getenv")
(def-builtin-method-wrapper builtin-method-process-setenv "setenv")
(def-builtin-method-wrapper builtin-method-process-command-line "command_line")
(def-builtin-method-wrapper builtin-method-process-is-self "is_self")
(def-builtin-method-wrapper builtin-method-process-is-child "is_child")
(def-builtin-method-wrapper builtin-method-process-set-working-directory "set_working_directory")
(def-builtin-method-wrapper builtin-method-process-set-redirect-error-to-output "set_redirect_error_to_output")
(def-builtin-method-wrapper builtin-method-process-start "start")
(def-builtin-method-wrapper builtin-method-process-is-started "is_started")
(def-builtin-method-wrapper builtin-method-process-is-alive "is_alive")
(def-builtin-method-wrapper builtin-method-process-pid "pid")
(def-builtin-method-wrapper builtin-method-process-wait "wait")
(def-builtin-method-wrapper builtin-method-process-exit-code "exit_code")
(def-builtin-method-wrapper builtin-method-process-terminate "terminate")
(def-builtin-method-wrapper builtin-method-process-kill "kill")
(def-builtin-method-wrapper builtin-method-process-write "write")
(def-builtin-method-wrapper builtin-method-process-write-line "write_line")
(def-builtin-method-wrapper builtin-method-process-close-stdin "close_stdin")
(def-builtin-method-wrapper builtin-method-process-read-line "read_line")
(def-builtin-method-wrapper builtin-method-process-read-all "read_all")
(def-builtin-method-wrapper builtin-method-process-read-error-line "read_error_line")
(def-builtin-method-wrapper builtin-method-process-read-error-all "read_error_all")
(def-builtin-method-wrapper builtin-method-process-to-string "to_string")

(defn deep-equals
  [a b]
  (value/nex-deep-equals? interp-object? a b))

(declare value-equals)

(defn- compiled-object?
  "True when `value` is a compiled Nex object — an instance of a generated class,
   not an interpreter-shaped object map or a builtin. Only these need reflective
   structural comparison; everything else is handled by `deep-equals`."
  [state value]
  (and (some? value)
       (not (interp-object? value))
       (some? (compiled-runtime-class-name state value))))

(defn- object-like?
  "True when value is a Nex object of either model: an interpreter object map or
   a compiled generated-class instance."
  [state value]
  (or (interp-object? value)
      (compiled-object? state value)))

(defn- structural-equals
  "State-aware structural equality spanning both object models. Two objects
   — compiled, interpreter, or one of each — are equal when they share a runtime
   class and every effective field is `value-equals` (so nested `equals`
   overrides and nested objects are honoured, matching the interpreter's
   `nex-deep-equals?`). `get-user-field` reads fields from either model, so a
   compiled object compares equal to an interpreter-built one of the same class
   and field values — the case a class invariant hits when it compares a stored
   field to a freshly constructed value. Everything else falls back to the
   structural walk."
  [state a b]
  (if (and (object-like? state a) (object-like? state b))
    (let [na (runtime-type-name state a)
          nb (runtime-type-name state b)]
      (and (some? na) (= na nb)
           (let [class-def (class-def-by-name state na)
                 field-names (collect-effective-field-names state class-def)]
             (every? (fn [field-name]
                       (value-equals state
                                     (get-user-field a field-name)
                                     (get-user-field b field-name)))
                     field-names))))
    (deep-equals a b)))

(defn value-equals
  "Value equality for the `=`/`/=` operators. Honours a class's `equals` override
   (a compiled user method, dispatched reflectively) when present; otherwise falls
   back to structural comparison — matching the interpreter."
  [state a b]
  (if (and (some? a)
           (find-user-method a (lowered-instance-method-name "equals" 1)))
    (boolean (invoke-user-method state a "equals" [b]))
    (structural-equals state a b)))

(defn any-equals
  "The `equals` that the Any protocol gives every value, for a receiver whose
   *static* type declares none. `value-equals` honours an `equals` override
   found on the runtime value (which, under dynamic dispatch, may be a subclass
   of the static type) and otherwise compares structurally — matching both the
   interpreter and the `=` operator."
  [state a b]
  (boolean (value-equals state a b)))

;; ---------------------------------------------------------------------------
;; Java equality for compiled objects.
;;
;; Set and Map are a java.util.LinkedHashSet and a java.util.HashMap here, so
;; membership, dedup and key lookup go through Object.equals/hashCode and
;; nothing else — no hook can reach inside `.add` or `.put`. Generated classes
;; therefore emit both (see emit-object-equals!/emit-object-hash-code!), and
;; these are what they call.
;;
;; The semantics are the interpreter's, which is the definition of correct here:
;; a class's `equals`/`hash` override when it has one, structural comparison and
;; hashing otherwise (cf. `value-equality-fn`/`value-hash-fn` in nex.interpreter,
;; bound over a run there for the same reason). Getting these right also makes
;; the defaults in nex.types.runtime correct for free: `value-equals?` falls back
;; to `=` and `value-hash` to `nex-hash-code`, which are exactly these methods.
;; ---------------------------------------------------------------------------

(defn- structural-hash-of-compiled-object
  "A structural hash over the object's effective fields, agreeing with
   `structural-equals`: it hashes the same fields that comparison reads, so
   values that compare equal hash equal. The shape mirrors
   `bi/nex-structural-hash` — class name, then each field name paired with its
   hash, in sorted order — so an interpreter-built object and a compiled one of
   the same class and field values land in the same bucket, which is the case
   `structural-equals` already goes out of its way to support."
  [state v]
  (let [class-name (runtime-type-name state v)
        class-def (class-def-by-name state class-name)
        field-names (if class-def (collect-effective-field-names state class-def) [])]
    (hash (into [class-name]
                (map (fn [field-name]
                       [field-name (bi/nex-structural-hash (get-user-field v field-name))])
                     (sort field-names))))))

(defn object-equals
  "Object.equals for a compiled object, in Nex terms. STATE is the object's
   `__state__`; when it is nil the object was built somewhere that had no state
   to give it, and there is no way to reach an override — fall back to identity,
   which is what this backend did before these methods existed, and which
   `object-hash-code` agrees with.

   The non-object cases are answered here rather than delegated, and must be:
   `value-equals` ends at `deep-equals` for any pair that is not two objects,
   `deep-equals` ends at Clojure `=`, and `=` on a compiled object now calls
   *this* — so handing it `(obj, nil)` would loop until the stack ran out. It is
   a short cycle and an easy one to reintroduce; the guards below are load-
   bearing, not defensive. Answering directly is also what the semantics say:
   an object is never equal to a non-object, void included."
  [state a b]
  (Boolean/valueOf
   (cond
     (identical? a b) true
     (nil? state) false
     (nil? b) false
     (not (object-like? state b)) false
     :else (boolean (value-equals state a b)))))

(defn object-hash-code
  "Object.hashCode for a compiled object, consistent with `object-equals`: a
   class's `hash` override when present, else the structural hash over the same
   fields comparison uses.

   A class that overrides `equals` without `hash` gets its override honoured
   here and a structural hash — the pairing the type checker warns about, and
   the same combination the interpreter produces, so the two backends misbehave
   identically rather than differently."
  [state v]
  (Integer/valueOf
   (int (if (nil? state)
          (System/identityHashCode v)
          (if-let [[_ ^Method m] (find-user-method v (lowered-instance-method-name "hash" 0))]
            (rt/nex-int->number (invoke-user-method state v "hash" []))
            (structural-hash-of-compiled-object state v))))))

(defn any-hash
  "The `hash` that the Any protocol gives every value, for a receiver whose
   *static* type declares none, agreeing with `any-equals`: a compiled object's
   `hash` override or structural hash (object-hash-code, the same hash a Map or
   Set places it with), a collection's element-wise hash, a scalar's own `hash`.
   Resolved on the runtime value, as `any-equals` is."
  [state v]
  (long
   (cond
     (nil? v) 0
     (compiled-object? state v) (object-hash-code state v)
     (interp-object? v) (bi/nex-structural-hash v)
     ;; A compiled Array/Map/Set; its hashCode reaches each element's,
     ;; object-hash-code included, so equal collections hash equal.
     (or (instance? java.util.Collection v) (instance? java.util.Map v)) (.hashCode ^Object v)
     :else (bi/call-builtin-method nil v v "hash" []))))

(defn- scalar-identity-value?
  [v]
  (or (nil? v)
      (string? v)
      (number? v)
      (boolean? v)
      (char? v)))

(defn identity-equals
  [a b]
  (cond
    ;; On scalars == coincides with = (spec §5.1), so a mixed Integer/Real
    ;; pair compares by numeric value: 5 == 5.0 is true, like the interpreter.
    (and (rt/nex-numeric? a) (rt/nex-numeric? b))
    (rt/nex-numeric-equals? a b)

    (and (scalar-identity-value? a)
         (scalar-identity-value? b))
    (= a b)

    :else
    (identical? a b)))

(defn clone-value
  [value]
  (value/nex-clone-value interp-object? bi/make-object value))

;; ---------------------------------------------------------------------------
;; Deep copy, in Nex terms, of values that may hold compiled objects.
;;
;; `clone-value` above is the interpreter's (nex.types.value/nex-clone-value):
;; it copies interpreter object maps and collections but does not recognise an
;; instance of a generated class, which it returned as is -- so `clone` on a
;; compiled object, or on a collection holding one, shared it with the original.
;; These copy compiled objects too, with the interpreter's semantics: the value
;; and everything it holds are copied structurally, and only a top-level `clone`
;; override is honoured (any-clone). Unlike the interpreter's, they keep shared
;; and cyclic references shared and cyclic in the copy instead of recursing
;; without end.
;; ---------------------------------------------------------------------------

(declare clone-deep)

(defn- copy-compiled-fields!
  "Copy SRC's fields into DST, a fresh instance of the same generated class:
   each field's value deep-copied, each parent part (`_parent_X`, which DST's
   own <init> has already built and linked) copied into in place, and the
   bookkeeping links (`__outer__`, `__container__`, `__state__`) left as DST's."
  [state src dst memo]
  (doseq [^Field f (.getDeclaredFields (.getClass ^Object src))
          :let [field-name (.getName f)]
          :when (and (not (java.lang.reflect.Modifier/isStatic (.getModifiers f)))
                     (not (#{"__outer__" "__container__" "__state__"} field-name)))]
    (.setAccessible f true)
    (if (str/starts-with? field-name "_parent_")
      (let [src-part (.get f src)
            dst-part (.get f dst)]
        (when (and src-part dst-part)
          (copy-compiled-fields! state src-part dst-part memo)))
      (.set f dst (clone-deep state (.get f src) memo)))))

(defn- clone-compiled-object
  "A copy of compiled object SRC, made through its class's no-argument <init>
   (which sets field defaults and builds the parent parts, and runs no user
   constructor), registered in MEMO before its fields are copied so a cycle
   back to SRC reaches the copy."
  [state ^Object src ^java.util.IdentityHashMap memo]
  (let [^Class cls (.getClass src)
        dst (.newInstance (.getDeclaredConstructor cls (into-array Class []))
                          (object-array 0))]
    (when-let [^Field state-field (reflected-field cls "__state__")]
      (.set state-field dst (or (.get state-field src) state)))
    (.put memo src dst)
    (copy-compiled-fields! state src dst memo)
    dst))

(defn- clone-deep
  [state v ^java.util.IdentityHashMap memo]
  (cond
    (nil? v) nil
    (.containsKey memo v) (.get memo v)
    (compiled-object? state v) (clone-compiled-object state v memo)

    (instance? java.util.ArrayList v)
    (let [out (java.util.ArrayList.)]
      (.put memo v out)
      (doseq [x v] (.add out (clone-deep state x memo)))
      out)

    (instance? java.util.LinkedHashSet v)
    (let [out (java.util.LinkedHashSet.)]
      (.put memo v out)
      (doseq [x v] (.add out (clone-deep state x memo)))
      out)

    (instance? java.util.HashMap v)
    (let [out (java.util.LinkedHashMap.)]
      (.put memo v out)
      (doseq [^java.util.Map$Entry e (.entrySet ^java.util.Map v)]
        (.put out (clone-deep state (.getKey e) memo) (clone-deep state (.getValue e) memo)))
      out)

    ;; Interpreter objects, portable collections, scalars, builtins.
    :else (clone-value v)))

(defn deep-clone
  "A deep copy of V, compiled objects included (see above)."
  [state v]
  (clone-deep state v (java.util.IdentityHashMap.)))

(defn any-clone
  "The `clone` that the Any protocol gives every value, for a receiver whose
   *static* type declares none: the runtime value's own `clone` override when it
   has one (resolved on the value, as `any-equals` and `any-hash` are), a deep
   copy otherwise."
  [state v]
  (if (and (compiled-object? state v)
           (find-user-method v (lowered-instance-method-name "clone" 0)))
    (invoke-user-method state v "clone" [])
    (deep-clone state v)))

(defn shallow-copy-collection
  "A new, independent Array/Map container holding the SAME elements/entries —
   unlike clone-value, does not recurse into them. Used only for `old`
   postcondition snapshots (see add-old-field-snapshots in nex.lower): an
   Array (a real java.util.ArrayList) or Map (a real java.util.HashMap on
   this backend) is a genuinely mutable container, so a snapshot IR node that
   just re-reads the field's current reference into a new local slot copies
   the *reference*, not a value — an in-place `.add`/`.put` later in the same
   method body was then visible through the \"old\" snapshot too (`old
   items.length = items.length - 1` failed because both sides read the SAME,
   already-mutated ArrayList). `old`, semantically, should freeze the
   container's own membership at snapshot time, not deep-copy every element
   the way clone-value's Nex-level `.clone()` does — that would silently
   change identity for any object-valued element (`old items.get(0) = obj`
   would start comparing a freshly-cloned copy against the original,
   unrelated to the actual bug)."
  [value]
  (cond
    (instance? java.util.Map value)
    (java.util.LinkedHashMap. ^java.util.Map value)

    (instance? java.util.Collection value)
    (java.util.ArrayList. ^java.util.Collection value)

    :else value))

(defn array-contains
  [values needle]
  (boolean (some #(deep-equals % needle) values)))

(defn array-index-of
  [values needle]
  (loop [idx 0
         remaining (seq values)]
    (cond
      (nil? remaining) -1
      (deep-equals (first remaining) needle) idx
      :else (recur (inc idx) (next remaining)))))

;; The compiled JVM path represents Map as a java.util.HashMap (see
;; emit-map-literal! and the direct put/size/keys/values/remove bytecode), so these
;; helpers operate on it directly rather than via the interpreter's portable map in
;; nex.types.runtime. They still accept a portable (tagged) map defensively, in case
;; one reaches the compiled path (e.g. from the HTTP runtime). Aligning the compiled
;; backend with the interpreter's value-semantics map is a separate (Phase 4) step.

(defn- hashmap-deep-get
  [^java.util.Map m key not-found]
  (if-let [^java.util.Map$Entry e (some (fn [^java.util.Map$Entry e]
                                          (when (deep-equals (.getKey e) key) e))
                                        (.entrySet m))]
    (.getValue e)
    not-found))

(defn map-contains-key
  [values needle]
  (if (rt/nex-map? values)
    (rt/nex-map-contains-key values needle)
    (boolean
     (some #(deep-equals (.getKey ^java.util.Map$Entry %) needle)
           (.entrySet ^java.util.Map values)))))

(defn set-contains
  [values needle]
  (if (rt/nex-set? values)
    (rt/nex-set-contains values needle)
    (boolean (some #(deep-equals % needle) values))))

;; add!/remove! mutate the LinkedHashSet in place, so -- unlike contains above,
;; which tolerates a bare host java.util.Set with no rt/nex-set? tag -- they
;; delegate straight to nex.types.runtime, whose nex-set-add!/nex-set-remove!
;; already know how to mutate either representation (portable or
;; java.util.LinkedHashSet) via Nex value-equality rather than the host's
;; hashCode/equals, keeping a set built at runtime free of Nex-equal
;; duplicates the same way contains already promises.
(defn set-add!
  [s v]
  (rt/nex-set-add! s v))

(defn set-remove!
  [s v]
  (rt/nex-set-remove! s v))

(defn map-get
  ;; A stored value can legitimately be nil (e.g. a Map[String, Any]
  ;; round-tripped from JSON, where a `null` becomes a present key with a
  ;; nil value) — checking `(nil? v)` alone can't tell that apart from an
  ;; absent key, so it's `map-contains-key` that decides presence, not the
  ;; retrieved value. Mirrors the interpreter's identical fix in
  ;; nex.types.builtins's :Map "get".
  [state m key]
  (if (map-contains-key m key)
    (if (rt/nex-map? m)
      (rt/nex-map-get m key)
      (hashmap-deep-get m key nil))
    (bi/report-missing-map-key key)))

(defn map-try-get
  [state m key default]
  (if (rt/nex-map? m)
    (if (rt/nex-map-contains-key m key) (rt/nex-map-get m key) default)
    (hashmap-deep-get m key default)))

(defn- sortable-builtin-scalar-value?
  [v]
  (or (string? v)
      (number? v)
      (boolean? v)
      (char? v)))

(defn- runtime-compare-values
  [state a b]
  (cond
    (and (sortable-builtin-scalar-value? a)
         (sortable-builtin-scalar-value? b))
    (bi/call-builtin-method nil a a "compare" [b])

    :else
    (let [runtime-name (runtime-type-name state a)]
      (when-not (and (string? runtime-name)
                     (runtime-compatible-with? state runtime-name "Comparable"))
        (throw (ex-info "Array.sort requires Comparable elements"
                        {:left a :right b})))
      (let [result (if (interp-object? a)
                     (invoke-interpreter-object-method state a "compare" [b])
                     (invoke-user-method state a "compare" [b]))]
        (if (integer? result)
          result
          (throw (ex-info "Comparable.compare must return Integer"
                          {:left a :right b :result result})))))))

(defn array-slice [arr start end] (rt/nex-array-slice arr start end))
(defn array-take [arr n] (rt/nex-array-take arr n))
(defn array-drop [arr n] (rt/nex-array-drop arr n))
(defn array-take-last [arr n] (rt/nex-array-take-last arr n))
(defn array-drop-last [arr n] (rt/nex-array-drop-last arr n))

(defn array-sort
  ([state values]
   (let [out (java.util.ArrayList. values)]
     (.sort out
            (reify java.util.Comparator
              (compare [_ a b]
                (int (runtime-compare-values state a b)))))
     out))
  ([state values comparator]
   (let [out (java.util.ArrayList. values)]
     (.sort out
            (reify java.util.Comparator
              (compare [_ a b]
                (let [result (if (fn? comparator)
                               (comparator a b)
                               (invoke-function-object state comparator [a b]))]
                  (if (integer? result)
                    result
                    (throw (ex-info "Array.sort comparator must return Integer"
                                    {:left a :right b :result result})))))))
     out)))

(defn collection-cursor
  [_state _kind value]
  ;; The `cursor` builtin just wraps the collection with an index atom and
  ;; ignores its context (and produces no output), so there is no need to rebuild
  ;; an interpreter context per `across` entry — pass nil, as the to_string path
  ;; already does.
  (bi/call-builtin-method nil value value "cursor" []))

(defn array-to-string
  "state is threaded through so a nested object's to_string override is
   honored (format-value-with-state), matching print's behavior — see that
   function's docstring. Called with just `values` from any spot that has no
   state at hand, e.g. array-to-string's own use as a REPL/debug helper;
   falls back to the plain, non-recursing format-value there."
  ([values] (rt/nex-array-str format-value values))
  ([state values] (rt/nex-array-str (partial format-value-with-state state) values)))

(defn map-to-string
  ([values] (map-to-string nil values))
  ([state values]
   (let [fmt (if state (partial format-value-with-state state) format-value)]
     (if (rt/nex-map? values)
       (rt/nex-map-str fmt values)
       (str "{"
            (str/join ", " (for [^java.util.Map$Entry e (.entrySet ^java.util.Map values)]
                             (str (fmt (.getKey e)) ": " (fmt (.getValue e)))))
            "}")))))

;; The compiled JVM path represents Set as a java.util.LinkedHashSet (see
;; emit-set-literal!), so these helpers operate on it directly rather than via the
;; interpreter's portable set in nex.types.runtime. Aligning the compiled backend
;; with the interpreter's value-semantics set is a separate (Phase 4) step.

(defn set-to-string
  ([values] (set-to-string nil values))
  ([state values]
   (str "#{" (str/join ", " (map (if state (partial format-value-with-state state) format-value) values)) "}")))

(defn set-union
  [a b]
  (let [out (java.util.LinkedHashSet. ^java.util.Collection a)]
    (.addAll out ^java.util.Collection b)
    out))

(defn set-difference
  [a b]
  (let [out (java.util.LinkedHashSet. ^java.util.Collection a)]
    (.removeAll out ^java.util.Collection b)
    out))

(defn set-intersection
  [a b]
  (let [out (java.util.LinkedHashSet. ^java.util.Collection a)]
    (.retainAll out ^java.util.Collection b)
    out))

(defn set-symmetric-difference
  [a b]
  (let [out (java.util.LinkedHashSet.)]
    (doseq [v a] (when-not (.contains ^java.util.Set b v) (.add out v)))
    (doseq [v b] (when-not (.contains ^java.util.Set a v) (.add out v)))
    out))

(defn set-to-array
  [s]
  (rt/nex-array-from s))

(def ^:private invoke-builtin-dispatch
  "Exact-name -> `(fn [state args] ...)`: the primary dispatch table for
   `invoke-builtin`, covering every helper name checked with `=` in the
   original `cond`. A name with no entry here falls through to the
   \"user-method:\"/\"user-field-get:\"/\"user-field-set:\"/\"method:\"
   prefix checks, then the top-level `bi/builtins` lookup — both still
   handled directly in `invoke-builtin` below, since prefix matching and a
   final catch-all fallback don't fit a plain equality-keyed map. Every
   handler here is already defined above this point in the file (this is
   the last form in it), so none need the deferred-`fn` wrapping
   `infer-type-dispatch`'s `:call` entry needed in lower.clj."
  {"validate-object-state"
   (fn [state args] (validate-object-state state (first args) (second args)))

   "validate-object-state-on-entry"
   (fn [state args] (validate-object-state-on-entry state (first args) (second args)))

   "call-user-method"
   (fn [state args]
     (let [[lowered-name method-name target & call-args] args]
       (call-user-method state nil lowered-name method-name target (object-array call-args))))

   "shallow-copy-collection"
   (fn [_state args] (shallow-copy-collection (first args)))

   "create-console"
   (fn [_state _args] {:nex-builtin-type :Console})

   "create-program"
   (fn [_state _args] (rt/nex-program))

   "create-process"
   (fn [_state _args] (rt/nex-process-self))

   "create-process-command"
   (fn [_state args]
     (if (= 2 (count args))
       (rt/nex-process-command (first args) (second args))
       (rt/nex-process-command (first args))))

   "create-channel"
   (fn [_state args]
     (if (seq args)
       (create-channel (first args))
       (create-channel)))

   "create-array"
   (fn [_state _args] (create-array))

   "create-array-filled"
   (fn [_state args] (create-array-filled (first args) (second args)))

   "create-string-from-bytes"
   (fn [_state args] (create-string-from-bytes (first args)))

   "create-min-heap-empty"
   (fn [_state _args] (create-min-heap-empty))

   "create-set-from-array"
   (fn [_state args] (create-set-from-array (first args)))

   "create-min-heap-from-comparator"
   (fn [_state args] (create-min-heap-from-comparator (first args)))

   "create-atomic-integer"
   (fn [_state args] (create-atomic-integer (first args)))

   "create-atomic-integer64"
   (fn [_state args] (create-atomic-integer64 (first args)))

   "create-atomic-boolean"
   (fn [_state args] (create-atomic-boolean (first args)))

   "create-atomic-reference"
   (fn [_state args] (create-atomic-reference (first args)))

   "java-create-object"
   (fn [state args]
     (reflect/invoke-constructor (resolve-java-host-class state (nth args 0))
                                 (java-arg-array rt/nex->java args 1)))

   "java-call-method"
   (fn [state args]
     (rt/java->nex (reflect/invoke-method (nth args 1) (nth args 0)
                                          (java-arg-array #(bi/java-arg state %) args 2))))

   "java-get-field"
   (fn [_state args] (java-get-field (first args) (second args)))

   "java-set-field"
   (fn [_state args] (java-set-field! (first args) (second args) (nth args 2)))

   "java-call-method-resolved"
   (fn [state args] (java-call-method-resolved state (nth args 0) (nth args 1) (nth args 2)
                                               (nth args 3) (vec (drop 4 args))))

   "java-call-static-resolved"
   (fn [state args] (java-call-static-resolved state (nth args 0) (nth args 1) (nth args 2)
                                               (vec (drop 3 args))))

   "java-create-object-resolved"
   (fn [state args] (java-create-object-resolved state (nth args 0) (nth args 1)
                                                 (vec (drop 2 args))))

   "java-call-method-resolved-varargs"
   (fn [state args] (java-call-method-resolved-varargs state (nth args 0) (nth args 1) (nth args 2)
                                                       (nth args 3) (nth args 4) (vec (drop 5 args))))

   "java-call-static-resolved-varargs"
   (fn [state args] (java-call-static-resolved-varargs state (nth args 0) (nth args 1) (nth args 2)
                                                       (nth args 3) (vec (drop 4 args))))

   "java-create-object-resolved-varargs"
   (fn [state args] (java-create-object-resolved-varargs state (nth args 0) (nth args 1) (nth args 2)
                                                         (vec (drop 3 args))))

   "spawn-function-object"
   (fn [state args] (spawn-function-object state (first args)))

   "op:await-all"
   (fn [_state args] (task-await-all (first args)))

   "op:await-any"
   (fn [_state args] (task-await-any (first args)))

   "select-deadline"
   (fn [_state args] (select-deadline (first args)))

   "deadline-expired?"
   (fn [_state args] (deadline-expired? (first args)))

   "select-sleep-step"
   (fn [_state _args] (select-sleep-step!))

   "op:string-concat"
   (fn [state args] (apply str (map #(concat-string-value state %) args)))

   "op:div-int"
   (fn [_state args] (div-int (first args) (second args)))

   "op:div-long"
   (fn [_state args] (div-long (first args) (second args)))

   "op:mod-int"
   (fn [_state args] (mod-int (first args) (second args)))

   "op:mod-long"
   (fn [_state args] (mod-long (first args) (second args)))

   "op:pow-int"
   (fn [_state args] (int (rt/nex-int-pow (int (first args)) (int (second args)))))

   "op:pow-long"
   (fn [_state args] (long (rt/nex-int-pow (long (first args)) (long (second args)))))

   "op:pow-double"
   (fn [_state args] (Math/pow (double (first args)) (double (second args))))

   "op:sized->integer"
   (fn [_state args] (sized->integer (first args)))

   "make-int16"
   (fn [_state args] (make-int16 (first args)))

   "make-int32"
   (fn [_state args] (make-int32 (first args)))

   ;; The Any protocol on a receiver whose static type declares no such method.
   ;; Kept apart from the "method:" helper below: that one routes to
   ;; `bi/call-builtin-method`, whose :Any defaults are written against the
   ;; interpreter's object maps and silently misread a compiled object (an
   ;; instance of a generated class) — `equals` on two equal objects returns
   ;; false, `to_string` renders `#object[...]`. These understand both object
   ;; models.
   "any:to_string"
   (fn [state args] (any-to-string state (first args)))

   "any:equals"
   (fn [state args] (any-equals state (first args) (second args)))

   "any:hash"
   (fn [state args] (any-hash state (first args)))

   "any:clone"
   (fn [state args] (any-clone state (first args)))})

(def ^:private ^java.util.concurrent.ConcurrentHashMap member-handlers
  "helper name -> handler, for the `user-method:m`, `user-field-get:f` and
   `user-field-set:f` names that dynamic calls and field accesses on a
   non-`this` target lower to. These are among the hottest runtime calls, so
   each name is taken apart once, not on every call."
  (java.util.concurrent.ConcurrentHashMap.))

(declare invoke-user-method get-user-field set-user-field!)

(defn- member-handler
  "The handler for a member-access helper NAME, or nil if it is not one."
  [^String name]
  (or (.get member-handlers name)
      (when-let [h (cond
                     (str/starts-with? name "user-method:")
                     (let [m (subs name (count "user-method:"))]
                       (fn [state args] (invoke-user-method state (nth args 0) m (rest args))))

                     (str/starts-with? name "user-field-get:")
                     (let [f (subs name (count "user-field-get:"))]
                       (fn [_state args] (get-user-field (nth args 0) f)))

                     (str/starts-with? name "user-field-set:")
                     (let [f (subs name (count "user-field-set:"))]
                       (fn [_state args] (set-user-field! (nth args 0) f (nth args 1)))))]
        (.put member-handlers name h)
        h)))

(defn invoke-builtin
  [state name args]
  (if-let [handler (or (get invoke-builtin-dispatch name) (member-handler name))]
    (handler state args)
    ;; User method calls and field get/set (`obj.method()`, `obj.field` on a
    ;; non-`this` target) are handled above, by member-handler; they work off
    ;; `state` directly and need no interpreter context. The rebuild is
    ;; deferred into the two branches that actually use one (builtin methods
    ;; and top-level builtins).
    (cond
      ;; An Any-protocol name (`get`, `length`, ...) on an `Any` inside
      ;; `with "java"`: the value may be a real Java object with a same-named
      ;; method (`map.get(k)` on a ConcurrentHashMap, `n.get()` on an
      ;; AtomicInteger) that the Nex builtin table does not cover. Try the
      ;; builtin, then reflection -- as the interpreter's eval-call does.
      ;; Which way a class goes is remembered: finding out means raising and
      ;; catching an exception, far dearer than the call itself.
      (str/starts-with? name "builtin-or-java:")
      (let [target (first args)
            method (subs name (count "builtin-or-java:"))
            java-only (when (some? target)
                        (reflect/on-class (.getClass ^Object target) [::java-only method]
                                          #(atom false)))]
        (if (and java-only @java-only)
          (java-call-method state method target (vec (rest args)))
          (try
            (bi/call-builtin-method (builtin-ctx state) target target method (rest args))
            (catch clojure.lang.ExceptionInfo e
              (if (and (= method (:method (ex-data e)))
                       (str/starts-with? (.getMessage e) "Method not found on type"))
                (do (when java-only (reset! java-only true))
                    (java-call-method state method target (vec (rest args))))
                (throw e))))))

      (str/starts-with? name "method:")
      (let [target (first args)]
        (bi/call-builtin-method (builtin-ctx state) target target
                                (subs name (count "method:")) (rest args)))

      :else
      (let [builtin-fn (get bi/builtins name)]
        (when-not builtin-fn
          (throw (ex-info (str "Undefined compiled builtin: " name) {:name name})))
        (apply builtin-fn (builtin-ctx state) args)))))
