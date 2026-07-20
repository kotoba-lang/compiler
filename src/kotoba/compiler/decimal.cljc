(ns kotoba.compiler.decimal
  (:require [kotoba.compiler.value :as value]))

(def max-f64-bytes 64)
(def ^:private decimal-f64-pattern
  #"^[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?)|(?:\.[0-9]+))(?:[eE][+-]?[0-9]{1,3})?$")
(def f64-option-type [:option :f64])

(defn parse-f64
  "Parse the sealed finite decimal subset. Data-invalid input returns a typed
  none; invalid UTF-16 remains an enclosing string-ABI violation."
  [input]
  (let [bytes (value/utf8-byte-count! input)]
    (if (or (> bytes max-f64-bytes)
            (nil? (re-matches decimal-f64-pattern input)))
      [f64-option-type false]
      (let [parsed #?(:clj (Double/parseDouble input)
                      :cljs (js/Number input))]
        (if #?(:clj (Double/isFinite parsed)
               :cljs (js/Number.isFinite parsed))
          [f64-option-type true parsed]
          [f64-option-type false])))))
