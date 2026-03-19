(ns nex.compiler.jvm.classloader
  "Helpers around a dedicated dynamic classloader for Nex JVM compilation."
  (:import [clojure.lang DynamicClassLoader RT]))

(defn make-loader
  []
  (DynamicClassLoader. ^ClassLoader (RT/baseLoader)))

(defn define-class!
  "Define a generated class in the supplied loader."
  [^DynamicClassLoader loader class-name ^bytes bytecode]
  (.defineClass loader class-name bytecode nil))

(defn load-defined-class
  [^DynamicClassLoader loader class-name]
  (.loadClass loader class-name))

(defn resolve-class
  [^DynamicClassLoader loader class-name]
  (try
    (.loadClass loader class-name)
    (catch ClassNotFoundException _
      nil)))
