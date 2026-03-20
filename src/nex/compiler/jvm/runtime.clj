(ns nex.compiler.jvm.runtime
  "Small runtime support for the future JVM bytecode compiler."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [nex.interpreter :as interp]
            [nex.types.bootstrap :as bootstrap]
            [nex.types.datetime :as dt]
            [nex.types.http :as http]
            [nex.types.json :as json-types]
            [nex.types.regex :as regex-types]
            [nex.types.runtime :as rt]
            [nex.types.value :as value]
            [nex.types.typeinfo :as typeinfo])
  (:import [clojure.lang DynamicClassLoader]
           [java.lang.reflect Field Method]
           [java.util HashMap]
           [java.util.concurrent CompletableFuture TimeUnit TimeoutException ExecutionException CancellationException]))

(declare rebuild-interpreter-ctx)
(declare lowered-instance-method-name)
(declare reflected-field)
(declare runtime-type-name)
(declare runtime-compatible-with?)
(declare invoke-user-method)

(defmacro ^:private def-builtin-method-wrapper
  [fn-name method-name]
  `(defn ~fn-name
     [target# & args#]
     (interp/call-builtin-method nil target# target# ~method-name (vec args#))))

(defrecord NexReplState [^clojure.lang.Atom values
                         ^clojure.lang.Atom types
                         ^clojure.lang.Atom functions
                         ^clojure.lang.Atom output
                         ^clojure.lang.Atom classes
                         ^clojure.lang.Atom imports
                         ^clojure.lang.Atom counter
                         ^DynamicClassLoader loader])

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
  (let [ctx (rebuild-interpreter-ctx state)
        imported (interp/resolve-imported-java-class ctx class-name)]
    (or imported
        (try
          (Class/forName class-name)
          (catch Exception _ nil))
        (try
          (Class/forName (str "java.lang." class-name))
          (catch Exception _ nil))
        (throw (ex-info (str "Undefined Java class: " class-name)
                        {:class-name class-name})))))

(defn invoke-repl-fn
  [state name args]
  (let [{:keys [owner method]} (state-get-fn state name)]
    (when-not (and owner method)
      (throw (ex-info (str "Undefined compiled REPL function: " name)
                      {:name name})))
    (let [^Class owner-class (resolve-owner-class state owner)
          ^Method target-method (.getDeclaredMethod owner-class
                                                    method
                                                    (into-array Class [nex.compiler.jvm.runtime.NexReplState
                                                                       (class (object-array 0))]))]
      (.invoke target-method nil (object-array [state (object-array args)])))))

(defn invoke-function-object
  [state target args]
  (when-not target
    (throw (ex-info "Cannot invoke Void as a function"
                    {:target target
                     :args args})))
  (if (interp/nex-object? target)
    (let [ctx (rebuild-interpreter-ctx state)
          call-method (str "call" (count args))
          literal-args (mapv (fn [v] {:type :literal :value v}) args)]
      (interp/eval-node ctx {:type :call
                             :target {:type :literal :value target}
                             :method call-method
                             :args literal-args}))
    (let [^Class cls (.getClass target)
          lowered-name (lowered-instance-method-name (str "call" (count args)) (count args))
          ^Method method (.getDeclaredMethod cls
                                             lowered-name
                                             (into-array Class [nex.compiler.jvm.runtime.NexReplState
                                                                (class (object-array 0))]))]
      (.invoke method target (object-array [state (object-array args)])))))

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
   (let [ctx (interp/make-context)
         create-node {:type :create
                      :class-name "Channel"
                      :generic-args nil
                      :constructor (when (pos? capacity) "with_capacity")
                      :args (if (pos? capacity)
                              [{:type :literal :value capacity}]
                              [])}]
     (interp/eval-node ctx create-node))))

(defn java-create-object
  [state class-name args]
  (let [ctx (rebuild-interpreter-ctx state)]
    (interp/java-create-object ctx class-name args)))

(defn java-call-static
  [state class-name method-name args]
  (let [^Class klass (resolve-java-host-class state class-name)]
    (clojure.lang.Reflector/invokeStaticMethod klass method-name (to-array args))))

(defn java-call-method
  [state method-name target args]
  (interp/java-call-method target method-name args))

(defn java-get-static-field
  [state class-name field-name]
  (let [^Class klass (resolve-java-host-class state class-name)
        ^Field field (or (reflected-field klass field-name)
                         (throw (ex-info (str "Undefined Java static field: " field-name)
                                         {:field field-name
                                          :class (.getName klass)})))]
    (.get field nil)))

(defn java-get-field
  [field-name target]
  (let [^Field field (or (reflected-field (.getClass target) field-name)
                         (throw (ex-info (str "Undefined Java field: " field-name)
                                         {:field field-name
                                          :class (.getName (.getClass target))})))]
    (.get field target)))

(defn java-set-field!
  [field-name target value]
  (let [^Field field (or (reflected-field (.getClass target) field-name)
                         (throw (ex-info (str "Undefined Java field: " field-name)
                                         {:field field-name
                                          :class (.getName (.getClass target))})))]
    (.set field target value)
    nil))

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
   (interp/call-builtin-method nil ch ch "send" [value]))
  ([ch value timeout]
   (interp/call-builtin-method nil ch ch "send" [value timeout])))

(defn channel-try-send-method
  [ch value]
  (interp/call-builtin-method nil ch ch "try_send" [value]))

(defn channel-receive-method
  ([ch]
   (interp/call-builtin-method nil ch ch "receive" []))
  ([ch timeout]
   (interp/call-builtin-method nil ch ch "receive" [timeout])))

(defn channel-try-receive-method
  [ch]
  (interp/call-builtin-method nil ch ch "try_receive" []))

(defn channel-close-method
  [ch]
  (interp/call-builtin-method nil ch ch "close" []))

(defn channel-is-closed-method
  [ch]
  (interp/call-builtin-method nil ch ch "is_closed" []))

(defn channel-capacity-method
  [ch]
  (interp/call-builtin-method nil ch ch "capacity" []))

(defn channel-size-method
  [ch]
  (interp/call-builtin-method nil ch ch "size" []))

(defn- invoke-interpreter-object-method
  [state target method-name args]
  (let [ctx (rebuild-interpreter-ctx state)]
    (interp/eval-node ctx {:type :call
                           :target {:type :literal :value target}
                           :method method-name
                           :args (mapv (fn [v] {:type :literal :value v}) args)
                           :has-parens true})))

(defn- dispatch-cursor-method
  [state target method-name args]
  (let [runtime-name (runtime-type-name state target)]
    (if (and (string? runtime-name)
             (runtime-compatible-with? state runtime-name "Cursor"))
      (if (interp/nex-object? target)
        (invoke-interpreter-object-method state target method-name args)
        (invoke-user-method state target method-name args))
      (interp/call-builtin-method nil target target method-name args))))

(defn builtin-cursor-start
  [state target]
  (dispatch-cursor-method state target "start" []))

(defn builtin-cursor-cursor
  [state target]
  (dispatch-cursor-method state target "cursor" []))

(defn builtin-cursor-item
  [state target]
  (dispatch-cursor-method state target "item" []))

(defn builtin-cursor-next
  [state target]
  (dispatch-cursor-method state target "next" []))

(defn builtin-cursor-at-end
  [state target]
  (dispatch-cursor-method state target "at_end" []))

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

(defn make-captured-function-object
  [state class-name capture-args]
  (when-not (even? (count capture-args))
    (throw (ex-info "Captured closure args must be name/value pairs"
                    {:class-name class-name
                     :capture-args capture-args})))
  (let [ctx (rebuild-interpreter-ctx state)
        closure-env (interp/make-env (:current-env ctx))]
    (doseq [[name value] (partition 2 capture-args)]
      (interp/env-define closure-env name value))
    (interp/make-object class-name {} closure-env)))

(defn- compiled-class-binary-name
  [state class-name]
  (some-> (.get ^HashMap @(:classes state) class-name)
          :binary-name))

(defn- instantiate-compiled-object
  [state class-name field-values]
  (let [binary-name (compiled-class-binary-name state class-name)]
    (when binary-name
      (let [^Class cls (resolve-owner-class state binary-name)
            ctor (.getDeclaredConstructor cls (into-array Class []))
            instance (.newInstance ctor (object-array 0))]
        (doseq [[field-name field-value] field-values]
          (when-let [^Field field (reflected-field cls (name field-name))]
            (.set field instance field-value)))
        instance))))

(defn- make-runtime-object
  [state class-name field-values]
  (or (instantiate-compiled-object state class-name field-values)
      (interp/make-object class-name field-values)))

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

(defn print-state-output!
  [state]
  (doseq [line (state-output state)]
    (println line))
  nil)

(defn- add-output!
  [state line]
  (swap! (:output state) conj line)
  nil)

(defn- rebuild-interpreter-ctx
  [state]
  (let [ctx (interp/make-context)]
    (reset! (:bindings (:globals ctx)) {})
    (reset! (:output ctx) @(:output state))
    (reset! (:imports ctx) (vec @(:imports state)))
    (swap! (:classes ctx)
           (fn [builtins]
             (let [copy (HashMap.)]
               (doseq [[k v] builtins]
                 (.put copy k v))
               (doseq [[k v] @(:classes state)]
                 (.put copy k v))
               copy)))
    (doseq [[k v] @(:values state)]
      (interp/env-define (:globals ctx) k v))
    ctx))

(defn- lowered-instance-method-name
  [method-name arity]
  (str "__method_" method-name "$arity" arity))

(defn- reflected-field
  [^Class cls field-name]
  (or (try
        (.getField cls field-name)
        (catch Exception _ nil))
      (some (fn [^Field f]
              (when (= (.getName f) field-name)
                (.setAccessible f true)
                f))
            (.getDeclaredFields cls))))

(defn- composition-fields
  [^Class cls]
  (->> (.getDeclaredFields cls)
       (filter (fn [^Field f] (str/starts-with? (.getName f) "_parent_")))
       (map (fn [^Field f]
              (.setAccessible f true)
              f))))

(defn- deep-reflected-field
  [value field-name]
  (or (when-let [^Field f (reflected-field (.getClass value) field-name)]
        [value f])
      (some (fn [^Field parent-field]
              (when-let [parent-value (.get parent-field value)]
                (deep-reflected-field parent-value field-name)))
            (composition-fields (.getClass value)))))

(defn- invoke-user-method
  [state target method-name args]
  (let [^Class cls (.getClass target)
        lowered-name (lowered-instance-method-name method-name (count args))]
    (try
      (let [^Method method (.getDeclaredMethod cls
                                               lowered-name
                                               (into-array Class [nex.compiler.jvm.runtime.NexReplState
                                                                  (class (object-array 0))]))]
        (.invoke method target (object-array [state (object-array args)])))
      (catch NoSuchMethodException e
        (let [runtime-name (runtime-type-name state target)]
          (if (and (= method-name "cursor")
                   (empty? args)
                   (string? runtime-name)
                   (runtime-compatible-with? state runtime-name "Cursor"))
            target
            (throw (ex-info (format "No matching method %s found taking %d args for class %s"
                                    method-name
                                    (count args)
                                    (.getName cls))
                            {:method method-name
                             :arity (count args)
                             :class (.getName cls)}
                            e))))))))

(defn- get-user-field
  [target field-name]
  (let [[owner ^Field field] (or (deep-reflected-field target field-name)
                                 (throw (ex-info (str "Undefined compiled field: " field-name)
                                                 {:field field-name
                                                  :class (.getName (.getClass target))})))]
    (.get field owner)))

(defn- set-user-field!
  [target field-name value]
  (let [[owner ^Field field] (or (deep-reflected-field target field-name)
                                 (throw (ex-info (str "Undefined compiled field: " field-name)
                                                 {:field field-name
                                                  :class (.getName (.getClass target))})))]
    (.set field owner value)
    nil))

(defn make-raised-exception
  [value]
  (ex-info (str value) {:type :nex-exception :value value}))

(defn make-retry-signal
  []
  (ex-info "retry" {:type :nex-retry}))

(defn retry-signal?
  [throwable]
  (and (instance? clojure.lang.ExceptionInfo throwable)
       (= :nex-retry (:type (ex-data throwable)))))

(defn exception-value
  [throwable]
  (if (and (instance? clojure.lang.ExceptionInfo throwable)
           (= :nex-exception (:type (ex-data throwable))))
    (:value (ex-data throwable))
    (.getMessage ^Throwable throwable)))

(defn- compiled-runtime-class-name
  [state value]
  (when value
    (let [binary-name (.getName (.getClass value))
          simple-name (last (str/split binary-name #"\."))]
      (cond
        (contains? @(:classes state) simple-name)
        simple-name

        :else
        (when-let [[_ candidate] (re-matches #"(.+)_\d{4}" simple-name)]
          (when (contains? @(:classes state) candidate)
            candidate))))))

(defn- runtime-type-name
  [state value]
  (or (compiled-runtime-class-name state value)
      (typeinfo/runtime-type-name interp/nex-object? typeinfo/get-type-name value)))

(defn convert-value
  [state value target-type-name]
  (let [runtime-name (runtime-type-name state value)
        ok? (and (some? value)
                 (string? target-type-name)
                 (runtime-compatible-with? state runtime-name target-type-name))]
    (object-array [(boolean ok?) (if ok? value nil)])))

(defn make-contract-violation
  [kind label]
  (ex-info (if (= kind "Loop variant")
             "Loop variant must decrease"
             (str kind " violation: " label))
           {:type :nex-contract-violation
            :kind kind
            :label label}))

(defn- class-def-by-name
  [state class-name]
  (.get ^HashMap @(:classes state) class-name))

(defn- compiled-is-parent?
  [state class-name parent-name]
  (letfn [(walk [current seen]
            (when (and current (not (contains? seen current)))
              (when-let [class-def (class-def-by-name state current)]
                (let [seen' (conj seen current)
                      parents (:parents class-def)]
                  (or (some #(= (:parent %) parent-name) parents)
                      (some #(walk (:parent %) seen') parents))))))]
    (walk class-name #{})))

(defn- runtime-compatible-with?
  [state runtime-name target-name]
  (let [ctx (rebuild-interpreter-ctx state)]
    (or (= runtime-name target-name)
        (compiled-is-parent? state runtime-name target-name)
        (typeinfo/convert-compatible-runtime? interp/is-parent? ctx runtime-name target-name))))

(defn- collect-effective-field-names
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

(defn validate-object-state
  [state class-name value]
  (when-not (some? value)
    (throw (ex-info "Cannot validate nil object on compiled path"
                    {:class-name class-name})))
  (let [class-def (class-def-by-name state class-name)]
    (when-not class-def
      (throw (ex-info "Missing compiled class metadata for object validation"
                      {:class-name class-name})))
    (let [ctx (rebuild-interpreter-ctx state)
          runtime-name (runtime-type-name state value)
          compatible? (and (string? class-name)
                           (string? runtime-name)
                           (runtime-compatible-with? state runtime-name class-name))]
      (when-not compatible?
        (throw (ex-info "Compiled object model mismatch"
                        {:expected class-name
                         :runtime runtime-name})))
      (let [inv-env (interp/make-env (:current-env ctx))]
        (doseq [field-name (collect-effective-field-names state class-def)]
          (interp/env-define inv-env field-name (get-user-field value field-name)))
        (interp/check-class-invariant (assoc ctx :current-env inv-env) class-def))
      value)))

(defn- concat-string-value
  [state value]
  (cond
    (string? value) value

    (nil? value) "nil"

    :else
    (let [string-value
          (try
            (let [result (invoke-user-method state value "to_string" [])]
              (if (string? result)
                result
                (interp/nex-format-value result)))
            (catch Exception _
              (interp/call-builtin-method nil nil value "to_string" [])))]
      (if (string? string-value)
        string-value
        (interp/nex-format-value string-value)))))

(defn format-value
  [value]
  (interp/nex-format-value value))

(defn- object-field-value
  [value field-name]
  (cond
    (interp/nex-object? value)
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

(defn pow-int
  [a b]
  (int (rt/nex-int-pow (int a) (int b))))

(defn pow-long
  [a b]
  (long (rt/nex-int-pow (long a) (long b))))

(defn pow-double
  [a b]
  (Math/pow (double a) (double b)))

(defn builtin-print!
  [state args]
  (add-output! state (str/join " " (map format-value args))))

(defn builtin-println!
  [state args]
  (add-output! state (str/join " " (map format-value args))))

(defn builtin-type-of
  [state value]
  (runtime-type-name state value))

(defn- runtime-type-is
  [state target-type value]
  (let [ctx (rebuild-interpreter-ctx state)]
    (typeinfo/runtime-type-is? #(runtime-type-name state %) interp/is-parent? ctx target-type value)))

(defn builtin-type-is
  [state target-type value]
  (runtime-type-is state target-type value))

(defn builtin-sleep!
  [millis]
  (Thread/sleep (long millis))
  nil)

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

(defn builtin-binary-file-close
  [handle]
  (rt/binary-file-close handle))

(def-builtin-method-wrapper builtin-method-any-to-string "to_string")
(def-builtin-method-wrapper builtin-method-any-equals "equals")
(def-builtin-method-wrapper builtin-method-any-clone "clone")

(def-builtin-method-wrapper builtin-method-integer-to-string "to_string")
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

(def-builtin-method-wrapper builtin-method-integer64-to-string "to_string")
(def-builtin-method-wrapper builtin-method-integer64-abs "abs")
(def-builtin-method-wrapper builtin-method-integer64-min "min")
(def-builtin-method-wrapper builtin-method-integer64-max "max")
(def-builtin-method-wrapper builtin-method-integer64-compare "compare")
(def-builtin-method-wrapper builtin-method-integer64-hash "hash")
(def-builtin-method-wrapper builtin-method-integer64-plus "plus")
(def-builtin-method-wrapper builtin-method-integer64-minus "minus")
(def-builtin-method-wrapper builtin-method-integer64-times "times")
(def-builtin-method-wrapper builtin-method-integer64-divided-by "divided_by")
(def-builtin-method-wrapper builtin-method-integer64-equals "equals")
(def-builtin-method-wrapper builtin-method-integer64-not-equals "not_equals")
(def-builtin-method-wrapper builtin-method-integer64-less-than "less_than")
(def-builtin-method-wrapper builtin-method-integer64-less-than-or-equal "less_than_or_equal")
(def-builtin-method-wrapper builtin-method-integer64-greater-than "greater_than")
(def-builtin-method-wrapper builtin-method-integer64-greater-than-or-equal "greater_than_or_equal")

(def-builtin-method-wrapper builtin-method-real-to-string "to_string")
(def-builtin-method-wrapper builtin-method-real-abs "abs")
(def-builtin-method-wrapper builtin-method-real-min "min")
(def-builtin-method-wrapper builtin-method-real-max "max")
(def-builtin-method-wrapper builtin-method-real-round "round")
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

(def-builtin-method-wrapper builtin-method-decimal-to-string "to_string")
(def-builtin-method-wrapper builtin-method-decimal-abs "abs")
(def-builtin-method-wrapper builtin-method-decimal-min "min")
(def-builtin-method-wrapper builtin-method-decimal-max "max")
(def-builtin-method-wrapper builtin-method-decimal-round "round")
(def-builtin-method-wrapper builtin-method-decimal-compare "compare")
(def-builtin-method-wrapper builtin-method-decimal-hash "hash")
(def-builtin-method-wrapper builtin-method-decimal-plus "plus")
(def-builtin-method-wrapper builtin-method-decimal-minus "minus")
(def-builtin-method-wrapper builtin-method-decimal-times "times")
(def-builtin-method-wrapper builtin-method-decimal-divided-by "divided_by")
(def-builtin-method-wrapper builtin-method-decimal-equals "equals")
(def-builtin-method-wrapper builtin-method-decimal-not-equals "not_equals")
(def-builtin-method-wrapper builtin-method-decimal-less-than "less_than")
(def-builtin-method-wrapper builtin-method-decimal-less-than-or-equal "less_than_or_equal")
(def-builtin-method-wrapper builtin-method-decimal-greater-than "greater_than")
(def-builtin-method-wrapper builtin-method-decimal-greater-than-or-equal "greater_than_or_equal")

(def-builtin-method-wrapper builtin-method-char-to-string "to_string")
(def-builtin-method-wrapper builtin-method-char-to-upper "to_upper")
(def-builtin-method-wrapper builtin-method-char-to-lower "to_lower")
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

(def-builtin-method-wrapper builtin-method-string-length "length")
(def-builtin-method-wrapper builtin-method-string-index-of "index_of")
(def-builtin-method-wrapper builtin-method-string-substring "substring")
(def-builtin-method-wrapper builtin-method-string-to-upper "to_upper")
(def-builtin-method-wrapper builtin-method-string-to-lower "to_lower")
(def-builtin-method-wrapper builtin-method-string-to-integer "to_integer")
(def-builtin-method-wrapper builtin-method-string-to-integer64 "to_integer64")
(def-builtin-method-wrapper builtin-method-string-to-real "to_real")
(def-builtin-method-wrapper builtin-method-string-to-decimal "to_decimal")
(def-builtin-method-wrapper builtin-method-string-contains "contains")
(def-builtin-method-wrapper builtin-method-string-starts-with "starts_with")
(def-builtin-method-wrapper builtin-method-string-ends-with "ends_with")
(def-builtin-method-wrapper builtin-method-string-trim "trim")
(def-builtin-method-wrapper builtin-method-string-replace "replace")
(def-builtin-method-wrapper builtin-method-string-char-at "char_at")
(def-builtin-method-wrapper builtin-method-string-compare "compare")
(def-builtin-method-wrapper builtin-method-string-hash "hash")
(def-builtin-method-wrapper builtin-method-string-split "split")
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
(def-builtin-method-wrapper builtin-method-console-read-integer "read_integer")
(def-builtin-method-wrapper builtin-method-console-read-real "read_real")

(def-builtin-method-wrapper builtin-method-process-getenv "getenv")
(def-builtin-method-wrapper builtin-method-process-setenv "setenv")
(def-builtin-method-wrapper builtin-method-process-command-line "command_line")

(defn deep-equals
  [a b]
  (value/nex-deep-equals? interp/nex-object? a b))

(defn clone-value
  [value]
  (value/nex-clone-value interp/nex-object? interp/make-object value))

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

(defn map-contains-key
  [values needle]
  (boolean
   (some #(deep-equals (.getKey ^java.util.Map$Entry %) needle)
         (.entrySet ^java.util.Map values))))

(defn set-contains
  [values needle]
  (boolean (some #(deep-equals % needle) values)))

(defn map-get
  [state m key]
  (let [ctx (rebuild-interpreter-ctx state)]
    (interp/call-builtin-method ctx m m "get" [key])))

(defn map-try-get
  [state m key default]
  (let [ctx (rebuild-interpreter-ctx state)]
    (interp/call-builtin-method ctx m m "try_get" [key default])))

(defn array-sort
  [state values]
  (let [ctx (rebuild-interpreter-ctx state)
        result (interp/call-builtin-method ctx values values "sort" [])]
    (reset! (:output state) @(:output ctx))
    result))

(defn array-join
  [state values sep]
  (let [ctx (rebuild-interpreter-ctx state)
        result (interp/call-builtin-method ctx values values "join" [sep])]
    (reset! (:output state) @(:output ctx))
    result))

(defn collection-cursor
  [state kind value]
  (let [ctx (rebuild-interpreter-ctx state)
        result (interp/call-builtin-method ctx value value "cursor" [])]
    (reset! (:output state) @(:output ctx))
    result))

(defn array-to-string
  [values]
  (rt/nex-array-str format-value values))

(defn map-to-string
  [values]
  (rt/nex-map-str format-value values))

(defn set-to-string
  [values]
  (rt/nex-set-str format-value values))

(defn set-union
  [a b]
  (rt/nex-set-union a b))

(defn set-difference
  [a b]
  (rt/nex-set-difference a b))

(defn set-intersection
  [a b]
  (rt/nex-set-intersection a b))

(defn set-symmetric-difference
  [a b]
  (rt/nex-set-symmetric-difference a b))

(defn invoke-builtin
  [state name args]
  (cond
    (= name "validate-object-state")
    (validate-object-state state (first args) (second args))

    (= name "make-captured-function-object")
    (make-captured-function-object state (first args) (vec (rest args)))

    (= name "create-channel")
    (if (seq args)
      (create-channel (first args))
      (create-channel))

    (= name "java-create-object")
    (java-create-object state (first args) (vec (rest args)))

    (= name "java-call-method")
    (java-call-method state (first args) (second args) (vec (drop 2 args)))

    (= name "java-get-field")
    (java-get-field (first args) (second args))

    (= name "java-set-field")
    (java-set-field! (first args) (second args) (nth args 2))

    (= name "spawn-function-object")
    (spawn-function-object state (first args))

    (= name "op:await-all")
    (task-await-all (first args))

    (= name "op:await-any")
    (task-await-any (first args))

    (= name "select-deadline")
    (select-deadline (first args))

    (= name "deadline-expired?")
    (deadline-expired? (first args))

    (= name "select-sleep-step")
    (select-sleep-step!)

    (= name "op:string-concat")
    (apply str (map #(concat-string-value state %) args))

    (= name "op:pow-int")
    (int (rt/nex-int-pow (int (first args)) (int (second args))))

    (= name "op:pow-long")
    (long (rt/nex-int-pow (long (first args)) (long (second args))))

    (= name "op:pow-double")
    (Math/pow (double (first args)) (double (second args)))

    :else
    (let [ctx (rebuild-interpreter-ctx state)]
      (if (str/starts-with? name "method:")
      (let [method-name (subs name (count "method:"))
            target (first args)
            method-args (rest args)
            result (interp/call-builtin-method ctx target target method-name method-args)]
        (reset! (:output state) @(:output ctx))
        result)
      (cond
        (str/starts-with? name "user-method:")
        (invoke-user-method state (first args) (subs name (count "user-method:")) (rest args))

        (str/starts-with? name "user-field-get:")
        (get-user-field (first args) (subs name (count "user-field-get:")))

        (str/starts-with? name "user-field-set:")
        (set-user-field! (first args) (subs name (count "user-field-set:")) (second args))

        :else
      (let [builtin-fn (get interp/builtins name)]
        (when-not builtin-fn
          (throw (ex-info (str "Undefined compiled builtin: " name) {:name name})))
        (let [result (apply builtin-fn ctx args)]
          (reset! (:output state) @(:output ctx))
          result)))))))
