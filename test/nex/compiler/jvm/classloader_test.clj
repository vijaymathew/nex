(ns nex.compiler.jvm.classloader-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.classloader :as loader]))

(deftest make-loader-test
  (testing "make-loader returns a dynamic classloader"
    (let [l (loader/make-loader)]
      (is (instance? clojure.lang.DynamicClassLoader l))
      (is (nil? (loader/resolve-class l "nex.repl.DoesNotExist"))))))
