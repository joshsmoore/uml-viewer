(ns uml-viewer.ruby-language.source-ruby
  "Ruby LanguageSource: open :file and find a def, class, or module."
  (:require [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["def\\s+(?:self\\.)?%s(?![\\w?!=])"
   "class\\s+(?:[\\w:]+::)?%s(?![\\w:])"
   "module\\s+(?:[\\w:]+::)?%s(?![\\w:])"])

(def impl (source/file-source pattern-fmts))

(source/register! :ruby impl)
