(ns nex.reflect-test
  "nex.types.reflect: reflective Java calls resolved once and remembered. Its
   promise is to make exactly the choices clojure.lang.Reflector makes (the
   same method or constructor, the same argument conversions, the same
   errors), so most checks here compare the two directly."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.types.reflect :as reflect])
  (:import [clojure.lang Reflector]
           [java.awt.image BufferedImage]))

(defn- args [& xs] (object-array xs))

(defn- outcome
  "What calling F gives: its value, or the class and message of what it threw."
  [f]
  (try
    [:value (f)]
    (catch Throwable t
      [:threw (class t) (.getMessage t)])))

(deftest instance-calls-match-reflector
  (doseq [[target member as] [["Kestrel" "toUpperCase" []]
                              ["Kestrel" "substring" [1 3]]
                              ["Kestrel" "indexOf" ["e"]]
                              ["Kestrel" "charAt" [2]]
                              ["Kestrel" "concat" [" Island"]]]]
    (testing (str member as)
      (is (= (outcome #(Reflector/invokeInstanceMethod target member (to-array as)))
             (outcome #(reflect/invoke-method target member (to-array as))))))))

(deftest overloads-pick-what-reflector-picks
  ;; StringBuilder.append has a dozen same-arity overloads; each argument
  ;; class must land on the same one Reflector chooses.
  (doseq [a ["text" 42 2.5 \c true nil (StringBuilder. "sb")]]
    (testing (pr-str a)
      (is (= (str (Reflector/invokeInstanceMethod (StringBuilder.) "append" (args a)))
             (str (reflect/invoke-method (StringBuilder.) "append" (args a))))))
    ;; And again, now that the choice is cached.
    (is (= (str (Reflector/invokeInstanceMethod (StringBuilder.) "append" (args a)))
           (str (reflect/invoke-method (StringBuilder.) "append" (args a))))))
  (testing "ArrayList.remove(int) against remove(Object)"
    (let [mine (java.util.ArrayList. [10 20 30])
          theirs (java.util.ArrayList. [10 20 30])]
      (reflect/invoke-method mine "remove" (args 1))
      (Reflector/invokeInstanceMethod theirs "remove" (args 1))
      (is (= theirs mine)))))

(deftest numbers-are-converted-like-reflector
  ;; A Long passed to an int parameter, a Long to a long one.
  (is (= "ff" (reflect/invoke-static Integer "toHexString" (args 255))))
  (is (= 7 (reflect/invoke-static Math "floorMod" (args 17 10))))
  (is (= (outcome #(Reflector/invokeStaticMethod Math "max" (args 3 4.5)))
         (outcome #(reflect/invoke-static Math "max" (args 3 4.5))))))

(deftest constructors-match-reflector
  ;; The doubles find no constructor: Reflector does not pass a Double to a
  ;; float parameter when the name is overloaded, and neither may we.
  (doseq [as [[1 2 3] [0.5 0.25 1.0] [255 0 0 128]]]
    (testing (pr-str as)
      (is (= (outcome #(Reflector/invokeConstructor java.awt.Color (to-array as)))
             (outcome #(reflect/invoke-constructor java.awt.Color (to-array as)))))))
  (is (= "abc" (str (reflect/invoke-constructor StringBuilder (args "abc")))))
  (is (= "abc" (reflect/invoke-static String "new" (args "abc")))))

(deftest methods-of-inaccessible-classes-go-through-a-public-base
  ;; createGraphics gives a sun.java2d.SunGraphics2D, in a package java.desktop
  ;; does not export; its methods must be called as Graphics2D's.
  (let [img (BufferedImage. 4 4 BufferedImage/TYPE_INT_RGB)
        g (.createGraphics img)]
    (dotimes [_ 3]
      (reflect/invoke-method g "setColor" (args java.awt.Color/RED))
      (reflect/invoke-method g "fillRect" (args 0 0 4 4)))
    (is (= 0xFF0000 (bit-and 0xFFFFFF (.getRGB img 2 2))))))

(deftest errors-are-reflectors-errors
  (testing "no such method"
    (is (= (outcome #(Reflector/invokeInstanceMethod "x" "noSuchMethod" (args 1)))
           (outcome #(reflect/invoke-method "x" "noSuchMethod" (args 1))))))
  (testing "no applicable overload"
    (is (= (outcome #(Reflector/invokeInstanceMethod (StringBuilder.) "insert" (args "a" "b")))
           (outcome #(reflect/invoke-method (StringBuilder.) "insert" (args "a" "b"))))))
  (testing "an exception from the method itself arrives unwrapped"
    (is (= [:threw NumberFormatException "For input string: \"x\""]
           (outcome #(reflect/invoke-static Integer "parseInt" (args "x")))))))

(deftest lookups-by-name
  (is (= String (reflect/class-for-name "java.lang.String")))
  (is (nil? (reflect/class-for-name "no.such.Klass")))
  (is (= java.awt.Color/RED (.get (reflect/field java.awt.Color "RED") nil)))
  (is (nil? (reflect/field java.awt.Color "NO_SUCH_FIELD")))
  (testing "a class that may be redefined is not remembered by name"
    (let [name "nex.reflect_test.Redefined"
          cw (doto (clojure.asm.ClassWriter. 0)
               (.visit clojure.asm.Opcodes/V1_8 clojure.asm.Opcodes/ACC_PUBLIC
                       "nex/reflect_test/Redefined" nil "java/lang/Object" nil)
               (.visitEnd))
          cls (.defineClass (clojure.lang.DynamicClassLoader.) name (.toByteArray cw) nil)
          ^java.util.Map remembered @#'reflect/classes-by-name]
      (is (= cls (reflect/class-for-name name)))
      (is (not (.containsKey remembered name)))
      (reflect/class-for-name "java.util.ArrayList")
      (is (.containsKey remembered "java.util.ArrayList")))))
