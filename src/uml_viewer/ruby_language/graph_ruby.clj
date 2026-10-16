(ns uml-viewer.ruby-language.graph-ruby
  "Ruby LanguageGraph: one class per .rb file. Constants resolve the way
  Zeitwerk autoloads them, so a Rails reference needs no require."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]))

(def ^:private skip-dir-names
  #{"assets" "javascript" "views" "node_modules" "vendor" "tmp" "log"
    "spec" "test" "db" "config" "bin" "public" "storage" "coverage"})

(defn- skip-dir? [name]
  (or (str/starts-with? name ".")
      (contains? skip-dir-names name)))

(defn- excluded-name? [name]
  (boolean (re-find #"_(?:spec|test)\.rb$" name)))

(defn- child-dirs [dir]
  (->> (.listFiles (io/file dir))
       (filter #(.isDirectory %))
       (remove #(skip-dir? (.getName %)))
       (sort-by #(.getName %))))

(defn- rails-root? [root]
  (.isDirectory (io/file root "app")))

(defn- scan-dirs
  "Directories to walk. A Rails root walks `app` and `lib`."
  [root]
  (if (rails-root? root)
    (filter #(.isDirectory %) [(io/file root "app") (io/file root "lib")])
    [root]))

(defn- app-roots [app]
  (concat (child-dirs app)
          (->> (child-dirs app)
               (map #(io/file % "concerns"))
               (filter #(.isDirectory %)))))

(defn autoload-roots
  "Zeitwerk roots under `root`: each `app/*` directory, its `concerns`,
  and `lib`. A root named `app` is read the same way. Anything else is
  its own root."
  [root]
  (let [root (.getCanonicalFile (io/file root))
        lib (io/file root "lib")]
    (mapv #(.getCanonicalFile %)
          (cond
            (rails-root? root) (cond-> (vec (app-roots (io/file root "app")))
                                 (.isDirectory lib) (conj lib))
            (= "app" (.getName root)) (app-roots root)
            :else [root]))))

(defn- walk-rb [dir]
  (->> (tree-seq (fn [f]
                   (and (.isDirectory f)
                        (or (= f dir) (not (skip-dir? (.getName f))))))
                 (fn [d] (vec (.listFiles d)))
                 dir)
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".rb"))
       (remove #(excluded-name? (.getName %)))))

(defn- source-files [root]
  (->> (scan-dirs (.getCanonicalFile (io/file root)))
       (mapcat #(walk-rb (.getCanonicalFile %)))
       (sort-by #(.getPath %))))

;; Masking: comments become spaces; strings, heredocs, and %-literals are
;; copied and recorded as holes so a constant inside them is ignored.

(def ^:private heredoc-start
  #"<<[~-]?(?:([\"'`])([A-Za-z_]\w*)\1|([A-Z_][A-Z0-9_]*)\b)")

(def ^:private closers {\( \) \[ \] \{ \} \< \>})

(defn- line-start? [source i]
  (or (zero? i) (= \newline (.charAt ^String source (dec i)))))

(defn- begin-block? [source i]
  (and (line-start? source i)
       (.startsWith ^String source "=begin" (int i))))

(defn- end-of-begin-block [source i]
  (let [m (re-matcher #"(?m)^=end\b[^\n]*" source)]
    (if (.find m (int i)) (.end m) (count source))))

(defn- percent-delimiter
  "Index of the opening delimiter of a %-literal at `i`, or nil."
  [source i]
  (let [n (count source)
        at (fn [j] (when (< j n) (.charAt ^String source j)))
        prev (when (pos? i) (.charAt ^String source (dec i)))
        letter? (contains? #{\q \Q \w \W \i \I \r \s} (at (inc i)))
        j (if letter? (+ i 2) (inc i))
        d (at j)]
    (when (and (or (nil? prev) (Character/isWhitespace ^char prev)
                   (contains? #{\( \, \= \[ \{} prev))
               d
               (if letter?
                 (contains? #{\( \[ \{ \< \| \! \/} d)
                 (contains? #{\( \[ \{ \<} d)))
      j)))

(defn- scan-percent [source j]
  (let [open (.charAt ^String source j)
        close (get closers open open)
        n (count source)]
    (loop [k (inc j) depth 0]
      (if (>= k n)
        n
        (let [c (.charAt ^String source k)]
          (cond
            (= c \\) (recur (+ k 2) depth)
            (and (= c close) (zero? depth)) (inc k)
            (= c close) (recur (inc k) (dec depth))
            (and (= c open) (not= open close)) (recur (inc k) (inc depth))
            :else (recur (inc k) depth)))))))

(defn- heredoc-at [source i]
  (let [m (re-matcher heredoc-start source)]
    (when (and (.find m (int i)) (= i (.start m)))
      {:end (.end m) :id (or (.group m 2) (.group m 3))})))

(defn- heredoc-body-end
  "Index after the terminator line of a heredoc whose body starts at `i`."
  [source i id]
  (let [m (re-matcher (re-pattern (str "(?m)^[ \\t]*" id "[ \\t]*$")) source)]
    (if (.find m (int i))
      (min (count source) (inc (.end m)))
      (count source))))

(defn- blank-to [sb source i end]
  (doseq [k (range i end)]
    (.append ^StringBuilder sb (if (= \newline (.charAt ^String source k)) \newline \space)))
  end)

(defn- copy-hole [sb source holes i end]
  (graph/copy-span sb source holes i end))

(defn- mask-step
  "One masking step at `i`. Returns {:i :holes :pending}."
  [source sb holes pending i]
  (let [n (count source)
        c (.charAt ^String source i)]
    (cond
      (and (= c \newline) (seq pending))
      (let [_ (.append ^StringBuilder sb c)
            end (reduce (fn [at id] (heredoc-body-end source at id)) (inc i) pending)
            step (copy-hole sb source holes (inc i) end)]
        (assoc step :pending []))

      (begin-block? source i)
      {:i (blank-to sb source i (end-of-begin-block source i)) :holes holes :pending pending}

      (= c \#)
      {:i (graph/mask-line-comment source sb i n) :holes holes :pending pending}

      (contains? #{\" \' \`} c)
      (assoc (copy-hole sb source holes i (graph/scan-quoted source i c n)) :pending pending)

      (and (= c \<) (heredoc-at source i))
      (let [{:keys [end id]} (heredoc-at source i)]
        (.append ^StringBuilder sb (subs source i end))
        {:i end :holes holes :pending (conj pending id)})

      (and (= c \%) (percent-delimiter source i))
      (let [end (scan-percent source (percent-delimiter source i))]
        (assoc (copy-hole sb source holes i end) :pending pending))

      :else
      (do (.append ^StringBuilder sb c)
          {:i (inc i) :holes holes :pending pending}))))

(defn mask-source
  "`{:text :holes}`: comments blanked, literal ranges recorded as holes."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0 holes [] pending []]
      (if (>= i n)
        {:text (str sb) :holes holes}
        (let [step (mask-step source sb holes pending i)]
          (recur (:i step) (:holes step) (:pending step)))))))

(defn- in-hole? [holes i]
  (boolean (some (fn [[a b]] (and (<= a i) (< i b))) holes)))

;; Constant names.

(defn camelize
  "`ruby_llm_adapter` is `RubyLlmAdapter`."
  [segment]
  (->> (str/split segment #"_")
       (remove str/blank?)
       (map #(str (str/upper-case (subs % 0 1)) (subs % 1)))
       str/join))

(defn const-key
  "Comparable key of constant segments. Acronyms and underscores fold,
  so `RubyLLMAdapter` and `ruby_llm_adapter` agree."
  [segs]
  (mapv #(str/lower-case (str/replace % "_" "")) segs))

(defn split-const [s]
  (vec (remove str/blank? (str/split (str s) #"::"))))

(defn kebab
  "`ActiveRecord` is `active-record`; `HTTPClient` is `http-client`."
  [s]
  (-> s
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace "_" "-")
      str/lower-case))

(def ^:private core-constants
  #{"Object" "BasicObject" "Module" "Class" "Kernel" "Comparable" "Enumerable"
    "Exception" "StandardError" "RuntimeError" "ArgumentError" "NameError"
    "NoMethodError" "TypeError" "KeyError" "IndexError" "NotImplementedError"
    "Struct" "Data" "Hash" "Array" "String" "Symbol" "Integer" "Float"
    "Numeric" "Set" "Time" "Date" "DateTime" "Range" "Proc" "IO" "File"
    "Singleton" "Forwardable"})

(defn core-constant?
  "True for a Ruby core or stdlib constant, which is never a foreign class."
  [segs]
  (and (= 1 (count segs)) (contains? core-constants (first segs))))

(defn foreign-id [segs]
  (keyword (str/join "." (map kebab segs))))

;; Reading one file.

(def ^:private decl-pattern
  #"(?m)^([ \t]*)(class|module)[ \t]+(::)?([A-Z][\w:]*)(?:[ \t]*<[ \t]*(?:::)?([A-Z][\w:]*))?")

(defn- push-decl [stack indent segs]
  (let [stack (vec (drop-while #(>= (:indent %) indent) (rseq stack)))
        outer (or (:segs (first stack)) [])]
    {:stack (conj (vec (reverse stack)) {:indent indent :segs (into outer segs)})
     :full (into outer segs)}))

(defn- declarations [text holes]
  (let [m (re-matcher decl-pattern text)]
    (loop [stack [] acc []]
      (if-not (.find m)
        acc
        (if (in-hole? holes (.start m))
          (recur stack acc)
          (let [indent (count (.group m 1))
                {:keys [stack full]} (push-decl stack indent (split-const (.group m 4)))]
            (recur stack
                   (conj acc {:kind (keyword (.group m 2))
                              :segs full
                              :super (some-> (.group m 5) split-const)}))))))))

(defn- refs-of [text holes]
  (let [m (re-matcher #"(?<![\w@$:.?])(::)?([A-Z]\w*(?:::[A-Z]\w*)*)" text)]
    (loop [acc []]
      (if (.find m)
        (recur (if (in-hole? holes (.start m))
                 acc
                 (conj acc {:top? (some? (.group m 1)) :segs (split-const (.group m 2))})))
        acc))))

(defn- mixins-of [text holes]
  (let [m (re-matcher #"(?m)^[ \t]*(?:include|extend|prepend)[ \t(]+((?:::)?[A-Z][\w:]*(?:[ \t]*,[ \t]*(?:::)?[A-Z][\w:]*)*)" text)]
    (loop [acc []]
      (if (.find m)
        (recur (if (in-hole? holes (.start m))
                 acc
                 (into acc (map split-const (str/split (.group m 1) #"[ \t]*,[ \t]*")))))
        acc))))

(defn- requires-of [text holes]
  (let [m (re-matcher #"(?m)^[ \t]*(require|require_relative)[ \t(]+[\"']([^\"']+)[\"']" text)]
    (loop [acc []]
      (if (.find m)
        (recur (if (in-hole? holes (.start m))
                 acc
                 (conj acc {:kind (keyword (str/replace (.group m 1) "_" "-"))
                            :path (.group m 2)})))
        acc))))

(defn singularize [word]
  (cond
    (re-find #"ies$" word) (str/replace word #"ies$" "y")
    (re-find #"(?:ss|x|ch|sh)es$" word) (str/replace word #"es$" "")
    (re-find #"[^su]s$" word) (subs word 0 (dec (count word)))
    :else word))

(defn- statement-text
  "Text from `i` to the end of the line, following lines that end in `,`."
  [text i]
  (loop [j i]
    (let [eol (or (str/index-of text \newline j) (count text))
          line (str/trimr (subs text j eol))]
      (if (and (str/ends-with? line ",") (< eol (count text)))
        (recur (inc eol))
        (subs text i eol)))))

(defn- association-target [macro assoc-name stmt]
  (when-not (re-find #"polymorphic:\s*true" stmt)
    (if-let [[_ cls] (re-find #"class_name:\s*[\"']([\w:]+)[\"']" stmt)]
      (split-const cls)
      (split-const (camelize (if (#{"has_many" "has_and_belongs_to_many"} macro)
                               (singularize assoc-name)
                               assoc-name))))))

(defn- associations-of [text holes]
  (let [m (re-matcher #"(?m)^[ \t]*(belongs_to|has_one|has_many|has_and_belongs_to_many)[ \t(]+:(\w+)" text)]
    (loop [acc []]
      (if (.find m)
        (recur (if (in-hole? holes (.start m))
                 acc
                 (if-let [t (association-target (.group m 1) (.group m 2)
                                                (statement-text text (.start m)))]
                   (conj acc t)
                   acc)))
        acc))))

(defn- indent-of [line]
  (count (re-find #"^[ \t]*" line)))

(defn- drop-deeper [m k]
  (into {} (remove (fn [[indent _]] (> indent k)) m)))

(defn- def-op [line self?]
  (when-let [[_ self-dot name] (re-find #"^\s*def\s+(self\.)?([A-Za-z_]\w*[?!=]?)" line)]
    (when-not (= "initialize" name)
      {:name name :text (if (or self-dot self?) (str "self." name) name)})))

(defn- ops-step [{:keys [vis selves ops]} line]
  (let [k (indent-of line)
        vis (drop-deeper vis k)
        selves (set (remove #(>= % k) selves))
        trimmed (str/trim line)]
    (cond
      (re-matches #"(private|protected|public)" trimmed)
      {:vis (assoc vis k (keyword trimmed)) :selves selves :ops ops}

      (re-find #"^class\s*<<\s*self\b" trimmed)
      {:vis vis :selves (conj selves k) :ops ops}

      (re-find #"^(?:private|protected)\s+def\s" trimmed)
      {:vis vis :selves selves :ops ops}

      :else
      (let [op (when (contains? #{nil :public} (get vis k))
                 (def-op line (some #(< % k) selves)))]
        {:vis vis :selves selves :ops (cond-> ops op (conj op))}))))

(defn ops-of
  "Public methods, in order. A method after a bare `private` or
  `protected` at the same indent is not public. `class << self` and
  `def self.x` are class methods."
  [text]
  (->> (str/split-lines text)
       (remove str/blank?)
       (reduce ops-step {:vis {} :selves #{} :ops []})
       :ops
       (reduce (fn [acc op] (if (some #(= (:name op) (:name %)) acc) acc (conj acc op))) [])))

(defn read-file
  "Surface of Ruby `source`: declarations, constant references, mixins,
  requires, associations, and public methods."
  [source]
  (let [{:keys [text holes]} (mask-source source)]
    {:decls (declarations text holes)
     :refs (refs-of text holes)
     :mixins (mixins-of text holes)
     :requires (requires-of text holes)
     :associations (associations-of text holes)
     :ops (ops-of text)
     :concern? (boolean (re-find #"(?m)^[ \t]*extend[ \t]+(?:::)?ActiveSupport::Concern\b" text))}))

;; Project index and resolution.

(defn- rel-segments [file root]
  (let [rel (str (.relativize (.toPath (.getCanonicalFile (io/file root)))
                              (.toPath (.getCanonicalFile (io/file file)))))]
    (-> rel
        (str/replace #"\\" "/")
        (str/replace #"\.rb$" "")
        (str/split #"/"))))

(defn- under? [file root]
  (str/starts-with? (.getCanonicalPath (io/file file))
                    (str (.getCanonicalPath (io/file root)) java.io.File/separator)))

(defn expected-const
  "Constant segments Zeitwerk expects `file` to define."
  [file roots]
  (let [root (->> roots
                  (filter #(under? file %))
                  (sort-by #(- (count (.getPath ^java.io.File %))))
                  first)]
    (mapv camelize (rel-segments file (or root (.getParentFile (io/file file)))))))

(defn- primary-decl [decls expected]
  (let [want (const-key expected)]
    (or (first (filter #(= want (const-key (:segs %))) decls))
        (first (filter #(= (peek want) (peek (const-key (:segs %)))) decls))
        (first decls))))

(defn- ns-join [ns-prefix relative]
  (cond
    (str/blank? ns-prefix) relative
    (or (= relative ns-prefix)
        (str/starts-with? relative (str ns-prefix "."))) relative
    :else (str ns-prefix "." relative)))

(defn- file-meta [file root roots prefix ns-prefix]
  (let [surface (read-file (slurp file))
        expected (expected-const file roots)
        primary (primary-decl (:decls surface) expected)
        relative (->> (rel-segments file root)
                      (map #(str/replace % "_" "-"))
                      (str/join "."))
        ns-str (ns-join ns-prefix relative)
        id (graph/id-of ns-str prefix)]
    {:file file
     :id id
     :ns ns-str
     :const (or (:segs primary) expected)
     :primary primary
     :surface surface
     :name (if primary (peek (:segs primary)) (graph/module-name id))}))

(defn- const-index
  "Constant key to class id. A file's own constant wins over a constant
  that is only nested inside another file. A module that only wraps a
  file's own constant is a namespace, not that file."
  [metas]
  (let [own (into {} (map (fn [m] [(const-key (:const m)) (:id m)]) metas))
        nested (for [m metas
                     :let [own-key (const-key (:const m))]
                     d (get-in m [:surface :decls])
                     :let [k (const-key (:segs d))]
                     :when (not= k (take (count k) own-key))]
                 [k (:id m)])]
    (reduce (fn [acc [k id]] (if (contains? acc k) acc (assoc acc k id)))
            own nested)))

(defn- prefixes [index]
  (into #{} (for [k (keys index) n (range 1 (inc (count k)))] (subvec k 0 n))))

(defn resolve-const
  "Class id that constant `segs` names from inside `scope`, or nil.
  The first segment resolves lexically, innermost scope first."
  [{:keys [index known]} scope segs top?]
  (let [ref (const-key segs)
        scope (const-key scope)
        scopes (if top? [[]] (map #(subvec scope 0 %) (range (count scope) -1 -1)))]
    (when (seq ref)
      (some (fn [s]
              (when (contains? known (conj s (first ref)))
                (or (some (fn [n] (get index (into s (subvec ref 0 n))))
                          (range (count ref) 0 -1))
                    ::namespace)))
            scopes))))

(defn- resolved [ctx scope segs top?]
  (let [hit (resolve-const ctx scope segs top?)]
    (when (keyword? hit)
      (when-not (= ::namespace hit) hit))))

(defn- found? [ctx scope segs]
  (some? (resolve-const ctx scope segs false)))

(defn- require-target [ctx file path]
  (let [f (.getCanonicalFile (io/file (.getParentFile (io/file file))
                                     (if (str/ends-with? path ".rb") path (str path ".rb"))))]
    (get (:by-file ctx) (.getPath f))))

(defn- require-facts [ctx m]
  (reduce (fn [acc {:keys [kind path]}]
            (if (= :require-relative kind)
              (if-let [id (require-target ctx (:file m) path)]
                (update acc :requires conj id)
                acc)
              (update acc :foreigns conj
                      (keyword (str/replace (str/replace path "/" ".") "_" "-")))))
          {:requires [] :foreigns []}
          (get-in m [:surface :requires])))

(defn- split-found [ctx scope segs-list]
  (reduce (fn [acc segs]
            (if-let [id (resolved ctx scope segs false)]
              (update acc :ids conj id)
              (if (or (found? ctx scope segs) (core-constant? segs))
                acc
                (update acc :foreign conj (foreign-id segs)))))
          {:ids [] :foreign []}
          segs-list))

(defn- analyze [ctx m]
  (let [scope (:const m)
        {:keys [surface primary]} m
        supers (some-> (:super primary) vector)
        inherit (split-found ctx scope supers)
        mixins (split-found ctx scope (:mixins surface))
        refs (keep #(resolved ctx scope (:segs %) (:top? %)) (:refs surface))
        assocs (keep #(resolved ctx scope % false) (:associations surface))
        req (require-facts ctx m)]
    {:inherits (:ids inherit)
     :impls (:ids mixins)
     :requires (vec (distinct (concat refs assocs (:requires req))))
     :foreigns (vec (distinct (concat (:foreign inherit) (:foreign mixins) (:foreigns req))))}))

(defn- stereotype [m implemented]
  (when (and (= :module (get-in m [:primary :kind]))
             (or (get-in m [:surface :concern?])
                 (contains? implemented (:id m))))
    :interface))

(defn- as-class [m implemented]
  (cond-> {:id (:id m)
           :name (:name m)
           :ns (:ns m)
           :lang :ruby
           :file (graph/relative-path (:file m))}
    (seq (get-in m [:surface :ops])) (assoc :ops (get-in m [:surface :ops]))
    (stereotype m implemented) (assoc :stereotype (stereotype m implemented))))

(defn- edges-of [id facts]
  (graph/member-edges (assoc facts :id id)
                      [[:impls :implements]
                       [:inherits :inheritance]
                       [:requires :dependency]
                       [:foreigns :dependency]]))

(defrecord RubyGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "app")
          ns-prefix (or (:ns-prefix opts) prefix)
          rootf (.getCanonicalFile (io/file root))
          roots (autoload-roots rootf)
          metas (mapv #(file-meta % rootf roots prefix ns-prefix) (source-files rootf))
          index (const-index metas)
          ctx {:index index
               :known (prefixes index)
               :by-file (into {} (map (fn [m] [(.getPath ^java.io.File (:file m)) (:id m)]) metas))}
          facts (mapv #(analyze ctx %) metas)
          implemented (set (mapcat :impls facts))
          project-ids (set (map :id metas))
          foreigns (->> (mapcat :foreigns facts)
                        distinct
                        (remove project-ids)
                        (mapv graph/foreign-class))]
      {:classes (into (mapv #(as-class % implemented) metas) foreigns)
       :edges (->> (mapcat (fn [m f] (edges-of (:id m) f)) metas facts)
                   (remove #(= (:from %) (:to %)))
                   distinct
                   vec)})))

(def impl (->RubyGraph))

(graph/register! :ruby impl)
