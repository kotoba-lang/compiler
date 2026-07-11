(ns kotoba.compiler.frontend
  (:require [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as rt]))

(def forbidden-heads
  '#{eval load load-file require use import ns-resolve resolve alter-var-root
     future pmap agent send send-off new . .. set! defmacro})

(defn read-forms [source]
  (when (re-find #"#=" source)
    (throw (ex-info "reader evaluation is forbidden" {:phase :read})))
  (let [r (rt/string-push-back-reader source)]
    (loop [out []]
      (let [x (reader/read {:read-cond :allow :features #{:kotoba} :eof ::eof} r)]
        (if (= x ::eof) out (recur (conj out x)))))))

(defn- reject! [message form]
  (throw (ex-info message {:phase :subset :form form})))

(declare validate-expr)

(defn validate-expr [form]
  (cond
    (integer? form) form
    (symbol? form) (reject! "unbound or dynamic symbol is forbidden" form)
    (seq? form)
    (let [[op & args] form]
      (when-not (symbol? op) (reject! "computed calls are forbidden" form))
      (when (or (contains? forbidden-heads op)
                (namespace op)
                (re-find #"[.]" (name op)))
        (reject! "dynamic loading, interop, and metaprogramming are forbidden" form))
      (when-not (contains? '#{+ - * quot} op)
        (reject! "operation has no admitted lowering" form))
      (when (or (empty? args) (and (= op 'quot) (not= 2 (count args))))
        (reject! "invalid arithmetic arity" form))
      (doseq [arg args] (validate-expr arg))
      form)
    :else (reject! "value type is outside the bootstrap safe profile" form)))

(defn analyze [source]
  (let [forms (read-forms source)
        defs (filter #(and (seq? %) (= 'defn (first %))) forms)
        other (remove #(or (and (seq? %) (= 'ns (first %)))
                           (and (seq? %) (= 'defn (first %)))) forms)]
    (when (seq other) (reject! "only ns and defn are allowed at top level" (first other)))
    (when-not (= 1 (count defs)) (reject! "exactly one defn is required" defs))
    (let [[_ name params & body] (first defs)]
      (when-not (= 'main name) (reject! "bootstrap entrypoint must be main" name))
      (when-not (and (vector? params) (empty? params))
        (reject! "main must take zero arguments" params))
      (when-not (= 1 (count body)) (reject! "main must contain one expression" body))
      (validate-expr (first body))
      {:format :kotoba.hir/v1 :entry 'main :params [] :result :i64
       :effects #{} :body (first body)})))
