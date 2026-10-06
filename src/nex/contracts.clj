(ns nex.contracts
  "Inherited contracts read in the heir's routine.

   A routine's contract is written against its own parameter names, but an
   inherited `require`/`ensure` is checked inside the overriding routine, whose
   parameters may be named differently (`f(x: Integer) require x > 0 deferred`
   effected as `f(n: Integer) do ... end`). `rename-params` rewrites the
   inherited assertions to read the heir's names, position by position. Both
   backends apply it when they assemble a routine's effective contract.

   The rewrite would be wrong if a heir's parameter took the name of something
   else the inherited contract reads (a field, say): the reference would then
   see the parameter. `captured-names` reports such names so the type checker
   can reject the override."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(defn- param-renames
  "from-name -> to-name for each position where the two parameter lists differ."
  [from-params to-params]
  (into {}
        (keep (fn [[from to]]
                (let [f (:name from) t (:name to)]
                  (when (and f t (not= f t)) [f t]))))
        (map vector from-params to-params)))

(defn- rename-names
  "NODE with each name in RENAMES (old -> new) read under its new name. A closure
   parameter that reuses a name hides it inside that closure."
  [renames node]
  (letfn [(walk [renames n]
            (cond
              (empty? renames) n

              (vector? n) (mapv #(walk renames %) n)

              (seq? n) (doall (map #(walk renames %) n))

              (not (map? n)) n

              (and (= :identifier (:type n)) (contains? renames (:name n)))
              (assoc n :name (renames (:name n)))

              ;; A paren-less, argument-less bare name can surface as a
              ;; target-less call; it names the parameter all the same.
              (and (= :call (:type n)) (nil? (:target n)) (not (:has-parens n))
                   (empty? (:args n)) (contains? renames (:method n)))
              (assoc n :method (renames (:method n)))

              (= :anonymous-function (:type n))
              (let [inner (apply dissoc renames (map :name (:params n)))]
                (into {} (map (fn [[k v]] [k (walk inner v)])) n))

              :else
              (into {}
                    (map (fn [[k v]]
                           (if (and (= :target k) (string? v) (contains? renames v))
                             ;; `x.length`: the receiver is held as a bare name.
                             [k (renames v)]
                             [k (walk renames v)])))
                    n)))]
    (walk renames node)))

(defn rename-params
  "ASSERTIONS, written against FROM-PARAMS, rewritten to read TO-PARAMS."
  [assertions from-params to-params]
  (let [renames (param-renames from-params to-params)]
    (if (or (empty? renames) (empty? assertions))
      assertions
      (mapv #(update % :condition (partial rename-names renames)) assertions))))

(defn- referenced-names
  "Every bare name NODE reads, closure parameters inside it excepted."
  [node]
  (letfn [(walk [bound n]
            (cond
              (or (vector? n) (seq? n)) (mapcat #(walk bound %) n)

              (not (map? n)) nil

              (= :identifier (:type n))
              (when-not (contains? bound (:name n)) [(:name n)])

              (= :anonymous-function (:type n))
              (let [bound' (into bound (map :name (:params n)))]
                (mapcat (fn [[_ v]] (walk bound' v)) n))

              :else
              (concat
               (when (and (= :call (:type n)) (nil? (:target n)) (not (:has-parens n))
                          (empty? (:args n)) (not (contains? bound (:method n))))
                 [(:method n)])
               (when (and (string? (:target n)) (not (contains? bound (:target n))))
                 [(:target n)])
               (mapcat (fn [[_ v]] (walk bound v)) n))))]
    (set (walk #{} node))))

(defn captured-names
  "Names the heir's parameters (TO-PARAMS) would capture if ASSERTIONS, written
   against FROM-PARAMS, were renamed to read them: a heir parameter whose name
   the contract already uses for something other than the parameter at that
   position."
  [assertions from-params to-params]
  (let [renames (param-renames from-params to-params)
        own (set (keep :name from-params))
        read-elsewhere (->> assertions
                            (map :condition)
                            (map referenced-names)
                            (apply set/union #{})
                            (remove own)
                            set)]
    (->> (vals renames)
         (filter read-elsewhere)
         distinct
         vec)))

(defn- assertions->condition
  "ASSERTIONS AND-ed into one condition."
  [assertions]
  (reduce (fn [acc {:keys [condition]}]
            (if acc
              {:type :binary :operator "and" :left acc :right condition}
              condition))
          nil
          assertions))

(defn- failing-label
  "The label of GROUP's first false assertion, given that GROUP as a whole is
   false: a string when the group has one assertion, otherwise an expression
   that tests each assertion but the last in turn. The last is never
   evaluated -- if every earlier one held, it must be the one that failed."
  [group]
  (reduce (fn [acc {:keys [label condition]}]
            {:type :when
             :condition condition
             :consequent acc
             :alternative {:type :string :value label}})
          {:type :string :value (:label (peek group))}
          (rseq (pop group))))

(defn combine-precondition-groups
  "A routine's effective precondition from its inherited precondition groups
   and its own: each group's assertions AND-ed, the groups OR-ed (an
   override can only weaken what callers must establish).

   When more than one group applies, the result is a single assertion. A call
   that breaks it broke every group, so it names the failing assertion of
   each, `positive or is_neg_one`. That name is a plain `:label` when each
   group has one assertion; otherwise it depends on which assertion of a group
   failed, and the assertion carries `:label-expr`, an expression a backend
   evaluates for the name only once the condition has failed."
  [inherited-groups local-assertions]
  (let [groups (vec (concat (keep (comp not-empty vec) inherited-groups)
                            (when (seq local-assertions)
                              [(vec local-assertions)])))]
    (case (count groups)
      0 nil
      1 (first groups)
      (let [condition (reduce (fn [acc group]
                                {:type :binary :operator "or"
                                 :left acc :right (assertions->condition group)})
                              (assertions->condition (first groups))
                              (rest groups))
            labels (map failing-label groups)]
        [(if (every? #(= :string (:type %)) labels)
           {:label (str/join " or " (map :value labels))
            :condition condition}
           {:label-expr (reduce (fn [acc label]
                                  {:type :binary :operator "+"
                                   :left {:type :binary :operator "+"
                                          :left acc :right {:type :string :value " or "}}
                                   :right label})
                                labels)
            :condition condition})]))))
