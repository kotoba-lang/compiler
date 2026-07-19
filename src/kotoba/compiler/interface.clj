(ns kotoba.compiler.interface
  (:require [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.frontend :as frontend]))

(def schema :kotoba.interface/v1)

(defn inspect-source
  "Return the closed public contract of SOURCE. Bodies, constants, local names,
  paths and host details are deliberately excluded."
  [source]
  (let [hir (frontend/analyze source)
        exports (set (:exports hir))
        functions (->> (:functions hir)
                       (filter #(contains? exports (:name %)))
                       (map (fn [{:keys [name params param-types result effects]}]
                              {:name name
                               :arity (count params)
                               :param-types (vec (or param-types (repeat (count params) :i64)))
                               :result result
                               :effects (set effects)}))
                       (sort-by (comp str :name))
                       vec)
        value {:format schema
               :namespace (:namespace hir)
               :entry (:entry hir)
               :hir-format (:format hir)
               :floating-point-policy :kotoba.floating-point/ieee-754-f32-f64-v1
               :exports functions
               :effects (set (:effects hir))}]
    (assoc value :sha256 (artifact/sha256 value))))

(defn valid? [value]
  (and (= schema (:format value))
       (vector? (:exports value))
       (string? (:sha256 value))
       (= (:sha256 value) (artifact/sha256 (dissoc value :sha256)))))
