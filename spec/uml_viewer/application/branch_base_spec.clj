(ns uml-viewer.application.branch-base-spec
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [speclj.core :refer :all]
            [uml-viewer.application.branch-base :as bb]
            [uml-viewer.ruby-language.graph-ruby]))

(defn- git! [dir & args]
  (let [r (apply sh/sh "git" (concat args [:dir (str dir)]))]
    (when-not (zero? (:exit r))
      (throw (ex-info (:err r) {:args args})))
    (:out r)))

(defn- write! [dir rel text]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f text)))

(defn- project-with-deletion
  "Ruby project on `feature` that deleted app/models/b.rb; returns
  the root and the fork commit."
  []
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                           (str "uml-base-" (System/nanoTime)))
              (.mkdirs))]
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "spec@example.com")
    (git! dir "config" "user.name" "Spec")
    (write! dir "app/models/a.rb" "class A\n  def go; end\nend\n")
    (write! dir "app/models/b.rb" "class B\n  def call; end\nend\n")
    (write! dir "uml.policy.edn"
            (pr-str {:hierarchical true :lang :ruby :src "app" :prefix "app"}))
    (git! dir "add" ".")
    (git! dir "commit" "-q" "-m" "base")
    (let [fork (.trim (git! dir "rev-parse" "HEAD"))]
      (git! dir "checkout" "-q" "-b" "feature")
      (git! dir "rm" "-q" "app/models/b.rb")
      [dir fork])))

(describe "source roots"
  (it "reads each source root, else the policy src"
    (should= ["app" "lib"] (bb/source-roots {:sources [{:root "app"} {:root "lib"}]}))
    (should= ["app"] (bb/source-roots {:src "app"}))
    (should= ["src"] (bb/source-roots {})))

  (it "moves every root under a directory"
    (should= {:sources [{:root "/t/app"} {:root "/t/lib" :prefix "x"}]}
             (bb/rebase-policy {:sources [{:root "app"} {:root "lib" :prefix "x"}]} "/t"))
    (should= {:src "/t/src"} (bb/rebase-policy {} "/t")))

  (it "makes scanned files relative to the project again"
    (let [dir (io/file (System/getProperty "java.io.tmpdir") "uml-strip")
          abs (str (.getCanonicalPath dir) "/app/a.rb")]
      (should= [{:file "app/a.rb"} {:file "lib/x.rb"} {}]
               (:classes (bb/strip-dir {:classes [{:file abs} {:file "lib/x.rb"} {}]} dir))))))

(describe "tree paths"
  (it "names the project root and trims a leading ./ or trailing /"
    (should= "" (bb/tree-path "."))
    (should= "" (bb/tree-path "./"))
    (should= "app" (bb/tree-path "./app"))
    (should= "app" (bb/tree-path "app/"))
    (should= "lib/x" (bb/tree-path "lib/x"))))

(describe "base document"
  (it "scans the fork with the policy and caches it"
    (let [[dir fork] (project-with-deletion)
          doc (bb/base-doc (str dir) "uml.policy.edn" fork)
          b (first (filter #(= "app/models/b.rb" (:file %)) (:classes doc)))]
      (should= ["call"] (mapv :name (:ops b)))
      (should= [(bb/cache-name (read-string (slurp (io/file dir "uml.policy.edn"))) fork)]
               (mapv #(.getName %) (.listFiles (io/file dir ".uml-viewer" "base"))))
      (should-not (.exists (io/file dir ".uml-viewer" "base" fork)))
      (should= doc (bb/base-doc (str dir) "uml.policy.edn" fork))))

  (it "rescans when the policy changes and drops the old cache"
    (let [[dir fork] (project-with-deletion)
          policy-file (io/file dir "uml.policy.edn")
          ids #(set (map :id (:classes (bb/base-doc (str dir) "uml.policy.edn" fork))))]
      (should-contain :models.b (ids))
      (spit policy-file (pr-str {:hierarchical true :lang :ruby :src "app/models" :prefix "app"}))
      (should-contain :b (ids))
      (should= 1 (count (.listFiles (io/file dir ".uml-viewer" "base"))))))

  (it "names the cache by fork, policy, and scanner"
    (let [policy {:lang :ruby :src "app"}]
      (should (.startsWith (bb/cache-name policy "abc") "abc-"))
      (should= (bb/cache-name policy "abc") (bb/cache-name policy "abc"))
      (should-not= (bb/cache-name policy "abc") (bb/cache-name (assoc policy :prefix "x") "abc"))
      (should-not= (bb/cache-name policy "abc") (bb/cache-name (assoc policy :lang :python) "abc"))))

  (it "returns the whole diagram for a commit and keeps caches for several"
    (let [[dir fork] (project-with-deletion)
          _ (git! dir "commit" "-q" "-m" "drop b")
          head (.trim (git! dir "rev-parse" "HEAD"))
          doc (bb/commit-doc (str dir) "uml.policy.edn" head)]
      (should (:hierarchical doc))
      (should= ["app/models/a.rb"] (keep :file (:classes doc)))
      (bb/commit-doc (str dir) "uml.policy.edn" fork)
      (should= 2 (count (.listFiles (io/file dir ".uml-viewer" "base"))))))

  (it "scans a policy rooted at the project itself"
    (let [[dir fork] (project-with-deletion)
          _ (spit (io/file dir "uml.policy.edn")
                  (pr-str {:hierarchical true :lang :ruby :src "." :prefix "app"}))
          doc (bb/base-doc (str dir) "uml.policy.edn" fork)]
      (should= #{"app/models/a.rb" "app/models/b.rb"} (set (keep :file (:classes doc))))
      (should= 1 (count (.listFiles (io/file dir ".uml-viewer" "base"))))))

  (it "is nil without a policy or fork"
    (let [[dir fork] (project-with-deletion)]
      (should-be-nil (bb/base-doc (str dir) "missing.edn" fork))
      (should-be-nil (bb/base-doc (str dir) nil fork))
      (should-be-nil (bb/base-doc (str dir) "uml.policy.edn" nil)))))
