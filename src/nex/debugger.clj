(ns nex.debugger
  "Debugger state and control flow for Nex REPL."
  (:require [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(defonce ^:dynamic ^:private *debug-enabled* (atom false))
(defonce ^:dynamic ^:private *debug-state*
  (atom {:breakpoints {}
         :next-breakpoint-id 1
         :break-on {:exception false :contract false}
         :mode :continue
         :counter 0
         :paused nil
         :last nil}))

(defn enabled? []
  @*debug-enabled*)

(defn set-enabled! [flag]
  (reset! *debug-enabled* (boolean flag)))

(defn parse-positive-int [s]
  (try
    (let [n (Integer/parseInt (str/trim s))]
      (when (pos? n) n))
    (catch Exception _ nil)))

(defn breakpoints []
  (:breakpoints @*debug-state*))

(defn breakpoint-entries
  "Return breakpoints as sorted [id bp] entries."
  []
  (sort-by first (seq (:breakpoints @*debug-state*))))

(defn break-on-status []
  (:break-on @*debug-state*))

(defn set-break-on! [kind flag]
  (swap! *debug-state* assoc-in [:break-on kind] (boolean flag)))

(defn breakpoint->str [bp]
  (case (:kind bp)
    :hit (str (:n bp))
    :cm (str (:class bp) "." (:method bp))
    :cm-line (str (:class bp) "." (:method bp) ":" (:line bp))
    :file-line (str (:source bp) ":" (:line bp))
    (str bp)))

(defn breakpoint-entry->str [[id bp]]
  (str "[" id "] " (breakpoint->str bp)
       (when-let [cond-expr (:condition bp)]
         (str " if " cond-expr))))

(defn parse-breakpoint-spec
  "Parse breakpoint spec.
   Supported:
   - 12
   - Class.method
   - Class.method:42
   - file.nex:42 or path/to/file.nex:42"
  [raw]
  (let [s (str/trim (or raw ""))]
    (or
      (when-let [n (parse-positive-int s)]
        {:kind :hit :n n})
      (when-let [[_ lhs line-str] (re-matches #"^(.+):(\d+)$" s)]
        (let [line (parse-positive-int line-str)]
          (when line
            (if (or (str/includes? lhs "/")
                    (str/includes? lhs "\\")
                    (str/ends-with? lhs ".nex"))
              {:kind :file-line :source lhs :line line}
              (when-let [[_ cls m] (re-matches #"^([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)$" lhs)]
                {:kind :cm-line :class cls :method m :line line})))))
      (when-let [[_ cls m] (re-matches #"^([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)$" s)]
        {:kind :cm :class cls :method m}))))

(defn parse-break-command
  "Parse :break argument into {:spec <breakpoint-spec> :condition <expr-or-nil>}."
  [raw]
  (let [s (str/trim (or raw ""))
        [_ spec-part cond-part] (or (re-matches #"(?is)^\s*(.+?)\s+if\s+(.+)\s*$" s)
                                    [nil s nil])]
    (when-let [spec (parse-breakpoint-spec spec-part)]
      (cond-> {:spec spec}
        (and cond-part (not (str/blank? cond-part)))
        (assoc :condition (str/trim cond-part))))))

(defn add-breakpoint! [spec]
  (let [bp (if (integer? spec) {:kind :hit :n spec} spec)
        id (:next-breakpoint-id @*debug-state*)]
    (swap! *debug-state*
           (fn [st]
             (-> st
                 (assoc-in [:breakpoints id] bp)
                 (update :next-breakpoint-id inc))))
    id))

(defn remove-breakpoint! [spec]
  (cond
    (integer? spec)
    (let [removed? (contains? (:breakpoints @*debug-state*) spec)]
      (swap! *debug-state* update :breakpoints dissoc spec)
      (if removed? 1 0))
    :else
    (let [bp (if (integer? spec) {:kind :hit :n spec} spec)
          ids (->> (:breakpoints @*debug-state*)
                   (filter (fn [[_ v]] (= v bp)))
                   (map first)
                   vec)]
      (swap! *debug-state*
             (fn [st]
               (reduce (fn [acc id] (update acc :breakpoints dissoc id))
                       st
                       ids)))
      (count ids))))

(defn clear-breakpoints! []
  (swap! *debug-state* assoc :breakpoints {} :next-breakpoint-id 1))

(defn reset-run-state! []
  (swap! *debug-state* assoc :counter 0 :mode :continue :paused nil :last nil
         :next-depth nil :finish-depth nil))

(defn- eval-breakpoint-condition?
  [ctx cond-expr]
  (try
    (let [ast (p/ast cond-expr)
          v (if (and (= (:type ast) :program) (seq (:calls ast)))
              (interp/eval-node ctx (last (:calls ast)))
              false)]
      (boolean v))
    (catch Exception _
      (try
        (let [wrapped (p/ast (str "class __DbgCond__\n"
                                  "  feature\n"
                                  "    __eval__() do\n"
                                  "      print(" cond-expr ")\n"
                                  "    end\n"
                                  "end"))
              expr-node (-> wrapped :classes first :body first :members first :body first :args first)
              v (interp/eval-node ctx expr-node)]
          (boolean v))
        (catch Exception _
          false)))))

(defn breakpoint-hit?
  [bp hit ctx node]
  (let [line (:dbg/line node)
        source (:debug-source ctx)
        cn (:current-class-name ctx)
        mn (:current-method-name ctx)
        base-match
        (case (:kind bp)
          :hit (= (:n bp) hit)
          :cm (and (= (:class bp) cn) (= (:method bp) mn))
          :cm-line (and (= (:class bp) cn) (= (:method bp) mn) (= (:line bp) line))
          :file-line (and (= (:source bp) source) (= (:line bp) line))
          false)]
    (and base-match
         (if-let [cond-expr (:condition bp)]
           (eval-breakpoint-condition? ctx cond-expr)
           true))))

(defn debug-should-pause?
  ([state hit depth ctx node]
   (debug-should-pause? state hit depth ctx node nil))
  ([{:keys [breakpoints mode next-depth finish-depth]} hit depth ctx node _opts]
   (or (some (fn [[_ bp]] (breakpoint-hit? bp hit ctx node))
             breakpoints)
       (= mode :step)
       (and (= mode :next) (some? next-depth) (<= depth next-depth))
       (and (= mode :finish) (some? finish-depth) (< depth finish-depth)))))

(defn- print-debug-pause [ctx hit depth node]
  (println (str "Paused at statement #" hit
                " depth=" depth
                " node=" (name (:type node))
                (when-let [line (:dbg/line node)] (str " line=" line))
                (when-let [src (:debug-source ctx)] (str " source=" src)))))

(defn- print-stack [ctx]
  (let [stack (or (:debug-stack ctx) [])]
    (println "stack:")
    (if (seq stack)
      (doseq [[idx frame] (map-indexed vector (reverse stack))]
        (println (str "  [" idx "] "
                      (:class frame) "." (:method frame)
                      (when-let [src (:source frame)] (str " (" src ")")))))
      (println "  (empty)"))))

(defn- env-bindings-chain
  [env]
  (loop [e env out [] level 0]
    (if e
      (recur (:parent e)
             (conj out {:level level :bindings @(:bindings e)})
             (inc level))
      out)))

(defn- debug-eval-expression [ctx expr wrap-expression-fn]
  (let [expr (str/trim expr)]
    (if (str/blank? expr)
      (println "Usage: :print <expr>")
      (try
        (reset! (:output ctx) [])
        (let [ast (p/ast expr)]
          (cond
            (and (= (:type ast) :program) (seq (:calls ast)))
            (let [v (interp/eval-node ctx (last (:calls ast)))]
              (println (interp/nex-format-value v)))
            :else
            (println "Could not evaluate expression in debugger context.")))
        (catch Exception _
          (try
            (let [wrapped (p/ast (wrap-expression-fn expr))
                  expr-node (-> wrapped :classes first :body first :members first :body first :args first)
                  v (interp/eval-node ctx expr-node)]
              (println (interp/nex-format-value v)))
            (catch Exception e2
              (println "Error:" (.getMessage e2)))))))))

(defn debugger-loop!
  "Interactive debug loop. Returns when execution should resume."
  [ctx node hit {:keys [read-line-fn wrap-expression-fn]}]
  (let [depth (or (:debug-depth ctx) 0)]
    (swap! *debug-state* assoc :paused {:ctx ctx :node node :hit hit :depth depth})
    (print-debug-pause ctx hit depth node)
    (println "dbg commands: :continue/:c, :step/:s, :next/:n, :finish/:f, :where, :locals, :print <expr>")
    (loop []
      (let [line (some-> (read-line-fn "dbg> ") str/trim)]
        (cond
          (or (nil? line) (= line ":continue") (= line ":c"))
          (swap! *debug-state* assoc :mode :continue :paused nil)

          (or (= line ":step") (= line ":s"))
          (swap! *debug-state* assoc :mode :step :paused nil)

          (or (= line ":next") (= line ":n"))
          (swap! *debug-state* assoc :mode :next :next-depth depth :paused nil)

          (or (= line ":finish") (= line ":f"))
          (swap! *debug-state* assoc :mode :finish :finish-depth depth :paused nil)

          (= line ":where")
          (do
            (println (str "statement #" hit ", depth " depth ", node " (name (:type node))))
            (when-let [src (:debug-source ctx)]
              (println (str "source: " src)))
            (when-let [ln (:dbg/line node)]
              (println (str "line: " ln)))
            (when-let [cn (:current-class-name ctx)]
              (println (str "class: " cn)))
            (when-let [mn (:current-method-name ctx)]
              (println (str "method: " mn)))
            (print-stack ctx)
            (recur))

          (= line ":locals")
          (do
            (doseq [{:keys [level bindings]} (env-bindings-chain (:current-env ctx))]
              (println (str "scope[" level "]"))
              (doseq [[k v] bindings]
                (println (str "  " k " = " (interp/nex-format-value v)))))
            (recur))

          (str/starts-with? line ":print ")
          (do
            (debug-eval-expression ctx (subs line (count ":print ")) wrap-expression-fn)
            (recur))

          :else
          (do
            (println "Unknown dbg command.")
            (recur)))))))

(defn make-debug-hook
  [{:keys [read-line-fn wrap-expression-fn] :as opts}]
  (fn [ctx node]
    (let [depth (or (:debug-depth ctx) 0)
          state-before @*debug-state*
          hit (inc (:counter state-before))]
      (swap! *debug-state* assoc :counter hit :last {:ctx ctx :node node :hit hit})
      (when (debug-should-pause? state-before hit depth ctx node opts)
        (debugger-loop! ctx node hit opts)))))

(defn maybe-break-on-error!
  "Enter debugger loop on error if corresponding break-on toggle is enabled."
  [fallback-ctx ex opts]
  (let [data (ex-data ex)
        contract? (contains? data :contract-type)
        mode (if contract? :contract :exception)
        enabled? (get-in @*debug-state* [:break-on mode] false)]
    (when enabled?
      (let [{:keys [ctx node hit]} (:last @*debug-state*)
            pause-ctx (or ctx fallback-ctx)
            pseudo-node (or node
                            {:type (if contract? :contract-violation :exception)
                             :dbg/line (:line data)})]
        (println (if contract?
                   "Paused on contract violation."
                   "Paused on exception."))
        (debugger-loop! pause-ctx pseudo-node (or hit 0) opts)))))
