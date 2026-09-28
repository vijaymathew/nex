(ns nex.redeclare
  "Redeclaring an inherited zero-argument query as a stored attribute.

   A heir may answer a query it inherits (`area: Real do ... end`, or a
   deferred `area(): Real deferred`) with a plain field of the same name
   (`area: Real`). The field is desugared into itself plus a synthesized
   getter of the same name:

       area: Real
       area(): Real do result := area end

   which turns the redeclaration into an ordinary routine override. Dynamic
   dispatch, deferred-routine effecting, return-type conformance and inherited
   postconditions then all work through the existing routine machinery on both
   backends. Inside the class a bare `area` still denotes the field (fields
   shadow routines there), so the getter body does not recurse.

   Run at each point a class definition is taken in (type checker, interpreter,
   JVM lowering). The pass is idempotent: a class that already declares a
   zero-argument routine of the field's name is left alone.")

(defn- feature-members
  [class-def]
  (->> (:body class-def)
       (filter #(= :feature-section (:type %)))
       (mapcat :members)))

(defn- zero-arg-routine?
  [member routine-name]
  (and (= :method (:type member))
       (= routine-name (:name member))
       (empty? (:params member))))

(defn- inherits-zero-arg-routine?
  [lookup class-def routine-name]
  (letfn [(walk [class-name visited]
            (when (and (string? class-name) (not (contains? visited class-name)))
              (when-let [ancestor (lookup class-name)]
                (or (some #(zero-arg-routine? % routine-name) (feature-members ancestor))
                    (some #(walk (:parent %) (conj visited class-name))
                          (:parents ancestor))))))]
    (some #(walk (:parent %) #{(:name class-def)}) (:parents class-def))))

(defn- synthesized-getter
  [field]
  (let [pos (select-keys field [:dbg/line :dbg/col])]
    (merge {:type :method
            :name (:name field)
            :params nil
            :return-type (:field-type field)
            :require nil
            :ensure nil
            :rescue nil
            :alias nil
            :note nil
            :declaration-only? false
            :synthesized-getter? true
            :body [(merge {:type :assign
                           :target "result"
                           :value (merge {:type :identifier :name (:name field)} pos)}
                          pos)]}
           pos)))

(defn desugar-class
  "CLASS-DEF with a synthesized getter for every non-constant field that
   redeclares a zero-argument routine inherited from an ancestor. LOOKUP maps a
   parent name (as written in an `inherit` clause) to its class definition, or
   nil when unknown."
  [lookup class-def]
  (if-not (and (map? class-def) (seq (:parents class-def)))
    class-def
    (let [members (feature-members class-def)
          own-query? (fn [n] (some #(zero-arg-routine? % n) members))
          getters (->> members
                       (filter #(and (= :field (:type %)) (not (:constant? %))))
                       (remove #(own-query? (:name %)))
                       (filter #(inherits-zero-arg-routine? lookup class-def (:name %)))
                       (mapv synthesized-getter))]
      (if (empty? getters)
        class-def
        (update class-def :body (fnil conj [])
                {:type :feature-section
                 :visibility {:type :public}
                 :members getters})))))

(defn class-lookup
  "A LOOKUP for `desugar-class` over CLASS-DEFS (by bare and qualified name),
   falling back to FALLBACK (a fn of a name, may be nil)."
  ([class-defs] (class-lookup class-defs nil))
  ([class-defs fallback]
   (let [by-name (reduce (fn [m cd]
                           (if (map? cd)
                             (cond-> (assoc m (:name cd) cd)
                               (:qualified-name cd) (assoc (:qualified-name cd) cd))
                             m))
                         {}
                         class-defs)]
     (fn [class-name]
       (or (get by-name class-name)
           (when fallback (fallback class-name)))))))

(defn desugar-classes
  "Apply `desugar-class` to every class in CLASS-DEFS, resolving parents among
   CLASS-DEFS first and then through FALLBACK."
  ([class-defs] (desugar-classes class-defs nil))
  ([class-defs fallback]
   (let [lookup (class-lookup class-defs fallback)]
     (mapv #(desugar-class lookup %) class-defs))))
