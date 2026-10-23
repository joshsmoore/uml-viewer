(ns uml-viewer.python-language.source-python
  "Python LanguageSource: open :file and find a def, class, or
  `Class.method`."
  (:require [clojure.string :as str]
            [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["async\\s+def\\s+%s\\b"
   "def\\s+%s\\b"
   "class\\s+%s\\b"])

(defn- start-of
  "Index of `member-name` in `text`: a def or class, or for
  `Class.method` the first def of that method after the class."
  [text member-name]
  (let [[cls method] (str/split (str member-name) #"\." 2)]
    (if method
      (when-let [c (source/pattern-start text cls ["class\\s+%s\\b"])]
        (some->> (source/pattern-start (subs text c) method (take 2 pattern-fmts))
                 (+ c)))
      (source/pattern-start text member-name pattern-fmts))))

(def ^:private by-pattern (source/file-source pattern-fmts))

(def impl
  (reify source/LanguageSource
    (locate [_ ident] (source/locate by-pattern ident))
    (extract [_ text ident]
      (some->> (start-of text (:name ident)) (source/declaration-line text)))
    (start-line [_ text ident]
      (some->> (start-of text (:name ident)) (source/line-number text)))
    (title [_ ident] (source/title by-pattern ident))))

(source/register! :python impl)
