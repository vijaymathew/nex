(ns nex.compiler.jvm.descriptor-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.descriptor :as d]
            [nex.ir :as ir]))

(deftest nex-type-to-jvm-type-test
  (testing "basic Nex type mappings"
    (is (= :int (d/nex-type->jvm-type "Integer")))
    (is (= :double (d/nex-type->jvm-type "Real")))
    (is (= (ir/object-jvm-type "java/lang/String")
           (d/nex-type->jvm-type "String")))
    (is (= (ir/object-jvm-type "java/util/ArrayList")
           (d/nex-type->jvm-type {:base-type "Array" :type-args ["Integer"]})))))

(deftest descriptors-test
  (testing "method and boxing descriptors"
    (is (= "(ILjava/lang/String;)V"
           (d/method-descriptor [:int (ir/object-jvm-type "java/lang/String")] :void)))
    (is (= "Ljava/lang/Integer;"
           (d/jvm-type->descriptor (ir/object-jvm-type "java/lang/Integer"))))
    (is (= "(I)Ljava/lang/Integer;"
           (d/boxing-descriptor :int)))
    (is (= {:owner "java/lang/Integer" :name "intValue" :descriptor "()I"}
           (d/unboxing-method :int)))))
