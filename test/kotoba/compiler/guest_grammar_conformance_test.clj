(ns kotoba.compiler.guest-grammar-conformance-test
  "ADR-2607180900 (com-junkawasaki/root): kotoba-lang/kotoba-lang's
  guest-grammar.edn is the sole authoritative form catalog; kotoba and
  compiler must consume it rather than silently invent divergent
  forbidden/sugar sets. This locks that kotoba.compiler.frontend/forbidden-heads
  actually reflects the vendored catalog (resources/kotoba/lang/guest-grammar.edn,
  refreshed from kotoba-lang/kotoba-lang's lang/guest-grammar.edn when the git
  pin advances) instead of only the small hand-written baseline set -- a
  regression here means the classpath resource lookup in
  kotoba.compiler.frontend/load-catalog-forbidden silently stopped finding
  the catalog (e.g. a moved/renamed resource) and forbidden-heads quietly
  fell back to the narrower hard-coded set."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [kotoba.compiler.frontend :as frontend]))

(defn- catalog-forbidden-heads []
  (with-open [r (io/reader (io/resource "kotoba/lang/guest-grammar.edn"))]
    (let [catalog (edn/read (java.io.PushbackReader. r))]
      (into #{} (map (fn [x] (if (symbol? x) x (symbol (name x)))))
            (:forbidden-heads catalog #{})))))

(deftest forbidden-heads-is-superset-of-catalog
  (let [catalog-heads (catalog-forbidden-heads)]
    (is (seq catalog-heads)
        "catalog resource must actually resolve on the classpath -- an empty
         set here means load-catalog-forbidden silently found nothing")
    (is (= catalog-heads (clojure.set/intersection catalog-heads frontend/forbidden-heads))
        "every catalog forbidden-head must appear in compiler's admitted
         forbidden-heads; a missing entry here is a silent safety regression,
         not a cosmetic mismatch")))
