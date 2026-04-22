(ns nex.types.bootstrap)

(defn build-function-base-class
  []
  (let [make-method (fn [n]
                      {:type :method
                       :name (str "call" n)
                       :params (if (zero? 0)
                                 []
                                 (mapv (fn [i]
                                         {:name (str "arg" i) :type "Any"})
                                       (range 1 (inc n))))
                       :return-type "Any"
                       :declaration-only? true
                       :note nil
                       :require nil
                       :body []
                       :ensure nil})
        methods (vec (cons (make-method 0) (mapv make-method (range 1 33))))]
    {:type :class
     :name "Function"
     :generic-params nil
     :note nil
     :parents nil
     :body [{:type :feature-section
             :visibility {:type :public}
             :members methods}]
     :invariant nil}))

(defn build-cursor-base-class
  []
  {:type :class
   :name "Cursor"
   :generic-params nil
   :note nil
   :parents nil
   :body [{:type :feature-section
           :visibility {:type :public}
           :members [{:type :method :name "start" :params nil :return-type nil
                      :declaration-only? true
                      :note nil :require nil :body [] :ensure nil}
                     {:type :method :name "cursor" :params nil :return-type "Cursor"
                      :note nil :require nil
                      :body [{:type :assign
                              :target "result"
                              :value {:type :this}}]
                      :ensure nil}
                     {:type :method :name "item" :params nil :return-type "Any"
                      :declaration-only? true
                      :note nil :require nil :body [] :ensure nil}
                     {:type :method :name "next" :params nil :return-type nil
                      :declaration-only? true
                      :note nil :require nil :body [] :ensure nil}
                     {:type :method :name "at_end" :params nil :return-type "Boolean"
                      :declaration-only? true
                      :note nil :require nil :body [] :ensure nil}]}]
   :invariant nil})

(defn build-comparable-base-class
  []
  {:type :class
   :name "Comparable"
   :deferred? true
   :generic-params nil
   :note nil
   :parents nil
   :body [{:type :feature-section
           :visibility {:type :public}
           :members [{:type :method :name "compare"
                      :params [{:name "a" :type "Any"}]
                      :return-type "Integer"
                      :declaration-only? true
                      :note nil :require nil :body [] :ensure nil}]}]
   :invariant nil})

(defn build-any-base-class
  []
  {:type :class
   :name "Any"
   :deferred? false
   :generic-params nil
   :note nil
   :parents nil
   :body []
   :invariant nil})

(defn build-hashable-base-class
  []
  {:type :class
   :name "Hashable"
   :deferred? true
   :generic-params nil
   :note nil
   :parents nil
   :body [{:type :feature-section
           :visibility {:type :public}
           :members [{:type :method :name "hash"
                      :params nil
                      :return-type "Integer"
                      :declaration-only? true
                      :note nil :require nil :body [] :ensure nil}]}]
   :invariant nil})

(defn build-builtin-scalar-class
  [name]
  {:type :class
   :name name
   :deferred? false
   :generic-params nil
   :note nil
   :parents [{:parent "Any"} {:parent "Comparable"} {:parent "Hashable"}]
   :body []
   :invariant nil})
