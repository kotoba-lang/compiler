(ns kotoba.compiler.xml
  (:require [clojure.string :as str]
            [kotoba.compiler.value :as value]
            #?@(:cljs [[kotoba.compiler.cljs-i64 :as i64]])))

(def node-limit 2048)
(def depth-limit 32)
(def attribute-limit 32)
(def path-segment-limit 32)

(def ^:private xml-name-pattern #"[A-Za-z_][A-Za-z0-9_.:-]{0,127}")

(defn- trap! [reason]
  (throw (ex-info (name reason) {:phase :xml :trap reason})))

(defn- valid-name? [text]
  (boolean (re-matches xml-name-pattern text)))

(defn- whitespace? [character]
  (contains? #{" " "\t" "\n" "\r"} character))

(defn- char-at [text index]
  (when (< index (count text)) (subs text index (inc index))))

(defn parse-elements
  "Parse the sealed, text-free XML subset into exact-path element records."
  [input]
  (let [text (value/bounded-string! input value/string-value-byte-limit)
        length (count text)
        cursor (volatile! 0)
        node-count (volatile! 0)
        output (volatile! [])]
    (letfn [(starts? [token] (str/starts-with? (subs text @cursor) token))
            (skip! []
              (while (and (< @cursor length) (whitespace? (char-at text @cursor)))
                (vswap! cursor inc)))
            (comment! []
              (when (starts? "<!--")
                (let [end (str/index-of text "-->" (+ @cursor 4))]
                  (when (or (nil? end)
                            (str/includes? (subs text (+ @cursor 4) end) "--"))
                    (trap! :invalid-xml-comment))
                  (vreset! cursor (+ end 3))
                  true)))
            (comments! []
              (loop []
                (skip!)
                (when (comment!) (recur))))
            (name! []
              (let [begin @cursor]
                (while (and (< @cursor length)
                            (boolean (re-matches #"[A-Za-z0-9_.:-]"
                                                 (char-at text @cursor))))
                  (vswap! cursor inc))
                (let [result (subs text begin @cursor)]
                  (when-not (valid-name? result) (trap! :invalid-xml-name))
                  result)))
            (element! [depth parent]
              (when (> depth depth-limit) (trap! :xml-depth-limit))
              (when (> (vswap! node-count inc) node-limit) (trap! :xml-node-limit))
              (when (or (not= "<" (char-at text @cursor))
                        (contains? #{"/" "!" "?"} (char-at text (inc @cursor))))
                (trap! :invalid-xml-element))
              (vswap! cursor inc)
              (let [tag (name!)
                    path (if (seq parent) (str parent "/" tag) tag)
                    attributes (volatile! {})
                    empty? (volatile! false)]
                (loop []
                  (skip!)
                  (cond
                    (starts? "/>") (do (vswap! cursor + 2) (vreset! empty? true))
                    (= ">" (char-at text @cursor)) (vswap! cursor inc)
                    :else
                    (let [attribute (name!)]
                      (when (contains? @attributes attribute)
                        (trap! :duplicate-xml-attribute))
                      (when (>= (count @attributes) attribute-limit)
                        (trap! :xml-attribute-limit))
                      (skip!)
                      (when-not (= "=" (char-at text @cursor))
                        (trap! :invalid-xml-attribute))
                      (vswap! cursor inc)
                      (skip!)
                      (let [quote (char-at text @cursor)]
                        (when-not (contains? #{"\"" "'"} quote)
                          (trap! :invalid-xml-attribute))
                        (vswap! cursor inc)
                        (let [begin @cursor]
                          (while (and (< @cursor length)
                                      (not= quote (char-at text @cursor)))
                            (when (contains? #{"<" "&"} (char-at text @cursor))
                              (trap! :xml-entity-or-markup-rejected))
                            (vswap! cursor inc))
                          (when (>= @cursor length) (trap! :unterminated-xml-attribute))
                          (let [attribute-value (value/bounded-string!
                                                 (subs text begin @cursor)
                                                 value/string-value-byte-limit)]
                            (vswap! cursor inc)
                            (vswap! attributes assoc attribute attribute-value))))
                      (recur))))
                (vswap! output conj {:path path :attributes @attributes})
                (when-not @empty?
                  (loop []
                    (comments!)
                    (skip!)
                    (cond
                      (starts? "</")
                      (do (vswap! cursor + 2)
                          (let [closing (name!)]
                            (skip!)
                            (when (or (not= closing tag)
                                      (not= ">" (char-at text @cursor)))
                              (trap! :xml-close-mismatch))
                            (vswap! cursor inc)))
                      (= "<" (char-at text @cursor))
                      (do (element! (inc depth) path) (recur))
                      :else (trap! :xml-text-rejected))))))]
      (comments!)
      (when (starts? "<?xml")
        (let [end (str/index-of text "?>" (+ @cursor 5))]
          (when (nil? end) (trap! :invalid-xml-declaration))
          (let [declaration (subs text @cursor (+ end 2))]
            (when-not (re-matches
                       #"<\?xml\s+version=(?:\"1\.[01]\"|'1\.[01]')(?:\s+encoding=(?:\"(?:UTF-8|utf-8)\"|'(?:UTF-8|utf-8)'))?\s*\?>"
                       declaration)
              (trap! :invalid-xml-declaration)))
          (vreset! cursor (+ end 2))))
      (comments!)
      (element! 1 "")
      (comments!)
      (skip!)
      (when-not (= @cursor length) (trap! :xml-trailing-content))
      @output)))

(defn validate-path! [path]
  (value/bounded-string! path value/string-value-byte-limit)
  (let [segments (str/split path #"/" -1)]
    (when (or (empty? segments) (> (count segments) path-segment-limit)
              (some #(not (valid-name? %)) segments))
      (trap! :invalid-xml-path))
    path))

(defn path-count [input path]
  (let [path (validate-path! path)
        result (count (filter #(= path (:path %)) (parse-elements input)))]
    #?(:clj (long result) :cljs (i64/->bigint result))))

(defn path-attr [input path index attribute]
  (let [path (validate-path! path)]
    (when-not (valid-name? (value/bounded-string! attribute value/string-value-byte-limit))
      (trap! :invalid-xml-name))
    (when #?(:clj (neg? index) :cljs (< index i64/zero))
      (trap! :xml-index-out-of-range))
    (let [matches (filterv #(= path (:path %)) (parse-elements input))
          host-index #?(:clj index :cljs (js/Number index))
          option-type [:option :string]]
      (if (>= host-index (count matches))
        [option-type false]
        (if-let [attribute-value (get-in matches [host-index :attributes attribute])]
          [option-type true attribute-value]
          [option-type false])))))
