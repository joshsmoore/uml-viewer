(ns uml-viewer.adapters.markup
  "Hiccup-style vectors to an SVG or HTML string, and a small JSON writer."
  (:require [clojure.string :as str]))

(defn escape
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "\n" "&#10;")))

(defn num-str
  "Compact number: integers stay whole, others keep two decimals."
  [n]
  (let [d (double n)
        r (Math/round d)]
    (if (< (Math/abs (- d r)) 0.005)
      (str r)
      (format "%.2f" d))))

(defn- attr-value [v]
  (cond
    (keyword? v) (name v)
    (number? v) (num-str v)
    :else (escape v)))

(defn- attrs-str [attrs]
  (apply str
         (for [[k v] attrs
               :when (some? v)]
           (str " " (name k) "=\"" (attr-value v) "\""))))

(declare html)

(defn- element [[tag & more]]
  (let [[attrs children] (if (map? (first more))
                           [(first more) (rest more)]
                           [{} more])]
    (str "<" (name tag) (attrs-str attrs)
         (if (seq children)
           (str ">" (apply str (map html children)) "</" (name tag) ">")
           "/>"))))

(defn html
  "Markup for `node`: a [tag attrs? & children] vector, a seq of nodes,
  text, or nil."
  [node]
  (cond
    (nil? node) ""
    (vector? node) (element node)
    (seq? node) (apply str (map html node))
    :else (escape node)))

(defn- json-string [s]
  (str "\""
       (-> (str s)
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace "\n" "\\n")
           (str/replace "\r" "\\r")
           (str/replace "\t" "\\t")
           (str/replace "<" "\\u003c"))
       "\""))

(defn json
  "JSON text for maps, sequentials, strings, keywords, numbers, booleans, nil."
  [v]
  (cond
    (nil? v) "null"
    (boolean? v) (str v)
    (number? v) (str v)
    (keyword? v) (json-string (name v))
    (map? v) (str "{"
                  (str/join "," (map (fn [[k x]] (str (json-string (name k)) ":" (json x))) v))
                  "}")
    (sequential? v) (str "[" (str/join "," (map json v)) "]")
    :else (json-string v)))
