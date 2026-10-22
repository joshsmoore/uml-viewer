(ns uml-viewer.ruby-language.grape
  "HTTP endpoints declared with Grape (or Sinatra-style) verb blocks."
  (:require [clojure.string :as str]))

(defn- indent [line]
  (count (re-find #"^[ \t]*" line)))

(def ^:private grape-scope
  #"^\s*(namespace|resources?|group|segment|route_param)\b\s*\(?\s*(:\w+|\"[^\"]*\"|'[^']*')?.*\bdo\b\s*(?:\|[^|]*\|)?\s*$")

(def ^:private grape-verb
  #"^\s*(get|post|put|patch|delete|head|options)\b\s*\(?\s*(:\w+|\"[^\"]*\"|'[^']*')?.*\bdo\b\s*(?:\|[^|]*\|)?\s*$")

(defn- path-part [kind arg]
  (let [p (some-> arg (str/replace #"^:|^[\"']|[\"']$" "") (str/replace #"^/+|/+$" ""))]
    (cond
      (str/blank? p) nil
      (= "route_param" kind) (str ":" p)
      :else p)))

(defn- endpoint-name [verb parts]
  (let [path (str/join "/" (remove nil? parts))]
    (str (str/upper-case verb) " " (if (str/blank? path) "/" path))))

(defn endpoints
  "Grape (or Sinatra-style) HTTP endpoints in Ruby `text`: a verb block
  such as `get :callback do` under `namespace :auth do`. Each is
  `{:name \"GET auth/callback\" :line n}`, 1-based, in order."
  [text]
  (:found
    (reduce (fn [{:keys [scopes found]} [n line]]
              (if (str/blank? line)
                {:scopes scopes :found found}
                (let [k (indent line)
                      scopes (filterv #(< (:indent %) k) scopes)
                      parts (map :part scopes)]
                  (if-let [[_ verb arg] (re-find grape-verb line)]
                    {:scopes scopes
                     :found (conj found {:name (endpoint-name verb (concat parts [(path-part verb arg)]))
                                         :line n})}
                    {:scopes (if-let [[_ kind arg] (re-find grape-scope line)]
                               (conj scopes {:indent k :part (path-part kind arg)})
                               scopes)
                     :found found}))))
            {:scopes [] :found []}
            (map-indexed (fn [i l] [(inc i) l]) (str/split-lines (or text ""))))))
