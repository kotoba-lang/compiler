(ns kotoba.compiler.wasm-typed-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.backend.wasm-typed :as typed]
            [kotoba.compiler.core :as compiler]))

(defn- node-probe [compiled javascript]
  (let [encoded (.encodeToString (java.util.Base64/getEncoder) ^bytes (:bytes compiled))
        probe (str "import('./runtime/browser-host.mjs').then(async m=>{"
                   "const h=await m.instantiateKotoba(Buffer.from(process.argv[1],'base64'));"
                   javascript "}).catch(e=>{console.error(e);process.exit(70)})")]
    (shell/sh "node" "--input-type=module" "-e" probe encoded)))

(def option-source
  "(defn main [] (match-option (option-some-of [:option :i64] 7)
                                [:option :i64]
                                (none 0)
                                (some value (+ value 1))))")

(deftest typed-metadata-is-versioned-deterministic-and-bounded
  (let [kir (:kir (compiler/compile-source option-source :js-kotoba-v1))
        table (typed/descriptor-table kir)
        bytes (typed/metadata-bytes kir)]
    (is (= typed/abi-version (first bytes)))
    (is (= bytes (typed/metadata-bytes kir)))
    (is (= (count table) (count (typed/descriptor-indices kir))))
    (is (empty? (typed/literal-table kir)))
    (is (some #{[:option :i64]} table))
    (is (every? #(<= 0 % 255) bytes))))

(deftest typed-custom-section-is-emitted-only-for-kir-v4
  (let [i64-kir (:kir (compiler/compile-source "(defn main [] 7)" :wasm32-kotoba-v1))
        typed-kir (assoc i64-kir :format :kotoba.kir/v4)
        typed-bytes (vec (map #(bit-and (int %) 0xff)
                              (wasm/emit typed-kir :wasm32-kotoba-v1)))
        i64-bytes (vec (map #(bit-and (int %) 0xff)
                            (wasm/emit i64-kir :wasm32-kotoba-v1)))
        marker (mapv int (.getBytes typed/custom-section-name "UTF-8"))]
    (testing "custom section identity is present in typed modules"
      (is (some #(= marker %) (partition (count marker) 1 typed-bytes))))
    (testing "legacy i64 modules do not acquire a typed ABI claim"
    (is (not-any? #(= marker %) (partition (count marker) 1 i64-bytes))))))

(deftest compatibility-is-sealed-and-host-admitted-before-instantiation
  (let [compiled (compiler/compile-source "(defn main [] 42)" :wasm32-browser-kotoba-v1)
        encoded (.encodeToString (java.util.Base64/getEncoder) ^bytes (:bytes compiled))
        probe (shell/sh
               "node" "--input-type=module" "-e"
               (str "import('./runtime/browser-host.mjs').then(async m=>{"
                    "const b=Buffer.from(process.argv[1],'base64');"
                    "const h=await m.instantiateKotoba(b);"
                    "if(h.compatibility.compiler!=='kotoba-compiler/1'||"
                    "h.compatibility.language!=='kotoba.language/safe-v1'||"
                    "h.compatibility.target!=='wasm32-browser-kotoba-v1')process.exit(2);"
                    "const marker=Buffer.from('kotoba-compiler/1');const at=b.indexOf(marker);"
                    "if(at<0)process.exit(3);b[at+marker.length-1]=50;"
                    "try{await m.instantiateKotoba(b);process.exit(4)}catch(e){"
                    "if(e.code!=='compatibility-mismatch')process.exit(5)}})"
                    ".catch(e=>{console.error(e);process.exit(70)})")
               encoded)]
    (is (= :kotoba.compatibility/v1 (get-in compiled [:compatibility :format])))
    (is (zero? (:exit probe)) (:err probe))))

(deftest externref-boundaries-reject-forgery-and-cross-schema-substitution
  (let [source "(ns typed.boundary (:export [main make-i64 make-string read-i64]))
                (defn main [] 0)
                (defn make-i64 [] [:option :i64] (option-some-of [:option :i64] 7))
                (defn make-string [] [:option :string] (option-some-of [:option :string] \"bad\"))
                (defn read-i64 [value [:option :i64]] :i64
                  (match-option value [:option :i64] (none 0) (some item item)))"
        compiled (compiler/compile-source source :wasm32-kotoba-v1)
        probe (node-probe
               compiled
               (str "const x=h.instance.exports;"
                    "if(x['read-i64'](x['make-i64']())!==7n)process.exit(2);"
                    "for(const forged of ["
                    "Object.freeze([Object.freeze(['option','i64']),true,7n]),"
                    "x['make-string']()]){let rejected=false;try{x['read-i64'](forged)}catch(e){rejected=true}"
                    "if(!rejected)process.exit(3)}"))]
    (is (= :kotoba.value/typed-v1 (:value-profile compiled)))
    (is (= :kotoba.typed/externref-v1 (:value-abi compiled)))
    (is (= #{:reference-types} (:wasm-features compiled)))
    (is (zero? (:exit probe)) (:err probe))))

(deftest algebraic-operations-have-sealed-wasm-runtime-parity
  (let [source
        "(ns typed.operations (:export [main check-option read-option check-result read-result
                                        update-vector compare-vector set-has set-add set-remove compare-set
                                        update-record compare-record nested-set-count]))
         (defn main [] 0)
         (defn check-option [] :bool (option-some?-of [:option :i64] (option-some-of [:option :i64] 7)))
         (defn read-option [] :i64 (option-value-of [:option :i64] (option-some-of [:option :i64] 7) (quot 1 0)))
         (defn check-result [] :bool (result-ok?-of [:result :i64 :string] (result-ok-of [:result :i64 :string] 8)))
         (defn read-result [] :i64 (result-value-of [:result :i64 :string] (result-ok-of [:result :i64 :string] 8) (quot 1 0)))
         (defn update-vector [] :string
           (hetero-vector-at [:vector [:i64 :string]]
             (hetero-vector-assoc [:vector [:i64 :string]]
               (hetero-vector [:vector [:i64 :string]] 1 \"before\") 1 \"after\") 1))
         (defn compare-vector [] :i64
           (hetero-vector-equal [:vector [:i64 :string]]
             (hetero-vector [:vector [:i64 :string]] 1 \"same\")
             (hetero-vector [:vector [:i64 :string]] 1 \"same\")))
         (defn set-has [] :bool
           (typed-set-contains [:set :i64] (typed-set [:set :i64] 1 2) 2))
         (defn set-add [] :i64
           (typed-set-count [:set :i64]
             (typed-set-conj [:set :i64] (typed-set [:set :i64] 1) 2)))
         (defn set-remove [] :i64
           (typed-set-count [:set :i64]
             (typed-set-disj [:set :i64] (typed-set [:set :i64] 1 2) 1)))
         (defn compare-set [] :i64
           (typed-set-equal [:set :i64]
             (typed-set [:set :i64] 2 1) (typed-set [:set :i64] 1 2)))
         (defn update-record [] :i64
           (record-get [:record :demo/person [[:name :string] [:age :i64]]]
             (record-assoc [:record :demo/person [[:name :string] [:age :i64]]]
               (record [:record :demo/person [[:name :string] [:age :i64]]] \"A\" 1) :age 2) :age))
         (defn compare-record [] :i64
           (record-equal [:record :demo/person [[:name :string] [:age :i64]]]
             (record [:record :demo/person [[:name :string] [:age :i64]]] \"A\" 1)
             (record [:record :demo/person [[:name :string] [:age :i64]]] \"A\" 1)))
         (defn nested-set-count [] :i64
           (typed-set-count [:set :keyword]
             (record-get [:record :demo/report [[:covered [:set :keyword]]]]
               (record [:record :demo/report [[:covered [:set :keyword]]]]
                 (typed-set [:set :keyword] :ready :reviewed))
               :covered)))"
        compiled (compiler/compile-source source :wasm32-kotoba-v1)
        probe (node-probe
               compiled
               (str "const x=h.instance.exports;"
                    "const expected={'check-option':true,'read-option':7n,'check-result':true,'read-result':8n,"
                    "'update-vector':'after','compare-vector':1n,'set-has':true,'set-add':2n,"
                    "'set-remove':1n,'compare-set':1n,'update-record':2n,'compare-record':1n,"
                    "'nested-set-count':2n};"
                    "for(const [name,value] of Object.entries(expected))"
                    "if(x[name]()!=value){console.error(name,x[name](),value);process.exit(2)}"))]
    (is (zero? (:exit probe)) (:err probe))))

(deftest typed-control-flow-preserves-bool-and-reference-comparisons
  (let [source
        "(ns typed.control (:export [main strings-match keyword-match]))
         (defn main [] :i64
           (if (string=? \"Kotoba\" \"Kotoba\")
             (if (= :ready :ready) 42 1)
             0))
         (defn strings-match [] :bool
           (if (string=? \"same\" \"same\") true false))
         (defn keyword-match [] :i64 (= :ready :ready))"
        compiled (compiler/compile-source source :wasm32-kotoba-v1)
        probe (node-probe
               compiled
               (str "const x=h.instance.exports;"
                    "if(x.main()!==42n||x['strings-match']()!==true||"
                    "x['keyword-match']()!==1n)process.exit(2);"))]
    (is (zero? (:exit probe)) (:err probe))))

(deftest typed-if-needs-no-unsealed-synthetic-boolean-literal
  (let [source
        "(ns typed.import-like (:export [main]))
         (defn covered? [items [:set :keyword]] :bool
           (typed-set-contains [:set :keyword] items :ready))
         (defn main [] :i64
           (if (covered? (typed-set [:set :keyword] :ready)) 42 0))"
        compiled (compiler/compile-source source :wasm32-kotoba-v1)
        probe (node-probe compiled
                          "if(h.instance.exports.main()!==42n)process.exit(2);")]
    (is (not-any? #{[:bool true]} (typed/literal-table (:kir compiled))))
    (is (zero? (:exit probe)) (:err probe))))

(deftest bounded-vector-i64-has-sealed-wasm-runtime-parity
  (let [full (str "(vector-i64 " (str/join " " (range 128)) ")")
        source
        (str "(ns typed.vector-i64 (:export [main bitops make count-items lookup at update append drop-items full]))\n"
             "(defn main [] :i64 42)\n"
             "(defn bitops [] :i64 (bit-xor (bit-and 255 15) 5))\n"
             "(defn make [] :vector-i64 (vector-i64 10 20 30))\n"
             "(defn count-items [items :vector-i64] :i64 (vector-count items))\n"
             "(defn lookup [items :vector-i64 index :i64] :i64 (vector-get items index 99))\n"
             "(defn at [items :vector-i64 index :i64] :i64 (vector-at items index))\n"
             "(defn update [items :vector-i64 index :i64] :vector-i64 (vector-assoc items index 77))\n"
             "(defn append [items :vector-i64] :vector-i64 (vector-conj items 40))\n"
             "(defn drop-items [items :vector-i64 index :i64] :vector-i64 (vector-drop items index))\n"
             "(defn full [] :vector-i64 " full ")")
        compiled (compiler/compile-source source :wasm32-kotoba-v1)
        probe (node-probe
               compiled
               (str "const x=h.instance.exports,v=x.make();"
                    "const binary=h.typedValues.bytes(Buffer.alloc(12171,7));"
                    "if(x.main()!==42n||x.bitops()!==10n||x['count-items'](v)!==3n||x.lookup(v,1n)!==20n||"
                    "x.lookup(v,-1n)!==99n||x.lookup(v,4294967296n)!==99n||x.at(v,2n)!==30n)process.exit(2);"
                    "if(x['count-items'](binary)!==12171n||x.at(binary,12170n)!==7n)process.exit(5);"
                    "const updated=x.update(v,1n),appended=x.append(v),dropped=x['drop-items'](v,2n);"
                    "if(x.at(updated,1n)!==77n||x['count-items'](appended)!==4n||"
                    "x['count-items'](dropped)!==1n||x.at(dropped,0n)!==30n)process.exit(3);"
                    "for(const run of [()=>x.at(v,-1n),()=>x.at(v,4294967296n),"
                    "()=>x.update(v,4294967296n),()=>x['drop-items'](v,4n),"
                    "()=>h.typedValues.bytes(Buffer.alloc(16385)),()=>h.typedValues.bytes([256]),"
                    "()=>h.typedValues.vectorI64([1n,2]),"
                    "()=>x['count-items'](Object.freeze([v[0],10n,20n,30n]))]){"
                    "let rejected=false;try{run()}catch(e){rejected=true}if(!rejected)process.exit(4)}"))]
    (is (= 2 typed/abi-version))
    (is (some #{:vector-i64} (typed/descriptor-table (:kir compiled))))
    (is (zero? (:exit probe)) (:err probe))))

(deftest bounded-typed-map-has-real-wasm-runtime-parity
  (let [source
        "(ns typed.map (:export [main present missing update remove compare-map]))
         (defn main [] :i64 42)
         (defn present [] :i64
           (option-value-of [:option :i64]
             (typed-map-get [:map :keyword :i64]
               (typed-map-new [:map :keyword :i64] :b 2 :a 1) :b) 0))
         (defn missing [] [:option :i64]
           (typed-map-get [:map :keyword :i64]
             (typed-map-new [:map :keyword :i64]) :missing))
         (defn update [] :i64
           (typed-map-count [:map :keyword :i64]
             (typed-map-assoc [:map :keyword :i64]
               (typed-map-new [:map :keyword :i64] :a 1) :b 2)))
         (defn remove [] :bool
           (typed-map-contains [:map :keyword :i64]
             (typed-map-dissoc [:map :keyword :i64]
               (typed-map-new [:map :keyword :i64] :a 1 :b 2) :a) :b))
         (defn compare-map [] :i64
           (typed-map-equal [:map :keyword :i64]
             (typed-map-new [:map :keyword :i64] :b 2 :a 1)
             (typed-map-new [:map :keyword :i64] :a 1 :b 2)))"
        compiled (compiler/compile-source source :wasm32-kotoba-v1)
        probe (node-probe
               compiled
               (str "const x=h.instance.exports,n=x.missing();"
                    "if(x.main()!==42n||x.present()!==2n||n[1]!==false||"
                    "x.update()!==2n||x.remove()!==true||x['compare-map']()!==1n)process.exit(2);"))]
    (is (= 31 (get-in compiled [:limits :typed-map-entries])))
    (is (zero? (:exit probe)) (:err probe))))

(deftest descriptor-node-budget-is-per-descriptor-with-a-separate-table-bound
  (let [names (mapv #(symbol (str "value-" %)) (range 16))
        exports (str/join " " (cons "main" (map str names)))
        functions
        (str/join
         "\n"
         (map-indexed
          (fn [index name]
            (let [type (str "[:record :budget/type-" index
                            " [[:a :i64] [:b :string] [:c [:option :i64]]]]")]
              (str "(defn " name " [] " type
                   " (record " type " " index " \"v\" (option-none-of [:option :i64])))")))
          names))
        source (str "(ns typed.descriptor-budget (:export [" exports "]))\n"
                    "(defn main [] :i64 42)\n" functions)
        compiled (compiler/compile-source source :wasm32-kotoba-v1)
        probe (node-probe compiled "if(h.instance.exports.main()!==42n)process.exit(2);")]
    (is (> (count (typed/descriptor-table (:kir compiled))) 16))
    (is (zero? (:exit probe)) (:err probe))))
