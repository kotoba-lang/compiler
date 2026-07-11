(ns kotoba.compiler.frontend
  (:require [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as rt]))

(def forbidden-heads
  '#{eval load load-file require use import ns-resolve resolve alter-var-root
     future pmap agent send send-off new . .. set! defmacro throw try catch
     locking dosync atom ref volatile!})

(def arithmetic '#{+ - * quot})
(def comparisons '#{= < > <= >=})

(defn read-forms [source]
  (when (> (count source) (* 1024 1024))
    (throw (ex-info "source exceeds 1 MiB admission limit" {:phase :read})))
  (when (re-find #"#=" source)
    (throw (ex-info "reader evaluation is forbidden" {:phase :read})))
  (let [r (rt/string-push-back-reader source)]
    (loop [out []]
      (when (> (count out) 10000)
        (throw (ex-info "too many top-level forms" {:phase :read})))
      (let [x (reader/read {:read-cond :allow :features #{:kotoba} :eof ::eof} r)]
        (if (= x ::eof) out (recur (conj out x)))))))

(defn- reject! [message form]
  (throw (ex-info message {:phase :subset :form form})))

(declare validate-expr)

(defn- validate-bindings [bindings locals functions depth]
  (when-not (and (vector? bindings) (even? (count bindings)))
    (reject! "let requires an even binding vector" bindings))
  (when-not (= (count (take-nth 2 bindings)) (count (distinct (take-nth 2 bindings))))
    (reject! "duplicate let binding" bindings))
  (loop [pairs (partition 2 bindings) env locals]
    (if-let [[name value] (first pairs)]
      (do
        (when-not (and (simple-symbol? name) (not (contains? forbidden-heads name)))
          (reject! "invalid local binding" name))
        (validate-expr value env functions (inc depth))
        (recur (next pairs) (conj env name)))
      env)))

(defn validate-expr [form locals functions depth]
  (when (> depth 256)
    (reject! "expression nesting exceeds admission limit" form))
  (cond
    (integer? form) form
    (symbol? form) (if (contains? locals form) form
                       (reject! "unbound or dynamic symbol is forbidden" form))
    (seq? form)
    (let [[op & args] form]
      (when-not (simple-symbol? op) (reject! "computed or namespaced calls are forbidden" form))
      (when (or (contains? forbidden-heads op) (re-find #"[.]" (name op)))
        (reject! "dynamic loading, interop, mutation, and metaprogramming are forbidden" form))
      (cond
        (= op 'let)
        (let [[bindings & body] args]
          (when-not (= 1 (count body)) (reject! "let requires one result expression" form))
          (validate-expr (first body) (validate-bindings bindings locals functions depth)
                         functions (inc depth)))

        (= op 'if)
        (do (when-not (= 3 (count args)) (reject! "if requires test, then, else" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth))))

        (contains? arithmetic op)
        (do (when (or (empty? args) (and (= op 'quot) (not= 2 (count args))))
              (reject! "invalid arithmetic arity" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth))))

        (contains? comparisons op)
        (do (when-not (= 2 (count args)) (reject! "comparison requires two operands" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth))))

        (contains? functions op)
        (let [expected (count (get functions op))]
          (when-not (= expected (count args))
            (reject! "function call arity mismatch" form))
          (doseq [arg args] (validate-expr arg locals functions (inc depth))))

        :else (reject! "operation has no admitted lowering" form))
      form)
    :else (reject! "value type is outside the safe profile" form)))

(defn analyze [source]
  (let [forms (read-forms source)
        defs (filter #(and (seq? %) (= 'defn (first %))) forms)
        other (remove #(or (and (seq? %) (= 'ns (first %)))
                           (and (seq? %) (= 'defn (first %)))) forms)
        parsed (mapv (fn [form]
                       (let [[_ name params & body] form]
                         (when-not (simple-symbol? name) (reject! "invalid function name" name))
                         (when-not (and (vector? params) (every? simple-symbol? params)
                                        (= (count params) (count (distinct params))))
                           (reject! "function parameters must be unique simple symbols" params))
                         (when-not (= 1 (count body))
                           (reject! "function must contain one result expression" body))
                         {:name name :params params :result :i64 :effects #{} :body (first body)}))
                     defs)
        signatures (into {} (map (juxt :name :params) parsed))]
    (when (seq other) (reject! "only ns and defn are allowed at top level" (first other)))
    (when (empty? parsed) (reject! "at least one defn is required" forms))
    (when-not (= (count parsed) (count signatures)) (reject! "duplicate function name" defs))
    (when-not (contains? signatures 'main) (reject! "main entrypoint is required" defs))
    (when-not (empty? (get signatures 'main)) (reject! "main must take zero arguments" 'main))
    (doseq [{:keys [params body]} parsed]
      (validate-expr body (set params) signatures 0))
    {:format :kotoba.hir/v2 :entry 'main :result :i64 :effects #{}
     :functions parsed}))
