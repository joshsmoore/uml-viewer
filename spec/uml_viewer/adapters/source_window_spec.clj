(ns uml-viewer.adapters.source-window-spec
  (:require [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.adapters.source-window :as source-window]))

(describe "source html"
  (it "escapes html-sensitive characters"
    (should= "&lt;a&amp;b&gt;" (source-window/html-escape "<a&b>")))

  (it "colorizes strings, keywords, and comments"
    (let [out (source-window/colorize-clojure-html "(println \"x\" :k) ; c")]
      (should (str/includes? out "class='str'"))
      (should (str/includes? out "class='kw'"))
      (should (str/includes? out "class='cmt'"))))

  (it "renders a titled document with line numbers"
    (let [doc (source-window/source->html "demo.clj" "(ns demo)\n")]
      (should (str/includes? doc "<div class='hdr'>demo.clj</div>"))
      (should (str/includes? doc "class='ln'>1</td>"))))

  (it "anchors and highlights the member line"
    (let [doc (source-window/source->html "f.clj" "(ns f)\n(defn go [])\n" 2)]
      (should (str/includes? doc "name='here'"))
      (should (str/includes? doc "class='hl'")))))

(def ^:private src "a\nb\nc\nd\n")

(defn- rows [doc]
  (re-seq #"<tr class='([^']*)'>(?:<td class='ln'>(?:<a name='here'></a>)?([^<]*)</td>)" doc))

(describe "branch diff in the source window"
  (it "marks added lines and shows removed lines before the hunk"
    (let [doc (source-window/source-lines->html
                src nil {:hunks [{:start 2 :count 1 :removed ["old b" "<x>"]}
                                 {:start 3 :count 0 :removed ["gone"]}]})]
      (should= [["" "1"] ["del" "−"] ["del" "−"] ["add" "2"] ["" "3"]
                ["del" "−"] ["" "4"] ["" "5"]]
               (map rest (rows doc)))
      (should (str/includes? doc "&lt;x&gt;"))))

  (it "marks every line of a new file as added"
    (should= ["add" "add" "add" "add" "add"]
             (map second (rows (source-window/source-lines->html
                                 src nil {:added-file true})))))

  (it "keeps the member highlight on an added line"
    (should= ["" "hl" "" "" ""]
             (map second (rows (source-window/source-lines->html
                                 src 2 {:hunks [{:start 2 :count 1 :removed []}]})))))

  (it "shows removed lines after the last line"
    (should= ["" "" "" "" "" "del"]
             (map second (rows (source-window/source-lines->html
                                 src nil {:hunks [{:start 5 :count 0 :removed ["tail"]}]})))))

  (it "marks every line of a deleted file as removed"
    (should= ["del" "del" "del" "del" "del"]
             (map second (rows (source-window/source-lines->html
                                 src nil {:deleted-file true})))))

  (it "names the base in the header"
    (should (str/includes? (source-window/source->html "f.rb" src nil
                                                        {:hunks [] :base "main"})
                           "f.rb · diff vs main"))))

(describe "open-member-window!"
  (it "chooses the ident lang and does not open a frame when source is missing"
    (let [calls (atom [])
          shown (atom nil)]
      (with-redefs [uml-viewer.source/member-source
                    (fn [impl ident]
                      (swap! calls conj [impl ident])
                      (when (= "go" (:name ident))
                        {:title "T" :body "(defn go [])" :line 4}))
                    uml-viewer.adapters.source-window/show-member-window!
                    (fn [title body line diff]
                      (reset! shown [title body line diff]))]
        (should-be-nil (source-window/open-member-window! :fallback {:ns "demo.a"}))
        (should= [[:fallback {:ns "demo.a"}]] @calls)
        (should-be-nil @shown)
        (should= true (source-window/open-member-window!
                        :fallback {:ns "demo.a" :name "go"}))
        (should= ["T" "(defn go [])" 4 nil] @shown)
        (source-window/open-member-window!
          :fallback {:ns "demo.a" :name "go" :diff {:hunks []}})
        (should= [:fallback {:ns "demo.a" :name "go"}] (last @calls))
        (should= {:hunks []} (last @shown))
        (source-window/open-member-window!
          :fallback {:ns "demo.a" :name "go" :lang :rust})
        (should= [:rust {:ns "demo.a" :name "go" :lang :rust}] (last @calls))
        (source-window/open-member-window! :fallback "demo.a" "go")
        (should= [:fallback {:ns "demo.a" :name "go"}] (last @calls))
        (source-window/open-member-window!
          :fallback {:ns "demo.a" :name "gone" :diff {:hunks []}})
        (should= [:fallback {:ns "demo.a"}] (last @calls))
        (reset! calls [])
        (source-window/open-member-window!
          :fallback {:ns "demo.a" :file "a.rb" :name "x" :diff {:deleted-file true :text "old"}})
        (should= [] @calls)
        (should= ["a.rb (deleted)" "old" nil {:deleted-file true :text "old"}] @shown))))

  (it "reads a pull request member from the diff text, not the disk"
    (let [shown (atom nil)]
      (with-redefs [uml-viewer.source/member-source (fn [& _] (throw (Exception. "disk read")))
                    uml-viewer.source/text-source
                    (fn [_ ident text]
                      (when (= "go" (:name ident)) {:title "T" :body text :line 2}))
                    uml-viewer.adapters.source-window/show-member-window!
                    (fn [title body line diff] (reset! shown [title body line diff]))]
        (source-window/open-member-window!
          :fallback {:ns "demo.a" :file "a.rb" :name "go" :diff {:hunks [] :text "pr"}})
        (should= ["T" "pr" 2 {:hunks [] :text "pr"}] @shown)))))
