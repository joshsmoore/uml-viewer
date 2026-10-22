(ns uml-viewer.application.branch-diff
  "Classes this branch changed since it left its base branch."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [uml-viewer.source :as source]))

(defn- run
  "Stdout of `cmd` in `root`, or nil when it fails."
  [root cmd & args]
  (try
    (let [{:keys [exit out]} (apply sh/sh cmd (concat args [:dir root]))]
      (when (zero? exit)
        out))
    (catch Exception _ nil)))

(defn- git-out [root & args]
  (apply run root "git" args))

(defn- git
  "Trimmed stdout of git in `root`, or nil when git fails."
  [root & args]
  (some-> (apply git-out root args) str/trim))

(defn- ref? [root ref]
  (some? (git root "rev-parse" "--verify" "--quiet" ref)))

(defn default-base
  "The remote's default branch (origin/HEAD), else main, else master."
  [root]
  (or (not-empty (git root "symbolic-ref" "--quiet" "--short" "refs/remotes/origin/HEAD"))
      (first (filter #(ref? root %) ["main" "master"]))))

(defn- on-base? [root base]
  (let [branch (git root "rev-parse" "--abbrev-ref" "HEAD")]
    (or (= branch base)
        (= (str "origin/" branch) base))))

(defn parse-name-status
  "Path to :added, :modified, or :deleted from `git diff --name-status`.
  A rename counts as a change to the new path."
  [out]
  (into {}
        (keep (fn [line]
                (let [[status & paths] (str/split line #"\t")]
                  (case (first status)
                    \A [(last paths) :added]
                    \D [(last paths) :deleted]
                    nil nil
                    [(last paths) :modified]))))
        (str/split-lines (or out ""))))

(defn changes
  "`{:base :sha :files}` for HEAD against where it left `base` (nil means
  the default base). Files include uncommitted edits and new files, as
  paths relative to `root`. Nil on the base itself or outside git."
  [root base]
  (when-let [base (or base (default-base root))]
    (when-not (on-base? root base)
      (when-let [fork (git root "merge-base" base "HEAD")]
        (let [tracked (parse-name-status
                        (git root "diff" "--name-status" "--relative" fork))
              untracked (remove str/blank?
                                (str/split-lines
                                  (or (git root "ls-files" "--others" "--exclude-standard") "")))]
          {:base base
           :fork fork
           :sha (subs fork 0 7)
           :files (into tracked (map (fn [p] [p :added]) untracked))})))))

(defn pr-ref
  "Local ref that holds pull request `pr`'s head."
  [pr]
  (str "refs/uml/pr/" pr))

(defn- pr-base
  "The pull request's own base on origin (from gh), else `base`, else the
  default base."
  [root base pr]
  (or (some->> (run root "gh" "pr" "view" (str pr) "--json" "baseRefName" "-q" ".baseRefName")
               str/trim not-empty (str "origin/"))
      base
      (default-base root)))

(defn- fetch-pr!
  "Fetch pull request `pr`'s head into `pr-ref`, and `base` when it is
  on origin."
  [root base pr]
  (apply git root "fetch" "--quiet" "origin" (str "+pull/" pr "/head:" (pr-ref pr))
         (when (str/starts-with? base "origin/")
           [(str "+refs/heads/" (subs base 7) ":refs/remotes/" base)])))

(defn pr-changes
  "`changes` for pull request `pr` without checking it out: its head
  fetched from origin (or the last fetch, offline) against where it left
  its base. Adds `:head` and `:pr`. Nil when the head is unknown."
  [root base pr]
  (when-let [base (pr-base root base pr)]
    (fetch-pr! root base pr)
    (when-let [head (git root "rev-parse" "--verify" "--quiet" (pr-ref pr))]
      (when-let [fork (git root "merge-base" base head)]
        {:base base
         :fork fork
         :sha (subs fork 0 7)
         :head head
         :pr pr
         :files (parse-name-status
                  (git root "diff" "--name-status" "--relative" fork head))}))))

(defn head-text
  "`path` as compared: at the pull request head, else in the working tree."
  [root changes path]
  (if-let [head (:head changes)]
    (git-out root "show" (str head ":./" path))
    (let [f (io/file root path)] (when (.isFile f) (slurp f)))))

(defn- hunks-out [root changes path]
  (apply git root "diff" "-U0"
         (concat [(:fork changes)] (when (:head changes) [(:head changes)]) ["--" path])))

(defn parse-diff
  "Hunks of `git diff -U0`: where each starts in the new file, how many
  new lines it has, and the old lines it removed."
  [out]
  (reduce (fn [hunks line]
            (if-let [[_ start n] (re-find #"^@@ -\S+ \+(\d+)(?:,(\d+))? @@" line)]
              (conj hunks {:start (parse-long start)
                           :count (if n (parse-long n) 1)
                           :removed []})
              (if (and (seq hunks)
                       (str/starts-with? line "-")
                       (not (str/starts-with? line "---")))
                (update-in hunks [(dec (count hunks)) :removed] conj (subs line 1))
                hunks)))
          []
          (str/split-lines (or out ""))))

(defn parse-hunks
  "New-file line numbers that `git diff -U0` hunks touch. A pure deletion
  marks the line it follows."
  [out]
  (into #{}
        (mapcat (fn [{:keys [start count]}]
                  (if (zero? count)
                    [(max 1 start)]
                    (range start (+ start count)))))
        (parse-diff out)))

(defn file-diff
  "Diff of `path` since the branch left its base: `{:added-file true}`
  for a new file, `{:deleted-file true :text base}` for a removed one,
  `{:hunks [...]}` for a changed one, nil otherwise."
  [root changes path]
  (case (get (:files changes) path)
    :added {:added-file true}
    :deleted {:deleted-file true
              :text (git-out root "show" (str (:fork changes) ":./" path))}
    :modified {:hunks (parse-diff (hunks-out root changes path))}
    nil))

(defn- indent [line]
  (count (re-find #"^[ \t]*" line)))

(defn- closer? [line]
  (boolean (re-matches #"\s*(end\b.*|[}\])]+[;,]?\s*)" line)))

(defn member-end
  "Last line of the member starting at 1-based line `start` of `text`:
  the line before the first later non-blank line indented no deeper,
  or that line itself when it closes the member (`end`, `}`). Never
  past `limit`."
  [text start limit]
  (let [src (str/split-lines text)
        own (indent (nth src (dec start) ""))
        stop (some (fn [n]
                     (let [line (nth src (dec n))]
                       (when (and (not (str/blank? line)) (<= (indent line) own))
                         (if (closer? line) n (dec n)))))
                   (range (inc start) (inc (count src))))]
    (min limit (or stop (count src)))))

(defn changed-ops
  "Op name to :added or :modified. An op absent from `base-text` is new;
  one whose span holds a changed line is modified. A span runs from the
  op's start line to its `member-end`, and never into the next op.
  Language-neutral: uses the extractor registered for `lang`, and marks
  nothing without one."
  [lang base-text text lines ops]
  (if-let [impl (source/lookup lang)]
    (let [starts (->> ops
                      (keep (fn [op]
                              (when-let [n (source/start-line impl text op)]
                                [(:name op) n])))
                      (sort-by second)
                      vec)
          limits (conj (mapv (comp dec second) (rest starts)) Long/MAX_VALUE)
          ends (mapv (fn [[_ start] limit] (member-end text start limit)) starts limits)]
      (into {}
            (keep (fn [[[nm start] end]]
                    (cond
                      (nil? (source/start-line impl base-text {:name nm})) [nm :added]
                      (some #(<= start % end) lines) [nm :modified]
                      :else nil)))
            (map vector starts ends)))
    {}))

(defn- mark-ops [c marks]
  (cond-> c
    (seq marks) (update :ops (fn [ops]
                               (mapv #(if-let [k (get marks (:name %))]
                                        (assoc % :changed k)
                                        %)
                                     ops)))))

(defn- op-marks
  "Changed ops of class `c` against the fork, read from git in `root`."
  [c changes root]
  (if (= :added (:changed c))
    (into {} (map (fn [op] [(:name op) :added])) (:ops c))
    (let [path (:file c)
          text (head-text root changes path)
          base-text (git-out root "show" (str (:fork changes) ":./" path))]
      (if (and text base-text)
        (changed-ops (or (:lang c) :clojure) base-text text
                     (parse-hunks (hunks-out root changes path))
                     (:ops c))
        {}))))

(defn member-diff
  "Source-window `:diff` for a card member: its file's diff and the base
  name, or nil when the file did not change on this branch. Reviewing a
  pull request, every file also carries `:text`, its content at the head."
  [root changes path]
  (let [d (file-diff root changes path)
        pr-text (when (and (:head changes) (not (:deleted-file d)))
                  (head-text root changes path))]
    (cond
      d (cond-> (assoc d :base (:base changes)) pr-text (assoc :text pr-text))
      pr-text {:hunks [] :base (:base changes) :text pr-text}
      :else nil)))

(defn mark-classes
  "Each class whose `:file` changed gets `:changed` :added or :modified."
  [classes files]
  (mapv (fn [c]
          (if-let [k (#{:added :modified} (get files (:file c)))]
            (assoc c :changed k)
            c))
        classes))

(defn mark-doc
  "Mark changed classes, and their changed ops, on a hierarchical IR
  document for the project at `root`."
  [doc changes root]
  (if (and changes (:hierarchical doc))
    (update doc :classes
            (fn [cs]
              (mapv #(if (:changed %) (mark-ops % (op-marks % changes root)) %)
                    (mark-classes cs (:files changes)))))
    doc))

(defn- deleted [m]
  (assoc m :changed :deleted))

(defn- deleted-class [c]
  (cond-> (deleted c)
    (:ops c) (update :ops #(mapv deleted %))))

(defn- with-deleted-ops
  "Changed class `c` plus the ops its base version had and it lacks."
  [c base-c]
  (let [names (set (map :name (:ops c)))
        gone (remove #(names (:name %)) (:ops base-c))]
    (cond-> c
      (and (:changed c) (seq gone)) (update :ops (fnil into []) (map deleted gone)))))

(defn mark-deleted
  "Add to `doc` what `base` had and the branch removed: classes and ops
  marked `:changed :deleted`, and base edges that touch a deleted class."
  [doc base]
  (if (seq (:classes base))
    (let [base-by-id (into {} (map (juxt :id identity)) (:classes base))
          current (set (map :id (:classes doc)))
          gone (into [] (comp (remove :foreign) (remove #(current (:id %))) (map deleted-class))
                     (:classes base))
          gone-ids (set (map :id gone))
          shown (into current gone-ids)
          known-edges (set (:edges doc))
          edges (filter #(and (or (gone-ids (:from %)) (gone-ids (:to %)))
                              (shown (:from %)) (shown (:to %))
                              (not (known-edges %)))
                        (:edges base))]
      (-> doc
          (update :classes #(-> (mapv (fn [c] (with-deleted-ops c (base-by-id (:id c)))) %)
                                (into gone)))
          (update :edges (fnil into []) edges)))
    doc))

(defn changed-only
  "`doc` keeping only classes this branch changed, foreign libs, and the
  edges between what is left."
  [doc]
  (let [classes (filterv #(or (:changed %) (:foreign %)) (:classes doc))
        ids (set (map :id classes))]
    (assoc doc
           :classes classes
           :edges (filterv #(and (ids (:from %)) (ids (:to %))) (:edges doc)))))

(defn banner
  "One line naming the base and how many `classes` changed, or nil."
  [changes classes]
  (when changes
    (let [n (count (filter :changed classes))]
      (str (when (:pr changes) (str "PR #" (:pr changes) " "))
           "vs " (:base changes) " @ " (:sha changes) " · "
           n (if (= 1 n) " class" " classes") " changed"))))

(defn git-dir
  "Absolute git directory for `root`, or nil outside git."
  [root]
  (not-empty (git root "rev-parse" "--absolute-git-dir")))

(defn git-stamp
  "Changes when HEAD moves or the index is rewritten: a checkout, commit,
  or stage. Nil without a git directory."
  [dir]
  (when dir
    [(.lastModified (io/file dir "HEAD"))
     (.lastModified (io/file dir "index"))]))
