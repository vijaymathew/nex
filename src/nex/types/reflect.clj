(ns nex.types.reflect
  "Reflective Java calls (`with \"java\"` code), resolved once and remembered.

   clojure.lang.Reflector resolves a call afresh every time it is made: it
   scans every public method of the target's class for the name and arity,
   picks an overload, and -- when the method is declared by a class the
   caller may not use directly, such as a JDK implementation class in a
   package its module does not export (the sun.java2d.SunGraphics2D behind
   every java.awt.Graphics2D) -- searches the class's supertypes for an
   accessible declaration of the same method. Graphics code makes the same
   few calls thousands of times a second, and that search was nearly all of
   their cost: changing a canvas's pen colour took over 100 microseconds.

   The functions here make exactly the choice Reflector makes (the same
   candidates, overload rule, argument boxing and return preparation), but
   once per call shape -- target class, member name, number of arguments,
   and for an overloaded name the arguments' classes -- and keep the answer.
   Anything unusual (no candidate, no applicable overload, no accessible
   declaration) is not cached and goes to Reflector itself, so error
   messages are unchanged.

   What is remembered about a class is kept in a ClassValue, which does not
   keep the class alive: a class the REPL redefines can still be unloaded."
  (:import [clojure.lang Compiler DynamicClassLoader Reflector Util]
           [java.lang ClassValue]
           [java.lang.reflect Constructor Field InvocationTargetException Method Modifier]
           [java.util.concurrent ConcurrentHashMap]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Per-class caches

(def ^:private ^ClassValue per-class
  "For each class, a map of what has been resolved on it."
  (proxy [ClassValue] []
    (computeValue [_] (ConcurrentHashMap.))))

(defn- class-cache
  ^ConcurrentHashMap [^Class cls]
  (.get per-class cls))

(deftype CallKey [kind ^String member ^long arity classes ^int h]
  Object
  (hashCode [_] h)
  (equals [_ o]
    (and (instance? CallKey o)
         (let [^CallKey k o]
           (and (identical? kind (.-kind k))
                (== arity (.-arity k))
                (.equals member (.-member k))
                (= classes (.-classes k)))))))

(defn- call-key
  "A key for KIND (:method, :static or :new) of MEMBER with ARITY arguments;
   CLASSES is nil, or the argument classes for an overloaded member."
  ^CallKey [kind ^String member arity classes]
  (CallKey. kind member (long arity) classes
            (unchecked-int (+ (* 31 (+ (* 31 (System/identityHashCode kind))
                                       (.hashCode member)))
                              (+ (long arity) (if classes (hash classes) 0))))))

(defn- arg-classes
  "The runtime classes of ARGS (nil for a nil argument), as a vector."
  [^objects args]
  (let [n (alength args)]
    (loop [i 0 acc (transient [])]
      (if (< i n)
        (let [a (aget args i)]
          (recur (inc i) (conj! acc (when a (.getClass ^Object a)))))
        (persistent! acc)))))

(def ^:private overloaded ::overloaded)

(declare fixed-class?)

(defn- remember!
  "Cache V under K, a key naming the argument classes CS, and answer V. A
   key naming a class that may be redefined is not kept, since it would
   keep that class alive."
  [^ConcurrentHashMap cache k cs v]
  (when (every? #(or (nil? %) (fixed-class? %)) cs)
    (.put cache k v))
  v)

;; ---------------------------------------------------------------------------
;; Reflector's rules, restated

(defn- box-arg
  "ARG converted for a parameter of type P, as clojure.lang.Reflector's
   (package-private) boxArg does it."
  [^Class p arg]
  (cond
    (not (.isPrimitive p)) (.cast p arg)
    (identical? p Boolean/TYPE) (.cast Boolean arg)
    (identical? p Character/TYPE) (.cast Character arg)
    (instance? Number arg)
    (let [^Number n arg]
      (cond
        (identical? p Integer/TYPE) (Integer/valueOf (.intValue n))
        (identical? p Double/TYPE) (Double/valueOf (.doubleValue n))
        (identical? p Long/TYPE) (Long/valueOf (.longValue n))
        (identical? p Float/TYPE) (Float/valueOf (.floatValue n))
        (identical? p Short/TYPE) (Short/valueOf (.shortValue n))
        (identical? p Byte/TYPE) (Byte/valueOf (.byteValue n))
        :else (throw (IllegalArgumentException.
                      (str "Unexpected param type, expected: " p
                           ", given: " (.getName (.getClass ^Object arg)))))))
    :else
    (throw (IllegalArgumentException.
            (str "Unexpected param type, expected: " p
                 ", given: " (if (nil? arg) "nil" (.getName (.getClass ^Object arg))))))))

(defn- box-args
  ^objects [^"[Ljava.lang.Class;" params ^objects args]
  (let [n (alength params)
        out (object-array n)]
    (dotimes [i n]
      (aset out i (box-arg (aget params i) (aget args i))))
    out))

(defn- congruent?
  "Whether every argument can be passed to the parameter in its place."
  [^"[Ljava.lang.Class;" params ^objects args]
  (and (== (alength params) (alength args))
       (loop [i 0]
         (if (< i (alength params))
           (let [a (aget args i)]
             (if (Reflector/paramArgTypeMatch (aget params i) (when a (.getClass ^Object a)))
               (recur (inc i))
               false))
           true))))

(defn- select-method
  "Of the same-arity METHODS, the one Reflector picks for ARGS: the most
   specific congruent one, by Compiler/subsumes; nil if none applies."
  ^Method [methods ^objects args]
  (reduce (fn [^Method found ^Method m]
            (let [params (.getParameterTypes m)]
              (if (and (congruent? params args)
                       (or (nil? found)
                           (Compiler/subsumes params (.getParameterTypes found))))
                m
                found)))
          nil
          methods))

(defn unchecked
  "M, with Java's access check turned off when that is allowed (it is for a
   public member of an exported package), so that invoking it no longer
   repeats the check on every call. M is already known to be callable."
  [^java.lang.reflect.AccessibleObject m]
  (when m
    (try (.trySetAccessible m) (catch Exception _ nil))
    m))

(defn- accessible
  "M, or the same method as declared by an accessible supertype of CLS when
   M's own declaring class can't be used directly; nil if there is none."
  ^Method [^Method m ^Class cls target]
  (unchecked
   (if (and (Modifier/isPublic (.getModifiers (.getDeclaringClass m)))
            (.canAccess m target))
     m
     (when (some? target)
       (Reflector/getAsMethodOfAccessibleBase cls m target)))))

(defn- invoke!
  [^Method m target ^objects args]
  (try
    (Reflector/prepRet (.getReturnType m)
                       (.invoke m target (box-args (.getParameterTypes m) args)))
    (catch InvocationTargetException e
      (throw (Util/sneakyThrow (or (.getCause e) e))))))

;; ---------------------------------------------------------------------------
;; Calls

(defn- resolve-method
  "The method KIND (:method or :static) call MEMBER on CLS with ARGS
   resolves to, cached; nil when Reflector should handle the call itself."
  ^Method [kind ^Class cls ^String member target ^objects args]
  (let [cache (class-cache cls)
        arity (alength args)
        k (call-key kind member arity nil)
        hit (.get cache k)]
    (cond
      (instance? Method hit) hit
      (identical? hit overloaded)
      (let [cs (arg-classes args)
            k2 (call-key kind member arity cs)]
        (or (.get cache k2)
            (when-let [m (some-> (select-method (Reflector/getMethods cls (int arity) member (= kind :static))
                                                args)
                                 (accessible cls target))]
              (remember! cache k2 cs m))))
      :else
      (let [candidates (Reflector/getMethods cls (int arity) member (= kind :static))]
        (case (count candidates)
          0 nil
          1 (when-let [m (accessible (first candidates) cls target)]
              (.put cache k m)
              m)
          (do (.put cache k overloaded)
              (resolve-method kind cls member target args)))))))

(defn invoke-method
  "Call the public instance method MEMBER of TARGET with ARGS (an Object
   array), as clojure.lang.Reflector/invokeInstanceMethod does."
  [target ^String member ^objects args]
  (if-let [m (resolve-method :method (.getClass ^Object target) member target args)]
    (invoke! m target args)
    (Reflector/invokeInstanceMethod target member args)))

(declare invoke-constructor)

(defn invoke-static
  "Call the public static method MEMBER of CLS with ARGS, as
   clojure.lang.Reflector/invokeStaticMethod does."
  [^Class cls ^String member ^objects args]
  (if (= "new" member)
    (invoke-constructor cls args)
    (if-let [m (resolve-method :static cls member nil args)]
      (invoke! m nil args)
      (Reflector/invokeStaticMethod cls member args))))

(defn- select-constructor
  "Reflector's rule: the only constructor of the right arity, or else the
   first congruent one."
  ^Constructor [^Class cls ^objects args]
  (let [ctors (filter (fn [^Constructor c] (== (alength (.getParameterTypes c)) (alength args)))
                      (.getConstructors cls))]
    (unchecked
     (if (= 1 (count ctors))
       (first ctors)
       (first (filter (fn [^Constructor c] (congruent? (.getParameterTypes c) args)) ctors))))))

(defn invoke-constructor
  "Make a CLS with ARGS, as clojure.lang.Reflector/invokeConstructor does."
  [^Class cls ^objects args]
  (let [cache (class-cache cls)
        arity (alength args)
        k (call-key :new "<init>" arity nil)
        hit (.get cache k)
        ^Constructor c (cond
                         (instance? Constructor hit) hit
                         (identical? hit overloaded)
                         (let [cs (arg-classes args)
                               k2 (call-key :new "<init>" arity cs)]
                           (or (.get cache k2)
                               (when-let [c (select-constructor cls args)]
                                 (remember! cache k2 cs c))))
                         :else
                         (case (count (filter (fn [^Constructor c]
                                                (== (alength (.getParameterTypes c)) arity))
                                              (.getConstructors cls)))
                           0 nil
                           1 (let [c (select-constructor cls args)]
                               (.put cache k c)
                               c)
                           (do (.put cache k overloaded)
                               (when-let [c (select-constructor cls args)]
                                 (let [cs (arg-classes args)]
                                   (remember! cache (call-key :new "<init>" arity cs) cs c))))))]
    (if c
      (try
        (.newInstance c (box-args (.getParameterTypes c) args))
        (catch InvocationTargetException e
          (throw (Util/sneakyThrow (or (.getCause e) e)))))
      (Reflector/invokeConstructor cls args))))

;; ---------------------------------------------------------------------------
;; Lookups by name, remembered

(def ^:private none ::none)

(defn on-class
  "The value of (F) for KEY on CLS, computed once per class. A nil result is
   remembered too."
  [^Class cls key f]
  (let [cache (class-cache cls)
        hit (.get cache key)]
    (cond
      (identical? hit none) nil
      (some? hit) hit
      :else (let [v (f)]
              (.put cache key (if (nil? v) none v))
              v))))

(def ^:private ^ConcurrentHashMap classes-by-name (ConcurrentHashMap.))

(defn- fixed-class?
  "Whether CLS can never be replaced by another class of the same name: it
   was not loaded by a DynamicClassLoader, which is where the REPL and
   generated code put the classes they may redefine."
  [^Class cls]
  (not (instance? DynamicClassLoader (.getClassLoader cls))))

(defn class-for-name
  "Class/forName, or nil (not an exception) when there is no such class.
   Remembered, for classes that can't be redefined."
  ^Class [^String name]
  (or (.get classes-by-name name)
      (when-let [cls (try (Class/forName name) (catch Exception _ nil))]
        (when (fixed-class? cls)
          (.put classes-by-name name cls))
        cls)))

(def ^:private ^ClassValue fields-of
  "For each class, its fields looked up so far, by name."
  (proxy [ClassValue] []
    (computeValue [_] (ConcurrentHashMap.))))

(defn field
  "The field NAME of CLS: a public one (getField), or else one CLS itself
   declares, made accessible. nil if neither. Remembered."
  ^Field [^Class cls ^String name]
  (let [^ConcurrentHashMap by-name (.get fields-of cls)
        hit (.get by-name name)]
    (cond
      (identical? hit none) nil
      (some? hit) hit
      :else (let [f (or (unchecked (try (.getField cls name) (catch Exception _ nil)))
                        (some (fn [^Field f]
                                (when (= (.getName f) name)
                                  (.setAccessible f true)
                                  f))
                              (.getDeclaredFields cls)))]
              (.put by-name name (if (nil? f) none f))
              f))))
