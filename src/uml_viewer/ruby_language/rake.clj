(ns uml-viewer.ruby-language.rake
  "Rake tasks declared in a .rake file."
  (:require [clojure.string :as str]))

(defn- indent [line]
  (count (re-find #"^[ \t]*" line)))

(def ^:private rake-namespace
  #"^\s*namespace\b\s*\(?\s*(?::(\w+)|[\"']([^\"']+)[\"']|(\w+):).*\bdo\b")

(def ^:private rake-task
  #"^\s*(?:multi)?task\b\s*\(?\s*(?::(\w+)|[\"']([^\"']+)[\"']|(\w+):)")

(defn- task-name [parts]
  (str/join ":" parts))

(defn rake-file? [name]
  (str/ends-with? name ".rake"))

(defn tasks
  "Rake tasks in `text`: `task :sync do` under `namespace :email do` is
  `{:name \"email:sync\" :line n}`, 1-based, in order."
  [text]
  (:found
    (reduce (fn [{:keys [scopes found]} [n line]]
              (if (str/blank? line)
                {:scopes scopes :found found}
                (let [k (indent line)
                      scopes (filterv #(< (:indent %) k) scopes)
                      parts (map :part scopes)]
                  (if-let [[_ & names] (re-find rake-task line)]
                    {:scopes scopes
                     :found (conj found {:name (task-name (concat parts [(some identity names)]))
                                         :line n})}
                    {:scopes (if-let [[_ & names] (re-find rake-namespace line)]
                               (conj scopes {:indent k :part (some identity names)})
                               scopes)
                     :found found}))))
            {:scopes [] :found []}
            (map-indexed (fn [i l] [(inc i) l]) (str/split-lines (or text ""))))))
