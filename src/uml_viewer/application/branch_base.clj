(ns uml-viewer.application.branch-base
  "The branch's fork point scanned with the diagram's own policy, cached
  per fork commit, so classes and methods the branch deleted can be shown."
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

(defn- checkout-roots!
  "Write the `roots` that exist at commit `fork` into `dir`."
  [root fork roots dir]
  (let [present (filterv #(git-ok? root "cat-file" "-e" (str fork ":" %)) roots)
        tar (io/file dir "base.tar")]
    (.mkdirs dir)
    (when (and (seq present)
               (apply git-ok? root "archive" "-o" (.getCanonicalPath tar) fork "--" present))
      (zero? (:exit (sh/sh "tar" "-xf" (.getCanonicalPath tar) "-C" (.getCanonicalPath dir)))))))

(defn- scan-fork [root policy fork]
  (let [dir (io/file root ".uml-viewer" "base" fork)]
    (try
      (when (checkout-roots! root fork (source-roots policy) dir)
        (let [canon (.getCanonicalPath dir)
              rebased (rebase-policy policy canon)
              impl (graph/lookup (or (:lang policy) :clojure))
              doc (ir-generator/document impl rebased)]
          (select-keys (strip-dir doc canon) [:classes :edges])))
      (finally
        (delete-tree! dir)))))

(defn- policy-langs [policy]
  (distinct (map #(keyword (or (:lang %) (:lang policy) :clojure))
                 (or (seq (:sources policy)) [policy]))))

(defn- scanner-source [lang]
  (some-> (graph/lookup lang) class .getName
          (str/replace #"\.[^.]+$" "")
          (str/replace "." "/")
          (str ".clj")
          io/resource
          slurp))

(defn cache-name
  "Cache file name for `fork` scanned with `policy`. Changes when the
  policy or the source of its language scanners changes."
  [policy fork]
  (format "%s-%08x.edn" fork
          (hash [policy (mapv scanner-source (policy-langs policy))])))

(defn- replace-cache! [cache doc]
  (run! #(.delete %) (.listFiles (.getParentFile cache)))
  (spit cache (pr-str doc)))

(defn base-doc
  "Classes and edges of the project at `root` as of commit `fork`, scanned
  with the policy at `policy-path`. Cached under `.uml-viewer/base` per
  fork, policy, and scanner source. Nil when the policy or fork cannot
  be read."
  [root policy-path fork]
  (when (and fork policy-path (.isFile (io/file root policy-path)))
    (let [policy (edn/read-string (slurp (io/file root policy-path)))
          cache (io/file root ".uml-viewer" "base" (cache-name policy fork))]
      (if (.isFile cache)
        (edn/read-string (slurp cache))
        (try
          (when-let [doc (scan-fork root policy fork)]
            (io/make-parents cache)
            (replace-cache! cache doc)
            doc)
          (catch Exception e
            (log/log-exception! e (str "scan base " fork))
            nil))))))
