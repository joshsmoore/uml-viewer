(ns uml-viewer.main.uml-viewer
  (:require [uml-viewer.adapters.core :as core]
            [uml-viewer.clojure-language.graph-clojure]
            [uml-viewer.clojure-language.source-clojure :as clj-source]
            [uml-viewer.domain.log :as log]
            [uml-viewer.python-language.graph-python]
            [uml-viewer.python-language.source-python]
            [uml-viewer.ruby-language.graph-ruby]
            [uml-viewer.ruby-language.source-ruby]
            [uml-viewer.rust-language.graph-rust]
            [uml-viewer.rust-language.source-rust]
            [uml-viewer.typescript-language.graph-typescript]
            [uml-viewer.typescript-language.source-typescript])
  (:gen-class))

(defn -main [& args]
  (log/install-exception-log!)
  (try
    (apply core/start! clj-source/impl args)
    (catch Throwable t
      (log/log-exception! t "start!")
      (System/exit 1))))
