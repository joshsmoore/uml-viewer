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
  (it "reads added, modified, and renamed paths; deletions drop out"
    (should= {"app/a.rb" :modified
              "app/new.rb" :added
              "app/moved.rb" :modified}
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
        (should-be-nil (bd/member-diff (str dir) ch "app/b.rb"))))))

(describe "changed methods"
  (let [base "class A\n  def keep\n    1\n  end\n\n  def edit\n    2\n  end\nend\n"
        now "class A\n  def keep\n    1\n  end\n\n  def edit\n    3\n  end\n\n  def fresh\n  end\nend\n"
        ops [{:name "keep"} {:name "edit"} {:name "fresh"}]]
    (it "marks a method whose lines changed and one the base lacks"
      (should= {"edit" :modified "fresh" :added}
               (bd/changed-ops :ruby base now #{7 9 10 11} ops)))

    (it "marks nothing without an extractor for the language"
      (should= {} (bd/changed-ops :cobol base now #{7} ops)))))

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
      (should-be-nil (bd/git-stamp nil)))))
