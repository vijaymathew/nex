(ns nex.interpreter
  (:require [clojure.string :as str]
            #?(:clj [nex.parser :as parser])
            [nex.types.runtime :as rt]
            [nex.types.concurrency :as conc]
            [nex.types.builtins :as bi]
            [nex.types.json :as json-types]
            [nex.types.http :as http]
            [nex.types.datetime :as dt]
            [nex.types.regex :as regex-types]
            [nex.types.value :as value]
            [nex.types.typeinfo :as typeinfo]
            [nex.types.bootstrap :as bootstrap])
  #?(:clj (:import [java.lang.reflect Field]
                   [java.nio.charset StandardCharsets]
                   [java.util.concurrent CompletableFuture ExecutionException Executors TimeUnit TimeoutException CancellationException]
                   [java.util.concurrent.atomic AtomicBoolean AtomicInteger AtomicLong AtomicReference])))

(declare nex-format-value)
(declare eval-node)
(declare object-equals-override)
(declare runtime-resolve-call-user-method)
(declare lookup-method-with-inheritance)
(declare lookup-class)
(declare lookup-class-if-exists)
(declare call-builtin-method)
(declare eval-node)
(declare eval-node)
(declare nex-ordering-compare)
(declare make-object)
(declare invoke-http-server-handler)
(declare nex-object?)

(defn- lowercase-filename
  [class-name]
  (-> class-name str/lower-case))

(defn- intern-filenames
  [class-name]
  (distinct [(str class-name ".nex")
             (str (lowercase-filename class-name) ".nex")]))

;;
;; Runtime type helpers imported from nex.types.*
;;

(def nex-array rt/nex-array)
(def nex-array-from rt/nex-array-from)
(def nex-array? rt/nex-array?)
(def nex-array-get rt/nex-array-get)
(def nex-array-add rt/nex-array-add)
(def nex-array-add-at rt/nex-array-add-at)
(def nex-array-set rt/nex-array-set)
(def nex-array-size rt/nex-array-size)
(def nex-array-empty? rt/nex-array-empty?)
(def nex-array-contains rt/nex-array-contains)
(def nex-array-index-of rt/nex-array-index-of)
(def nex-array-remove rt/nex-array-remove)
(def nex-array-reverse rt/nex-array-reverse)
(def nex-array-sort rt/nex-array-sort)
(def nex-array-slice rt/nex-array-slice)
(def nex-array-take rt/nex-array-take)
(def nex-array-drop rt/nex-array-drop)
(def nex-array-take-last rt/nex-array-take-last)
(def nex-array-drop-last rt/nex-array-drop-last)
(def nex-array-concat rt/nex-array-concat)
(defn nex-array-str [arr] (rt/nex-array-str nex-format-value arr))

(def nex-map rt/nex-map)
(def nex-map-from rt/nex-map-from)
(def nex-map? rt/nex-map?)
(def nex-map-get rt/nex-map-get)
(def nex-map-put rt/nex-map-put)
(def nex-map-size rt/nex-map-size)
(def nex-map-empty? rt/nex-map-empty?)
(def nex-map-contains-key rt/nex-map-contains-key)
(def nex-map-keys rt/nex-map-keys)
(def nex-map-values rt/nex-map-values)
(def nex-map-entries rt/nex-map-entries)
(def nex-map-remove rt/nex-map-remove)
(defn nex-map-str [m] (rt/nex-map-str nex-format-value m))

(def nex-set rt/nex-set)
(def nex-set-from rt/nex-set-from)
(def nex-set? rt/nex-set?)
(def nex-set-contains rt/nex-set-contains)
(def nex-set-size rt/nex-set-size)
(def nex-set-empty? rt/nex-set-empty?)
(def nex-set-union rt/nex-set-union)
(def nex-set-difference rt/nex-set-difference)
(def nex-set-intersection rt/nex-set-intersection)
(def nex-set-symmetric-difference rt/nex-set-symmetric-difference)
(def nex-set-to-array rt/nex-set-to-array)
(def nex-set-seq rt/nex-set-seq)
(defn nex-set-str [s] (rt/nex-set-str nex-format-value s))

(def nex-bitwise-left-shift rt/nex-bitwise-left-shift)
(def nex-bitwise-right-shift rt/nex-bitwise-right-shift)
(def nex-bitwise-logical-right-shift rt/nex-bitwise-logical-right-shift)
(def nex-bitwise-rotate-left rt/nex-bitwise-rotate-left)
(def nex-bitwise-rotate-right rt/nex-bitwise-rotate-right)
(def nex-bitwise-and rt/nex-bitwise-and)
(def nex-bitwise-or rt/nex-bitwise-or)
(def nex-bitwise-xor rt/nex-bitwise-xor)
(def nex-bitwise-not rt/nex-bitwise-not)
(def nex-bitwise-is-set rt/nex-bitwise-is-set)
(def nex-bitwise-set rt/nex-bitwise-set)
(def nex-bitwise-unset rt/nex-bitwise-unset)
(def nex-abs rt/nex-abs)
(def nex-round rt/nex-round)
(def nex-int-pow rt/nex-int-pow)
(def nex-integer? rt/nex-integer?)
(def ->nex-integer rt/->nex-integer)
(def nex-int->number rt/nex-int->number)
(def ->nex-real rt/->nex-real)
(def nex-numeric? rt/nex-numeric?)
(def nex-int-add rt/nex-int-add)
(def nex-int-sub rt/nex-int-sub)
(def nex-int-mul rt/nex-int-mul)
(def nex-int-neg rt/nex-int-neg)
(def nex-int-quot rt/nex-int-quot)
(def nex-int-div rt/nex-int-div)
(def nex-int-mod rt/nex-int-mod)
(def nex-real-rem rt/nex-real-rem)
(def nex-int-zero? rt/nex-int-zero?)
(def nex-numeric-compare rt/nex-numeric-compare)
(def nex-numeric-equals? rt/nex-numeric-equals?)
(def nex-numeric-lt rt/nex-numeric-lt)
(def nex-numeric-lte rt/nex-numeric-lte)
(def nex-numeric-gt rt/nex-numeric-gt)
(def nex-numeric-gte rt/nex-numeric-gte)

(defn nex-error-message
  "A clean, Nex-level message for a Throwable raised during evaluation. Host
   (Clojure/JVM or JS) exceptions whose own messages would leak interpreter
   internals — integer overflow (\"long overflow\"), number parsing (\"For input
   string\"), type casts (\"class java.lang.String cannot be cast to ...\"),
   arity — are translated to Nex-facing wording; messages from the interpreter's
   own ex-info (already user-level: contract violations, \"Method not found\",
   \"Division by zero\", etc.) pass through unchanged."
  [e]
  (let [raw (or (ex-message e) "")]
    #?(:clj
       (cond
         (instance? java.lang.ArithmeticException e)
         (cond
           (re-find #"(?i)overflow" raw)                 "Arithmetic overflow"
           (re-find #"(?i)divide by zero|/ by zero" raw) "Division by zero"
           :else raw)
         (instance? java.lang.NumberFormatException e)     "Not a valid number"
         (instance? java.lang.ClassCastException e)        "Type error: a value was not of the expected type"
         (instance? clojure.lang.ArityException e)         "Wrong number of arguments"
         (instance? java.lang.NullPointerException e)      "Used a value that is void (nil)"
         (instance? java.lang.IndexOutOfBoundsException e) (if (seq raw) raw "Index out of bounds")
         :else (if (seq raw) raw (str e)))
       :cljs
       (cond
         (re-find #"(?i)overflow" raw)                          "Arithmetic overflow"
         (re-find #"(?i)division by zero|divide by zero" raw)   "Division by zero"
         (re-find #"(?i)cannot mix bigint|cannot convert .*bigint" raw) "Type error: a value was not of the expected type"
         (re-find #"(?i)cannot convert|not a (valid|finite) number|invalid (number|bigint)" raw) "Not a valid number"
         :else (if (seq raw) raw (str e))))))

(def nex-console-print rt/nex-console-print)
(def nex-console-println rt/nex-console-println)
(def nex-console-error rt/nex-console-error)
(def nex-console-newline rt/nex-console-newline)
(def nex-console-flush rt/nex-console-flush)
(def nex-console-read-line rt/nex-console-read-line)
(def nex-parse-integer64-string rt/nex-parse-integer64-string)
(def nex-parse-integer rt/nex-parse-integer)
(def nex-parse-real rt/nex-parse-real)

(def nex-process-getenv rt/nex-process-getenv)
(def nex-process-setenv rt/nex-process-setenv)
(def nex-process-command-line rt/nex-process-command-line)

#?(:clj
   (defn- http-response-headers->nex-map
     [headers]
     (http/http-response-headers->nex-map headers)))

#?(:clj
   (defn- make-http-response-object
     [status body headers]
     (http/make-http-response-object make-object status body headers)))

#?(:clj
   (defn- make-http-server-request-object
     [method-name path-value body-text header-map route-params query-map]
     (http/make-http-server-request-object make-object method-name path-value body-text header-map route-params query-map)))

#?(:clj
   (defn- make-http-server-default-response-object
     []
     (http/make-http-server-default-response-object make-object)))

#?(:clj
   (defn- http-exchange-headers->nex-map
     [headers]
     (http/http-exchange-headers->nex-map headers)))

#?(:clj (def java-http-request bi/java-http-request))

#?(:clj (def make-http-server-handle bi/make-http-server-handle))

#?(:clj (def url-decode http/url-decode))
#?(:clj (def path-segments http/path-segments))
#?(:clj (def parse-query-map http/parse-query-map))
#?(:clj (def route-match http/route-match))
#?(:clj (def find-route http/find-route))
#?(:clj (def http-server-response-status http/http-server-response-status))
#?(:clj (def http-server-response-body http/http-server-response-body))
#?(:clj (def http-server-response-headers http/http-server-response-headers))

#?(:clj (def start-http-server! bi/start-http-server!))


;; Built-in IO / cursor / primitive predicates imported from nex.types.runtime
(def nex-console? rt/nex-console?)
(def nex-process? rt/nex-process?)
(def nex-task? rt/nex-task?)
(def nex-channel? rt/nex-channel?)
(def nex-array-cursor? rt/nex-array-cursor?)
(def nex-string-cursor? rt/nex-string-cursor?)
(def nex-map-cursor? rt/nex-map-cursor?)
(def nex-set-cursor? rt/nex-set-cursor?)
(def nex-coll-get rt/nex-coll-get)
(def nex-char? rt/nex-char?)

;;
;; Runtime Environment
;;

(defrecord Environment [bindings parent])

(defn make-env
  "Create a new environment, optionally with a parent scope."
  ([]
   (make-env nil))
  ([parent]
   (->Environment (atom {}) parent)))

(defn env-lookup
  "Look up a variable in the environment, searching parent scopes if needed."
  [env var-name]
  (let [bindings @(:bindings env)]
    (if (contains? bindings var-name)
      (get bindings var-name)
      (if-let [parent (:parent env)]
        (env-lookup parent var-name)
        (throw (ex-info (str "Undefined variable: " var-name)
                        {:var-name var-name}))))))

(defn- env-contains?
  [env var-name]
  (or (contains? @(:bindings env) var-name)
      (when-let [parent (:parent env)]
        (env-contains? parent var-name))))

(defn env-define
  "Define a variable in the current environment."
  [env var-name value]
  (swap! (:bindings env) assoc var-name value)
  value)

(defn env-set!
  "Set a variable in the environment where it's defined."
  [env var-name value]
  (if (contains? @(:bindings env) var-name)
    (env-define env var-name value)
    (if-let [parent (:parent env)]
      (env-set! parent var-name value)
      (throw (ex-info (str "Cannot assign to undefined variable: " var-name)
                      {:var-name var-name})))))

(defn- env-replace-object-aliases!
  "Replace all bindings in the environment chain that still point at old-obj."
  [env old-obj new-obj]
  (when (and env (some? old-obj) (not (identical? old-obj new-obj)))
    (swap! (:bindings env)
           (fn [bindings]
             (reduce-kv (fn [m k v]
                          (assoc m k (if (identical? v old-obj) new-obj v)))
                        {}
                        bindings)))
    (when-let [parent (:parent env)]
      (env-replace-object-aliases! parent old-obj new-obj))))

(def ^:private write-back-target-key ::write-back-target)
(def ^:private write-back-source-key ::write-back-source)

(defn- make-literal-node
  [value]
  {:type :literal :value value})

(defn- infer-reference-target-expr
  "Infer an assignable target expression for an object result returned from a query.
   This supports query chains such as obj.child().grandchild().mutate()."
  [base-target current-obj result]
  (when (and base-target (nex-object? current-obj) (some? result))
    (some (fn [[field-key field-val]]
            (let [field-name (name field-key)
                  field-expr {:type :call
                              :target base-target
                              :method field-name
                              :args []
                              :has-parens false}]
              (cond
                (identical? field-val result)
                field-expr

                (nex-array? field-val)
                (when-let [idx (first (keep-indexed (fn [i item]
                                                      (when (identical? item result) i))
                                                    field-val))]
                  {:type :call
                   :target field-expr
                   :method "get"
                   :args [(make-literal-node idx)]
                   :has-parens true})

                (nex-map? field-val)
                (when-let [entry (some (fn [[k v]]
                                         (when (identical? v result) [k v]))
                                       field-val)]
                  {:type :call
                   :target field-expr
                   :method "get"
                   :args [(make-literal-node (first entry))]
                   :has-parens true})

                :else
                nil)))
          (:fields current-obj))))

(defn- annotate-reference-result
  [target-expr current-obj result]
  (if-let [origin (and (nex-object? result)
                       (infer-reference-target-expr target-expr current-obj result))]
    (try
      (with-meta result (assoc (meta result)
                               write-back-target-key origin
                               write-back-source-key result))
      (catch #?(:clj Exception :cljs :default) _
        result))
    result))

;;
;; Runtime Context (holds classes, globals, current environment)
;;

(defrecord Context [classes globals current-env output imports specialized-classes compiled-state])

(declare register-class)

(def build-function-base-class bootstrap/build-function-base-class)
(def build-cursor-base-class bootstrap/build-cursor-base-class)
(def build-comparable-base-class bootstrap/build-comparable-base-class)
(def build-any-base-class bootstrap/build-any-base-class)
(def build-hashable-base-class bootstrap/build-hashable-base-class)
(def build-builtin-scalar-class bootstrap/build-builtin-scalar-class)

(defn make-context
  "Create a new runtime context."
  []
  (let [globals (make-env)]
    (let [ctx (->Context
               (atom {})           ; classes registry
               globals             ; global environment
               globals             ; current environment starts as global
               (atom [])           ; output accumulator
               (atom [])           ; imports registry
               (atom {})           ; specialized classes cache
               (atom nil))]        ; compiled runtime state for fallback dispatch
      ;; Register built-in base classes
      (register-class ctx (build-any-base-class))
      (register-class ctx (build-function-base-class))
      (register-class ctx (build-cursor-base-class))
      (register-class ctx (build-comparable-base-class))
      (register-class ctx (build-hashable-base-class))
      (doseq [scalar ["String" "Integer" "Real" "Boolean" "Char"]]
        (register-class ctx (build-builtin-scalar-class scalar)))
      ctx)))

(defn register-class
  "Register a class definition in the context."
  [ctx class-def]
  (letfn [(cycle-path [class-name start-parent]
            (letfn [(visit [current path seen]
                      (cond
                        (= current class-name)
                        (conj path current)

                        (contains? seen current)
                        nil

                        :else
                        (when-let [current-def (lookup-class-if-exists ctx current)]
                          (let [seen' (conj seen current)
                                path' (conj path current)]
                            (some #(visit (:parent %) path' seen')
                                  (:parents current-def))))))]
              (visit start-parent [class-name] #{class-name})))
          (validate-class! [registered-class]
            (let [class-name (:name registered-class)]
              (doseq [{:keys [parent]} (:parents registered-class)]
                (when (= parent class-name)
                  (throw (ex-info (str "Class " class-name " cannot inherit from itself")
                                  {:class-name class-name
                                   :parent parent})))
                (when-let [path (cycle-path class-name parent)]
                  (throw (ex-info (str "Cyclic inheritance detected: "
                                       (str/join " -> " path))
                                  {:class-name class-name
                                   :cycle path}))))))]
    (let [class-name (:name class-def)
          previous (get @(:classes ctx) class-name)]
      (swap! (:classes ctx) assoc class-name class-def)
      (try
        (validate-class! class-def)
        (catch #?(:clj Exception :cljs :default) e
          (if previous
            (swap! (:classes ctx) assoc class-name previous)
            (swap! (:classes ctx) dissoc class-name))
          (throw e))))))

(defn lookup-class
  "Look up a class definition by name."
  [ctx class-name]
  (or (get @(:classes ctx) class-name)
      (get @(:specialized-classes ctx) class-name)
      (throw (ex-info (str "Undefined class: " class-name)
                      {:class-name class-name}))))

(defn lookup-class-if-exists
  "Look up a class definition by name, or nil if not found."
  [ctx class-name]
  (or (get @(:classes ctx) class-name)
      (get @(:specialized-classes ctx) class-name)))

#?(:clj (def resolve-imported-java-class bi/resolve-imported-java-class))
#?(:clj (def java-create-object bi/java-create-object))
#?(:clj (def java-call-method bi/java-call-method))

#?(:clj
   (defn runtime-resolve-call-user-method
     [ctx target method-name arg-values]
     (let [resolver (requiring-resolve 'nex.compiler.jvm.runtime/call-compiled-user-method)
           compiled-state-slot (:compiled-state ctx)
           state (cond
                   (instance? clojure.lang.IDeref compiled-state-slot) @compiled-state-slot
                   (some? compiled-state-slot) compiled-state-slot
                   :else nil)]
       (resolver state target method-name arg-values))))

#?(:clj
   (defn- reflected-field
     [^Class cls field-name]
     (or (try
           (.getField cls field-name)
           (catch Exception _ nil))
         (some (fn [^Field field]
                 (when (= (.getName field) field-name)
                   (.setAccessible field true)
                   field))
               (.getDeclaredFields cls)))))

#?(:clj
   (defn- composition-fields
     [^Class cls]
     (->> (.getDeclaredFields cls)
          (filter (fn [^Field field] (str/starts-with? (.getName field) "_parent_")))
          (map (fn [^Field field]
                 (.setAccessible field true)
                 field)))))

#?(:clj
   (defn- deep-reflected-field
     [value field-name]
     (or (when-let [^Field field (reflected-field (.getClass value) field-name)]
           [value field])
         (some (fn [^Field parent-field]
                 (when-let [parent-value (.get parent-field value)]
                   (deep-reflected-field parent-value field-name)))
               (composition-fields (.getClass value))))))

#?(:clj
   (defn- compiled-object-field
     [value field-name]
     (when-let [[owner ^Field field] (deep-reflected-field value field-name)]
       [true (.get field owner)])))

#?(:clj
   (defn- compiled-runtime-class-name
     [ctx value]
     (when value
       (let [binary-name (.getName (.getClass value))
             simple-name (last (str/split binary-name #"\."))
             known-classes @(:classes ctx)]
         (cond
           (contains? known-classes simple-name)
           simple-name

           :else
           (when-let [[_ candidate] (re-matches #"(.+)_\d{4}" simple-name)]
             (when (contains? known-classes candidate)
               candidate)))))))

(defn register-specialized-class
  "Register a specialized (type-realized) class in the context."
  [ctx class-def]
  (swap! (:specialized-classes ctx) assoc (:name class-def) class-def))

(defn lookup-specialized-class
  "Look up a specialized class definition by name."
  [ctx class-name]
  (get @(:specialized-classes ctx) class-name))

;;
;; Generic Class Specialization
;;

(defn specialized-class-name
  "Build a specialized class name like Box[Integer]."
  [base-name type-args]
  (str base-name "[" (clojure.string/join "," type-args) "]"))

(defn substitute-type
  "Replace type parameter strings with concrete types using type-map."
  [type-expr type-map]
  (cond
    (string? type-expr)
    (get type-map type-expr type-expr)

    (map? type-expr)
    (-> type-expr
        (update :base-type #(get type-map % %))
        (update :type-args (fn [args]
                             (when args
                               (mapv #(substitute-type % type-map) args)))))

    :else type-expr))

(defn substitute-in-body
  "Walk class body sections, substituting types using type-map."
  [body type-map]
  (mapv (fn [section]
          (cond
            (= (:type section) :feature-section)
            (update section :members
                    (fn [members]
                      (mapv (fn [member]
                              (case (:type member)
                                :field
                                (update member :field-type #(substitute-type % type-map))

                                :method
                                (-> member
                                    (update :params
                                            (fn [params]
                                              (when params
                                                (mapv #(update % :type (fn [t] (substitute-type t type-map)))
                                                      params))))
                                    (update :return-type
                                            (fn [rt] (when rt (substitute-type rt type-map)))))

                                member))
                            members)))

            (= (:type section) :constructors)
            (update section :constructors
                    (fn [ctors]
                      (mapv (fn [ctor]
                              (update ctor :params
                                      (fn [params]
                                        (when params
                                          (mapv #(update % :type (fn [t] (substitute-type t type-map)))
                                                params)))))
                            ctors)))

            :else section))
        body))

(defn specialize-class
  "Create a specialized version of a generic class with concrete type args."
  [generic-class-def type-args]
  (let [generic-params (:generic-params generic-class-def)
        type-map (into {} (map (fn [param arg]
                                 [(:name param) arg])
                               generic-params type-args))
        spec-name (specialized-class-name (:name generic-class-def) type-args)]
    (-> generic-class-def
        (assoc :name spec-name)
        (assoc :template-name (:name generic-class-def))
        (assoc :generic-params nil)
        (update :body #(substitute-in-body % type-map)))))

(defn add-output
  "Add output to the context (for print statements)."
  [ctx value]
  (swap! (:output ctx) conj value))

;;
;; Object Representation
;;

(defrecord NexObject [class-name fields closure-env])

(defn make-object
  "Create a new object instance."
  ([class-name field-values]
   (make-object class-name field-values nil))
  ([class-name field-values closure-env]
   (->NexObject class-name field-values closure-env)))

#?(:clj
   (defonce ^:private concurrent-executor
     (Executors/newCachedThreadPool)))

#?(:clj
   (defn shutdown-runtime!
     "Release shared JVM runtime resources so short-lived tools and test runners can exit cleanly."
     []
     (.shutdown ^java.util.concurrent.ExecutorService concurrent-executor)
     (when-not (.awaitTermination ^java.util.concurrent.ExecutorService concurrent-executor 100 TimeUnit/MILLISECONDS)
       (.shutdownNow ^java.util.concurrent.ExecutorService concurrent-executor))))

#?(:cljs
   (defn shutdown-runtime!
     []
     nil))

(defn nex-object?
  "Check if a value is a Nex object instance."
  [v]
  (or (instance? NexObject v)
      (and (map? v) (contains? v :class-name) (contains? v :fields))))

(def channel-timeout-signal conc/channel-timeout-signal)
(def task-timeout-signal conc/task-timeout-signal)
(def current-time-ms conc/current-time-ms)
(def timeout-ms conc/timeout-ms)
(def channel-closed-signal conc/channel-closed-signal)
(def queue-empty conc/queue-empty)
(def queue-conj conc/queue-conj)
(def queue-pop conc/queue-pop)
(def make-task conc/make-task)
#?(:cljs (def promise? conc/promise?))
#?(:cljs (def ->promise conc/->promise))
#?(:cljs (def promise-all conc/promise-all))
(def make-channel conc/make-channel)
(def task-await conc/task-await)
(def task-done? conc/task-done?)
(def await-all-tasks conc/await-all-tasks)
(def await-any-task conc/await-any-task)
(def task-cancel conc/task-cancel)
(def task-cancelled? conc/task-cancelled?)
(def queue-remove-first conc/queue-remove-first)
(def channel-send conc/channel-send)
(def channel-try-send conc/channel-try-send)
(def channel-receive conc/channel-receive)
(def channel-try-receive conc/channel-try-receive)
(def channel-close conc/channel-close)

(declare eval-node)
(declare get-all-fields)
(declare get-all-constants)
(declare eval-body-with-rescue)
(declare lookup-constructor)
(declare get-parent-classes)
(declare combine-assertions)
(declare combine-preconditions)
(declare combine-precondition-groups)
(declare get-type-name)

(defn- select-op-call
  [expr]
  (when (and (map? expr) (= :call (:type expr)))
    expr))

(defn- eval-select-target
  [ctx target]
  (let [target-name (when (string? target) target)
        class-target (when target-name (lookup-class-if-exists ctx target-name))]
    (if class-target
      nil
      (if target-name
        (env-lookup (:current-env ctx) target-name)
        (eval-node ctx target)))))

(defn- prepare-select-clause
  [ctx {:keys [expr alias body] :as clause}]
  (let [{:keys [target method args] :as call} (select-op-call expr)]
    {:method method
     :alias alias
     :body body
     :target (eval-select-target ctx target)
     :args (mapv #(eval-node ctx %) args)}))

(defn- execute-select-body
  [ctx body alias value]
  (let [body-ctx (if alias
                   (assoc ctx :current-env (doto (make-env (:current-env ctx))
                                            (env-define alias value)))
                   ctx)]
    (last (map #(eval-node body-ctx %) body))))

(defn- attempt-select-clause
  [{:keys [method target args] :as prepared}]
  (cond
    (and (= (:nex-builtin-type target) :Task)
         (= method "await"))
    (when (task-done? target)
      {:selected? true
       :value (task-await target)})

    (#{"receive" "try_receive"} method)
    (let [value (channel-try-receive target)]
      (when (some? value)
        {:selected? true :value value}))

    (#{"send" "try_send"} method)
    (when (channel-try-send target (first args))
      {:selected? true})

    :else nil))

(defn- sleep-select-step! []
  #?(:clj (Thread/sleep 1)
     :cljs nil))

;;
;; Debugger Hooks
;;

(def ^:private debuggable-node-types
  #{:call :member-assign :assign :let :if :case :match :loop :raise :retry :scoped-block})

(defn debuggable-node?
  "Whether this node should trigger debugger pause checks."
  [node]
  (and (map? node)
       (contains? debuggable-node-types (:type node))))

(defn maybe-debug-pause
  "Invoke optional debugger hook before executing a debuggable node."
  [ctx node]
  (when (and (debuggable-node? node) (:debug-hook ctx))
    ((:debug-hook ctx) ctx node)))

;;
;; Contract Checking
;;

;; Contract types
(def Precondition bi/Precondition)
(def Postcondition bi/Postcondition)
(def Loop-invariant bi/Loop-invariant)
(def Class-invariant bi/Class-invariant)
(def report-contract-violation bi/report-contract-violation)

(defn check-assertions
  "Check a list of assertions. Throws exception if any fail."
  [ctx assertions contract-type]
  (doseq [{:keys [label condition]} assertions]
    (let [result (eval-node ctx condition)]
      (when-not result
        (report-contract-violation contract-type label condition)))))

(defn check-class-invariant
  "Check the class invariant for an object or class context."
  [ctx class-def]
  (letfn [(collect-invariants [class-def seen]
            (let [class-name (:name class-def)
                  already-seen? (and class-name (contains? seen class-name))
                  seen' (if class-name (conj seen class-name) seen)]
              (if already-seen?
                [[] seen]
                (let [[parent-invariants seen'']
                      (if-let [parents (get-parent-classes ctx class-def)]
                        (reduce (fn [[acc seen-so-far] {parent-class-def :class-def}]
                                  (let [[inv seen-next] (collect-invariants parent-class-def seen-so-far)]
                                    [(into acc inv) seen-next]))
                                [[] seen']
                                parents)
                        [[] seen'])
                      local-invariants (or (:invariant class-def) [])]
                  [(vec (concat parent-invariants local-invariants)) seen'']))))]
    (let [[invariant-assertions _] (collect-invariants class-def #{})]
      (when (seq invariant-assertions)
        (check-assertions ctx invariant-assertions Class-invariant)))))

;;
;; Inheritance Support
;;

(defn get-parent-classes
  "Get the list of parent class definitions for a class."
  [ctx class-def]
  (when-let [parents (:parents class-def)]
    (mapv (fn [parent-info]
            (let [parent-class (lookup-class ctx (:parent parent-info))]
              (assoc parent-info :class-def parent-class)))
          parents)))

(defn feature-members
  "Return feature members with section visibility copied onto each member."
  [class-def]
  (mapcat (fn [section]
            (when (= (:type section) :feature-section)
              (map #(if (:visibility %)
                      %
                      (assoc % :visibility (:visibility section)))
                   (:members section))))
          (:body class-def)))

(defn public-member?
  [member]
  (not= :private (-> member :visibility :type)))

(defn- member-visible?
  [member declaring-class-name caller-class-name]
  (or (= caller-class-name declaring-class-name)
      (public-member? member)))

(defn get-all-constants
  "Collect accessible constants for a class:
   inherited public constants first, then local constants."
  [ctx class-def]
  (let [parent-constants (when-let [parents (get-parent-classes ctx class-def)]
                           (mapcat (fn [{:keys [class-def]}]
                                     (filter public-member?
                                             (get-all-constants ctx class-def)))
                                   parents))
        local-constants (->> (feature-members class-def)
                             (filter #(and (= (:type %) :field)
                                           (:constant? %)))
                             (map #(assoc % :declaring-class class-def)))
        merged (reduce (fn [m constant]
                         (assoc m (:name constant) constant))
                       {}
                       (concat parent-constants local-constants))]
    (vals merged)))

(defn lookup-class-constant
  "Look up a constant on a class and its parent chain.
   Local constants always apply; inherited constants must be public."
  [ctx class-def constant-name]
  (let [local-constant (some (fn [member]
                               (when (and (= (:type member) :field)
                                          (:constant? member)
                                          (= (:name member) constant-name))
                                 (assoc member :declaring-class class-def)))
                             (feature-members class-def))]
    (or local-constant
        (when-let [parents (get-parent-classes ctx class-def)]
          (some (fn [{:keys [class-def]}]
                  (some (fn [member]
                          (when (and (public-member? member)
                                     (= (:name member) constant-name))
                            member))
                        (get-all-constants ctx class-def)))
                parents)))))

(defn lookup-method-in-class
  "Look up a method in a specific class (without searching parents)."
  [class-def method-name arg-count]
  (->> (feature-members class-def)
       (filter #(= (:type %) :method))
       (filter #(= (:name %) method-name))
       (filter #(or (nil? arg-count)
                    (= (count (or (:params %) [])) arg-count)))
       first))

(defn- collect-inherited-method-contract-sources
  [ctx class-def method-name arg-count caller-class-name]
  (letfn [(collect [cls seen]
            (let [class-name (:name cls)
                  already-seen? (and class-name (contains? seen class-name))
                  seen' (if class-name (conj seen class-name) seen)]
              (if already-seen?
                [[] seen]
                (let [[parent-sources seen'']
                      (if-let [parents (get-parent-classes ctx cls)]
                        (reduce (fn [[acc seen-so-far] {parent-class-def :class-def}]
                                  (let [[sources seen-next] (collect parent-class-def seen-so-far)]
                                    [(into acc sources) seen-next]))
                                [[] seen']
                                parents)
                        [[] seen'])
                      local-method (lookup-method-in-class cls method-name arg-count)
                      local-source (when (and local-method
                                              (member-visible? local-method class-name caller-class-name))
                                     [{:method local-method
                                       :source-class cls}])]
                  [(vec (concat parent-sources local-source)) seen'']))))]
    (if-let [parents (get-parent-classes ctx class-def)]
      (first (reduce (fn [[acc seen] {parent-class-def :class-def}]
                       (let [[sources seen'] (collect parent-class-def seen)]
                         [(into acc sources) seen']))
                     [[] #{}]
                     parents))
      [])))

(defn lookup-method-with-inheritance
  "Look up a method in a class, searching parent classes if needed."
  ([ctx class-def method-name arg-count]
   (lookup-method-with-inheritance ctx class-def method-name arg-count nil))
  ([ctx class-def method-name arg-count caller-class-name]
   (let [method (lookup-method-in-class class-def method-name arg-count)
         accessible? (and method
                          (member-visible? method (:name class-def) caller-class-name))]
     (if accessible?
       (let [inherited-sources (collect-inherited-method-contract-sources ctx
                                                                          class-def
                                                                          method-name
                                                                          arg-count
                                                                          caller-class-name)
             effective-require (combine-precondition-groups
                                (mapv (fn [{:keys [method]}] (:require method))
                                      inherited-sources)
                                (:require method))
             effective-ensure (vec (concat (mapcat (fn [{:keys [method]}]
                                                     (or (:ensure method) []))
                                                   inherited-sources)
                                           (or (:ensure method) [])))]
         {:method method
          :source-class class-def
          :effective-require effective-require
          :effective-ensure effective-ensure})
       (when-let [parents (get-parent-classes ctx class-def)]
         (some (fn [parent-info]
                 (lookup-method-with-inheritance ctx
                                                 (:class-def parent-info)
                                                 method-name
                                                 arg-count
                                                 caller-class-name))
               parents))))))

(defn- lookup-field-with-inheritance
  [ctx class-def field-name caller-class-name]
  (let [local-field (some (fn [member]
                            (when (and (= (:type member) :field)
                                       (not (:constant? member))
                                       (= (:name member) field-name)
                                       (member-visible? member (:name class-def) caller-class-name))
                              (assoc member :declaring-class (:name class-def))))
                          (feature-members class-def))]
    (or local-field
        (when-let [parents (get-parent-classes ctx class-def)]
          (some (fn [{:keys [class-def]}]
                  (lookup-field-with-inheritance ctx class-def field-name caller-class-name))
                parents)))))

(defn- lookup-field-with-inheritance-any-visibility
  [ctx class-def field-name]
  (let [local-field (some (fn [member]
                            (when (and (= (:type member) :field)
                                       (not (:constant? member))
                                       (= (:name member) field-name))
                              (assoc member :declaring-class (:name class-def))))
                          (feature-members class-def))]
    (or local-field
        (when-let [parents (get-parent-classes ctx class-def)]
          (some (fn [{:keys [class-def]}]
                  (lookup-field-with-inheritance-any-visibility ctx class-def field-name))
                parents)))))

(defn- field-write-error-message
  [field-name declaring-class]
  (str "Cannot assign to field " field-name
       " outside of class " declaring-class))

(defn- ensure-callable-defined!
  [callable]
  (when (:declaration-only? callable)
    (throw (ex-info (str "Function or method declared but not defined: " (:name callable))
                    {:name (:name callable)
                     :declaration-only? true}))))

(defn is-parent?
  "Check if parent-name appears in the parent chain of class-name."
  [ctx class-name parent-name]
  (letfn [(parent? [current seen]
            (when (and current (not (contains? seen current)))
              (when-let [class-def (lookup-class-if-exists ctx current)]
                (when-let [parents (:parents class-def)]
                  (let [seen' (conj seen current)]
                    (or (some #(= (:parent %) parent-name) parents)
                        (some #(parent? (:parent %) seen') parents)))))))]
    (parent? class-name #{})))

(def runtime-type-name bi/runtime-type-name)

(def numeric-subtype-runtime? typeinfo/numeric-subtype-runtime?)
(def cursor-subtype-runtime? typeinfo/cursor-subtype-runtime?)

(def runtime-type-is? bi/runtime-type-is?)

(defn- convert-compatible-runtime? [ctx runtime-type target-type]
  (typeinfo/convert-compatible-runtime? is-parent? ctx runtime-type target-type))

(defn eval-class-constant
  "Evaluate a class constant value with inherited constant bindings available."
  ([ctx class-def constant-name]
   (eval-class-constant ctx class-def constant-name (or (:constant-visiting ctx) #{})))
  ([ctx class-def constant-name visiting]
   (let [visit-key [(:name class-def) constant-name]]
     (when (contains? visiting visit-key)
       (throw (ex-info (str "Cyclic constant definition: " (:name class-def) "." constant-name)
                       {:class-name (:name class-def)
                        :constant constant-name})))
     (let [constant (lookup-class-constant ctx class-def constant-name)]
       (when-not constant
         (throw (ex-info (str "Undefined constant: " (:name class-def) "." constant-name)
                         {:class-name (:name class-def)
                          :constant constant-name})))
        (let [source-class (:declaring-class constant class-def)
              const-env (make-env (:globals ctx))
              next-visiting (conj visiting visit-key)
              eval-ctx (assoc ctx
                              :current-env const-env
                              :current-class-name (:name source-class)
                              :constant-visiting next-visiting)]
         (eval-node eval-ctx (:value constant)))))))

(defn bind-class-constants!
  "Bind all constants visible from class-def into env."
  [ctx env class-def]
  (doseq [constant (get-all-constants ctx class-def)]
    (env-define env
                (:name constant)
                (eval-class-constant ctx
                                     (:declaring-class constant class-def)
                                     (:name constant)))))

(defn combine-assertions
  "Combine assertions from parent and child methods (for contracts)."
  [parent-assertions child-assertions]
  (vec (concat (or parent-assertions []) (or child-assertions []))))

(defn assertions->condition
  "Collapse a list of assertions into a single condition using logical AND."
  [assertions]
  (when (seq assertions)
    (reduce (fn [acc {:keys [condition]}]
              (if acc
                {:type :binary
                 :operator "and"
                 :left acc
                 :right condition}
                condition))
            nil
            assertions)))

(defn combine-precondition-groups
  "Combine inherited and local preconditions by OR-ing assertion groups, where
   each group's assertions are AND-ed together."
  [inherited-groups local-assertions]
  (let [groups (vec (concat (keep seq inherited-groups)
                            (when (seq local-assertions)
                              [(vec local-assertions)])))]
    (cond
      (empty? groups)
      nil

      (= 1 (count groups))
      (vec (first groups))

      :else
      [{:label "inherited_or_local_require"
        :condition (reduce (fn [acc group]
                             (let [group-condition (assertions->condition group)]
                               (if acc
                                 {:type :binary
                                  :operator "or"
                                  :left acc
                                  :right group-condition}
                                 group-condition)))
                           nil
                           groups)}])))

(defn combine-preconditions
  "Combine parent and child preconditions as:
   (parent-require) OR (child-require)."
  [parent-assertions child-assertions]
  (combine-precondition-groups [parent-assertions] child-assertions))

(def nex-format-value bi/nex-format-value)
(def nex-clone-value bi/nex-clone-value)
(def nex-map-entry-match? bi/nex-map-entry-match?)
(def nex-deep-equals? bi/nex-deep-equals?)
(def nex-structural-hash bi/nex-structural-hash)
(def builtin-scalar-value? bi/builtin-scalar-value?)
(def membership-equals? bi/membership-equals?)
(def nex-array-contains-value? bi/nex-array-contains-value?)
(def nex-array-index-of-value bi/nex-array-index-of-value)
(def nex-map-contains-key-value? bi/nex-map-contains-key-value?)
(def nex-set-contains-value? bi/nex-set-contains-value?)
(def sortable-builtin-scalar-value? bi/sortable-builtin-scalar-value?)
(def nex-value-compare bi/nex-value-compare)
(def nex-array-sort-with-ctx bi/nex-array-sort-with-ctx)
(def make-min-heap bi/make-min-heap)
(def make-atomic-integer bi/make-atomic-integer)
(def make-atomic-integer64 bi/make-atomic-integer64)
(def make-atomic-boolean bi/make-atomic-boolean)
(def make-atomic-reference bi/make-atomic-reference)
(def deep-equals-runtime? bi/deep-equals-runtime?)
(def atomic-reference-cas! bi/atomic-reference-cas!)
(def heap-compare bi/heap-compare)
(def heap-sift-up bi/heap-sift-up)
(def heap-sift-down bi/heap-sift-down)
(def heap-insert! bi/heap-insert!)
(def heap-peek bi/heap-peek)
(def heap-extract-min! bi/heap-extract-min!)
(def nex-display-value bi/nex-display-value)

(declare print-output-value)

;;
;; Built-in Functions
;;

(def builtins bi/builtins)

;;
;; Operator Implementations
;;

(def nex-ordering-compare bi/nex-ordering-compare)

(defn- scalar-identity-value?
  [v]
  (or (nil? v)
      (string? v)
      (nex-numeric? v)
      (boolean? v)
      (char? v)))

(defn- nex-identity-equals?
  [a b]
  (cond
    (and (nex-numeric? a) (nex-numeric? b))
    (nex-numeric-equals? a b)

    (and (scalar-identity-value? a)
         (scalar-identity-value? b))
    (= a b)

    :else
    (identical? a b)))

(defn apply-binary-op
  "Apply a binary operator to two values."
  [op left right]
  (case op
    ;; Arithmetic dispatches on representation: Integer op Integer stays Integer
    ;; (64-bit checked — Clojure long on the JVM, BigInt on JS); any Real operand
    ;; promotes both to Real. On JS, BigInt and number cannot be mixed in a raw
    ;; operator, so the promotion is explicit (->nex-real).
    "+" (cond
          (or (string? left) (string? right))
          (throw (ex-info "String concatenation requires evaluation context"
                          {:operator op :left left :right right}))
          (and (nex-integer? left) (nex-integer? right)) (nex-int-add left right)
          :else (+ (->nex-real left) (->nex-real right)))
    "-" (if (and (nex-integer? left) (nex-integer? right))
          (nex-int-sub left right)
          (- (->nex-real left) (->nex-real right)))
    "*" (if (and (nex-integer? left) (nex-integer? right))
          (nex-int-mul left right)
          (* (->nex-real left) (->nex-real right)))
    ;; Integer division by zero raises (there is no integer result); Real
    ;; division follows IEEE-754: x/0.0 -> +/-Infinity, 0.0/0.0 -> NaN.
    ;; NOTE (JVM): clojure.core// on *boxed* doubles routes through Numbers.divide,
    ;; which raises on a zero divisor — only primitive doubles yield IEEE Inf/NaN,
    ;; so the :clj Real branch divides primitives.
    "/" (if (and (nex-integer? left) (nex-integer? right))
          (nex-int-div left right)
          #?(:clj (/ (double left) (double right))
             :cljs (/ (->nex-real left) (->nex-real right))))
    "^" (if (and (nex-integer? left) (nex-integer? right))
          (nex-int-pow left right)
          (Math/pow (->nex-real left) (->nex-real right)))
    ;; Same asymmetry for remainder: integral % 0 raises, Real % 0.0 is IEEE NaN.
    ;; Both are truncated (sign of the dividend), matching LREM/DREM and JS %.
    "%" (if (and (nex-integer? left) (nex-integer? right))
          (nex-int-mod left right)
          (nex-real-rem (->nex-real left) (->nex-real right)))
    "=" (if (and (nex-numeric? left) (nex-numeric? right))
          (nex-numeric-equals? left right)
          (= left right))
    "/=" (not (if (and (nex-numeric? left) (nex-numeric? right))
                (nex-numeric-equals? left right)
                (= left right)))
    "==" (nex-identity-equals? left right)
    "!=" (not (nex-identity-equals? left right))
    ;; Numeric ordering takes the IEEE-correct fast path: a 3-way compare cannot
    ;; express NaN-unordered (spec §B.3 — every ordering against NaN is false).
    "<" (if (and (nex-numeric? left) (nex-numeric? right))
          (nex-numeric-lt left right)
          (neg? (nex-ordering-compare left right)))
    "<=" (if (and (nex-numeric? left) (nex-numeric? right))
           (nex-numeric-lte left right)
           (not (pos? (nex-ordering-compare left right))))
    ">" (if (and (nex-numeric? left) (nex-numeric? right))
          (nex-numeric-gt left right)
          (pos? (nex-ordering-compare left right)))
    ">=" (if (and (nex-numeric? left) (nex-numeric? right))
           (nex-numeric-gte left right)
           (not (neg? (nex-ordering-compare left right))))
    "and" (and left right)
    "or" (or left right)
    (throw (ex-info (str "Unknown binary operator: " op)
                    {:operator op}))))

(def concat-string-value bi/concat-string-value)

(defn object-equals-override
  "If `a` and `b` are both Nex objects and `a`'s class (or an ancestor) overrides
   the `equals` feature, invoke it and return its boolean result. Returns nil to
   signal \"no override\" — callers then fall back to structural comparison. A nil
   `ctx` (no evaluation context available) also yields nil."
  [ctx a b]
  (when (and ctx (nex-object? a) (nex-object? b))
    (let [class-def (lookup-class ctx (:class-name a))
          override (and class-def
                        (lookup-method-with-inheritance ctx class-def "equals" 1))]
      (when override
        (boolean (eval-node ctx {:type :call
                                 :target {:type :literal :value a}
                                 :method "equals"
                                 :args [{:type :literal :value b}]}))))))

(defn object-hash-override
  "If `v` is a Nex object whose class (or an ancestor) overrides the `hash`
   feature, invoke it and return its hash as a host number for bucketing. Returns
   nil for \"no override\" (caller uses the structural hash) or when ctx is nil."
  [ctx v]
  (when (and ctx (nex-object? v))
    (let [class-def (lookup-class ctx (:class-name v))
          override (and class-def
                        (lookup-method-with-inheritance ctx class-def "hash" 0))]
      (when override
        (nex-int->number
         (eval-node ctx {:type :call
                         :target {:type :literal :value v}
                         :method "hash"
                         :args []}))))))

(defn value-equality-fn
  "Equality over Nex values bound into the runtime collections for the dynamic
   extent of a program run: a class's `equals` override when present, structural
   comparison otherwise."
  [ctx]
  (fn [a b]
    (let [overridden (object-equals-override ctx a b)]
      (if (some? overridden) overridden (nex-deep-equals? a b)))))

(defn value-hash-fn
  "Hash over Nex values consistent with value-equality-fn: a class's `hash`
   override when present, structural hash otherwise."
  [ctx]
  (fn [v]
    (or (object-hash-override ctx v)
        (nex-structural-hash v))))

(defn with-value-semantics*
  "Run `thunk` with Set/Map element equality and hashing bound to the Nex value
   semantics for `ctx` (honouring `equals`/`hash` overrides). Outside this extent
   the collections fall back to host structural equality, which is still correct
   for any value without an override."
  [ctx thunk]
  (binding [rt/*value-equals* (value-equality-fn ctx)
            rt/*value-hash* (value-hash-fn ctx)]
    (thunk)))

(defn- nex-objects-equal?
  "Value equality for `=` / `/=` when at least one operand is a Nex object.
   Two objects are equal when the (possibly inherited) `equals` feature says so;
   if no class on the hierarchy overrides `equals`, fall back to structural,
   field-by-field comparison (the same engine `=` used historically). An object
   is never equal to a non-object value, including void."
  [ctx a b]
  (cond
    (identical? a b) true
    (not (and (nex-object? a) (nex-object? b))) false
    :else
    (let [overridden (object-equals-override ctx a b)]
      (if (some? overridden) overridden (nex-deep-equals? a b)))))

(def print-output-value bi/print-output-value)

(defn apply-unary-op
  "Apply a unary operator to a value."
  [op value]
  (case op
    "-" (- value)
    "not" (not value)
    (throw (ex-info (str "Unknown unary operator: " op)
                    {:operator op}))))

(defn get-default-field-value
  "Get default value for a field type"
  [field-type]
  (cond
    ;; Handle parameterized types
    (map? field-type)
    (case (:base-type field-type)
      "Array" (nex-array)
      "Map" (nex-map)
      "Set" (nex-set)
      "Min_Heap" (make-min-heap nil)
      nil)

    ;; Handle simple types
    (string? field-type)
    (case field-type
      "Integer" (->nex-integer 0)
      "Real" 0.0
      "Char" \0
      "Boolean" false
      "String" ""
      "Min_Heap" (make-min-heap nil)
      "Console" {:nex-builtin-type :Console}
      "Process" {:nex-builtin-type :Process}
      "Task" nil
      "Channel" nil
      nil)

    :else nil))

;;
;; Built-in Type Methods
;;

(def builtin-type-methods bi/builtin-type-methods)
(def builtin-type-method-return-type bi/builtin-type-method-return-type)
(def get-type-name bi/get-type-name)
(def call-builtin-method bi/call-builtin-method)
;;
;; Node Evaluators
;;

(defmulti eval-node
  "Evaluate an AST node in the given context."
  (fn [ctx node]
    (cond
      (map? node) (:type node)
      :else :literal)))

#?(:clj
   (defn- intern-search-roots
     "Return directories to search for project-local interned classes.
      Prefer the currently loaded source file's directory when available, then
      fall back to the user's original working directory."
     [ctx]
     (let [source-dir (when-let [source (:debug-source ctx)]
                        (let [f (clojure.java.io/file source)]
                          (when (.isAbsolute f)
                            (.getParentFile f))))
           user-dir (when-let [udir (System/getProperty "nex.user.dir")]
                      (clojure.java.io/file udir))
           pwd (clojure.java.io/file ".")]
       (->> [source-dir user-dir pwd]
            (remove nil?)
            distinct)))

   :cljs
   (defn- intern-search-roots
     [ctx]
     (let [path-module (js/require "path")
           source-dir (when-let [source (:debug-source ctx)]
                        (when (.isAbsolute path-module source)
                          (.dirname path-module source)))
           user-dir (or (.-nex_user_dir js/process.env)
                        (.-NEX_USER_DIR js/process.env)
                        (.-PWD js/process.env))
           pwd (.resolve path-module ".")]
       (->> [source-dir user-dir pwd]
            (remove nil?)
            distinct))))

#?(:clj
   (defn find-intern-file
     "Search for an intern file in the specified locations.
      Returns the absolute path if found, otherwise throws an exception."
     [ctx path class-name]
     (let [filenames (intern-filenames class-name)
           current-root (System/getenv "NEX_USER_DIR")
           current-dir (map #(str (clojure.java.io/file current-root %)) filenames)
           local-roots (intern-search-roots ctx)
           local-direct (mapcat (fn [root]
                                  (map #(str (clojure.java.io/file root %)) filenames))
                                local-roots)
           local-lib (when (seq path)
                       (mapcat (fn [root]
                                 (concat
                                  (map #(str (clojure.java.io/file root "lib" path %)) filenames)
                                  (map #(str (clojure.java.io/file root "lib" path "src" %)) filenames)))
                               local-roots))
           home-deps (if (seq path)
                       (concat
                        (map #(str (System/getProperty "user.home") "/.nex/deps/" path "/" %) filenames)
                        (map #(str (System/getProperty "user.home") "/.nex/deps/" path "/src/" %) filenames))
                       (concat
                        (map #(str (System/getProperty "user.home") "/.nex/deps/" %) filenames)
                        (map #(str (System/getProperty "user.home") "/.nex/deps/src/" %) filenames)))
           ;; A path-qualified intern (e.g. `intern net/Http_Server`) names a
           ;; module under that path, so the path-qualified locations (./lib/<path>
           ;; and the dependency cache) must be searched BEFORE the unqualified
           ;; same-directory locations. Otherwise a source file that merely shares
           ;; the module's bare filename (e.g. examples/http_server.nex) would
           ;; shadow the real library module.
           locations (vec (if (seq path)
                            (concat local-lib home-deps current-dir local-direct)
                            (concat current-dir local-direct home-deps)))
           found (first (filter #(-> % clojure.java.io/file .exists) locations))]
       (if found
         found
         (throw (ex-info (str "Cannot find intern file for "
                              (if (seq path)
                                (str path "/" class-name)
                                class-name))
                        {:path path
                         :class-name class-name
                         :searched-locations locations})))))
   :cljs
   (defn find-intern-file
     "Search for an intern file in the specified locations.
      Returns the absolute path if found, otherwise throws an exception."
     [ctx path class-name]
     (let [fs (js/require "fs")
           path-module (js/require "path")
           filenames (intern-filenames class-name)
           home (or (.-HOME js/process.env) (.-USERPROFILE js/process.env) ".")
           local-roots (intern-search-roots ctx)
           local-direct (mapcat (fn [root]
                                  (map #(.join path-module root %) filenames))
                                local-roots)
           local-lib (when (seq path)
                       (mapcat (fn [root]
                                 (concat
                                  (map #(.join path-module root "lib" path %) filenames)
                                  (map #(.join path-module root "lib" path "src" %) filenames)))
                               local-roots))
           home-deps (if (seq path)
                       (concat
                        (map #(str home "/.nex/deps/" path "/" %) filenames)
                        (map #(str home "/.nex/deps/" path "/src/" %) filenames))
                       (concat
                        (map #(str home "/.nex/deps/" %) filenames)
                        (map #(str home "/.nex/deps/src/" %) filenames)))
           locations (vec (concat local-direct local-lib home-deps))
           found (first (filter #(.existsSync fs %) locations))]
       (if found
         found
         (throw (ex-info (str "Cannot find intern file for "
                              (if (seq path)
                                (str path "/" class-name)
                                class-name))
                        {:path path
                         :class-name class-name
                         :searched-locations locations}))))))

#?(:clj
   (defn process-intern
     "Load and interpret an external file, then register the class with the given alias."
     [ctx {:keys [path class-name alias]}]
     (let [file-path (find-intern-file ctx path class-name)
           ;; Load and parse the external file
           file-content (slurp file-path)
           file-ast (parser/ast file-content)
           ;; Interpret the file to register its classes
           _ (eval-node ctx file-ast)
           ;; Look up the class that was registered
           registered-class (get @(:classes ctx) class-name)
           ;; Determine the name to use (alias or original)
           intern-name (or alias class-name)]
       (when-not registered-class
         (throw (ex-info (str "Class " class-name " not found in file " file-path)
                        {:file file-path :class-name class-name})))
       ;; Register the class under the new name (if alias is provided)
       (when alias
         (swap! (:classes ctx) assoc alias registered-class))
       ;; Return the class name that was registered
       intern-name))
   :cljs
   (defn process-intern
     "In ClojureScript, intern is not supported. Use registerClass instead."
     [ctx {:keys [path class-name alias]}]
     (throw (ex-info "intern is not supported in ClojureScript. Parse on the JVM and send the AST, or use registerClass to manually register classes."
                    {:path path :class-name class-name :alias alias}))))

#?(:clj
   (defn- resolve-interned*
     "Traverse intern declarations recursively and collect both the class
      definitions and the import declarations they bring into scope for static
      analysis. Returns {:classes [...] :imports [...] :seen #{...}}. Aliased
      interns add an extra class entry under the alias name. Imports are carried
      through so that an interned module's host-class imports (e.g.
      `import java.net.ServerSocket`) are visible to the typechecker that
      elaborates the merged program."
     [source-id program seen-files]
     (letfn [(resolve* [current-source current-program seen]
               (let [ctx (assoc (make-context) :debug-source current-source)]
                 (reduce
                  (fn [{:keys [classes imports functions seen]} {:keys [path class-name alias]}]
                    (let [file-path (find-intern-file ctx path class-name)
                          canonical (.getCanonicalPath (clojure.java.io/file file-path))]
                      (if (contains? seen canonical)
                        {:classes classes :imports imports :functions functions :seen seen}
                        (let [file-ast (parser/ast (slurp file-path))
                              nested (resolve* canonical file-ast (conj seen canonical))
                              direct-classes (:classes file-ast)
                              all-file-classes (concat direct-classes (:classes nested))
                              all-file-imports (concat (:imports file-ast) (:imports nested))
                              ;; Free functions defined in an interned module are
                              ;; brought into scope too, so a library can export
                              ;; helper/combinator functions, not just classes.
                              all-file-functions (concat (:functions file-ast) (:functions nested))
                              aliased-class (when alias
                                              (when-let [class-def (some #(when (= (:name %) class-name) %) all-file-classes)]
                                                [(assoc class-def :name alias)]))]
                          {:classes (into classes (concat all-file-classes aliased-class))
                           :imports (into imports all-file-imports)
                           :functions (into functions all-file-functions)
                           :seen (:seen nested)}))))
                  {:classes [] :imports [] :functions [] :seen seen}
                  (:interns current-program))))]
       (resolve* source-id program seen-files))))

#?(:clj
   (defn resolve-interned-classes
     "Resolve intern declarations to the class ASTs they bring into scope for static analysis.
      Returns a flat sequence of class definitions, including recursively interned classes.
      Aliased interns are represented as an additional class entry with the alias name."
     ([source-id program]
      (resolve-interned-classes source-id program #{}))
     ([source-id program seen-files]
      (:classes (resolve-interned* source-id program seen-files))))
   :cljs
   (defn resolve-interned-classes
     [& _]
     []))

#?(:clj
   (defn resolve-interned-imports
     "Resolve intern declarations to the import declarations they bring into scope
      for static analysis (recursively, deduplicated). These let the typechecker
      see the host-class imports declared inside interned modules."
     ([source-id program]
      (resolve-interned-imports source-id program #{}))
     ([source-id program seen-files]
      (distinct (:imports (resolve-interned* source-id program seen-files)))))
   :cljs
   (defn resolve-interned-imports
     [& _]
     []))

#?(:clj
   (defn resolve-interned-functions
     "Resolve intern declarations to the free-function definitions they bring into
      scope (recursively), so the typechecker and compiled backend can see a
      library's exported functions. The runtime interpreter registers them when it
      evaluates the interned module, so this is only needed for static analysis
      and compilation."
     ([source-id program]
      (resolve-interned-functions source-id program #{}))
     ([source-id program seen-files]
      (:functions (resolve-interned* source-id program seen-files))))
   :cljs
   (defn resolve-interned-functions
     [& _]
     []))

(defmethod eval-node :program
  [ctx {:keys [imports interns classes functions statements calls duplicate-functions
               function-signature-conflicts]}]
  ;; Free-function names must be unique within a program (Definition §4.8). The
  ;; walker collapses duplicate definitions last-wins before they reach here, so
  ;; the authoritative interpreter must reject them explicitly — otherwise the
  ;; earlier definition would vanish silently rather than being diagnosed.
  (when-let [dup (first duplicate-functions)]
    (throw (ex-info (str "Function '" dup "' is defined more than once. "
                         "Free-function names must be unique within a program; "
                         "a later definition would silently replace the earlier one. "
                         "Rename or remove the duplicate.")
                    {:nex-error :duplicate-function :function dup})))
  ;; A `declare function` signature must be matched exactly by its later
  ;; definition. The declaration is collapsed away before evaluation, so the
  ;; authoritative interpreter rejects a mismatch rather than silently adopting
  ;; the definition's signature.
  (when-let [conflict (first function-signature-conflicts)]
    (throw (ex-info (:message conflict)
                    {:nex-error :function-signature-conflict :function (:name conflict)})))
  ;; Bind Set/Map value semantics for the whole run so collection membership and
  ;; dedup honour `equals`/`hash` overrides (and are structural otherwise).
  (with-value-semantics* ctx
    (fn []
      ;; First, store all import statements (for code generation)
      (doseq [import-node imports]
        (when (map? import-node)
          (swap! (:imports ctx) conj import-node)))

      ;; Then, process all intern statements
      (doseq [intern-node interns]
        (when (map? intern-node)
          (process-intern ctx intern-node)))

      ;; Register all class definitions
      (doseq [class-node classes]
        (when (map? class-node)
          (register-class ctx class-node)))

      ;; Register and instantiate functions
      (doseq [fn-node functions]
        (when (map? fn-node)
          (eval-node ctx fn-node)))

      ;; Execute top-level executable statements in source order.
      ;; Fall back to legacy :calls-only programs if :statements is absent.
      (doseq [stmt-node (if (seq statements) statements calls)]
        (when (map? stmt-node)
          (eval-node ctx stmt-node)))))

  ;; Return the context for inspection
  ctx)

(defmethod eval-node :class
  [ctx class-def]
  ;; Classes are just registered, not executed
  (register-class ctx class-def)
  nil)

(defmethod eval-node :function
  [ctx {:keys [name class-def class-name]}]
  ;; Register the generated function class and create a global instance
  (register-class ctx class-def)
  (let [obj (make-object class-name {})]
    (env-define (:current-env ctx) name obj)
    obj))

(defmethod eval-node :anonymous-function
  [ctx {:keys [class-def class-name]}]
  ;; Register the generated function class and return an object with closure env
  (register-class ctx class-def)
  (make-object class-name {} (:current-env ctx)))

(defn- write-back-target!
  "Propagate an updated object value back into a direct target expression.
   Supports plain variables, collection access (`get`), and field access."
  [ctx target-expr value & [old-value]]
  (letfn [(eval-target [expr]
            (if (string? expr)
              (env-lookup (:current-env ctx) expr)
              (eval-node ctx expr)))]
    (let [updated? (cond
                     (string? target-expr)
                     (do
                       (env-set! (:current-env ctx) target-expr value)
                       true)

                     (and (map? target-expr) (= :call (:type target-expr)))
                     (let [{:keys [target method args has-parens]} target-expr]
                       (cond
                         (some-> (-> (eval-target target-expr) meta write-back-target-key)
                                 (#(write-back-target! ctx % value old-value)))
                         true

                         (and has-parens (= method "get") (= 1 (count args)))
                         (let [coll (eval-target target)
                               idx  (eval-node ctx (first args))]
                           (cond
                             (nex-array? coll) (do (nex-array-set coll idx value) true)
                             (nex-map? coll)   (do (nex-map-put coll idx value) true)
                             :else false))

                         (and (false? has-parens)
                              target)
                         (let [parent (eval-target target)]
                           (if (and (nex-object? parent)
                                    (contains? (:fields parent) (keyword method)))
                             (let [updated-parent (make-object (:class-name parent)
                                                               (assoc (:fields parent) (keyword method) value)
                                                               (:closure-env parent))]
                               (write-back-target! ctx target updated-parent parent))
                             false))

                         :else false))

                     :else false)]
      (when updated?
        (env-replace-object-aliases! (:current-env ctx) old-value value))
      updated?)))

(defn dispatch-parent-call
  "Dispatch a call to a specific parent class's method/constructor on the current object."
  [ctx current-obj parent-class-name method arg-values]
  (let [parent-class-def (lookup-class ctx parent-class-name)
        ;; Try method first
        method-lookup (lookup-method-with-inheritance ctx parent-class-def method (count arg-values))
        ;; If no method found, try constructor
        ctor-def (when-not method-lookup
                   (lookup-constructor parent-class-def method))]
    (if-let [callable (or (:method method-lookup) ctor-def)]
      (let [class-def (lookup-class ctx (:class-name current-obj))
            all-fields (get-all-fields ctx class-def)
            ;; Track which fields belong to the parent class (for selective propagation)
            parent-fields (get-all-fields ctx parent-class-def)
            parent-field-names (set (map :name parent-fields))
            method-env (make-env (:current-env ctx))
            ;; Define fields first, then params (so params shadow fields with same name)
            _ (doseq [[field-name field-val] (:fields current-obj)]
                (env-define method-env (name field-name) field-val))
            params (:params callable)
            _ (ensure-callable-defined! callable)
            _ (when params
                (doseq [[param arg-val] (map vector params arg-values)]
                  (env-define method-env (:name param) arg-val)))
            return-type (:return-type callable)
            default-result (when return-type (get-default-field-value return-type))
            _ (env-define method-env "result" default-result)
            _ (env-define method-env "this" current-obj)
            new-ctx (-> ctx
                       (assoc :current-env method-env)
                       (assoc :current-object current-obj)
                       (assoc :current-target (:current-target ctx))
                       (assoc :current-class-name parent-class-name)
                       (assoc :current-method-name method)
                       (update :debug-stack (fnil conj [])
                               {:class parent-class-name
                                :method method
                                :env method-env
                                :arg-names (set (map :name (or params [])))
                                :field-names (set (map :name all-fields))
                                :source (:debug-source ctx)})
                       (assoc :debug-depth (inc (or (:debug-depth ctx) 0))))
            _ (if-let [rescue (:rescue callable)]
                (eval-body-with-rescue new-ctx (:body callable) rescue)
                (doseq [stmt (:body callable)]
                  (eval-node new-ctx stmt)))
            updated-fields (reduce (fn [m field]
                                    (let [field-name (:name field)
                                          field-key (keyword field-name)
                                          val (try
                                                (env-lookup method-env field-name)
                                                (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                                      (if (not= val ::not-found)
                                        (assoc m field-key val)
                                        m)))
                                  (:fields current-obj)
                                  all-fields)
            updated-obj (make-object (:class-name current-obj) updated-fields (:closure-env current-obj))
            result (let [res (try
                               (env-lookup method-env "result")
                               (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                     (if (not= res ::not-found)
                       res
                       nil))]
        ;; Update the object in the calling context
        (when-let [tgt (:current-target ctx)]
          (try
            (env-set! (:current-env ctx) tgt updated-obj)
            (catch #?(:clj Exception :cljs :default) _)))
        ;; Only update field env vars that belong to the parent class
        (doseq [[field-name field-val] (:fields updated-obj)]
          (when (contains? parent-field-names (name field-name))
            (try
              (env-set! (:current-env ctx) (name field-name) field-val)
              (catch #?(:clj Exception :cljs :default) _))))
        result)
      (throw (ex-info (str "Method not found in parent " parent-class-name ": " method)
                      {:parent parent-class-name :method method})))))

(defmethod eval-node :call
  [ctx {:keys [target method args has-parens]}]
  (maybe-debug-pause ctx {:type :call :target target :method method :args args :has-parens has-parens})
  (if (and (map? target) (= :create (:type target)) (nil? method))
    (if (nil? (:constructor target))
      (throw (ex-info (str "Invalid create syntax for " (:class-name target)
                           ". Use 'create " (:class-name target)
                           "' or 'create " (:class-name target) ".<ctor>(...)'.")
                      {:class-name (:class-name target)
                       :args args}))
      (eval-node ctx (assoc target :args args)))
    (let [arg-values (mapv #(eval-node ctx %) args)]
      (if target
      (let [target-name (when (string? target) target)
            class-target (when target-name (lookup-class-if-exists ctx target-name))
            ;; Check if target is a parent class name (parent-qualified call: A.method())
            parent-class (when (and target-name (:current-object ctx))
                           (let [cls (lookup-class-if-exists ctx target-name)]
                             (when (and cls
                                        (is-parent? ctx (:class-name (:current-object ctx)) target-name))
                               cls)))
            ;; Check if target is a Java host class (only when inside with "java" block)
            java-class? (and (:with-java? ctx)
                             target-name
                             (not class-target)
                             (not parent-class)
                             (re-matches #"[A-Z][A-Za-z0-9_]*" target-name)
                             (not (env-contains? (:current-env ctx) target-name)))
            obj (when-not (or parent-class java-class?)
                  (if class-target
                    nil
                    (if target-name
                    (env-lookup (:current-env ctx) target-name)
                    (eval-node ctx target))))]
        (cond
          ;; Java static method or field access inside with "java" block
          java-class?
          #?(:clj (let [klass (or (resolve-imported-java-class ctx target-name)
                                  (try (Class/forName (str "java.lang." target-name)) (catch Exception _ nil))
                                  (throw (ex-info (str "Undefined Java class: " target-name) {:class-name target-name})))]
                    (if has-parens
                      (clojure.lang.Reflector/invokeStaticMethod klass method (to-array arg-values))
                      (let [^java.lang.reflect.Field field (.getField klass method)]
                        (.get field nil))))
             :cljs nil)

          ;; Class-qualified constant access: A.CONST
          (and class-target
               (false? has-parens)
               (lookup-class-constant ctx class-target method))
          (eval-class-constant ctx class-target method)

          ;; Parent-qualified call: A.method() where A is a parent class
          parent-class
          (dispatch-parent-call ctx (:current-object ctx) target-name method arg-values)

          (nex-object? obj)
          (let [class-def (lookup-class ctx (:class-name obj))
                method-lookup (lookup-method-with-inheritance ctx class-def method (count arg-values))]
            (if method-lookup
                (let [method-def (:method method-lookup)
                    params (:params method-def)]
                (ensure-callable-defined! method-def)
                ;; Bug fix: disallow paren-less calls to methods that require arguments
                (when (and (false? has-parens) (seq params))
                  (throw (ex-info (str method " requires arguments")
                                  {:method method :params (mapv :name params)})))
                (let [source-class (:source-class method-lookup)
                    all-fields (get-all-fields ctx class-def)
                    effective-require (:effective-require method-lookup)
                    effective-ensure (:effective-ensure method-lookup)
                    has-postconditions? (seq effective-ensure)
                    old-values (when has-postconditions? (:fields obj))
                    source-obj (or (-> obj meta write-back-source-key) obj)]
                (let [method-env (make-env (or (:closure-env obj) (:current-env ctx)))
                      param-names (set (map :name params))
                      ;; Define fields first, then params — so params shadow fields
                      _ (doseq [[field-name field-val] (:fields obj)]
                          (env-define method-env (name field-name) field-val))
                      _ (bind-class-constants! ctx method-env class-def)
                      _ (when params
                          (doseq [[param arg-val] (map vector params arg-values)]
                            (env-define method-env (:name param) arg-val)))
                      modified-fields (atom #{})
                      return-type (:return-type method-def)
                      default-result (if return-type
                                      (get-default-field-value return-type)
                                      nil)
                      _ (env-define method-env "result" default-result)
                      _ (env-define method-env "this" obj)
                      new-ctx (-> ctx
                                 (assoc :current-env method-env)
                                 (assoc :current-object obj)
                                 (assoc :current-target target-name)
                                 (assoc :current-class-name (:name source-class))
                                 (assoc :current-method-name method)
                                 (assoc :old-values old-values)
                                 (assoc :modified-fields modified-fields)
                                 (update :debug-stack (fnil conj [])
                                         {:class (:name source-class)
                                          :method method
                                          :env method-env
                                          :arg-names (set (map :name (or params [])))
                                          :field-names (set (map name (keys (:fields obj))))
                                          :source (:debug-source ctx)})
                                 (assoc :debug-depth (inc (or (:debug-depth ctx) 0))))
                      _ (when-let [require-assertions effective-require]
                          (check-assertions new-ctx require-assertions Precondition))
                      _ (if-let [rescue (:rescue method-def)]
                          (eval-body-with-rescue new-ctx (:body method-def) rescue)
                          (doseq [stmt (:body method-def)]
                            (eval-node new-ctx stmt)))
                      updated-fields (reduce (fn [m field]
                                              (let [field-name (:name field)
                                                    field-key (keyword field-name)]
                                                ;; Skip fields shadowed by params unless explicitly modified via this.field :=
                                                (if (and (contains? param-names field-name)
                                                         (not (contains? @modified-fields field-name)))
                                                  m
                                                  (let [val (try
                                                              (env-lookup method-env field-name)
                                                              (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                                                    (if (not= val ::not-found)
                                                      (assoc m field-key val)
                                                      m)))))
                                            (:fields obj)
                                            all-fields)
                      updated-obj (make-object (:class-name obj) updated-fields (:closure-env obj))
                      result-flag (try
                                    (env-lookup method-env "__result_assigned__")
                                    (catch #?(:clj Exception :cljs :default) _ ::not-found))
                      result (cond
                               (= result-flag "result")
                               (env-lookup method-env "result")
                               :else
                               (let [res (try
                                           (env-lookup method-env "result")
                                           (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                                 (if (not= res ::not-found)
                                   res
                                   nil)))]
                  (try
                    (when-let [ensure-assertions effective-ensure]
                      (check-assertions new-ctx ensure-assertions Postcondition))
                    (check-class-invariant new-ctx class-def)
                    (write-back-target! ctx target updated-obj source-obj)
                    (annotate-reference-result target obj result)
                    (catch #?(:clj Exception :cljs :default) e
                      (write-back-target! ctx target source-obj source-obj)
                      (throw e))))))
              (let [field (lookup-field-with-inheritance ctx class-def method (:current-class-name ctx))]
                (if field
                  (let [field-val (get (:fields obj) (keyword method))]
                    (if (and has-parens (nex-object? field-val))
                      ;; Function field with parens: invoke callN on it
                      (let [call-method (str "call" (count arg-values))
                            literal-args (mapv (fn [v] {:type :literal :value v}) arg-values)]
                        (eval-node ctx {:type :call
                                        :target {:type :literal :value field-val}
                                        :method call-method
                                        :args literal-args}))
                      ;; No parens or not a Function: return field value (if no args)
                      (if (empty? arg-values)
                        field-val
                        (throw (ex-info (str "Method not found: " method)
                                        {:object obj :method method})))))
                  (if (get-in builtin-type-methods [:Any method])
                    (call-builtin-method ctx (or target-name target) obj method arg-values)
                    (throw (ex-info (str "Method not found: " method)
                                    {:object obj :method method})))))))

          (get-type-name obj)
          (call-builtin-method ctx (or target-name target) obj method arg-values)

          :else
          #?(:clj (let [compiled-class-name (compiled-runtime-class-name ctx obj)
                        compiled-class-def (when compiled-class-name
                                             (lookup-class-if-exists ctx compiled-class-name))]
                    (if compiled-class-def
                      (if (and (empty? arg-values)
                               (false? has-parens))
                        (if-let [_ (lookup-field-with-inheritance ctx
                                                                  compiled-class-def
                                                                  method
                                                                  (:current-class-name ctx))]
                          (if-let [[_ field-value] (compiled-object-field obj method)]
                            field-value
                            (throw (ex-info (str "Undefined field: " method)
                                            {:field method
                                             :class-name compiled-class-name})))
                          (if-let [_ (lookup-field-with-inheritance-any-visibility ctx
                                                                                   compiled-class-def
                                                                                   method)]
                            (throw (ex-info (str "Undefined field: " method)
                                            {:field method
                                             :class-name compiled-class-name}))
                            (runtime-resolve-call-user-method ctx obj method arg-values)))
                        (runtime-resolve-call-user-method ctx obj method arg-values))
                      (java-call-method obj method arg-values)))
             :cljs (throw (ex-info (str "Method not found on type: " method)
                                   {:target target :value obj :method method})))))

      (let [fn-obj (try
                     (env-lookup (:current-env ctx) method)
                     (catch #?(:clj Exception :cljs :default) _ ::not-found))]
        (if (not= fn-obj ::not-found)
          (let [compiled-callable? #?(:clj (boolean (compiled-runtime-class-name ctx fn-obj))
                                      :cljs false)]
            (cond
              (or (nex-object? fn-obj) compiled-callable?)
              (if (not= has-parens false)
                ;; has-parens is true or nil (default): invoke the Function
                (let [call-method (str "call" (count args))]
                  (eval-node ctx {:type :call
                                  :target method
                                  :method call-method
                                  :args args}))
                ;; has-parens is false: return the Function object
                fn-obj)

              ;; A plain host callable (e.g. a compiled top-level function bridged
              ;; into the interpreter for a deoptimized closure): apply it.
              (and (fn? fn-obj) (not= has-parens false))
              (apply fn-obj arg-values)

              ;; Variable value found (non-callable). In no-parens form, treat as identifier.
              ;; This keeps expressions like x + 1 working when parser emits :call for bare identifiers.
              (false? has-parens)
              fn-obj

              :else
              (throw (ex-info (str "Undefined function: " method)
                              {:function method}))))
          (if-let [current-obj (:current-object ctx)]
            (let [class-def (lookup-class ctx (:class-name current-obj))
                  method-lookup (lookup-method-with-inheritance ctx
                                                                class-def
                                                                method
                                                                (count args)
                                                                (:current-class-name ctx))]
              (if method-lookup
                (let [all-fields (get-all-fields ctx class-def)
                  current-env (:current-env ctx)
                  updated-fields (reduce (fn [m field]
                                          (let [field-name (:name field)
                                                field-key (keyword field-name)
                                                val (try
                                                      (env-lookup current-env field-name)
                                                      (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                                            (if (not= val ::not-found)
                                              (assoc m field-key val)
                                              m)))
                                        (:fields current-obj)
                                        all-fields)
                  updated-obj (make-object (:class-name current-obj) updated-fields (:closure-env current-obj))
                  _ (when-let [target-name (:current-target ctx)]
                      (env-set! (-> ctx :current-env :parent) target-name updated-obj))
                  result (eval-node ctx {:type :call
                                         :target (:current-target ctx)
                                         :method method
                                         :args args})
                  called-obj (when-let [target-name (:current-target ctx)]
                               (env-lookup (-> ctx :current-env :parent) target-name))
                  _ (when called-obj
                      (doseq [[field-name field-val] (:fields called-obj)]
                        (env-set! current-env (name field-name) field-val)))]
              result)
                (if-let [builtin (get builtins method)]
                  (apply builtin ctx arg-values)
                  (throw (ex-info (str "Undefined method: " method)
                                  {:function method :object current-obj})))))
            (if-let [builtin (get builtins method)]
              (apply builtin ctx arg-values)
              (throw (ex-info (str "Undefined function: " method)
                              {:function method}))))))))))

(defmethod eval-node :this
  [ctx _]
  (:current-object ctx))

(defmethod eval-node :member-assign
  [ctx {:keys [object object-type field value]}]
  (maybe-debug-pause ctx {:type :member-assign :object object :object-type object-type :field field :value value})
  (let [target-expr (or object (when (= object-type :this) {:type :this}))
        target-obj (eval-node ctx target-expr)
        class-name (or (:class-name target-obj) (:current-class-name ctx))
        class-def (when class-name (lookup-class-if-exists ctx class-name))
        field-def (when class-def
                    (lookup-field-with-inheritance ctx class-def field (:current-class-name ctx)))]
    (when-not (nex-object? target-obj)
      (throw (ex-info "Field assignment target must be an object"
                      {:target target-expr :value target-obj})))
    (when (and class-def (lookup-class-constant ctx class-def field))
      (throw (ex-info (str "Cannot assign to constant: " field)
                      {:field field :constant? true})))
    (when (and field-def (:once? field-def) (not (:in-constructor? ctx)))
      (throw (ex-info (str "Cannot assign to once field outside constructor: " field)
                      {:field field :once? true})))
    (when-not field-def
      (throw (ex-info (str "Undefined field: " field)
                      {:field field :class-name class-name})))
    (when-not (= (:current-class-name ctx) (:declaring-class field-def))
      (throw (ex-info (field-write-error-message field (:declaring-class field-def))
                      {:field field
                       :class-name class-name
                       :declaring-class (:declaring-class field-def)})))
    (let [val (eval-node ctx value)]
      (if (and (= (:type target-expr) :this) (:current-object ctx))
        (do
          ;; Track that this field was explicitly modified via this.field :=
          (when-let [mf (:modified-fields ctx)]
            (swap! mf conj field))
          ;; this.field sets the env variable
          ;; (fields are tracked as env vars, extracted back to object after body)
          (env-set! (:current-env ctx) field val)
          val)
        (let [updated-obj (make-object (:class-name target-obj)
                                       (assoc (:fields target-obj) (keyword field) val)
                                       (:closure-env target-obj))
              write-back-target (if (= :identifier (:type target-expr))
                                  (:name target-expr)
                                  target-expr)]
          (when-not (write-back-target! ctx write-back-target updated-obj target-obj)
            (throw (ex-info "Field assignment target is not writable"
                            {:target target-expr :field field})))
          val)))))

(defmethod eval-node :assign
  [ctx {:keys [target value]}]
  (maybe-debug-pause ctx {:type :assign :target target :value value})
  (when-let [current-class-name (:current-class-name ctx)]
    (when-let [class-def (lookup-class-if-exists ctx current-class-name)]
      (when (lookup-class-constant ctx class-def target)
        (throw (ex-info (str "Cannot assign to constant: " target)
                        {:target target :constant? true})))
      (when-let [field-def (lookup-field-with-inheritance ctx class-def target current-class-name)]
        (when (and (:once? field-def) (not (:in-constructor? ctx)))
          (throw (ex-info (str "Cannot assign to once field outside constructor: " target)
                          {:target target :once? true}))))))
  (let [val (eval-node ctx value)]
    ;; Assignment (without let) ONLY updates existing variables
    ;; It should fail if the variable doesn't exist
    (env-set! (:current-env ctx) target val)
    (when (#{"result"} target)
      (env-define (:current-env ctx) "__result_assigned__" target))
    ;; Strictly speaking, assignment is a statement and does not have a value,
    ;; but returning the value is helpful for repl users.
    val))

(defmethod eval-node :let
  [ctx {:keys [name value]}]
  (maybe-debug-pause ctx {:type :let :name name :value value})
  (let [val (eval-node ctx value)]
    ;; Always define a new binding in the current scope (can shadow outer scopes)
    (env-define (:current-env ctx) name val)
    (when (#{"result"} name)
      (env-define (:current-env ctx) "__result_assigned__" name))
    ;; Strictly speaking, let-binding is a statement and does not have a value,
    ;; but returning the value is helpful for repl users.
    val))

(defmethod eval-node :block
  [ctx statements]
  ;; Execute each statement and return the last value
  (when (sequential? statements)
    (last (map #(eval-node ctx %) statements))))

(defmethod eval-node :raise
  [ctx {:keys [value]}]
  (maybe-debug-pause ctx {:type :raise :value value})
  (let [val (eval-node ctx value)]
    (throw (ex-info (str val) {:type :nex-exception :value val}))))

(defmethod eval-node :retry
  [ctx _node]
  (maybe-debug-pause ctx {:type :retry})
  (throw (ex-info "retry" {:type :nex-retry})))

(defn eval-body-with-rescue
  "Execute body statements with rescue/retry support.
   If rescue contains retry, re-executes body.
   If rescue completes without retry, the exception is considered handled."
  [ctx body rescue]
  (let [should-retry (atom true)]
    (while @should-retry
      (reset! should-retry false)
      (try
        (doseq [stmt body] (eval-node ctx stmt))
        (catch #?(:clj Exception :cljs :default) e
          ;; Don't catch retry markers from nested blocks
          (if (and (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e)
                   (= :nex-retry (:type (ex-data e))))
            (throw e)
            ;; Real exception — run rescue
            (let [exc-value (if (and (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e)
                                    (= :nex-exception (:type (ex-data e))))
                              (:value (ex-data e))
                              (nex-error-message e))
                  rescue-env (make-env (:current-env ctx))
                  _ (env-define rescue-env "exception" exc-value)
                  rescue-ctx (assoc ctx :current-env rescue-env)]
              (try
                (doseq [stmt rescue] (eval-node rescue-ctx stmt))
                (catch #?(:clj Exception :cljs :default) re
                  (if (and (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) re)
                           (= :nex-retry (:type (ex-data re))))
                    (reset! should-retry true) ;; retry: loop back
                    (throw re)))))))))))

(defmethod eval-node :scoped-block
  [ctx {:keys [body rescue]}]
  (maybe-debug-pause ctx {:type :scoped-block :body body :rescue rescue})
  ;; Create a new lexical scope
  (let [new-env (make-env (:current-env ctx))
        new-ctx (assoc ctx :current-env new-env)]
    (if rescue
      (eval-body-with-rescue new-ctx body rescue)
      ;; Execute the block in the new scope
      (last (map #(eval-node new-ctx %) body)))))

(defmethod eval-node :when
  [ctx {:keys [condition consequent alternative]}]
  (if (eval-node ctx condition)
    (eval-node ctx consequent)
    (eval-node ctx alternative)))

(defmethod eval-node :convert
  [ctx {:keys [value var-name target-type]}]
  (let [v (eval-node ctx value)
        target-name (if (map? target-type) (:base-type target-type) target-type)
        runtime-name (runtime-type-name v)
        ok? (and (some? v)
                 (string? target-name)
                 (convert-compatible-runtime? ctx runtime-name target-name))]
    (env-define (:current-env ctx) var-name (if ok? v nil))
    ok?))

(defmethod eval-node :if
  [ctx {:keys [condition then elseif else]}]
  (maybe-debug-pause ctx {:type :if :condition condition :then then :elseif elseif :else else})
  ;; Evaluate condition, then elseif chain, then optional else
  (let [cond-val (eval-node ctx condition)]
    (if cond-val
      (last (map #(eval-node ctx %) then))
      (if-let [matched (some (fn [clause]
                               (when (eval-node ctx (:condition clause))
                                 clause))
                             elseif)]
        (last (map #(eval-node ctx %) (:then matched)))
        (when else
          (last (map #(eval-node ctx %) else)))))))

(defmethod eval-node :case
  [ctx {:keys [expr clauses else]}]
  (maybe-debug-pause ctx {:type :case :expr expr :clauses clauses :else else})
  (let [val (eval-node ctx expr)
        matched (loop [cs clauses]
                  (if (empty? cs)
                    ::no-match
                    (let [{:keys [values body]} (first cs)]
                      (if (some #(= val (eval-node ctx %)) values)
                        (eval-node ctx body)
                        (recur (rest cs))))))]
    (if (= matched ::no-match)
      (if else
        (eval-node ctx else)
        (throw (ex-info "No matching case and no else clause"
                        {:value val})))
      matched)))

(defmethod eval-node :match
  [ctx {:keys [expr clauses else]}]
  (maybe-debug-pause ctx {:type :match :expr expr :clauses clauses :else else})
  (let [val (eval-node ctx expr)
        val-class (or (when (nex-object? val) (:class-name val))
                      #?(:clj (compiled-runtime-class-name ctx val)))
        ;; A generic instance carries its specialized name (e.g. "Ok[Integer,String]")
        ;; while a `when` clause names the base class ("Ok"), so compare on the
        ;; base name too.
        val-class-base (when val-class
                         (if-let [i (clojure.string/index-of val-class "[")]
                           (subs val-class 0 i)
                           val-class))
        matched (some (fn [{:keys [class-name var-name body]}]
                        (when (and val-class
                                   (or (= val-class class-name)
                                       (= val-class-base class-name)
                                       (is-parent? ctx val-class class-name)
                                       (is-parent? ctx val-class-base class-name)))
                          (let [match-env (make-env (:current-env ctx))]
                            (env-define match-env var-name val)
                            [:matched (last (map #(eval-node (assoc ctx :current-env match-env) %) body))])))
                      clauses)]
    (if matched
      (second matched)
      (if else
        (last (map #(eval-node ctx %) else))
        (throw (ex-info "No matching clause in match"
                        {:value val}))))))

(defmethod eval-node :select
  [ctx {:keys [clauses else timeout] :as node}]
  (let [prepared (mapv #(prepare-select-clause ctx %) clauses)
        timeout-ms-val (when timeout (timeout-ms (eval-node ctx (:duration timeout))))
        deadline (when timeout-ms-val (+ (current-time-ms) timeout-ms-val))]
    (loop []
      (if-let [[clause outcome] (some (fn [prepared-clause]
                                        (when-let [outcome (attempt-select-clause prepared-clause)]
                                          [prepared-clause outcome]))
                                      prepared)]
        (execute-select-body ctx (:body clause) (:alias clause) (:value outcome))
        (if else
          (last (map #(eval-node ctx %) else))
          (if (and deadline (>= (current-time-ms) deadline))
            (last (map #(eval-node ctx %) (:body timeout)))
            (do
              (sleep-select-step!)
              (recur))))))))

(defmethod eval-node :loop
  [ctx {:keys [init invariant variant until body]}]
  (maybe-debug-pause ctx {:type :loop :init init :invariant invariant :variant variant :until until :body body})
  (let [loop-env (make-env (:current-env ctx))
        loop-ctx (assoc ctx :current-env loop-env)]
    ;; Execute initialization statements inside the loop scope.
    (doseq [stmt init]
      (eval-node loop-ctx stmt))

    ;; Loop until the 'until' condition becomes true
    (loop [last-result nil
           prev-variant nil
           iteration 0]
      ;; Check invariant before iteration (if present)
      (when invariant
        (check-assertions loop-ctx invariant Loop-invariant))

      ;; Check exit condition
      (let [until-val (eval-node loop-ctx until)]
        (if until-val
          ;; Exit loop
          last-result
          ;; Continue loop
          (let [;; Evaluate variant before body (if present)
                curr-variant (when variant (eval-node loop-ctx variant))

                ;; Check variant decreases (if present and not first iteration)
                _ (when (and variant prev-variant)
                    (when-not (< curr-variant prev-variant)
                      (throw (ex-info "Loop variant must decrease"
                                      {:iteration iteration
                                       :previous-variant prev-variant
                                       :current-variant curr-variant}))))

                ;; Execute loop body in a NEW scope each iteration
                ;; This ensures 'let' creates shadowed variables that don't persist
                ;; while plain ':=' can still update variables in parent scopes
                body-env (make-env (:current-env loop-ctx))
                body-ctx (assoc loop-ctx :current-env body-env)
                result (last (map #(eval-node body-ctx %) body))]

            ;; Check invariant after iteration (if present)
            (when invariant
              (check-assertions loop-ctx invariant Loop-invariant))

            ;; Recur with new state
            (recur result curr-variant (inc iteration))))))))

(defmethod eval-node :statement
  [ctx node]
  ;; Statement wrapper - just evaluate the inner node
  (eval-node ctx node))

(defmethod eval-node :binary
  [ctx {:keys [operator left right]}]
  (cond
    ;; Short-circuit logical operators: the right operand is evaluated only
    ;; when the left does not already determine the result (matches the JVM
    ;; compiler's `emit-boolean-short-circuit!`).
    (= operator "and")
    (let [left-val (eval-node ctx left)]
      (if left-val (eval-node ctx right) left-val))

    (= operator "or")
    (let [left-val (eval-node ctx left)]
      (if left-val left-val (eval-node ctx right)))

    :else
    (let [left-val (eval-node ctx left)
          right-val (eval-node ctx right)]
      (cond
        (and (= operator "+")
             (or (string? left-val) (string? right-val)))
        (str (concat-string-value ctx left-val)
             (concat-string-value ctx right-val))

        ;; Value equality honours a user-defined `equals` when an object is
        ;; involved; `==`/`!=` keep identity semantics in apply-binary-op.
        (and (or (= operator "=") (= operator "/="))
             (or (nex-object? left-val) (nex-object? right-val)))
        (let [eq (nex-objects-equal? ctx left-val right-val)]
          (if (= operator "=") eq (not eq)))

        :else
        (apply-binary-op operator left-val right-val)))))

(defmethod eval-node :unary
  [ctx {:keys [operator expr]}]
  (let [val (eval-node ctx expr)]
    (apply-unary-op operator val)))

(defmethod eval-node :integer
  [_ctx {:keys [value] :as node}]
  ;; Prefer the exact decimal string when present: the AST is parsed on the JVM
  ;; and may be transferred to JS, where a literal above 2^53 would lose precision
  ;; as a `number`. `:value-str` round-trips the full 64-bit value into a BigInt.
  (->nex-integer (or (:value-str node) value)))

(defmethod eval-node :real
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :boolean
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :char
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :string
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :nil
  [_ctx _node]
  nil)

(defmethod eval-node :array-literal
  [ctx {:keys [elements]}]
  ;; Evaluate all elements and return as a mutable array
  (nex-array-from (mapv #(eval-node ctx %) elements)))

(defmethod eval-node :set-literal
  [ctx {:keys [elements]}]
  (nex-set-from (mapv #(eval-node ctx %) elements)))

(defmethod eval-node :map-literal
  [ctx {:keys [entries]}]
  ;; Evaluate all key-value pairs and return as a mutable map
  (nex-map-from (mapv (fn [{:keys [key value]}]
                        [(eval-node ctx key) (eval-node ctx value)])
                      entries)))

(defmethod eval-node :identifier
  [ctx {:keys [name]}]
  (let [val (try
              (env-lookup (:current-env ctx) name)
              (catch #?(:clj Exception :cljs :default) _ ::not-found))]
    (if (not= val ::not-found)
      val
      ;; Not in env - check if it's a zero-arg method on current object
      (if-let [current-class-name (:current-class-name ctx)]
        (let [class-def (lookup-class ctx current-class-name)]
          (if-let [constant (lookup-class-constant ctx class-def name)]
            (eval-class-constant ctx (:declaring-class constant class-def) name)
            (if-let [current-obj (:current-object ctx)]
              (let [method-lookup (lookup-method-with-inheritance ctx
                                                                  class-def
                                                                  name
                                                                  0
                                                                  current-class-name)]
                (if method-lookup
                  ;; It's a method - invoke it (implicit this)
                  (eval-node ctx {:type :call
                                  :target (:current-target ctx)
                                  :method name
                                  :args []})
                  (throw (ex-info (str "Undefined variable: " name)
                                  {:var-name name}))))
              (throw (ex-info (str "Undefined variable: " name)
                              {:var-name name})))))
        (throw (ex-info (str "Undefined variable: " name)
                        {:var-name name}))))))

(defn get-all-fields
  "Get all fields from a class and its parents"
  [ctx class-def]
  (let [;; Get fields from parents first
        parent-fields (when-let [parents (get-parent-classes ctx class-def)]
                       (mapcat (fn [parent-info]
                                (get-all-fields ctx (:class-def parent-info)))
                              parents))
        ;; Get fields from current class
        current-fields (->> (:body class-def)
                           (mapcat (fn [section]
                                    (when (= (:type section) :feature-section)
                                      (:members section))))
                           (filter #(and (= (:type %) :field)
                                         (not (:constant? %)))))]
    (concat parent-fields current-fields)))

(defn lookup-constructor
  "Look up a constructor by name in a class"
  [class-def constructor-name]
  (->> (:body class-def)
       (mapcat (fn [section]
                 (when (= (:type section) :constructors)
                   (:constructors section))))
       (filter #(= (:name %) constructor-name))
       first))

(defn lookup-constructor-with-inheritance
  "Look up a constructor by name in a class and its parent chain."
  [ctx class-def constructor-name]
  (or (some-> (lookup-constructor class-def constructor-name)
              (assoc :source-class class-def))
      (some (fn [{:keys [class-def]}]
              (lookup-constructor-with-inheritance ctx class-def constructor-name))
            (get-parent-classes ctx class-def))))

(defmethod eval-node :create
  [ctx {:keys [class-name generic-args constructor args]}]
  ;; Handle built-in IO types
  (case class-name
    "Console" {:nex-builtin-type :Console}
    "Process" {:nex-builtin-type :Process}
    "Array" (let [arg-values (mapv #(eval-node ctx %) args)]
              (cond
                (nil? constructor) (nex-array)
                (= constructor "filled")
                (let [[size value] arg-values]
                  (when-not (integer? size)
                    (throw (ex-info "Array.filled requires an Integer size"
                                    {:class-name "Array" :constructor constructor})))
                  (when (neg? size)
                    (throw (ex-info "Array size must be non-negative"
                                    {:class-name "Array" :constructor constructor})))
                  (nex-array-from (vec (repeat size value))))
                :else
                (throw (ex-info (str "Constructor not found: Array." constructor)
                                {:class-name "Array" :constructor constructor}))))
    "Map" (nex-map)
    "Min_Heap" (let [arg-values (mapv #(eval-node ctx %) args)]
                 (cond
                   (or (nil? constructor) (= constructor "empty"))
                   (do
                     (when (seq arg-values)
                       (throw (ex-info "Min_Heap.empty expects no arguments"
                                       {:class-name "Min_Heap" :constructor constructor})))
                     (make-min-heap nil))

                   (= constructor "from_comparator")
                   (do
                     (when-not (= 1 (count arg-values))
                       (throw (ex-info "Min_Heap.from_comparator expects 1 argument"
                                       {:class-name "Min_Heap" :constructor constructor})))
                     (make-min-heap (first arg-values)))

                   :else
                   (throw (ex-info (str "Constructor not found: Min_Heap." constructor)
                                   {:class-name "Min_Heap" :constructor constructor}))))
    "Atomic_Integer" (let [arg-values (mapv #(eval-node ctx %) args)]
                       (when-not (= constructor "make")
                         (throw (ex-info (str "Constructor not found: Atomic_Integer." constructor)
                                         {:class-name "Atomic_Integer" :constructor constructor})))
                       (when-not (= 1 (count arg-values))
                         (throw (ex-info "Atomic_Integer.make expects 1 argument"
                                         {:class-name "Atomic_Integer" :constructor constructor})))
                       (make-atomic-integer (first arg-values)))
    "Atomic_Integer64" (let [arg-values (mapv #(eval-node ctx %) args)]
                         (when-not (= constructor "make")
                           (throw (ex-info (str "Constructor not found: Atomic_Integer64." constructor)
                                           {:class-name "Atomic_Integer64" :constructor constructor})))
                         (when-not (= 1 (count arg-values))
                           (throw (ex-info "Atomic_Integer64.make expects 1 argument"
                                           {:class-name "Atomic_Integer64" :constructor constructor})))
                         (make-atomic-integer64 (first arg-values)))
    "Atomic_Boolean" (let [arg-values (mapv #(eval-node ctx %) args)]
                       (when-not (= constructor "make")
                         (throw (ex-info (str "Constructor not found: Atomic_Boolean." constructor)
                                         {:class-name "Atomic_Boolean" :constructor constructor})))
                       (when-not (= 1 (count arg-values))
                         (throw (ex-info "Atomic_Boolean.make expects 1 argument"
                                         {:class-name "Atomic_Boolean" :constructor constructor})))
                       (make-atomic-boolean (first arg-values)))
    "Atomic_Reference" (let [arg-values (mapv #(eval-node ctx %) args)]
                         (when-not (= constructor "make")
                           (throw (ex-info (str "Constructor not found: Atomic_Reference." constructor)
                                           {:class-name "Atomic_Reference" :constructor constructor})))
                         (when-not (= 1 (count arg-values))
                           (throw (ex-info "Atomic_Reference.make expects 1 argument"
                                           {:class-name "Atomic_Reference" :constructor constructor})))
                         (make-atomic-reference (first arg-values)))
    "Channel" #?(:clj (let [arg-values (mapv #(eval-node ctx %) args)]
                        (cond
                          (nil? constructor) (make-channel)
                          (= constructor "with_capacity")
                          (let [capacity (first arg-values)]
                            (when-not (integer? capacity)
                              (throw (ex-info "Channel.with_capacity requires an Integer capacity"
                                              {:class-name "Channel" :constructor constructor})))
                            (when (neg? capacity)
                              (throw (ex-info "Channel capacity must be non-negative"
                                              {:class-name "Channel" :constructor constructor})))
                            (make-channel capacity))
                          :else
                          (throw (ex-info (str "Constructor not found: Channel." constructor)
                                          {:class-name "Channel" :constructor constructor}))))
                 :cljs (throw (ex-info "Channels are not supported in ClojureScript interpreter"
                                       {:class-name "Channel"})))
    "Set" (let [arg-values (mapv #(eval-node ctx %) args)]
            (cond
              (nil? constructor) (nex-set)
              (= constructor "from_array") (let [source (first arg-values)]
                                             (cond
                                               (nex-array? source) (nex-set-from #?(:clj source :cljs (array-seq source)))
                                               (sequential? source) (nex-set-from source)
                                               :else (throw (ex-info "Set.from_array requires an array"
                                                                     {:class-name "Set"}))))
              :else (throw (ex-info (str "Constructor not found: Set." constructor)
                                    {:class-name "Set" :constructor constructor}))))
  ;; Resolve effective class name (handle generic specialization)
  (let [effective-class-name
        (if (seq generic-args)
          (let [spec-name (specialized-class-name class-name generic-args)]
            (if (lookup-specialized-class ctx spec-name)
              spec-name
              ;; Need to create the specialization
              (let [template (get @(:classes ctx) class-name)]
                (when-not template
                  (throw (ex-info (str "Undefined template class: " class-name)
                                  {:class-name class-name})))
                (when-not (:generic-params template)
                  (throw (ex-info (str "Class " class-name " is not generic")
                                  {:class-name class-name})))
                (when (not= (count (:generic-params template)) (count generic-args))
                  (throw (ex-info (str "Type argument count mismatch for " class-name
                                       ": expected " (count (:generic-params template))
                                       ", got " (count generic-args))
                                  {:class-name class-name
                                   :expected (count (:generic-params template))
                                   :got (count generic-args)})))
                (let [specialized (specialize-class template generic-args)]
                  (register-specialized-class ctx specialized)
                  spec-name))))
          class-name)
        class-def (lookup-class-if-exists ctx effective-class-name)
        _ (when (and class-def (:deferred? class-def))
            (throw (ex-info (str "Cannot instantiate deferred class: " class-name)
                            {:class-name class-name
                             :deferred? true})))
        ;; Get all fields (including inherited)
        all-fields (when class-def (get-all-fields ctx class-def))
        ;; Initialize fields with default values
        initial-field-map (when class-def
                            (reduce (fn [m field]
                                      (assoc m (keyword (:name field))
                                             (get-default-field-value (:field-type field))))
                                    {}
                                    all-fields))
        ;; If a constructor is specified, call it and update fields
        final-field-map (when class-def
                          (if constructor
                            (let [ctor-def (lookup-constructor-with-inheritance ctx class-def constructor)]
                              (when-not ctor-def
                                (throw (ex-info (str "Constructor not found: " class-name "." constructor)
                                                {:class-name class-name :constructor constructor})))
                              ;; Create environment for constructor execution
                              (let [ctor-env (make-env (:current-env ctx))
                                    ;; Bind fields as local variables FIRST
                                    _ (doseq [[field-name field-val] initial-field-map]
                                        (env-define ctor-env (name field-name) field-val))
                                    _ (bind-class-constants! ctx ctor-env class-def)
                                    ;; Bind parameters SECOND (so they shadow fields with same name)
                                    params (:params ctor-def)
                                    arg-values (mapv #(eval-node ctx %) args)
                                    _ (when params
                                        (doseq [[param arg-val] (map vector params arg-values)]
                                          (env-define ctor-env (:name param) arg-val)))
                                    temp-obj (make-object effective-class-name initial-field-map)
                                    source-class-name (:name (:source-class ctor-def))
                                    new-ctx (-> ctx
                                               (assoc :current-env ctor-env)
                                               (assoc :current-object temp-obj)
                                               (assoc :current-class-name source-class-name)
                                               (assoc :current-method-name constructor)
                                               (assoc :in-constructor? true)
                                               (update :debug-stack (fnil conj [])
                                                       {:class source-class-name
                                                        :method (or constructor "make")
                                                        :env ctor-env
                                                        :arg-names (set (map :name (or params [])))
                                                        :field-names (set (map name (keys initial-field-map)))
                                                        :source (:debug-source ctx)})
                                               (assoc :debug-depth (inc (or (:debug-depth ctx) 0))))
                                    ;; Check pre-conditions
                                    _ (when-let [require-assertions (:require ctor-def)]
                                        (check-assertions new-ctx require-assertions Precondition))
                                    ;; Execute constructor body
                                    _ (if-let [rescue (:rescue ctor-def)]
                                        (eval-body-with-rescue new-ctx (:body ctor-def) rescue)
                                        (doseq [stmt (:body ctor-def)]
                                          (eval-node new-ctx stmt)))
                                    ;; Update object fields from modified environment
                                 updated-fields (reduce (fn [m field]
                                                         (let [field-name (:name field)
                                                               field-key (keyword field-name)
                                                               val (try
                                                                     (env-lookup ctor-env field-name)
                                                                     (catch #?(:clj Exception :cljs :default) _ ::not-found))]
                                                           (if (not= val ::not-found)
                                                             (assoc m field-key val)
                                                             m)))
                                                       initial-field-map
                                                       all-fields)
                                    ;; Check post-conditions
                                    _ (when-let [ensure-assertions (:ensure ctor-def)]
                                        (check-assertions new-ctx ensure-assertions Postcondition))]
                                updated-fields))
                            ;; No constructor: use default initialization
                            initial-field-map))
        ;; Create the final object
        obj (when class-def
              (make-object effective-class-name final-field-map))]

    ;; Check class invariant with object fields in scope
    (if class-def
      (do
        (let [inv-env (make-env (:current-env ctx))
              _ (doseq [[field-name field-val] final-field-map]
                  (env-define inv-env (name field-name) field-val))
              inv-ctx (assoc ctx :current-env inv-env)]
          (check-class-invariant inv-ctx class-def))
        ;; Return the object
        obj)
      ;; Java interop fallback (CLJ only)
      #?(:clj (java-create-object ctx class-name (mapv #(eval-node ctx %) args))
         :cljs (throw (ex-info (str "Undefined class: " class-name)
                               {:class-name class-name})))))))

(defmethod eval-node :spawn
  [ctx {:keys [body]}]
  #?(:clj
     (make-task
       (CompletableFuture/supplyAsync
        (reify java.util.function.Supplier
          (get [_]
            (let [spawn-env (make-env (:current-env ctx))
                  _ (env-define spawn-env "result" nil)
                  spawn-ctx (assoc ctx :current-env spawn-env)]
              (doseq [stmt body]
                (eval-node spawn-ctx stmt))
              (let [result-flag (try
                                  (env-lookup spawn-env "__result_assigned__")
                                  (catch Exception _ ::not-found))]
                (if (= result-flag "result")
                  (env-lookup spawn-env "result")
                  nil)))))
        concurrent-executor))
     :cljs
     (throw (ex-info "spawn is not supported in ClojureScript interpreter"
                     {:type :unsupported}))))

(defmethod eval-node :literal
  [_ctx node]
  ;; Handle literal values that might be passed directly
  (cond
    (string? node) node
    (map? node) (:value node)
    :else node))

(defmethod eval-node :old
  [ctx {:keys [expr]}]
  ;; Look up the value from the old-values snapshot
  (if-let [old-values (:old-values ctx)]
    (if (and (map? expr) (= (:type expr) :identifier))
      ;; Simple identifier: look up in old values
      (let [var-name (:name expr)]
        (if (contains? old-values (keyword var-name))
          (get old-values (keyword var-name))
          (throw (ex-info (str "'old' can only be used on object fields in postconditions")
                         {:variable var-name}))))
      ;; More complex expression: evaluate it in an environment with old values
      (let [old-env (make-env (:current-env ctx))
            _ (doseq [[field-name field-val] old-values]
                (env-define old-env (name field-name) field-val))
            old-ctx (assoc ctx :current-env old-env)]
        (eval-node old-ctx expr)))
    (throw (ex-info "'old' can only be used in postconditions"
                   {:expr expr}))))

(defmethod eval-node :with
  [ctx {:keys [target body]}]
  (when (= target "java")
    (let [ctx' (assoc ctx :with-java? true)]
      (doseq [stmt body] (eval-node ctx' stmt)))))

(defmethod eval-node :default
  [ctx node]
  ;; If it's a plain string (identifier), look it up
  (if (string? node)
    (env-lookup (:current-env ctx) node)
    (throw (ex-info (str "Cannot evaluate node type: " (or (:type node) (type node)))
                    {:node node}))))

;;
;; Public API
;;

(defn interpret
  "Interpret an AST and return the context with results."
  [ast]
  (let [ctx (make-context)]
    (eval-node ctx ast)
    ctx))

(defn interpret-and-get-output
  "Interpret an AST and return the output as a vector of strings."
  [ast]
  (let [ctx (interpret ast)]
    @(:output ctx)))

(defn run
  "Convenience function to interpret and print output."
  [ast]
  (let [output (interpret-and-get-output ast)]
    (doseq [line output]
      (println line))
    output))


;; ---------------------------------------------------------------------------
;; Engine-hook registration: give the extracted builtin library
;; (nex.types.builtins) this engine's object representation and evaluator.
;; ---------------------------------------------------------------------------
(bi/set-engine-hooks!
 {:nex-object? nex-object?
  :make-object make-object
  :object-equals-override object-equals-override
  :call-object-method (fn [ctx obj method args]
                        (eval-node ctx {:type :call
                                        :target {:type :literal :value obj}
                                        :method method
                                        :args (mapv (fn [v] {:type :literal :value v}) args)}))
  :add-output add-output
  :is-parent? is-parent?
  :user-to-string (fn [ctx value]
                    (when (and ctx (nex-object? value))
                      (let [class-def (lookup-class ctx (:class-name value))]
                        (when (lookup-method-with-inheritance ctx class-def "to_string" 0)
                          (let [result (eval-node ctx {:type :call
                                                       :target {:type :literal :value value}
                                                       :method "to_string"
                                                       :args []})]
                            (if (string? result) result (nex-format-value result)))))))})
