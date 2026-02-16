(require '[nex.parser :as p])

(def code "class Test
  feature
    demo(a, b, c, d: Integer) do
      print(a, b, c, d)
    end
end")

(println "Code:")
(println code)
(println)

(let [ast (p/ast code)
      class-def (first (:classes ast))
      method (-> class-def :body first :members first)]
  (println "Method name:" (:name method))
  (println "Params:" (:params method))
  (println)
  (println "Parameters:")
  (doseq [param (:params method)]
    (println "  •" (:name param) ":" (:type param))))
