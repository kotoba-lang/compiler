(ns kotoba.compiler.component-core
  "Dedicated standard32 core emission for qualified Component Model slices."
  (:require [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.wasm-tools :as wasm-tools]))

(defn- reject [message data]
  (throw (ex-info message (assoc data :phase :component-core))))

(defn- exported-functions [kir]
  (let [names (set (:exports kir))]
    (filterv #(contains? names (:name %)) (:functions kir))))

(defn- scalar-function? [{:keys [param-types result]}]
  (and (every? #{:i64 :f32 :f64} param-types)
       (contains? #{:i64 :f32 :f64} result)))

(defn- string-identity-function? [{:keys [params param-types result body]}]
  (and (= 1 (count params))
       (= [:string] param-types)
       (= :string result)
       (= (first params) body)))

(defn assert-supported! [kir]
  (let [exports (exported-functions kir)]
    (cond
      (every? scalar-function? exports) :scalar
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (string-identity-function? (first exports))
           (empty? (:effects kir))) :string-identity
      :else
      (reject "component function body has no qualified Canonical lowering"
              {:exports (mapv #(select-keys % [:name :param-types :result :body]) exports)}))))

(defn- wit-name [symbol]
  (let [value (name symbol)]
    (when-not (re-matches #"[a-z][a-z0-9-]*" value)
      (reject "string component export has no direct WIT name" {:name symbol}))
    value))

(defn- string-identity-wat [function]
  (let [export (wit-name (:name function))]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") 3 3)\n"
     "  (global $next (mut i32) (i32.const 8))\n"
     "  (func $realloc (export \"cm32p2_realloc\")\n"
     "    (param $old-ptr i32) (param $old-size i32)\n"
     "    (param $align i32) (param $new-size i32) (result i32)\n"
     "    (local $ptr i32) (local $end i32) (local $copy-size i32)\n"
     "    local.get $new-size i32.eqz if i32.const 0 return end\n"
     "    local.get $align i32.eqz if unreachable end\n"
     "    local.get $align i32.const 8 i32.gt_u if unreachable end\n"
     "    local.get $align local.get $align i32.const 1 i32.sub i32.and if unreachable end\n"
     "    global.get $next local.get $align i32.const 1 i32.sub i32.add\n"
     "    i32.const 0 local.get $align i32.sub i32.and local.tee $ptr\n"
     "    local.get $new-size i32.add local.tee $end local.get $ptr i32.lt_u\n"
     "    if unreachable end\n"
     "    local.get $end i32.const 196608 i32.gt_u if unreachable end\n"
     "    local.get $end global.set $next\n"
     "    local.get $old-ptr i32.eqz if else\n"
     "      local.get $old-size local.get $new-size i32.lt_u\n"
     "      if (result i32) local.get $old-size else local.get $new-size end\n"
     "      local.set $copy-size\n"
     "      local.get $ptr local.get $old-ptr local.get $copy-size memory.copy\n"
     "    end local.get $ptr)\n"
     "  (func (export \"cm32p2||" export "\") (param $ptr i32) (param $len i32) (result i32)\n"
     "    (local $end i32) (local $out i32) (local $ret i32)\n"
     "    local.get $len i32.const 65536 i32.gt_u if unreachable end\n"
     "    local.get $ptr local.get $len i32.add local.tee $end local.get $ptr i32.lt_u\n"
     "    if unreachable end\n"
     "    local.get $end i32.const 196608 i32.gt_u if unreachable end\n"
     "    i32.const 0 i32.const 0 i32.const 1 local.get $len call $realloc local.set $out\n"
     "    local.get $out local.get $ptr local.get $len memory.copy\n"
     "    i32.const 0 i32.const 0 i32.const 4 i32.const 8 call $realloc local.tee $ret\n"
     "    local.get $out i32.store\n"
     "    local.get $ret local.get $len i32.store offset=4 local.get $ret)\n"
     "  (func (export \"cm32p2||" export "_post\") (param i32)\n"
     "    i32.const 8 global.set $next)\n"
     "  (func (export \"cm32p2_initialize\") i32.const 8 global.set $next))\n")))

(defn emit [kir target]
  (case (assert-supported! kir)
    :scalar (wasm/emit-component-core kir target)
    :string-identity (wasm-tools/parse-wat
                      (string-identity-wat (first (exported-functions kir))))))
