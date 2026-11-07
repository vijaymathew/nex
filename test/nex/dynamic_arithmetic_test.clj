(ns nex.dynamic-arithmetic-test
  "Arithmetic on values whose type is known only at run time: a Java call's
   result inside `with \"java\"`, typed Any. The compiled backend used to have
   no instruction for it and failed with an internal error (\"Unsupported
   binary opcode emission\"); it now does it at run time as the interpreter
   does (nex.types.runtime/nex-arithmetic, shared by both)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "dynamic_arithmetic" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})
            interpreted (run {:interpret? true})]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest arithmetic-on-java-results-test
  (testing "+ - * / % ^ and unary minus with an Any operand: Integer and Real
            results, a Java String with +, nesting, division by zero, and a
            result returned as an Integer"
    (is (= ["\"ms/1000 > 0: true\""
            "\"abs(-7) % 4: 3\""
            "\"abs(-5) + 1: 6\""
            "\"abs(-5) - 10: -5\""
            "\"abs(-5) * 2: 10\""
            "\"-abs(-5): -5\""
            "\"sqrt(16) / 2.0: 2.0\""
            "\"abs(-5) / 2.5: 2.0\""
            "\"abs(-7) / 2: 3\""
            "\"abs(-2) ^ 10: 1024\""
            "\"sqrt(2) * sqrt(2): 2.0000000000000004\""
            "\"toString(5) + 1: 51\""
            "\"1 + toString(5): 15\""
            "\"nested: 20\""
            "\"raised: Division by zero\""
            "\"clock_ms() > 0: true\""]
           (both "import java.lang.System\nimport java.lang.Math\nimport java.util.Objects\nfunction show(label: String, v: Any) do print(label + \": \" + v.to_string) end\nlet a: Any := nil\nwith \"java\" do\n  a := System.currentTimeMillis() / 1000\nend\nshow(\"ms/1000 > 0\", a /= nil)\nwith \"java\" do a := Math.abs(-7) % 4 end\nshow(\"abs(-7) % 4\", a)\nwith \"java\" do a := Math.abs(-5) + 1 end\nshow(\"abs(-5) + 1\", a)\nwith \"java\" do a := Math.abs(-5) - 10 end\nshow(\"abs(-5) - 10\", a)\nwith \"java\" do a := Math.abs(-5) * 2 end\nshow(\"abs(-5) * 2\", a)\nwith \"java\" do a := -Math.abs(-5) end\nshow(\"-abs(-5)\", a)\nwith \"java\" do a := Math.sqrt(16.0) / 2.0 end\nshow(\"sqrt(16) / 2.0\", a)\nwith \"java\" do a := Math.abs(-5) / 2.5 end\nshow(\"abs(-5) / 2.5\", a)\nwith \"java\" do a := Math.abs(-7) / 2 end\nshow(\"abs(-7) / 2\", a)\nwith \"java\" do a := Math.abs(-2) ^ 10 end\nshow(\"abs(-2) ^ 10\", a)\nwith \"java\" do a := Math.sqrt(2.0) * Math.sqrt(2.0) end\nshow(\"sqrt(2) * sqrt(2)\", a)\nwith \"java\" do a := Objects.toString(5) + 1 end\nshow(\"toString(5) + 1\", a)\nwith \"java\" do a := 1 + Objects.toString(5) end\nshow(\"1 + toString(5)\", a)\nwith \"java\" do a := (Math.abs(-9) + 1) * (Math.abs(-3) - 1) end\nshow(\"nested\", a)\nfunction divide_by_zero(): String do\n  with \"java\" do\n    result := (Math.abs(-7) / 0).to_string\n  end\nrescue\n  result := \"raised: \" + exception.to_string\nend\nprint(divide_by_zero())\nfunction clock_ms(): Integer do\n  with \"java\" do\n    result := System.nanoTime() / 1000000\n  end\nend\nshow(\"clock_ms() > 0\", clock_ms() > 0)\n")))))
