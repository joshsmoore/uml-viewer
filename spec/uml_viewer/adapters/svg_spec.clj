(ns uml-viewer.adapters.svg-spec
  (:require [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.adapters.markup :as markup]
            [uml-viewer.adapters.palette :as palette]
            [uml-viewer.adapters.svg :as svg]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.engine.layout :as layout]))

(defn- nodes [tree]
  (tree-seq #(or (vector? %) (seq? %))
            #(if (vector? %) (rest %) %)
            tree))

(defn- tagged [tag tree]
  (filter #(and (vector? %) (= tag (first %))) (nodes tree)))

(defn- attrs [n]
  (when (map? (second n)) (second n)))

(defn- texts [tree]
  (->> (concat (tagged :text tree) (tagged :tspan tree))
       (mapcat rest)
       (filter string?)))

(defn- fills [tree]
  (set (keep #(:fill (attrs %)) (nodes tree))))

(defn- strokes [tree]
  (set (keep #(:stroke (attrs %)) (nodes tree))))

(def ^:private dims {:w 1500 :h 920})

(defn- scene []
  (compose/compile-diagram
    (ir/normalize
      {:title "Tiny"
       :packages
       [{:id :p :label "P"
         :classes [{:id :a :name "A" :ns "demo.a" :stereotype :interface
                    :ops [{:name "go" :args ["x"] :returns "void"}]}
                   {:id :b :name "B"}]}]
       :edges [{:from :a :to :b :kind :association}]})))

(defn- state []
  {:scene (scene) :selected nil :hover nil :cam-x 0 :cam-y 0})

(defn- class-of [s id]
  (first (filter #(= id (:id %)) (:classes (:scene s)))))

(describe "svg primitives"
  (it "aligns text and fills with an optional alpha"
    (should= [:text {:x 1 :y 2 :font-size 12 :text-anchor "middle" :dominant-baseline "central"
                     :font-style "italic" :fill "rgb(1,2,3)"}
              "s"]
             (svg/text "s" 1 2 {:size 12 :ink [1 2 3] :align [:center :center] :italic true}))
    (should= "start" (:text-anchor (attrs (svg/text "s" 0 0 {:size 9 :ink [0 0 0]}))))
    (should= 0.5 (double (:fill-opacity (svg/paint [0 0 0] 127.5)))))

  (it "breaks multi-line text into tspans"
    (let [t (svg/text-lines "a\n\nb" 5 6 {:size 10 :ink [0 0 0]})]
      (should= ["a" " " "b"] (texts t))
      (should= [0 13.0 13.0] (map (comp :dy attrs) (tagged :tspan t)))))

  (it "rounds a rect only when asked and never sizes it negative"
    (should-be-nil (:rx (attrs (svg/rect {:x 0 :y 0 :w 1 :h 1} {}))))
    (should= 4 (:rx (attrs (svg/rect {:x 0 :y 0 :w 1 :h 1} 4 {}))))
    (should= 0 (:width (attrs (svg/rect {:x 0 :y 0 :w -3 :h 1} {})))))

  (it "skips a polyline of fewer than two points"
    (should-be-nil (svg/polyline [[0 0]] {}))
    (should= "0.0,0.0 1.5,2.0" (:points (attrs (svg/polyline [[0 0] [1.5 2]] {}))))))

(def ^:private stroke (svg/pen palette/muted 1))

(describe "arrowheads"
    (it "fills a triangle for inheritance and diamonds for aggregation and composition"
      (should= :polygon (first (svg/arrowhead :triangle [10 0] [0 0] stroke)))
      (should= (palette/css palette/bg) (:fill (attrs (svg/arrowhead :diamond [10 0] [0 0] stroke))))
      (should= (palette/css palette/ink) (:fill (attrs (svg/arrowhead :diamond-fill [10 0] [0 0] stroke))))
      (should= 4 (count (str/split (:points (attrs (svg/arrowhead :diamond [10 0] [0 0] stroke))) #" "))))

    (it "draws an open chevron otherwise"
      (should= [:line :line] (map first (svg/arrowhead :open [10 0] [0 0] stroke)))))

(describe "edges"
  (it "inks violating and selected edges"
    (should= [palette/violation-hot 3.2] (svg/edge-ink {:violating true} true true))
    (should= [palette/violation 2.0] (svg/edge-ink {:violating true} false true))
    (should= [palette/gold 2.2] (svg/edge-ink {} true true))
    (should= [palette/muted 1.4] (svg/edge-ink {} false true)))

  (it "colors a changed arrow by its change unless changes are hidden"
    (should= [palette/changed 2.0] (svg/edge-ink {:changed :added} false true))
    (should= [palette/deleted 2.0] (svg/edge-ink {:changed :deleted} false true))
    (should= [palette/muted 1.4] (svg/edge-ink {:changed :added} false false))
    (should= [palette/violation 2.0] (svg/edge-ink {:changed :added :violating true} false true))
    (should= [palette/gold 2.2] (svg/edge-ink {:changed :added} true true)))

  (it "draws nothing without two points and a head at the tip"
    (should-be-nil (svg/edge {:points [[0 0]]} false {:classes []} true))
    (let [e (svg/edge {:strokes [[[0 0] [5 5]]] :tip [5 5] :behind [0 0] :head :triangle} true {} true)]
      (should= 1 (count (tagged :polyline e)))
      (should= 1 (count (tagged :polygon e)))
      (should (contains? (strokes e) (palette/css palette/gold)))))

  (it "routes a live edge from its points"
    (let [s (scene)
          e (first (:edges s))
          drawn (svg/live-edge (dissoc e :strokes) s)]
      (should (seq (:strokes drawn)))
      (should (:tip drawn))))

  (it "leaves out dummies and the edge's own ends as obstacles"
    (should= [{:x 1}]
             (svg/obstacle-rects {:classes [{:id :a :rect {:x 0}} {:id :c :rect {:x 1}}
                                            {:id :d :dummy? true :rect {:x 2}}]}
                                 {:from :a :to :b})))

  (it "heats edges touching the selection or under the pointer"
    (should (svg/edge-hot? {:from :a :to :b} :a nil))
    (should (svg/edge-hot? {:from :a :to :b} :b nil))
    (should (svg/edge-hot? {:from :x :to :y :via-ids #{:a}} :a nil))
    (should (svg/edge-hot? {:from :a :to :b} nil {:kind :edge :from :a :to :b}))
    (should-not (svg/edge-hot? {:from :a :to :b} :c {:kind :class :id :a}))))

(describe "classes"
  (it "paints name, members, C/M dots, and an interface mark"
    (let [c (class-of (state) :a)
          out (svg/class-box c false false nil nil)]
      (should-contain "A" (texts out))
      (should-contain "go(x) : void" (texts out))
      (should-contain "C" (texts out))
      (should-contain "M" (texts out))
      (should-contain "I" (texts out))
      (should= "italic" (some #(when (= "A" (last %)) (:font-style (attrs %))) (tagged :text out)))))

  (it "strokes gold when selected and ink when hovered"
    (let [c (class-of (state) :b)]
      (should (contains? (strokes (svg/class-box c true false nil nil)) (palette/css palette/gold)))
      (should (contains? (strokes (svg/class-box c false true nil nil)) (palette/css palette/ink)))))

  (it "paints a level number and an abstract mark"
    (let [c (assoc (class-of (state) :b) :level 3 :stereotype :abstract)
          out (svg/class-box c false false nil nil)]
      (should-contain "3" (texts out))
      (should-contain "α" (texts out))))

  (it "paints a foreign dependency as an oval"
    (let [out (svg/class-box {:shape :oval :name "ext" :rect {:x 0 :y 0 :w 40 :h 20}} false false nil nil)]
      (should= 1 (count (tagged :ellipse out)))
      (should-contain "ext" (texts out))))

  (it "washes a hovered child row"
    (let [r {:x 0 :y 0 :w 100 :h 40}
          line {:kind :child :id :k :text "k"}]
      (should= 1 (count (tagged :rect (svg/class-line r 5 line 10 {:kind :child :id :k} nil false))))
      (should= 0 (count (tagged :rect (svg/class-line r 5 line 10 nil nil false))))
      (should= 1 (count (tagged :line (svg/class-line r 5 {:kind :rule} 10 nil nil false))))))

  (it "marks ports, hot when hovered and red when violating"
    (let [p {:id :x :name "x" :rect {:x 0 :y 0 :w 20 :h 10}}]
      (should (contains? (strokes (svg/port p {:kind :port :id :x} nil)) (palette/css palette/gold)))
      (should (contains? (strokes (svg/port (assoc p :violating true) nil nil)) (palette/css palette/violation)))
      (should (contains? (strokes (svg/port p nil nil)) (palette/css palette/muted)))))

  (it "only italicizes component and non-class names"
    (should (svg/italic-name? {:contents [:x]}))
    (should (svg/italic-name? {:drill? true}))
    (should (svg/italic-name? {:stereotype :enum}))
    (should-not (svg/italic-name? {:stereotype :class}))
    (should-not (svg/italic-name? {}))))

(describe "branch changes"
  (it "labels a new class and a box holding changes"
    (should= "new" (svg/change-badge {:changed :added}))
    (should= "3 changed" (svg/change-badge {:changed-count 3}))
    (should= "deleted" (svg/change-badge {:changed :deleted}))
    (should-be-nil (svg/change-badge {:changed :modified}))
    (should-be-nil (svg/change-badge {})))

  (it "outlines a changed class and badges it"
    (let [r {:x 0 :y 0 :w 10 :h 10}
          out (svg/change-mark {:rect r :changed :deleted})]
      (should-contain "deleted" (texts out))
      (should (contains? (strokes out) (palette/css palette/deleted)))
      (should= [] (texts (svg/change-mark {:rect r :changed :modified})))
      (should= [] (tagged :rect (svg/change-mark {:rect r})))))

  (it "names the base branch and how to toggle"
    (let [s {:branch {:base "main" :sha "abc1234"}
             :doc {:classes [{:changed :added} {:id :x}]}}]
      (should= "vs main @ abc1234 · 1 class changed  (B hides)  (C shows only changes)"
               (svg/branch-line s))
      (should= "vs main @ abc1234 · 1 class changed  (hidden; B shows)  (C shows only changes)"
               (svg/branch-line (assoc s :hide-changes true)))
      (should= "vs main @ abc1234 · 1 class changed  (B hides)  (only changes; C shows all)"
               (svg/branch-line (assoc s :only-changes true)))
      (should-be-nil (svg/branch-line {})))))

(describe "arrow popup"
  (it "shows one pair without a header"
    (should= [{:text "a -> b" :violating nil :dep {:from :a :to :b}}]
             (svg/popup-lines [{:from :a :to :b}])))

  (it "counts pairs, lists violations first, and names non-dependency kinds"
    (let [rows (svg/popup-lines [{:from :ops.b :to :ui.x :kind :implements}
                                 {:from :ops.a :to :ui.x :kind :dependency :violating true}])]
      (should= "2 pairs, 1 violating" (:text (first rows)))
      (should (:header (first rows)))
      (should= "ops.a -> ui.x" (:text (second rows)))
      (should= "ops.b -> ui.x  (implements)" (:text (nth rows 2)))))

  (it "tags a changed pair"
    (should= "a -> b  · new" (svg/dep-label {:from :a :to :b :changed :added}))
    (should= "a -> b  (inheritance)  · removed"
             (svg/dep-label {:from :a :to :b :kind :inheritance :changed :deleted}))
    (should= "a -> b" (svg/dep-label {:from :a :to :b})))

  (it "caps a long list with a remainder row"
    (let [rows (svg/popup-lines (for [i (range 45)] {:from (keyword (str "c" i)) :to :t}))]
      (should= "45 pairs" (:text (first rows)))
      (should= (+ 2 svg/popup-max-lines) (count rows))
      (should= "… 15 more" (:text (last rows)))))

  (it "boxes the pairs near the pointer and inside the window"
    (let [hover {:kind :edge :deps [{:from :a :to :b :violating true}]}
          out (svg/edge-popup hover [1490 910] dims false)
          box (attrs (first (tagged :rect out)))]
      (should-contain "a -> b" (texts out))
      (should (<= (+ (:x box) (:width box)) 1500))
      (should (< (:y box) 910))
      (should (contains? (fills out) (palette/css palette/violation)))
      (should= 26.0 (double (:y (attrs (first (tagged :rect (svg/edge-popup hover [10 10] dims false)))))))
      (should-be-nil (svg/edge-popup {:kind :class :deps [{:from :a :to :b}]} [0 0] dims false))
      (should-be-nil (svg/edge-popup hover nil dims false))))

  (it "lists each pair's call sites under it only when pinned"
    (let [deps [{:from :a :to :b :sites [{:line 3 :in "go" :call "run"} {:line 7}]}]]
      (should= ["a -> b"] (map :text (svg/popup-lines deps)))
      (should= ["a -> b" "    go:3  → run" "    top level:7"] (map :text (svg/popup-lines deps true)))
      (should= {:line 3 :in "go" :call "run"} (:site (second (svg/popup-lines deps true))))))

  (it "pins the list with a hint, and finds the pair row under a click"
    (let [deps [{:from :a :to :b} {:from :a :to :c}]
          out (svg/edge-popup {:kind :edge :deps deps} [100 100] dims true)
          layout (svg/popup-layout deps [100 100] dims true)
          [hint header ab] (:rows layout)
          at (fn [row] (svg/popup-row-at layout (+ 2 (:x (:rect row))) (+ 2 (:y (:rect row)))))]
      (should-contain svg/pinned-hint (texts out))
      (should= svg/pinned-hint (:text hint))
      (should= "2 pairs" (:text header))
      (should-be-nil (at hint))
      (should-be-nil (at header))
      (should= {:from :a :to :b} (:dep (at ab)))
      (should-be-nil (svg/popup-row-at layout 0 0)))))

(describe "inspector"
  (it "explains itself when nothing is selected"
    (let [out (svg/sidebar {:doc {:title "Tiny"}} dims)]
      (should-contain "Inspector" (texts out))
      (should-contain "Tiny" (texts out))
      (should-contain "Proposals" (texts out))
      (should-contain "Regen" (texts out))
      (should-contain "Declutter none" (texts out))
      (should-contain "Right-click for refresh and omit." (texts out))))

  (it "lists proposals and highlights the shown one"
    (let [out (svg/sidebar-chrome {:doc {:proposals [{:id :p1 :name "One" :layers []}]}
                                   :proposal-id :p1 :declutter :arrows}
                                  dims)]
      (should-contain "One" (texts out))
      (should-contain "Real diagram" (texts out))
      (should-contain "Declutter arrows" (texts out))))

  (it "shows a class, a package, an error, and the mail status"
    (let [s (assoc (state) :selected {:kind :class :id :a} :error "bad" :mail-status "Regen requested.")
          out (svg/sidebar s dims)]
      (should-contain "A" (texts out))
      (should-contain "package  p" (texts out))
      (should-contain "IR error:" (texts out))
      (should-contain "Regen requested." (texts out)))
    (let [out (svg/sidebar (assoc (state) :selected {:kind :package :id :p}) dims)]
      (should-contain "P" (texts out))
      (should-contain "2 classes" (texts out)))
    (should-be-nil (svg/sidebar-class 0 0 (scene) :missing))
    (should-be-nil (svg/sidebar-package 0 0 (scene) :missing)))

  (it "counts uncovered mutants beside the score"
    (should-be-nil (svg/mutation-line {}))
    (should= (layout/format-mutants 3 1) (svg/mutation-line {:killed 3 :survived 1 :uncovered 0}))
    (should (str/ends-with? (svg/mutation-line {:killed 3 :survived 1 :uncovered 2}) "2 uncovered"))))

(describe "window frame"
  (it "names the selected and hovered box"
    (should= :k (svg/selected-box-id {:kind :class :id :k}))
    (should= :p (svg/selected-box-id {:kind :child :parent :p}))
    (should= :p (svg/selected-box-id {:kind :port :parent :p}))
    (should= :g (svg/selected-box-id {:kind :package :id :g}))
    (should-be-nil (svg/selected-box-id {:kind :edge}))
    (should= :p (svg/hovered-box-id {:kind :port :parent :p}))
    (should-be-nil (svg/hovered-box-id {:kind :package :id :g})))

  (it "culls boxes outside the camera"
    (should (svg/in-view? {:x 10 :y 10 :w 5 :h 5} 0 0 100 100))
    (should-not (svg/in-view? {:x 200 :y 10 :w 5 :h 5} 0 0 100 100))
    (should-not (svg/in-view? {:x 10 :y 200 :w 5 :h 5} 0 0 100 100))
    (should-not (svg/in-view? nil 0 0 100 100)))

  (it "scales and translates the world by the camera"
    (let [w (svg/world (assoc (state) :zoom 2.0 :cam-x 5 :cam-y 7) dims)]
      (should= "scale(2.0) translate(-5 -7)" (:transform (attrs w)))))

  (it "paints the diagram, banners, and inspector"
    (let [s (assoc (state) :focus [:demo] :branch {:base "main" :sha "abc1234"} :doc {:classes []})
          out (svg/frame s dims)
          html (markup/html out)]
      (should (str/starts-with? html "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1500\""))
      (should-contain "← demo" (texts out))
      (should-contain "A" (texts out))
      (should-contain "B" (texts out))
      (should (some #(str/starts-with? % "vs main") (texts out)))
      (should-contain "Inspector" (texts out))))

  (it "dims a box shown only as the far end of a changed arrow"
    (let [s (update-in (state) [:scene :classes]
                       (fn [cs] (mapv #(if (= :b (:id %)) (assoc % :context true) %) cs)))
          veils (filter #(= 150 (Math/round (* 255 (double (or (:fill-opacity (attrs %)) 0)))))
                        (tagged :rect (svg/world s dims)))]
      (should= 1 (count veils))
      (should= 0 (count (filter #(= 150 (Math/round (* 255 (double (or (:fill-opacity (attrs %)) 0)))))
                                (tagged :rect (svg/world (state) dims)))))))

  (it "colors changed arrows unless B hides changes"
    (let [s (update-in (state) [:scene :edges] (fn [es] (mapv #(assoc % :changed :added) es)))
          cyan (palette/css palette/changed)]
      (should (contains? (strokes (svg/world s dims)) cyan))
      (should-not (contains? (strokes (svg/world (assoc s :hide-changes true) dims)) cyan))))

  (it "shows the waiting message and hides edges when asked"
    (let [s (assoc (state) :waiting true)
          s (assoc-in s [:scene :diagram :hide-edges] true)
          out (svg/frame s dims)]
      (should-contain "Waiting for agent to create diagram." (texts out))
      (should= [] (tagged :polyline (svg/world s dims)))))

  (it "titles a proposal in gold"
    (let [s (-> (state)
                (assoc-in [:scene :diagram :proposal] true)
                (assoc-in [:scene :diagram :title] "PROPOSAL X"))]
      (should= (palette/css palette/gold)
               (some #(when (= "PROPOSAL X" (last %)) (:fill (attrs %))) (tagged :text (svg/frame s dims)))))))

(defn- model [] (detail/model (scene) :a))

(describe "class card"
    (it "is as tall as its rows and lists the members"
      (let [out (svg/card (model))]
        (should= (detail/content-h (detail/rows (model))) (:height (attrs out)))
        (should-contain "A" (texts out))
        (should (some #(str/starts-with? % "+ go") (texts out)))))

    (it "marks rows that open source or a card"
      (should (svg/hot-row? {:op-name "go"}))
      (should (svg/hot-row? {:module true}))
      (should (svg/hot-row? {:kind :rel}))
      (should-not (svg/hot-row? {:kind :heading}))
      (should= {:class "hot"} (attrs (svg/detail-row {:kind :rel :text "x" :y 0 :h 18}))))

    (it "colors rows by kind and change"
      (should= palette/ink (svg/detail-row-color {:kind :name}))
      (should= palette/gold (svg/detail-row-color {:kind :heading}))
      (should= palette/changed (svg/detail-row-color {:kind :changed}))
      (should= palette/muted (svg/detail-row-color {:kind :other}))
      (should= (palette/css palette/deleted)
               (:fill (attrs (first (tagged :text (svg/detail-row {:kind :stats :text "x" :y 0 :h 18 :changed :deleted}))))))
      (should= (palette/css palette/muted)
               (:fill (attrs (first (tagged :text (svg/detail-row {:kind :stats :text "x" :y 0 :h 18 :private true})))))))

    (it "colors metric cells and keeps mutation notes off the header"
      (should= palette/white (svg/cell-color {} {:id :killed}))
      (should= palette/violation (svg/cell-color {:survived 2} {:id :survived}))
      (should= palette/good (svg/cell-color {:uncovered 0} {:id :uncovered}))
      (should= palette/muted (svg/cell-color {} {:id :cc}))
      (should= (palette/coverage-ink 0.9) (svg/cell-color {:coverage 0.9} {:id :cov}))
      (should-contain "no sites" (texts (svg/detail-row {:kind :stats :text "x" :y 0 :h 18 :mut-note "no sites"})))
      (should-contain "Crap" (texts (svg/detail-row {:kind :col-header :y 0 :h 18 :mut-note "no sites"})))
      (should-contain "--crap--" (texts (svg/detail-row {:kind :group-header :y 0 :h 18}))))

    (it "clips a long label with an ellipsis"
      (should= "abc" (svg/clip "abc" 100 10))
      (should= "ab…" (svg/clip "abcdef" 17 10))))
