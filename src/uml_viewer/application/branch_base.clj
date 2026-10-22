(ns uml-viewer.application.branch-base
  "Any commit scanned with the diagram's own policy, cached per commit:
  the fork point shows what a branch deleted, and a pull request's head
  is the diagram itself when reviewing without a checkout."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [uml-viewer.application.ir-generator :as ir-generator]
            [uml-viewer.domain.log :as log]
            [uml-viewer.graph :as graph]))

(defn source-roots
  "Paths `policy` scans, relative to the project root."
  [policy]
  (if (seq (:sources policy))
    (mapv #(or (:root %) (:src %) (:src policy) "src") (:sources policy))
    [(or (:src policy) "src")]))

(defn rebase-policy
  "`policy` with every scanned root moved under `dir`."
  [policy dir]
  (let [under #(str dir "/" %)]
    (if (seq (:sources policy))
      (update policy :sources
              (fn [ss] (mapv #(assoc % :root (under (or (:root %) (:src %) (:src policy) "src"))) ss)))
      (assoc policy :src (under (or (:src policy) "src"))))))

(defn- unprefix [path prefixes]
  (or (some #(when (str/starts-with? path %) (subs path (count %))) prefixes)
      path))

(defn strip-dir
  "Class `:file` paths made relative again after scanning under `dir`."
  [doc dir]
  (let [prefixes [(str (.getCanonicalPath (io/file dir)) "/")
                  (str (graph/relative-path dir) "/")]]
    (update doc :classes
            (fn [cs]
              (mapv #(cond-> % (:file %) (update :file unprefix prefixes)) cs)))))

(defn- git-ok? [root & args]
  (try
    (zero? (:exit (apply sh/sh "git" (concat args [:dir root]))))
    (catch Exception _ false)))

(defn- delete-tree! [f]
  (when (.isDirectory f)
    (run! delete-tree! (.listFiles f)))
  (.delete f))

(defn tree-path
  "`path` as git names it inside a commit's tree: `.` is the root."
  [path]
  (let [p (str/replace (str path) #"^\./|/$" "")]
    (if (= "." p) "" p)))

(defn- checkout-roots!
  "Write the `roots` that exist at `commit` into `dir`."
  [root commit roots dir]
  (let [present (filterv #(git-ok? root "cat-file" "-e" (str commit ":" (tree-path %))) roots)
        tar (io/file (.getParentFile dir) (str (.getName dir) ".tar"))]
    (.mkdirs dir)
    (try
      (when (and (seq present)
                 (apply git-ok? root "archive" "-o" (.getCanonicalPath tar) commit "--" present))
        (zero? (:exit (sh/sh "tar" "-xf" (.getCanonicalPath tar) "-C" (.getCanonicalPath dir)))))
      (finally
        (.delete tar)))))

(defn- scan-commit [root policy commit]
  (let [dir (io/file root ".uml-viewer" "base" commit)]
    (try
      (when (checkout-roots! root commit (source-roots policy) dir)
        (let [canon (.getCanonicalPath dir)
              rebased (rebase-policy policy canon)
              impl (graph/lookup (or (:lang policy) :clojure))]
          (strip-dir (ir-generator/document impl rebased) canon)))
      (finally
        (delete-tree! dir)))))

(defn- policy-langs [policy]
  (distinct (map #(keyword (or (:lang %) (:lang policy) :clojure))
                 (or (seq (:sources policy)) [policy]))))

(defn- ns-source [ns-sym]
  (some-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/") (str ".clj") io/resource slurp))

(defn- scanner-source
  "Source of every loaded namespace in the package of `lang`'s scanner."
  [lang]
  (when-let [cls (some-> (graph/lookup lang) class .getName)]
    (let [pkg (-> cls (str/replace #"\.[^.]+$" "") (str/replace #"\.[^.]+$" "") (str/replace "_" "-"))]
      (->> (all-ns)
           (map ns-name)
           (filter #(str/starts-with? (str %) (str pkg ".")))
           sort
           (mapv ns-source)))))

(defn- cache-stamp [policy]
  (format "%08x" (hash [:ir policy (mapv scanner-source (policy-langs policy))])))

(defn cache-name
  "Cache file name for `commit` scanned with `policy`. Changes when the
  policy or the source of its language scanners changes."
  [policy commit]
  (str commit "-" (cache-stamp policy) ".edn"))

(def ^:private kept-caches 8)

(defn- write-cache!
  "Write `doc` to `cache`; drop caches from another policy or scanner and
  all but the newest few."
  [cache doc stamp]
  (spit cache (pr-str doc))
  (let [files (->> (.listFiles (.getParentFile cache))
                   (filter #(.isFile %))
                   (sort-by #(- (.lastModified %))))
        stale (filter #(not (str/ends-with? (.getName %) (str "-" stamp ".edn"))) files)
        extra (drop kept-caches (remove (set stale) files))]
    (run! #(.delete %) (concat stale extra))))

(defn commit-doc
  "IR document of the project at `root` as of `commit`, scanned with the
  policy at `policy-path`. Cached under `.uml-viewer/base` per commit,
  policy, and scanner source. Nil when the policy or commit cannot be
  read."
  [root policy-path commit]
  (when (and commit policy-path (.isFile (io/file root policy-path)))
    (let [policy (edn/read-string (slurp (io/file root policy-path)))
          cache (io/file root ".uml-viewer" "base" (cache-name policy commit))]
      (if (.isFile cache)
        (edn/read-string (slurp cache))
        (try
          (when-let [doc (scan-commit root policy commit)]
            (io/make-parents cache)
            (write-cache! cache doc (cache-stamp policy))
            doc)
          (catch Exception e
            (log/log-exception! e (str "scan " commit))
            nil))))))

(defn base-doc
  "Classes and edges of the project at `root` as of commit `fork`."
  [root policy-path fork]
  (some-> (commit-doc root policy-path fork) (select-keys [:classes :edges])))
