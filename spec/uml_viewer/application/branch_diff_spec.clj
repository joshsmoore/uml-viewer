(ns uml-viewer.application.branch-diff-spec
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [speclj.core :refer :all]
            [uml-viewer.application.branch-diff :as bd]
            [uml-viewer.ruby-language.source-ruby]))

(defn- temp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "uml-branch-" (System/nanoTime)))
    (.mkdirs)))

(defn- git! [dir & args]
  (let [r (apply sh/sh "git" (concat args [:dir (str dir)]))]
    (when-not (zero? (:exit r))
      (throw (ex-info (:err r) {:args args})))
    (:out r)))

(defn- write! [dir rel text]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f text)))

(defn- repo-on-main []
  (let [dir (temp-dir)]
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "spec@example.com")
    (git! dir "config" "user.name" "Spec")
    (write! dir "app/a.rb" "class A\nend\n")
    (write! dir "app/b.rb" "class B\nend\n")
    (git! dir "add" ".")
    (git! dir "commit" "-q" "-m" "base")
    dir))

(describe "branch file changes"
  (it "reads added, modified, renamed, and deleted paths"
    (should= {"app/a.rb" :modified
              "app/new.rb" :added
              "app/moved.rb" :modified
              "app/gone.rb" :deleted}
             (bd/parse-name-status
               "M\tapp/a.rb\nA\tapp/new.rb\nR087\tapp/old.rb\tapp/moved.rb\nD\tapp/gone.rb\n")))

  (it "reads nothing from blank output"
    (should= {} (bd/parse-name-status ""))
    (should= {} (bd/parse-name-status nil))))

(describe "changed lines"
  (it "reads the new-file lines each hunk touches"
    (should= #{3 4 5 9 12}
             (bd/parse-hunks (str "diff --git a/x b/x\n"
                                  "@@ -3,1 +3,3 @@\n+a\n+b\n+c\n"
                                  "@@ -10 +9 @@\n-x\n+y\n"
                                  "@@ -14,2 +12,0 @@\n-gone\n-gone\n"))))

  (it "reads nothing from no diff"
    (should= #{} (bd/parse-hunks nil))))

(describe "file diff"
  (it "reads each hunk's new lines and the old lines it removed"
    (should= [{:start 3 :count 2 :removed ["old a"]}
              {:start 9 :count 0 :removed ["gone 1" "gone 2"]}]
             (bd/parse-diff (str "diff --git a/x b/x\n--- a/x\n+++ b/x\n"
                                 "@@ -3 +3,2 @@\n-old a\n+new a\n+new b\n"
                                 "@@ -10,2 +9,0 @@\n-gone 1\n-gone 2\n")))
    (should= [] (bd/parse-diff nil)))

  (it "diffs a changed file against where the branch left its base"
    (let [dir (repo-on-main)]
      (git! dir "checkout" "-q" "-b" "feature")
      (write! dir "app/a.rb" "class A\n  def x; end\nend\n")
      (write! dir "app/c.rb" "class C\nend\n")
      (let [ch (bd/changes (str dir) "main")]
        (should= {:hunks [{:start 2 :count 1 :removed []}]}
                 (bd/file-diff (str dir) ch "app/a.rb"))
        (should= {:added-file true} (bd/file-diff (str dir) ch "app/c.rb"))
        (should-be-nil (bd/file-diff (str dir) ch "app/b.rb"))
        (should-be-nil (bd/file-diff (str dir) nil "app/a.rb"))
        (should= {:added-file true :base "main"} (bd/member-diff (str dir) ch "app/c.rb"))
        (should-be-nil (bd/member-diff (str dir) ch "app/b.rb")))))

  (it "returns the base text of a deleted file"
    (let [dir (repo-on-main)]
      (git! dir "checkout" "-q" "-b" "feature")
      (.delete (io/file dir "app/b.rb"))
      (let [ch (bd/changes (str dir) "main")]
        (should= :deleted (get (:files ch) "app/b.rb"))
        (should= {:deleted-file true :text "class B\nend\n"}
                 (bd/file-diff (str dir) ch "app/b.rb"))))))

(def ^:private base-ruby "class A\n  def keep\n    1\n  end\n\n  def edit\n    2\n  end\nend\n")

(def ^:private now-ruby
  "class A\n  def keep\n    1\n  end\n\n  def edit\n    3\n  end\n\n  def fresh\n  end\nend\n")

(def ^:private ruby-ops [{:name "keep"} {:name "edit"} {:name "fresh"}])

(describe "changed methods"
  (it "marks a method whose lines changed and one the base lacks"
    (should= {"edit" :modified "fresh" :added}
             (bd/changed-ops :ruby base-ruby now-ruby #{7 9 10 11} ruby-ops)))

  (it "marks nothing without an extractor for the language"
    (should= {} (bd/changed-ops :cobol base-ruby now-ruby #{7} ruby-ops)))

  (it "does not stretch the last method over code after it"
    (let [text "class A\n  def last\n    1\n  end\n\n  get :x do\n    2\n  end\nend\n"]
      (should= {} (bd/changed-ops :ruby text text #{7} [{:name "last"}]))
      (should= {"last" :modified} (bd/changed-ops :ruby text text #{3} [{:name "last"}])))))

(describe "changed endpoints"
  (it "marks the Grape endpoint whose block changed"
    (let [base "class Api < Grape::API\n  def helper\n  end\n  namespace :auth do\n    get :callback do\n      1\n    end\n  end\nend\n"
          now "class Api < Grape::API\n  def helper\n  end\n  namespace :auth do\n    get :callback do\n      2\n    end\n  end\nend\n"]
      (should= {"GET auth/callback" :modified}
               (bd/changed-ops :ruby base now #{6}
                               [{:name "helper"} {:name "GET auth/callback"}])))))

(describe "member end"
  (it "ends a Ruby method at its own end"
    (should= 4 (bd/member-end "class A\n  def x\n    1\n  end\n  def y; end\nend\n" 2 99)))

  (it "ends a one-line method on its own line"
    (should= 2 (bd/member-end "class A\n  def x; end\n  def y; end\nend\n" 2 99)))

  (it "ends a Python function before the next dedented line"
    (should= 3 (bd/member-end "def f():\n    return 1\n\nx = 2\n" 1 99)))

  (it "ends a brace body at its closing brace"
    (should= 3 (bd/member-end "function f() {\n  return 1;\n}\nconst x = 2;\n" 1 99)))

  (it "runs to the end of the file, never past the limit"
    (should= 3 (bd/member-end "(defn f []\n  1\n  2)\n" 1 99))
    (should= 2 (bd/member-end "(defn f []\n  1\n  2)\n" 1 2))))

(describe "marking classes"
  (it "marks a class whose file changed"
    (should= [{:id :a :file "app/a.rb" :changed :modified}
              {:id :b :file "app/b.rb"}
              {:id :c :file "app/c.rb" :changed :added}
              {:id :lib}]
             (bd/mark-classes [{:id :a :file "app/a.rb"}
                               {:id :b :file "app/b.rb"}
                               {:id :c :file "app/c.rb"}
                               {:id :lib}]
                              {"app/a.rb" :modified "app/c.rb" :added})))

  (it "marks a hierarchical document and leaves others alone"
    (let [doc {:hierarchical true :classes [{:id :a :file "app/a.rb"
                                             :ops [{:name "x"}]}]}
          marked (bd/mark-doc doc {:files {"app/a.rb" :added}} ".")]
      (should= :added (get-in marked [:classes 0 :changed]))
      (should= :added (get-in marked [:classes 0 :ops 0 :changed]))
      (should= doc (bd/mark-doc doc nil "."))
      (should= {:diagrams []} (bd/mark-doc {:diagrams []} {:files {"x" :added}} "."))))

  (it "summarizes the comparison for the banner"
    (should= "vs main @ abc1234 · 2 classes changed"
             (bd/banner {:base "main" :sha "abc1234"}
                        [{:changed :added} {:id :x} {:changed :modified}]))
    (should= "vs main @ abc1234 · 1 class changed"
             (bd/banner {:base "main" :sha "abc1234"} [{:changed :added}]))
    (should-be-nil (bd/banner nil [{:changed :added}]))))

(describe "comparing a git branch"
  (it "lists nothing on the base branch itself"
    (let [dir (repo-on-main)]
      (write! dir "app/a.rb" "class A\n  def x; end\nend\n")
      (should-be-nil (bd/changes (str dir) "main"))))

  (it "lists commits, edits, and new files since the branch left its base"
    (let [dir (repo-on-main)]
      (git! dir "checkout" "-q" "-b" "feature")
      (write! dir "app/a.rb" "class A\n  def x; end\nend\n")
      (git! dir "commit" "-q" "-am" "edit a")
      (write! dir "app/b.rb" "class B\n  def y; end\nend\n")
      (write! dir "app/c.rb" "class C\nend\n")
      (let [ch (bd/changes (str dir) "main")]
        (should= "main" (:base ch))
        (should= 7 (count (:sha ch)))
        (should= {"app/a.rb" :modified
                  "app/b.rb" :modified
                  "app/c.rb" :added}
                 (:files ch)))))

  (it "marks the methods a branch edited or added"
    (let [dir (repo-on-main)]
      (write! dir "app/a.rb" "class A\n  def keep\n    1\n  end\n\n  def edit\n    2\n  end\nend\n")
      (git! dir "commit" "-q" "-am" "methods")
      (git! dir "checkout" "-q" "-b" "feature")
      (write! dir "app/a.rb" "class A\n  def keep\n    1\n  end\n\n  def edit\n    3\n  end\n\n  def fresh\n  end\nend\n")
      (let [doc {:hierarchical true
                 :classes [{:id :a :lang :ruby :file "app/a.rb"
                            :ops [{:name "keep"} {:name "edit"} {:name "fresh"}]}]}
            ops (get-in (bd/mark-doc doc (bd/changes (str dir) "main") (str dir))
                        [:classes 0 :ops])]
        (should= [nil :modified :added] (map :changed ops)))))

  (it "ignores work that landed on the base after the branch left it"
    (let [dir (repo-on-main)]
      (git! dir "checkout" "-q" "-b" "feature")
      (git! dir "checkout" "-q" "main")
      (write! dir "app/b.rb" "class B\n  def later; end\nend\n")
      (git! dir "commit" "-q" "-am" "later on main")
      (git! dir "checkout" "-q" "feature")
      (should= {} (:files (bd/changes (str dir) "main")))))

  (it "falls back to main when no base is configured and no remote names one"
    (let [dir (repo-on-main)]
      (git! dir "checkout" "-q" "-b" "feature")
      (should= "main" (bd/default-base (str dir)))
      (should= "main" (:base (bd/changes (str dir) nil)))))

  (it "lists nothing outside a git repository"
    (should-be-nil (bd/changes (str (temp-dir)) nil)))

  (it "stamps the repository so a commit or checkout is noticed"
    (let [dir (repo-on-main)
          gd (bd/git-dir (str dir))
          before (bd/git-stamp gd)]
      (should-not-be-nil gd)
      (Thread/sleep 1100)
      (git! dir "checkout" "-q" "-b" "feature")
      (should-not= before (bd/git-stamp gd))
      (should-be-nil (bd/git-stamp nil))))

  (it "keeps only changed classes, foreign libs, and edges between them"
    (let [doc {:classes [{:id :app.a :changed :modified}
                         {:id :app.b}
                         {:id :app.c :changed :added}
                         {:id :quil :foreign true}]
               :edges [{:from :app.a :to :app.c}
                       {:from :app.a :to :app.b}
                       {:from :app.c :to :quil}]}
          only (bd/changed-only doc)]
      (should= [:app.a :app.c :quil] (mapv :id (:classes only)))
      (should= [{:from :app.a :to :app.c} {:from :app.c :to :quil}] (:edges only))))

  (it "keeps the unchanged far end of a changed edge as context"
    (let [doc {:classes [{:id :a :changed :modified}
                         {:id :target}
                         {:id :gone-target}
                         {:id :bystander}
                         {:id :other}]
               :edges [{:from :a :to :target :changed :added}
                       {:from :a :to :gone-target :changed :deleted}
                       {:from :target :to :gone-target}
                       {:from :bystander :to :other}
                       {:from :a :to :other}]}
          only (bd/changed-only doc)]
      (should= [{:id :a :changed :modified}
                {:id :target :context true}
                {:id :gone-target :context true}]
               (:classes only))
      (should= [{:from :a :to :target :changed :added}
                {:from :a :to :gone-target :changed :deleted}]
               (:edges only)))))

(def ^:private current-doc
  {:classes [{:id :a :changed :modified :ops [{:name "keep"}]}
             {:id :c :ops [{:name "same"}]}]
   :edges [{:from :a :to :c}]})

(def ^:private base-doc
  {:classes [{:id :a :ops [{:name "keep"} {:name "gone"}]}
             {:id :b :file "app/b.rb" :ops [{:name "call"}]}
             {:id :c :ops [{:name "same"} {:name "moved"}]}
             {:id :quil :foreign true}]
   :edges [{:from :a :to :c} {:from :a :to :b} {:from :b :to :quil}]})

(defn- marked-by-id []
  (into {} (map (juxt :id identity)) (:classes (bd/mark-deleted current-doc base-doc))))

(describe "deleted classes and methods"
  (it "adds a deleted class with its methods marked deleted"
    (should= :deleted (:changed ((marked-by-id) :b)))
    (should= [{:name "call" :changed :deleted}] (:ops ((marked-by-id) :b)))
    (should-not-contain :quil (marked-by-id)))

  (it "puts a deleted method back where it stood in the base"
    (let [doc {:classes [{:id :a :changed :modified
                          :ops [{:name "H"} {:name "H.keep"} {:name "tail"}]}]}
          base {:classes [{:id :a :ops [{:name "first-gone"} {:name "H"} {:name "H.gone"}
                                        {:name "H.keep"} {:name "tail"} {:name "last-gone"}]}]}]
      (should= ["first-gone" "H" "H.gone" "H.keep" "tail" "last-gone"]
               (mapv :name (:ops (first (:classes (bd/mark-deleted doc base))))))))

  (it "adds deleted methods to a changed class only"
    (should= [{:name "keep"} {:name "gone" :changed :deleted}] (:ops ((marked-by-id) :a)))
    (should= [{:name "same"}] (:ops ((marked-by-id) :c))))

  (it "brings back base edges between shown classes as deleted"
    (should= [{:from :a :to :c} {:from :a :to :b :changed :deleted}]
             (:edges (bd/mark-deleted current-doc base-doc))))

  (it "marks an edge the base lacks as added, by its ends and kind"
    (should= [{:from :a :to :c :kind :dependency}
              {:from :c :to :a :kind :dependency :changed :added}
              {:from :a :to :c :kind :inheritance :changed :deleted}]
             (:edges (bd/mark-deleted
                       {:classes [{:id :a} {:id :c}]
                        :edges [{:from :a :to :c :kind :dependency}
                                {:from :c :to :a :kind :dependency}]}
                       {:classes [{:id :a} {:id :c}]
                        :edges [{:from :a :to :c :kind :dependency}
                                {:from :a :to :c :kind :inheritance}]}))))

  (it "leaves the document alone without a base"
    (should= current-doc (bd/mark-deleted current-doc nil)))

  (it "never marks a current class from a deleted file status"
    (should= [{:file "x"}] (bd/mark-classes [{:file "x"}] {"x" :deleted}))))

(defn- repo-with-pr
  "Repo on main whose origin holds pull request 7, which edits a.rb and
  deletes b.rb. The PR is never checked out locally."
  []
  (let [origin (temp-dir)
        dir (repo-on-main)]
    (write! dir "app/c.rb" "class C\nend\n")
    (git! dir "add" ".")
    (git! dir "commit" "-q" "-m" "c")
    (git! origin "init" "-q" "--bare")
    (git! dir "remote" "add" "origin" (str origin))
    (git! dir "push" "-q" "origin" "main")
    (git! dir "checkout" "-q" "-b" "pr")
    (write! dir "app/a.rb" "class A\n  def x; end\nend\n")
    (git! dir "rm" "-q" "app/b.rb")
    (git! dir "commit" "-q" "-am" "pr")
    (git! dir "push" "-q" "origin" "pr:refs/pull/7/head")
    (git! dir "checkout" "-q" "main")
    (git! dir "branch" "-q" "-D" "pr")
    dir))

(describe "pull request changes"
  (it "fetches the PR head and lists its files without checking it out"
    (let [dir (repo-with-pr)
          ch (bd/pr-changes (str dir) nil 7)]
      (should= 7 (:pr ch))
      (should= "main" (:base ch))
      (should= {"app/a.rb" :modified "app/b.rb" :deleted} (:files ch))
      (should= (.trim (git! dir "rev-parse" "refs/uml/pr/7")) (:head ch))
      (should= "class A\nend\n" (slurp (io/file dir "app/a.rb")))
      (should (.isFile (io/file dir "app/b.rb")))))

  (it "fetches the base too when it is on origin"
    (let [dir (repo-with-pr)
          ch (bd/pr-changes (str dir) "origin/main" 7)]
      (should= "origin/main" (:base ch))
      (should= {"app/a.rb" :modified "app/b.rb" :deleted} (:files ch))))

  (it "is nil for a pull request origin does not have"
    (should-be-nil (bd/pr-changes (str (repo-with-pr)) nil 99)))

  (it "reads files and hunks at the PR head"
    (let [dir (repo-with-pr)
          ch (bd/pr-changes (str dir) nil 7)]
      (should= "class A\n  def x; end\nend\n" (bd/head-text (str dir) ch "app/a.rb"))
      (should= {:hunks [{:start 2 :count 1 :removed []}]
                :base "main"
                :text "class A\n  def x; end\nend\n"}
               (bd/member-diff (str dir) ch "app/a.rb"))
      (should= {:hunks [] :base "main" :text "class C\nend\n"}
               (bd/member-diff (str dir) ch "app/c.rb"))
      (should= {:deleted-file true :text "class B\nend\n" :base "main"}
               (bd/member-diff (str dir) ch "app/b.rb"))))

  (it "marks methods the PR added"
    (let [dir (repo-with-pr)
          ch (bd/pr-changes (str dir) nil 7)
          doc (bd/mark-doc {:hierarchical true
                            :classes [{:id :a :file "app/a.rb" :lang :ruby :ops [{:name "x"}]}]}
                           ch (str dir))]
      (should= {:id :a :file "app/a.rb" :lang :ruby :changed :modified
                :ops [{:name "x" :changed :added}]}
               (first (:classes doc)))))

  (it "names the PR in the banner"
    (should= "PR #7 vs main @ abc1234 · 0 classes changed"
             (bd/banner {:pr 7 :base "main" :sha "abc1234"} []))))
