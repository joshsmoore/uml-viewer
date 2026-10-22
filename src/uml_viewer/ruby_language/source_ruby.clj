(ns uml-viewer.ruby-language.source-ruby
  "Ruby LanguageSource: open :file and find a def, class, module, or HTTP
  endpoint."
  (:require [clojure.string :as str]
            [uml-viewer.ruby-language.grape :as grape]
            [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["def\\s+(?:self\\.)?%s(?![\\w?!=])"
   "class\\s+(?:[\\w:]+::)?%s(?![\\w:])"
   "module\\s+(?:[\\w:]+::)?%s(?![\\w:])"])

(def ^:private by-pattern (source/file-source pattern-fmts))

(defn- endpoint-line [text member-name]
  (some #(when (= member-name (:name %)) (:line %)) (grape/endpoints text)))

(def impl
  (reify source/LanguageSource
    (locate [_ ident] (source/locate by-pattern ident))
    (extract [_ text ident]
      (if-let [n (endpoint-line text (:name ident))]
        (str/trimr (nth (str/split-lines text) (dec n)))
        (source/extract by-pattern text ident)))
    (start-line [_ text ident]
      (or (endpoint-line text (:name ident))
          (source/start-line by-pattern text ident)))
    (title [_ ident] (source/title by-pattern ident))))

(source/register! :ruby impl)
