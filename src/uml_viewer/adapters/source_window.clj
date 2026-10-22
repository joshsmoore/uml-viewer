(ns uml-viewer.adapters.source-window
  (:require [clojure.string :as str]
            [uml-viewer.source :as source])
  (:import [java.awt Dimension]
           [java.util.regex Pattern]
           [javax.swing JEditorPane JFrame JScrollPane SwingUtilities]))

(defn html-escape
  [s]
  (-> (or s "")
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn colorize-clojure-html
  [source]
  (let [split-comment (fn [line]
                        (loop [idx 0
                               in-string? false
                               escaped? false]
                          (if (>= idx (count line))
                            [line nil]
                            (let [ch (.charAt line idx)]
                              (cond
                                (and (not in-string?) (= ch \;))
                                [(subs line 0 idx) (subs line idx)]

                                escaped?
                                (recur (inc idx) in-string? false)

                                (and in-string? (= ch \\))
                                (recur (inc idx) in-string? true)

                                (= ch \")
                                (recur (inc idx) (not in-string?) false)

                                :else
                                (recur (inc idx) in-string? false))))))
        [code-part comment-part] (split-comment (or source ""))
        escaped (html-escape code-part)
        string-pattern (Pattern/compile "\"([^\"\\\\]|\\\\.)*\"")
        keyword-pattern (Pattern/compile ":[a-zA-Z0-9\\-\\?!_\\./]+")
        apply-style (fn [text pattern class-name]
                      (let [matcher (.matcher pattern text)
                            sb (StringBuffer.)]
                        (loop []
                          (if (.find matcher)
                            (do
                              (.appendReplacement matcher sb (str "<span class='" class-name "'>$0</span>"))
                              (recur))
                            (do
                              (.appendTail matcher sb)
                              (str sb))))))]
    (str (-> escaped
             (apply-style string-pattern "str")
             (apply-style keyword-pattern "kw"))
         (when comment-part
           (str "<span class='cmt'>" (html-escape comment-part) "</span>")))))

(defn expand-tabs
  [line]
  (str/replace (or line "") "\t" "  "))

(defn- added-lines
  "New-file lines a diff adds or changes; every line for a new file."
  [diff n]
  (if (:added-file diff)
    (set (range 1 (inc n)))
    (into #{} (mapcat #(range (:start %) (+ (:start %) (:count %)))) (:hunks diff))))

(defn- removed-before
  "Line number to the old lines shown just above it. A pure deletion
  sits after its start line."
  [diff]
  (reduce (fn [m {:keys [start removed] :as h}]
            (if (seq removed)
              (update m (if (zero? (:count h)) (inc start) (max 1 start))
                      (fnil into []) removed)
              m))
          {}
          (:hunks diff)))

(defn- removed-rows [lines]
  (apply str
         (map (fn [line]
                (str "<tr class='del'><td class='ln'>−</td>"
                     "<td class='code'><pre>"
                     (let [h (html-escape (expand-tabs line))] (if (str/blank? h) "&nbsp;" h))
                     "</pre></td></tr>"))
              lines)))

(defn- diff-lines->html
  [source highlight-line diff]
  (let [lines (str/split (or source "") #"\r?\n" -1)
        added (added-lines diff (count lines))
        removed (removed-before diff)]
    (str
      (->> lines
           (map-indexed (fn [idx line]
                          (let [n (inc idx)
                                hl? (and highlight-line (= n highlight-line))
                                classes (cond hl? "hl" (added n) "add" :else "")
                                line-html (colorize-clojure-html (expand-tabs line))
                                visible-line (if (str/blank? line-html) "&nbsp;" line-html)]
                            (str (removed-rows (get removed n))
                                 "<tr class='" classes "'>"
                                 "<td class='ln'>"
                                 (when hl? "<a name='here'></a>")
                                 n "</td>"
                                 "<td class='code'><pre>" visible-line "</pre></td>"
                                 "</tr>"))))
           (apply str))
      (removed-rows (mapcat val (filter #(> (key %) (count lines)) removed))))))

(defn source-lines->html
  ([source] (source-lines->html source nil nil))
  ([source highlight-line] (source-lines->html source highlight-line nil))
  ([source highlight-line diff]
   (if (:deleted-file diff)
     (removed-rows (str/split (or source "") #"\r?\n" -1))
     (diff-lines->html source highlight-line diff))))

(defn source->html
  ([title source] (source->html title source nil nil))
  ([title source highlight-line] (source->html title source highlight-line nil))
  ([title source highlight-line diff]
   (str "<html><head><style>"
        "body{margin:0;padding:0;background:#f8fafc;color:#111827;font-family:Menlo,Monaco,Consolas,monospace;}"
        ".hdr{padding:10px 12px;background:#e5e7eb;border-bottom:1px solid #cbd5e1;font-family:sans-serif;font-size:13px;}"
        ".src{padding:0;line-height:1.35;font-size:13px;}"
        ".src table{border-collapse:collapse;width:100%;}"
        ".ln{width:52px;padding:0 10px;background:#eef2f7;color:#6b7280;text-align:right;vertical-align:top;"
        "border-right:1px solid #d1d5db;user-select:none;}"
        ".code{padding:0 12px;vertical-align:top;}"
        ".code pre{margin:0;white-space:pre;tab-size:2;}"
        ".add td{background:#dcfce7;}"
        ".add .ln{background:#bbf7d0;color:#166534;}"
        ".del td{background:#fee2e2;color:#991b1b;}"
        ".del .ln{background:#fecaca;}"
        ".hl td{background:#fde68a;}"
        ".hl .ln{background:#fcd34d;}"
        ".cmt{color:#6b7280;}"
        ".str{color:#b45309;}"
        ".kw{color:#1d4ed8;}"
        "</style></head><body>"
        "<div class='hdr'>" (html-escape title)
        (when (:base diff) (str " · diff vs " (html-escape (:base diff))))
        "</div>"
        "<div class='src'><table>" (source-lines->html source highlight-line diff) "</table></div>"
        "</body></html>")))

(defn- build-frame!
  [title body line diff]
  (let [frame (JFrame. title)
        editor (JEditorPane. "text/html" (source->html title body line diff))
        scroll (JScrollPane. editor)]
    (.setEditable editor false)
    (.setCaretPosition editor 0)
    (.setPreferredSize scroll (Dimension. 900 640))
    (.add (.getContentPane frame) scroll)
    (.pack frame)
    (.setLocationByPlatform frame true)
    (.setVisible frame true)
    (when line
      (SwingUtilities/invokeLater
        (fn []
          (.scrollToReference editor "here"))))
    frame))

(defn- source-for-member [source-impl ident]
  (if (:lang ident) (:lang ident) source-impl))

(defn- member-or-file
  "The member's source, read from the diff's `:text` (a pull request
  head) when present; for a member the branch deleted, its whole file
  (or, for a deleted file, the base text) so the diff shows the removal."
  [source-impl ident diff]
  (let [impl (source-for-member source-impl ident)]
    (if (:deleted-file diff)
      {:title (str (:file ident) " (deleted)") :body (:text diff) :line nil}
      (let [find (if (:text diff)
                   #(source/text-source impl % (:text diff))
                   #(source/member-source impl %))]
        (or (find ident)
            (when diff (find (dissoc ident :name))))))))

(defn- show-member-window! [title body line diff]
  (SwingUtilities/invokeLater
    (fn []
      (build-frame! title body line diff))))

(defn open-member-window!
  "Open an independent source window for a member.
  `source-impl` satisfies `LanguageSource`. `ident` is a source identity
  map, or `ns-name` plus `member-name`. An ident `:diff` (from
  branch-diff/file-diff, plus `:base`) shades the branch's changes."
  ([source-impl ident]
   (let [diff (:diff ident)
         ident (dissoc ident :diff)]
     (when-let [{:keys [title body line]} (member-or-file source-impl ident diff)]
       (show-member-window! title body line diff)
       true)))
  ([source-impl ns-name member-name]
   (open-member-window! source-impl {:ns ns-name :name member-name})))
