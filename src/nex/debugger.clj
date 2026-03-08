(ns nex.debugger
  "Debugger state and control flow for Nex REPL."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(defonce ^:dynamic ^:private *debug-enabled* (atom false))
(defonce ^:dynamic ^:private *debug-state*
  (atom {:breakpoints {}
         :next-breakpoint-id 1
         :breakpoint-hit-counts {}
         :watchpoints {}
         :next-watchpoint-id 1
         :watch-values {}
         :break-on {:exception false :contract false}
         :break-on-filters {:exception nil :contract nil}
         :debug-script []
         :mode :continue
         :counter 0
         :selected-frame 0
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

(defn- parse-non-negative-int [s]
  (try
    (let [n (Integer/parseInt (str/trim s))]
      (when (>= n 0) n))
    (catch Exception _ nil)))

(defn breakpoints []
  (:breakpoints @*debug-state*))

(defn breakpoint-entries
  "Return breakpoints as sorted [id bp] entries."
  []
  (sort-by first (seq (:breakpoints @*debug-state*))))

(defn watchpoint-entries
  "Return watchpoints as sorted [id wp] entries."
  []
  (sort-by first (seq (:watchpoints @*debug-state*))))

(defn break-on-status []
  (:break-on @*debug-state*))

(defn break-on-filters []
  (:break-on-filters @*debug-state*))

(defn set-break-on! [kind flag]
  (swap! *debug-state* assoc-in [:break-on kind] (boolean flag)))

(defn set-break-on-filter! [kind filter-val]
  (swap! *debug-state* assoc-in [:break-on-filters kind] filter-val))

(defn clear-break-on-filter! [kind]
  (swap! *debug-state* assoc-in [:break-on-filters kind] nil))

(defn breakpoint->str [bp]
  (case (:kind bp)
    :hit (str (:n bp))
    :cm (str (:class bp) "." (:method bp))
    :cm-line (str (:class bp) "." (:method bp) ":" (:line bp))
    :file-line (str (:source bp) ":" (:line bp))
    :field-write (str "field:" (:field bp))
    :class-field-write (str (:class bp) "#" (:field bp))
    (str bp)))

(defn breakpoint-entry->str [[id bp]]
  (str "[" id "] " (breakpoint->str bp)
       (when (:temporary bp) " (temp)")
       (when (false? (:enabled bp true)) " (disabled)")
       (when-let [after (:after bp)]
         (str " after " after))
       (when-let [every (:every bp)]
         (str " every " every))
       (when-let [cond-expr (:condition bp)]
         (str " if " cond-expr))))

(defn watchpoint-entry->str [[id wp]]
  (str "[" id "] " (:expr wp)
       (when-let [cond-expr (:condition wp)]
         (str " if " cond-expr))
       (when (false? (:enabled wp true)) " (disabled)")))

(defn parse-breakpoint-spec
  "Parse breakpoint spec.
   Supported:
   - 12
   - Class.method
   - Class.method:42
   - file.nex:42 or path/to/file.nex:42
   - field:status
   - Order#status"
  [raw]
  (let [s (str/trim (or raw ""))]
    (or
      (when-let [n (parse-positive-int s)]
        {:kind :hit :n n})
      (when-let [[_ cls f] (re-matches #"^([A-Za-z_][A-Za-z0-9_]*)#([A-Za-z_][A-Za-z0-9_]*)$" s)]
        {:kind :class-field-write :class cls :field f})
      (when-let [[_ f] (re-matches #"^field:([A-Za-z_][A-Za-z0-9_]*)$" s)]
        {:kind :field-write :field f})
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

(defn parse-watch-command
  "Parse :watch argument into {:expr <expr> :condition <expr-or-nil>}."
  [raw]
  (let [s (str/trim (or raw ""))
        [_ expr-part cond-part] (or (re-matches #"(?is)^\s*(.+?)\s+if\s+(.+)\s*$" s)
                                    [nil s nil])]
    (when-not (str/blank? expr-part)
      (cond-> {:expr (str/trim expr-part)}
        (and cond-part (not (str/blank? cond-part)))
        (assoc :condition (str/trim cond-part))))))

(defn add-breakpoint! [spec]
  (let [bp (if (integer? spec)
             {:kind :hit :n spec :enabled true}
             (assoc spec :enabled (if (contains? spec :enabled) (:enabled spec) true)))
        id (:next-breakpoint-id @*debug-state*)]
    (swap! *debug-state*
           (fn [st]
             (-> st
                 (assoc-in [:breakpoints id] bp)
                 (update :next-breakpoint-id inc))))
    id))

(defn add-watchpoint! [expr]
  (let [wp (if (map? expr)
             (assoc expr :enabled (if (contains? expr :enabled) (:enabled expr) true))
             {:expr expr :enabled true})
        id (:next-watchpoint-id @*debug-state*)]
    (swap! *debug-state*
           (fn [st]
             (-> st
                 (assoc-in [:watchpoints id] wp)
                 (update :next-watchpoint-id inc))))
    id))

(defn remove-breakpoint! [spec]
  (cond
    (integer? spec)
    (let [removed? (contains? (:breakpoints @*debug-state*) spec)]
      (swap! *debug-state* update :breakpoints dissoc spec)
      (if removed? 1 0))

    :else
    (let [bp (if (integer? spec) {:kind :hit :n spec} spec)
          canonical (fn [m] (dissoc m :enabled :temporary))
          ids (->> (:breakpoints @*debug-state*)
                   (filter (fn [[_ v]] (= (canonical v) (canonical bp))))
                   (map first)
                   vec)]
      (swap! *debug-state*
             (fn [st]
               (reduce (fn [acc id] (update acc :breakpoints dissoc id))
                       st
                       ids)))
      (count ids))))

(defn remove-watchpoint! [id]
  (let [removed? (contains? (:watchpoints @*debug-state*) id)]
    (swap! *debug-state*
           (fn [st]
             (-> st
                 (update :watchpoints dissoc id)
                 (update :watch-values dissoc id))))
    (if removed? 1 0)))

(defn clear-breakpoints! []
  (swap! *debug-state* assoc :breakpoints {} :next-breakpoint-id 1 :breakpoint-hit-counts {}))

(defn clear-watchpoints! []
  (swap! *debug-state* assoc :watchpoints {} :next-watchpoint-id 1 :watch-values {}))

(defn set-breakpoint-enabled! [id enabled?]
  (if (contains? (:breakpoints @*debug-state*) id)
    (do
      (swap! *debug-state* assoc-in [:breakpoints id :enabled] (boolean enabled?))
      true)
    false))

(defn set-breakpoint-after! [id after]
  (if (contains? (:breakpoints @*debug-state*) id)
    (do
      (swap! *debug-state*
             (fn [st]
               (-> st
                   (assoc-in [:breakpoints id :after] after)
                   (assoc-in [:breakpoint-hit-counts id] 0))))
      true)
    false))

(defn clear-breakpoint-after! [id]
  (if (contains? (:breakpoints @*debug-state*) id)
    (do
      (swap! *debug-state* update-in [:breakpoints id] dissoc :after)
      true)
    false))

(defn set-breakpoint-every! [id every]
  (if (contains? (:breakpoints @*debug-state*) id)
    (do
      (swap! *debug-state* update-in [:breakpoints id] assoc :every every)
      true)
    false))

(defn clear-breakpoint-every! [id]
  (if (contains? (:breakpoints @*debug-state*) id)
    (do
      (swap! *debug-state* update-in [:breakpoints id] dissoc :every)
      true)
    false))

(defn set-all-breakpoints-enabled! [enabled?]
  (swap! *debug-state*
         update :breakpoints
         (fn [m]
           (into {}
                 (map (fn [[id bp]]
                        [id (assoc bp :enabled (boolean enabled?))])
                      m)))))

(defn set-watchpoint-enabled! [id enabled?]
  (if (contains? (:watchpoints @*debug-state*) id)
    (do
      (swap! *debug-state* assoc-in [:watchpoints id :enabled] (boolean enabled?))
      true)
    false))

(defn set-all-watchpoints-enabled! [enabled?]
  (swap! *debug-state*
         update :watchpoints
         (fn [m]
           (into {}
                 (map (fn [[id wp]]
                        [id (assoc wp :enabled (boolean enabled?))])
                      m)))))

(defn set-debug-script! [lines]
  (swap! *debug-state* assoc :debug-script (vec (remove str/blank? lines))))

(defn clear-debug-script! []
  (swap! *debug-state* assoc :debug-script []))

(defn debug-script-active? []
  (seq (:debug-script @*debug-state*)))

(defn pop-debug-script-line! []
  (let [line (first (:debug-script @*debug-state*))]
    (when (some? line)
      (swap! *debug-state* update :debug-script subvec 1))
    line))

(defn reset-run-state! []
  (swap! *debug-state* assoc :counter 0 :mode :continue :paused nil :last nil
         :next-depth nil :finish-depth nil :selected-frame 0 :watch-values {}
         :breakpoint-hit-counts {}))

(defn snapshot-breakpoints-and-watchpoints []
  {:breakpoints (mapv second (breakpoint-entries))
   :watchpoints (mapv second (watchpoint-entries))})

(defn restore-breakpoints-and-watchpoints! [snapshot]
  (clear-breakpoints!)
  (clear-watchpoints!)
  (doseq [bp (:breakpoints snapshot)]
    (add-breakpoint! bp))
  (doseq [wp (:watchpoints snapshot)]
    (add-watchpoint! wp)))

(defn- last-program-top-node
  "Return last executable top-level node from a parsed program AST.
   Prefer :statements, fall back to legacy :calls."
  [ast]
  (if (seq (:statements ast))
    (last (:statements ast))
    (last (:calls ast))))

(defn- eval-breakpoint-condition?
  [ctx cond-expr]
  (let [eval-ctx (assoc ctx :debug-hook nil)]
    (try
    (let [ast (p/ast cond-expr)
          top-node (when (= (:type ast) :program)
                     (last-program-top-node ast))
          v (if top-node
              (interp/eval-node eval-ctx top-node)
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
              v (interp/eval-node eval-ctx expr-node)]
          (boolean v))
        (catch Exception _
          false))))))

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
          :field-write (and (= :member-assign (:type node))
                            (= (:field bp) (:field node)))
          :class-field-write (and (= :member-assign (:type node))
                                  (= (:class bp) cn)
                                  (= (:field bp) (:field node)))
          false)]
    (and (:enabled bp true)
         base-match
         (if-let [cond-expr (:condition bp)]
           (eval-breakpoint-condition? ctx cond-expr)
           true))))

(defn- breakpoint-modifier-hit?
  [id bp]
  (let [count-now (inc (get-in @*debug-state* [:breakpoint-hit-counts id] 0))
        _ (swap! *debug-state* assoc-in [:breakpoint-hit-counts id] count-now)
        after (or (:after bp) 0)
        every (:every bp)
        after-ok (> count-now after)
        every-ok (if every
                   (zero? (mod count-now every))
                   true)]
    (and after-ok every-ok)))

(defn matching-breakpoint-entries
  "Return all [id bp] entries that match at current execution point."
  [breakpoints hit ctx node]
  (->> breakpoints
       (filter (fn [[id bp]]
                 (and (breakpoint-hit? bp hit ctx node)
                      (breakpoint-modifier-hit? id bp))))
       vec))

(defn- eval-watch-expression
  [ctx expr wrap-expression-fn]
  (let [eval-ctx (assoc ctx :debug-hook nil)]
    (try
    (let [ast (p/ast expr)]
      (if (and (= (:type ast) :program) (last-program-top-node ast))
        (interp/eval-node eval-ctx (last-program-top-node ast))
        ::invalid))
    (catch Exception _
      (try
        (let [wrapped (p/ast (wrap-expression-fn expr))
              expr-node (-> wrapped :classes first :body first :members first :body first :args first)]
          (interp/eval-node eval-ctx expr-node))
        (catch Exception _
          ::invalid))))))

(defn watchpoint-hits!
  [ctx {:keys [wrap-expression-fn]}]
  (let [watch-entries (watchpoint-entries)
        old-values (:watch-values @*debug-state*)
        new-values (atom old-values)
        hits (atom [])]
    (doseq [[id wp] watch-entries
            :when (:enabled wp true)]
      (let [curr (eval-watch-expression ctx (:expr wp) wrap-expression-fn)]
        (when (not= curr ::invalid)
          (let [had-prev? (contains? old-values id)
                prev (get old-values id ::none)]
            (swap! new-values assoc id curr)
            (when (and had-prev?
                       (not= prev curr)
                       (if-let [cond-expr (:condition wp)]
                         (eval-breakpoint-condition? ctx cond-expr)
                         true))
              (swap! hits conj {:id id :expr (:expr wp) :old prev :new curr}))))))
    (swap! *debug-state* assoc :watch-values @new-values)
    @hits))

(defn debug-should-pause?
  ([state hit depth ctx node]
   (debug-should-pause? state hit depth ctx node nil))
  ([{:keys [breakpoints mode next-depth finish-depth]} hit depth ctx node _opts]
   (or (boolean (seq (matching-breakpoint-entries breakpoints hit ctx node)))
       (= mode :step)
       (and (= mode :next) (some? next-depth) (<= depth next-depth))
       (and (= mode :finish) (some? finish-depth) (< depth finish-depth)))))

(defn- print-debug-pause [ctx hit depth node]
  (println (str "Paused at statement #" hit
                " depth=" depth
                " node=" (name (:type node))
                (when-let [line (:dbg/line node)] (str " line=" line))
                (when-let [src (:debug-source ctx)] (str " source=" src)))))

(defn- print-stack [ctx selected]
  (let [stack (or (:debug-stack ctx) [])]
    (println "stack:")
    (if (seq stack)
      (doseq [[idx frame] (map-indexed vector (reverse stack))]
        (println (str "  " (if (= idx selected) "*" " ") "[" idx "] "
                      (:class frame) "." (:method frame)
                      (when-let [src (:source frame)] (str " (" src ")")))))
      (println "  (empty)"))))

(defn- frame-by-index [ctx idx]
  (let [stack (vec (reverse (or (:debug-stack ctx) [])))]
    (when (and (integer? idx) (<= 0 idx) (< idx (count stack)))
      (nth stack idx))))

(defn- print-current-frame-locals [frame]
  (let [env (:env frame)
        bindings (if env @(:bindings env) {})
        arg-names (or (:arg-names frame) #{})
        field-names (or (:field-names frame) #{})
        special-names #{"this" "result"}
        args (into {} (filter (fn [[k _]] (contains? arg-names k)) bindings))
        fields (into {} (filter (fn [[k _]] (contains? field-names k)) bindings))
        special (into {} (filter (fn [[k _]] (contains? special-names k)) bindings))
        locals (into {} (remove (fn [[k _]]
                                  (or (contains? arg-names k)
                                      (contains? field-names k)
                                      (contains? special-names k)))
                                bindings))]
    (when (seq args)
      (println "args:")
      (doseq [[k v] args]
        (println (str "  " k " = " (interp/nex-format-value v)))))
    (when (seq fields)
      (println "fields:")
      (doseq [[k v] fields]
        (println (str "  " k " = " (interp/nex-format-value v)))))
    (when (seq locals)
      (println "locals:")
      (doseq [[k v] locals]
        (println (str "  " k " = " (interp/nex-format-value v)))))
    (when (seq special)
      (println "special:")
      (doseq [[k v] special]
        (println (str "  " k " = " (interp/nex-format-value v)))))))

(defn selected-frame-index []
  (:selected-frame @*debug-state* 0))

(defn set-selected-frame! [idx]
  (swap! *debug-state* assoc :selected-frame idx))

(defn- frame-context [ctx]
  (let [selected (selected-frame-index)
        frame (frame-by-index ctx selected)]
    (if frame
      (cond-> ctx
        (:env frame) (assoc :current-env (:env frame))
        (:class frame) (assoc :current-class-name (:class frame))
        (:method frame) (assoc :current-method-name (:method frame))
        (:source frame) (assoc :debug-source (:source frame)))
      ctx)))

(defn- print-source-snippet [ctx node]
  (when-let [line (:dbg/line node)]
    (let [src (:debug-source ctx)]
      (when (and (string? src)
                 (not (str/blank? src))
                 (not= src "<repl>")
                 (.exists (io/file src)))
        (try
          (let [lines (vec (str/split-lines (slurp src)))
                max-line (count lines)
                from (max 1 (- line 2))
                to (min max-line (+ line 2))]
            (println "context:")
            (doseq [ln (range from (inc to))]
              (println (str "  " (if (= ln line) ">" " ")
                            (format "%4d" ln) " | " (nth lines (dec ln))))))
          (catch Exception _ nil))))))

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
            (and (= (:type ast) :program) (last-program-top-node ast))
            (let [v (interp/eval-node ctx (last-program-top-node ast))]
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
    (swap! *debug-state* assoc :paused {:ctx ctx :node node :hit hit :depth depth}
           :selected-frame 0)
    (print-debug-pause ctx hit depth node)
    (println "dbg commands: :help, :continue/:c, :step/:s, :next/:n, :finish/:f, :where, :frames, :frame <n>, :locals, :print <expr>")
    (loop []
      (let [script-line (pop-debug-script-line!)
            line-raw (or script-line (read-line-fn "dbg> "))
            line (some-> line-raw str/trim)]
        (when script-line
          (println (str "dbg(script)> " line)))
        (cond
          (or (nil? line) (= line ":continue") (= line ":c"))
          (swap! *debug-state* assoc :mode :continue :paused nil)

          (or (= line ":step") (= line ":s"))
          (swap! *debug-state* assoc :mode :step :paused nil)

          (or (= line ":next") (= line ":n"))
          (swap! *debug-state* assoc :mode :next :next-depth depth :paused nil)

          (or (= line ":finish") (= line ":f"))
          (swap! *debug-state* assoc :mode :finish :finish-depth depth :paused nil)

          (or (= line ":help") (= line ":h") (= line ":?"))
          (do
            (println "Debugger commands:")
            (println "  :help                - Show this help")
            (println "  :continue, :c        - Resume execution")
            (println "  :step, :s            - Step to next statement")
            (println "  :next, :n            - Step over calls")
            (println "  :finish, :f          - Run until current frame returns")
            (println "  :where               - Show location, stack, and source context")
            (println "  :frames              - List stack frames")
            (println "  :frame <n>           - Select active frame")
            (println "  :locals              - Show locals in active frame")
            (println "  :print <expr>        - Evaluate expression in active frame")
            (recur))

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
            (println (str "selected-frame: " (selected-frame-index)))
            (print-stack ctx (selected-frame-index))
            (print-source-snippet ctx node)
            (recur))

          (or (= line ":frames") (= line ":frame"))
          (do
            (print-stack ctx (selected-frame-index))
            (recur))

          (str/starts-with? line ":frame ")
          (let [idx (parse-non-negative-int (subs line (count ":frame ")))]
            (if-let [frame (and idx (frame-by-index ctx idx))]
              (do
                (set-selected-frame! idx)
                (println (str "Selected frame [" idx "] "
                              (:class frame) "." (:method frame)))
                (recur))
              (do
                (println "Usage: :frame <index>, where top frame is 0")
                (recur))))

          (= line ":locals")
          (do
            (if-let [frame (frame-by-index ctx (selected-frame-index))]
              (do
                (print-current-frame-locals frame)
                (let [env (:env frame)]
                  (when-let [p (:parent env)]
                    (doseq [{:keys [level bindings]} (env-bindings-chain p)]
                      (println (str "scope[" (inc level) "]"))
                      (doseq [[k v] bindings]
                        (println (str "  " k " = " (interp/nex-format-value v))))))))
              (let [fctx (frame-context ctx)]
                (doseq [{:keys [level bindings]} (env-bindings-chain (:current-env fctx))]
                  (println (str "scope[" level "]"))
                  (doseq [[k v] bindings]
                    (println (str "  " k " = " (interp/nex-format-value v)))))))
            (recur))

          (str/starts-with? line ":print ")
          (do
            (debug-eval-expression (frame-context ctx) (subs line (count ":print ")) wrap-expression-fn)
            (recur))

          :else
          (do
            (println (str "Unknown dbg command: " line))
            (println "Type :help for debugger commands.")
            (recur)))))))

(defn- exception-filter-match?
  [filter-val ex]
  (if (nil? filter-val)
    true
    (let [s (str/lower-case (str (class ex) " " (.getMessage ex)))
          f (str/lower-case (str filter-val))]
      (str/includes? s f))))

(defn- contract-kind [contract-type]
  (let [s (str/lower-case (str contract-type))]
    (cond
      (str/includes? s "precondition") :pre
      (str/includes? s "postcondition") :post
      (str/includes? s "invariant") :invariant
      :else :contract)))

(defn- contract-filter-match?
  [filter-val ex]
  (if (nil? filter-val)
    true
    (let [data (ex-data ex)
          ct (:contract-type data)
          ck (contract-kind ct)]
      (if (keyword? filter-val)
        (= filter-val ck)
        (str/includes? (str/lower-case (str ct " " (.getMessage ex)))
                       (str/lower-case (str filter-val)))))))

(defn pause-for-watch-hits!
  [ctx node hit watch-hits opts]
  (when (seq watch-hits)
    (doseq [{:keys [id expr old new]} watch-hits]
      (println (str "Watchpoint [" id "] changed: " expr
                    " | old=" (interp/nex-format-value old)
                    ", new=" (interp/nex-format-value new))))
    (debugger-loop! ctx node hit opts)))

(defn make-debug-hook
  [{:keys [read-line-fn wrap-expression-fn] :as opts}]
  (fn [ctx node]
    (let [depth (or (:debug-depth ctx) 0)
          state-before @*debug-state*
          hit (inc (:counter state-before))
          matched-breakpoints (matching-breakpoint-entries (:breakpoints state-before) hit ctx node)
          watch-hits (watchpoint-hits! ctx {:wrap-expression-fn wrap-expression-fn})]
      (swap! *debug-state* assoc :counter hit :last {:ctx ctx :node node :hit hit})
      (when (debug-should-pause? state-before hit depth ctx node opts)
        (debugger-loop! ctx node hit opts))
      (when (seq matched-breakpoints)
        (doseq [[id bp] matched-breakpoints
                :when (:temporary bp)]
          (swap! *debug-state* update :breakpoints dissoc id)))
      (when (seq watch-hits)
        (pause-for-watch-hits! ctx node hit watch-hits opts)))))

(defn maybe-break-on-error!
  "Enter debugger loop on error if corresponding break-on toggle is enabled."
  [fallback-ctx ex opts]
  (let [data (ex-data ex)
        contract? (contains? data :contract-type)
        mode (if contract? :contract :exception)
        enabled? (get-in @*debug-state* [:break-on mode] false)
        filter-val (get-in @*debug-state* [:break-on-filters mode])
        allowed? (if contract?
                   (contract-filter-match? filter-val ex)
                   (exception-filter-match? filter-val ex))]
    (when (and enabled? allowed?)
      (let [{:keys [ctx node hit]} (:last @*debug-state*)
            pause-ctx (or ctx fallback-ctx)
            pseudo-node (or node
                            {:type (if contract? :contract-violation :exception)
                             :dbg/line (:line data)})]
        (println (if contract?
                   "Paused on contract violation."
                   "Paused on exception."))
        (debugger-loop! pause-ctx pseudo-node (or hit 0) opts)))))
