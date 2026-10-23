(ns uml-viewer.source
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.regex Pattern]))

(defprotocol LanguageSource
  (locate [this ident]
    "Path to the file that should contain `ident`, or nil.")
  (extract [this source ident]
    "Source span for `ident` from `source` text, or nil.")
  (start-line [this source ident]
    "1-based line of `ident` in `source`, or nil.")
  (title [this ident]
    "Window title for this member."))

(defonce ^:private languages (atom {}))

(defn register!
  "Install `impl` as the extractor for `lang` (e.g. `:clojure`)."
  [lang impl]
  (swap! languages assoc lang impl)
  lang)

(defn lookup
  [lang]
  (get @languages lang))

(defn pattern-start
  "Index of the first pattern in `fmts` that matches `member-name`, or nil."
  [source member-name fmts]
  (when (and source member-name)
    (let [quoted (Pattern/quote (str member-name))]
      (some (fn [fmt]
              (let [m (.matcher (Pattern/compile (format fmt quoted)) source)]
                (when (.find m) (.start m))))
            fmts))))

(defn line-number
  "1-based line of index `start` in `source`."
  [source start]
  (inc (count (re-seq #"\n" (subs source 0 start)))))

(defn declaration-line
  "Trimmed line that begins at `start`."
  [source start]
  (let [end (or (str/index-of source \newline start) (count source))]
    (str/trimr (subs source start end))))

(defn line-member
  "Declaration line of `member-name`, or nil."
  [source member-name fmts]
  (when-let [start (pattern-start source member-name fmts)]
    (declaration-line source start)))

(defn member-line-of
  "1-based line of `member-name`, or nil."
  [source member-name fmts]
  (when-let [start (pattern-start source member-name fmts)]
    (line-number source start)))

(defn existing-file
  "Forward-slash path when `ident` names a file that exists."
  [ident]
  (let [f (:file ident)]
    (when (and (seq (str f)) (.isFile (io/file f)))
      (str/replace (str f) #"\\" "/"))))

(defn file-title
  "Title from the file path and, when present, the member name."
  [ident]
  (str (or (:file ident) (:ns ident))
       (when (:name ident) (str "/" (:name ident)))))

(defn file-source
  "LanguageSource that opens `:file` and finds a member with `fmts`.
  `extract-fn` is `(fn [source member-name] string-or-nil)`."
  ([fmts]
   (file-source fmts (fn [text name] (line-member text name fmts))))
  ([fmts extract-fn]
   (reify LanguageSource
     (locate [_ ident] (existing-file ident))
     (extract [_ text ident] (extract-fn text (:name ident)))
     (start-line [_ text ident] (member-line-of text (:name ident) fmts))
     (title [_ ident] (file-title ident)))))

(defn- from-text [impl ident lang path src]
  (let [named? (seq (str (:name ident)))]
    (when (or (not named?) (extract impl src ident))
      {:title (if named?
                (str path ":" (or (start-line impl src ident) 1))
                path)
       :file path
       :body src
       :line (when named? (start-line impl src ident))
       :lang lang})))

(defn- from-impl [impl ident lang]
  (when impl
    (when-let [path (locate impl ident)]
      (from-text impl ident lang path (slurp path)))))

(defn- impl-and-lang [lang-or-impl ident]
  (if (keyword? lang-or-impl)
    [(lookup lang-or-impl) lang-or-impl]
    [lang-or-impl (or (:lang ident) :clojure)]))

(defn member-source
  "Locate a member. `ident` is a map with at least `:name`.
  One-arg form looks up the extractor by `:lang` (default `:clojure`).
  Two-arg form takes a `LanguageSource` impl, or a lang keyword.
  Returns `{:title :file :body :line :lang}` — `body` is the whole file,
  `line` is where the member starts — or nil."
  ([ident]
   (member-source (or (:lang ident) :clojure) ident))
  ([lang-or-impl ident]
   (let [[impl lang] (impl-and-lang lang-or-impl ident)]
     (from-impl impl ident lang))))

(defn text-source
  "`member-source` over `text`, the content of `(:file ident)` at some
  commit, instead of the file on disk."
  [lang-or-impl ident text]
  (let [[impl lang] (impl-and-lang lang-or-impl ident)]
    (when (and impl text)
      (from-text impl ident lang (:file ident) text))))

(defn- word-at? [line w]
  (and (seq (str w))
       (re-find (re-pattern (str "(?<![\\w.])" (Pattern/quote (str w)) "(?!\\w)")) line)))

(def ^:private import-line
  #"^\s*(?:import|from|require|require_relative|use|include|extend|prepend|load)\b|^\s*[\[(]|:require")

(defn reference-line
  "1-based line of `text` that first refers to `target` (a class map
  with `:ns`, `:name`, `:id`): a line naming its full namespace, else an
  import-like line naming its last namespace segment, else a word match
  of its class name, else of that segment. Nil when none matches."
  [text target]
  (let [lines (vec (str/split-lines (or text "")))
        full (or (:ns target) (some-> (:id target) name))
        seg (some-> full (str/split #"\.") last)
        segs (distinct (remove nil? [seg (some-> seg (str/replace "-" "_"))]))
        find-line (fn [pred] (some (fn [[i l]] (when (pred l) (inc i))) (map-indexed vector lines)))]
    (or (find-line #(word-at? % full))
        (find-line (fn [l] (and (re-find import-line l) (some #(word-at? l %) segs))))
        (find-line #(word-at? % (:name target)))
        (find-line (fn [l] (some #(word-at? l %) segs))))))
