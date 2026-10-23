(ns uml-viewer.adapters.svg
  "The diagram, inspector, and class card as SVG hiccup."
  (:require [clojure.string :as str]
            [uml-viewer.adapters.palette :as palette :refer [bg ink white muted gold line
                                                             good violation violation-hot
                                                             changed]]
            [uml-viewer.application.branch-diff :as branch-diff]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.application.document :as document]
            [uml-viewer.domain.config :as config]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.domain.hierarchy :as hierarchy]
            [uml-viewer.engine.curve :as curve]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.engine.route :as route]))

(def ^:private baselines
  {:top "hanging" :center "central" :bottom "text-after-edge"})

(def ^:private anchors
  {:left "start" :center "middle" :right "end"})

(defn paint
  "Fill attributes for `c`, with an optional 0–255 alpha."
  ([c] {:fill (palette/css c)})
  ([c a] {:fill (palette/css c) :fill-opacity (/ (double a) 255.0)}))

(defn pen
  "Stroke attributes for `c` at width `w`."
  [c w]
  {:stroke (palette/css c) :stroke-width w})

(def ^:private no-fill {:fill "none"})

(defn text
  "One text node. `opts` holds :size, :ink, :align [h v], :italic."
  [s x y {:keys [size align italic] :as opts}]
  (let [[h v] (or align [:left :top])]
    [:text (merge {:x x :y y
                   :font-size size
                   :text-anchor (anchors h)
                   :dominant-baseline (baselines v)
                   :font-style (when italic "italic")}
                  (paint (:ink opts)))
     s]))

(defn text-lines
  "Left-aligned text starting at the top, one line per newline."
  [s x y {:keys [size] :as opts}]
  (let [lh (* 1.3 size)]
    (into [:text (merge {:x x :y y :font-size size :dominant-baseline "hanging"}
                        (paint (:ink opts)))]
          (map-indexed (fn [i l] [:tspan {:x x :dy (if (zero? i) 0 lh)} (if (str/blank? l) " " l)])
                       (str/split-lines (str s))))))

(defn rect
  ([r attrs] (rect r 0 attrs))
  ([r radius attrs]
   [:rect (merge {:x (:x r) :y (:y r) :width (max 0 (:w r)) :height (max 0 (:h r))
                  :rx (when (pos? radius) radius)}
                 attrs)]))

(defn- seg [[x1 y1] [x2 y2] attrs]
  [:line (merge {:x1 x1 :y1 y1 :x2 x2 :y2 y2} attrs)])

(defn- points-str [pts]
  (str/join " " (map (fn [[x y]] (str (format "%.1f" (double x)) "," (format "%.1f" (double y)))) pts)))

(defn- polygon [pts attrs]
  [:polygon (merge {:points (points-str pts)} attrs)])

(defn polyline [pts attrs]
  (when (next pts)
    [:polyline (merge {:points (points-str pts)} attrs)]))

(defn arrowhead
  "Mermaid class markers: long shallow chevron (half-angle ~19°)."
  [kind [x y] [px py] stroke]
  (let [ang (Math/atan2 (- y py) (- x px))
        size layout/head-size
        half (/ Math/PI 9)
        left (+ ang half)
        right (- ang half)
        p1 [(- x (* size (Math/cos left))) (- y (* size (Math/sin left)))]
        p2 [(- x (* size (Math/cos right))) (- y (* size (Math/sin right)))]
        back (- ang Math/PI)
        pb [(+ x (* size (Math/cos back))) (+ y (* size (Math/sin back)))]]
    (case kind
      :triangle (polygon [[x y] p1 p2] (merge stroke (paint bg) {:stroke-width 1.5}))
      :diamond (polygon [[x y] p1 pb p2] (merge stroke (paint bg)))
      :diamond-fill (polygon [[x y] p1 pb p2] (merge stroke (paint ink)))
      (list (seg [x y] p1 stroke) (seg [x y] p2 stroke)))))

(defn obstacle-rects [scene e]
  (let [ends #{(:from e) (:to e)}]
    (mapv :rect
          (remove (fn [c]
                    (or (:dummy? c) (contains? ends (:id c))))
                  (:classes scene)))))

(defn live-edge [e scene]
  (let [pts (vec (:points e))]
    (when (next pts)
      (let [from (hit/class-by-id scene (:from e))
            to (hit/class-by-id scene (:to e))
            path (-> (curve/basis-path pts)
                     (curve/constrain-ends (:rect from) (:rect to)))
            [behind tip] (curve/end-tangent path)
            samples (curve/flatten-path path)]
        {:strokes (geom/gap-polyline samples (obstacle-rects scene e) layout/under-gap)
         :tip tip
         :behind behind}))))

(defn edge-ink [e selected?]
  (cond
    (and (:violating e) selected?) [violation-hot 3.2]
    (:violating e) [violation 2.0]
    selected? [gold 2.2]
    :else [muted 1.4]))

(defn edge [e selected? scene]
  (when-let [drawn (if (:strokes e) e (live-edge e scene))]
    (let [[c w] (edge-ink e selected?)
          stroke (assoc (pen c w) :stroke-linecap "round")]
      (into [:g]
            (concat
              (keep #(polyline % (merge stroke no-fill)) (:strokes drawn))
              (when (and (:head e) (:tip drawn))
                [(arrowhead (:head e) (:tip drawn) (:behind drawn) stroke)]))))))

(defn italic-name?
  "True for component/layer boxes and non-class classifiers (interface, enum, …)."
  [c]
  (boolean
    (or (seq (:contents c))
        (:drill? c)
        (let [st (some-> (:stereotype c) name)]
          (and st (not= "class" st))))))

(def ^:private dot-size 12)
(def ^:private dot-inset 10)
(def ^:private dot-gap 14)

(defn- dot-centers [r]
  (let [y (+ (:y r) dot-inset)
        mx (- (geom/right r) dot-inset)]
    {:c [(- mx dot-gap) y]
     :m [mx y]}))

(defn- metric-dot [[x y] ch grade]
  (list
    [:circle (merge {:cx x :cy y :r (/ dot-size 2)}
                    (paint (palette/fill-for grade))
                    (pen (palette/stroke-for grade) 1))]
    (text ch x y {:size 9 :ink white :align [:center :center]})))

(defn metric-dots [m r]
  (let [dots (dot-centers r)]
    (list (metric-dot (:c dots) "C" (palette/crap-grade-of m))
          (metric-dot (:m dots) "M" (palette/mutation-grade-of m)))))

(defn package-body [p selected?]
  (let [g (palette/grade-of p)]
    (rect (:rect p) 8
          (merge (paint (palette/fill-for g) 80)
                 (pen (if selected? gold (palette/stroke-for g))
                      (if selected? 2.5 1.4))))))

(defn package-title [p]
  (let [r (:rect p)]
    (list
      (text (:title p) (+ (:x r) layout/pad) (+ (:y r) (/ layout/banner-h 2))
            {:size 14 :ink gold :align [:left :center] :italic true})
      (metric-dots p r))))

(defn class-line-ink [kind]
  (case kind
    :crap gold
    :stereo muted
    ink))

(defn- rule [r grade y]
  (seg [(+ (:x r) 6) (+ y 4)] [(- (geom/right r) 6) (+ y 4)]
       (pen (palette/stroke-for grade) 1)))

(defn- highlight-child? [line mark]
  (and (= :child (:kind line))
       (= :child (:kind mark))
       (= (:id line) (:id mark))))

(defn- child-wash [r y]
  (rect {:x (+ (:x r) 5) :y (- y 1) :w (- (:w r) 10) :h layout/line-h} 2 (paint gold 55)))

(defn class-line
  "Nodes for one class line at `y`."
  [r grade line y hover selected italic?]
  (list
    (when (or (highlight-child? line hover) (highlight-child? line selected))
      (child-wash r y))
    (if (= :rule (:kind line))
      (rule r grade y)
      (text (:text line) (geom/cx r) y
            {:size (if (= :name (:kind line)) 14 12)
             :ink (class-line-ink (:kind line))
             :align [:center :top]
             :italic (and italic? (= :name (:kind line)))}))))

(defn- class-lines [c r grade hover selected]
  (loop [[l & more] (:lines c)
         y (+ (:y r) layout/pad 4)
         acc []]
    (if l
      (recur more
             (if (= :rule (:kind l)) (+ y layout/pad) (+ y layout/line-h))
             (conj acc (class-line r grade l y hover selected (italic-name? c))))
      (seq acc))))

(defn- port-marked? [p mark]
  (and (= :port (:kind mark))
       (= (:id p) (:id mark))))

(defn port [p hover selected]
  (let [r (:rect p)
        on? (or (port-marked? p hover) (port-marked? p selected))
        bad? (:violating p)]
    (list
      (rect r 3 (merge (paint (if on? [74 96 78] [42 56 52]))
                       (pen (cond on? gold bad? violation :else muted)
                            (if (or on? bad?) 2.2 1))))
      (text (or (:name p) "") (geom/cx r) (geom/cy r)
            {:size 10 :ink (cond on? ink bad? violation :else muted) :align [:center :center]}))))

(defn- port-link [a b bad?]
  (let [stroke (pen (if bad? violation muted) (if bad? 2.0 1.3))]
    (list (seg a b stroke)
          (arrowhead :open b a stroke))))

(defn- class-ports [c hover selected]
  (let [r (:rect c)
        entry (+ (:x r) (* (:w r) (route/anchor-t c :top :end)))
        exit (+ (:x r) (* (:w r) (route/anchor-t c :bottom :start)))]
    (concat
      (for [p (:in-ports c)
            :let [pr (:rect p)]]
        (list (port p hover selected)
              (port-link [(geom/cx pr) (geom/bottom pr)] [entry (:y r)] (:violating p))))
      (for [p (:out-ports c)
            :let [pr (:rect p)]]
        (list (port p hover selected)
              (port-link [exit (geom/bottom r)] [(geom/cx pr) (:y pr)] (:violating p)))))))

(defn corner-mark [c]
  (case (some-> (:stereotype c) name)
    "abstract" "α"
    "interface" "I"
    nil))

(defn- box-stroke [selected? hovered? normal]
  (pen (cond selected? gold hovered? ink :else normal)
       (if selected? 2.6 1.3)))

(defn- oval [c selected? hovered?]
  (let [r (:rect c)]
    (list
      [:ellipse (merge {:cx (geom/cx r) :cy (geom/cy r) :rx (/ (:w r) 2.0) :ry (/ (:h r) 2.0)}
                       (paint (palette/fill-for nil))
                       (box-stroke selected? hovered? (palette/stroke-for nil)))]
      (text (:name c) (geom/cx r) (geom/cy r) {:size 14 :ink ink :align [:center :center]}))))

(defn class-box
  "A class, its ports, lines, metric dots, and corner mark."
  [c selected? hovered? hover selected]
  (if (= :oval (:shape c))
    (oval c selected? hovered?)
    (let [r (:rect c)
          g (palette/grade-of c)]
      (list
        (rect r 4 (merge (paint (palette/fill-for g))
                         (box-stroke selected? hovered? (palette/stroke-for g))))
        (when (some? (:level c))
          (text (str (:level c)) (+ (:x r) 5) (+ (:y r) 3) {:size 11 :ink muted}))
        (class-ports c hover selected)
        (class-lines c r g hover selected)
        (metric-dots c r)
        (when-let [ch (corner-mark c)]
          (text ch (- (first (:c (dot-centers r))) 10) (+ (:y r) 4)
                {:size 12 :ink white :align [:right :top]}))))))

(defn change-badge
  "Corner label for a box this branch touched, or nil."
  [c]
  (cond
    (:changed-count c) (str (:changed-count c) " changed")
    (= :added (:changed c)) "new"
    (= :deleted (:changed c)) "deleted"
    :else nil))

(defn change-mark
  "Cyan outline on a class this branch changed, magenta on one it deleted;
  a corner badge for a new or deleted class or a box holding changes."
  [c]
  (let [r (:rect c)]
    (list
      (when (:changed c)
        (rect {:x (- (:x r) 3) :y (- (:y r) 3) :w (+ (:w r) 6) :h (+ (:h r) 6)} 6
              (merge no-fill (pen (palette/change-ink (:changed c)) 2.6))))
      (when-let [label (change-badge c)]
        (text label (- (geom/right r) 6) (- (geom/bottom r) 4)
              {:size 11 :ink (palette/change-ink (:changed c)) :align [:right :bottom]})))))

(defn branch-line
  "Banner naming the base branch and whether highlighting is on, or nil."
  [state]
  (when-let [b (:branch state)]
    (str (branch-diff/banner b (get-in state [:doc :classes]))
         (if (:hide-changes state) "  (hidden; B shows)" "  (B hides)")
         (if (:only-changes state) "  (only changes; C shows all)" "  (C shows only changes)"))))

(def declutter-label
  {:full "Declutter none"
   :arrows "Declutter arrows"
   :triangles "Remove arrows"
   :elements "Declutter elements"
   :classes "Declutter classes"})

(def popup-max-lines 30)

(defn dep-label [d]
  (str (name (:from d)) " -> " (name (:to d))
       (when (and (:kind d) (not= :dependency (:kind d)))
         (str "  (" (name (:kind d)) ")"))))

(defn popup-lines
  "Popup rows for an arrow's pairs: a count header when it bundles more
  than one, violating pairs first, capped with a `… n more` row."
  [deps]
  (let [deps (vec (sort-by (juxt (complement :violating)
                                 #(name (:from %))
                                 #(name (:to %)))
                           (distinct deps)))
        bad (count (filter :violating deps))
        shown (take popup-max-lines deps)
        more (- (count deps) (count shown))]
    (cond-> []
      (> (count deps) 1)
      (conj {:text (str (count deps) " pairs" (when (pos? bad) (str ", " bad " violating")))
             :header true})
      true (into (map (fn [d] {:text (dep-label d) :violating (:violating d)}) shown))
      (pos? more) (conj {:text (str "… " more " more") :header true}))))

(defn- dep-triangle [ind]
  (polygon (:triangle ind) (paint (if (:violating ind) violation muted))))

(defn edge-popup
  "Hover box listing an arrow's dependency pairs, kept inside the window."
  [hover pointer {:keys [w h]}]
  (when (and (#{:edge :dep :port} (:kind hover)) (seq (:deps hover)) (seq pointer))
    (let [rows (popup-lines (:deps hover))
          line-h 16
          pad 8
          tw (apply max 40 (map (comp layout/text-w :text) rows))
          box-w (+ (* 2 pad) tw)
          box-h (+ (* 2 pad) (* line-h (count rows)))
          [mx my] pointer
          x (max 8 (min (double mx) (max 8.0 (- w box-w 8))))
          y (let [below (+ (double my) 16)]
              (if (< (+ below box-h) h)
                below
                (max 8.0 (- (double my) box-h 8))))
          box {:x x :y y :w box-w :h box-h}]
      (list
        (rect box 4 (merge (paint [18 22 24] 240) (pen line 1)))
        (map-indexed (fn [i row]
                       (text (:text row) (+ x pad) (+ y pad (* i line-h))
                             {:size 12
                              :ink (cond (:violating row) violation
                                         (:header row) gold
                                         :else ink)}))
                     rows)))))

(defn- button [r label]
  (list (rect r 4 (paint [42 61 54]))
        (text label (geom/cx r) (geom/cy r) {:size 13 :ink gold :align [:center :center]})))

(defn- inspector-row [r label on?]
  (list (when on? (rect r 3 (paint gold 40)))
        (text label (+ (:x r) 6) (geom/cy r) {:size 12 :ink (if on? gold ink) :align [:left :center]})))

(defn sidebar-chrome [state {:keys [w h]}]
  (let [x (- w layout/sidebar-w)
        ps (hierarchy/named-proposals (:doc state))
        selected (:proposal-id state)]
    (list
      (rect {:x x :y 0 :w layout/sidebar-w :h h} 0 (paint [18 22 24] 230))
      (seg [x 0] [x h] (pen line 1))
      (text "Inspector" (+ x 16) 16 {:size 16 :ink gold})
      (inspector-row (layout/real-diagram-rect w)
                     (or (get-in state [:doc :title]) "Real diagram")
                     (nil? selected))
      (text "Proposals" (+ x 16) layout/proposals-label-y {:size 11 :ink muted})
      (map-indexed (fn [i p]
                     (inspector-row (layout/proposal-row-rect w i)
                                    (or (:name p) (name (:id p)))
                                    (= selected (:id p))))
                   ps)
      (button (layout/new-proposal-rect w (count ps)) "New Proposal")
      (button (layout/declutter-rect w (count ps))
              (get declutter-label (or (:declutter state) :full) "Declutter none")))))

(def help-text
  (str "Click a component for its card.\nDouble-click a component to open it.\n"
       "Right-click for refresh and omit.\nEsc (or ←) goes up a level.\n"
       "Scroll to pan; Shift-scroll for horizontal.\nCtrl+/− zoom 10%; Ctrl+0 resets.\n"
       "R reloads.\nB shows or hides this branch's changes.\n"
       "C shows only this branch's changes.\nClick the real diagram above Proposals,\n"
       "or a proposal to show it."))

(defn mutation-line [m]
  (when-let [s (layout/format-mutants (:killed m) (:survived m))]
    (let [u (:uncovered m)]
      (if (and u (pos? u))
        (str s "   " (long u) " uncovered")
        s))))

(defn- stack
  "Text rows top-down from `y`; each is [text ink] or nil, 18px apart."
  [x y rows]
  (loop [[r & more] rows y y acc []]
    (cond
      (and (nil? r) (empty? more)) (seq acc)
      (nil? r) (recur more y acc)
      :else (recur more (+ y 18) (conj acc (text-lines (first r) x y {:size 13 :ink (second r)}))))))

(defn sidebar-class [x y scene id]
  (when-let [c (hit/class-by-id scene id)]
    (stack (+ x 16) y
           [[(:name c) ink]
            [(if-let [p (:package c)] (str "package  " (name p)) "foreign") muted]
            (when (some? (:level c)) [(str "Level " (:level c)) muted])
            (when-let [s (layout/format-crap (:crap c))] [s gold])
            (when-let [s (mutation-line c)] [s muted])
            [(str/join "\n" (keep :text (filter #(#{:field :op} (:kind %)) (:lines c)))) ink]])))

(defn sidebar-package [x y scene id]
  (when-let [p (hit/package-by-id scene id)]
    (stack (+ x 16) y
           [[(:label p) ink]
            (when-let [s (layout/format-crap (:crap p))] [s gold])
            (when-let [s (mutation-line p)] [s muted])
            [(str (count (filter #(= (:id p) (:package %)) (:classes scene))) " classes") muted]])))

(defn- regen-button [state {:keys [w h]}]
  (let [r (layout/regen-button w h)]
    (list (button r "Regen")
          (when-let [s (:mail-status state)]
            (text s (:x r) (- (:y r) 8) {:size 11 :ink muted :align [:left :bottom]})))))

(defn sidebar [state {:keys [w h] :as dims}]
  (let [x (- w layout/sidebar-w)
        y (layout/inspector-body-y (count (hierarchy/named-proposals (:doc state))))
        sel (:selected state)
        scene (:scene state)]
    (list
      (sidebar-chrome state dims)
      (case (:kind sel)
        nil (text-lines help-text (+ x 16) y {:size 12 :ink muted})
        :class (sidebar-class x y scene (:id sel))
        :package (sidebar-package x y scene (:id sel))
        nil)
      (when-let [err (:error state)]
        (text-lines (str "IR error:\n" err) (+ x 16) (- h 120) {:size 12 :ink [224 122 74]}))
      (regen-button state dims))))

(defn in-view? [r cam-x cam-y vw vh]
  (boolean
    (and r
         (< (:x r) (+ cam-x vw))
         (> (geom/right r) cam-x)
         (< (:y r) (+ cam-y vh))
         (> (geom/bottom r) cam-y))))

(defn world-view [state {:keys [w h]}]
  (let [z (double (or (:zoom state) 1.0))]
    {:z z
     :cam-x (:cam-x state 0)
     :cam-y (:cam-y state 0)
     :world-w (/ (max 0 (- w layout/sidebar-w)) z)
     :world-h (/ h z)}))

(defn selected-box-id [hit]
  (case (:kind hit)
    :class (:id hit)
    (:child :port) (:parent hit)
    :package (:id hit)
    nil))

(defn hovered-box-id [hit]
  (case (:kind hit)
    :class (:id hit)
    (:child :port) (:parent hit)
    nil))

(defn- package-chosen? [state p]
  (and (= :package (get-in state [:selected :kind]))
       (= (:id p) (get-in state [:selected :id]))))

(defn edge-hot? [e sel-id hover]
  (boolean
    (or (= sel-id (:from e))
        (= sel-id (:to e))
        (contains? (:via-ids e) sel-id)
        (and (= :edge (:kind hover))
             (= (:from e) (:from hover))
             (= (:to e) (:to hover))))))

(defn- class-visible? [c view?]
  (or (view? (:rect c))
      (some #(view? (:rect %)) (concat (:in-ports c) (:out-ports c)))))

(defn world
  "Packages, arrows, sections, and classes under the camera."
  [state dims]
  (let [{:keys [z cam-x cam-y world-w world-h]} (world-view state dims)
        view? #(in-view? % cam-x cam-y world-w world-h)
        scene (:scene state)
        sel (:selected state)
        hover (:hover state)
        sel-id (selected-box-id sel)
        hover-id (hovered-box-id hover)
        packages (filter #(view? (:rect %)) (:packages scene))
        changes? (not (:hide-changes state))]
    [:g {:transform (str "scale(" z ") translate(" (- cam-x) " " (- cam-y) ")")}
     (map #(package-body % (package-chosen? state %)) packages)
     (when-not (get-in scene [:diagram :hide-edges])
       (for [e (:edges scene)
             :when (let [b (:draw-bounds e)] (or (nil? b) (view? b)))]
         (edge e (edge-hot? e sel-id hover) scene)))
     (for [sec (:sections scene)]
       (text (or (:title sec) "") layout/pad (:title-y sec) {:size 20 :ink gold}))
     (map package-title packages)
     (for [c (remove :dummy? (:classes scene))
           :when (class-visible? c view?)]
       (list (class-box c (= sel-id (:id c)) (= hover-id (:id c)) hover sel)
             (when changes? (change-mark c))))
     (map dep-triangle (:dep-indicators scene))]))

(defn- diagram-banner [state]
  (when-let [title (and (not (:waiting state)) (get-in state [:scene :diagram :title]))]
    (if (get-in state [:scene :diagram :proposal])
      (text title 12 8 {:size 16 :ink gold})
      (text title 12 8 {:size 12 :ink muted}))))

(defn- nav-label [state]
  (when (or (seq (:focus state)) (:open-layer state))
    (text (str "← " (if (seq (:focus state))
                      (str/join "." (map name (:focus state)))
                      (or (get-in state [:scene :diagram :title])
                          (name (:open-layer state)))))
          12 28 {:size 14 :ink gold})))

(defn- branch-banner [state]
  (when-let [s (branch-line state)]
    (text s 12 48 {:size 12 :ink changed})))

(defn frame
  "The whole window for `state` at `dims` {:w :h}."
  [state {:keys [w h] :as dims}]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width w :height h
         :font-family "Helvetica Neue, Helvetica, Arial, sans-serif"}
   (rect {:x 0 :y 0 :w w :h h} 0 (paint bg))
   (when (:waiting state)
     (text document/waiting-message (/ (max 0 (- w layout/sidebar-w)) 2.0) (/ h 2.0)
           {:size 18 :ink muted :align [:center :center]}))
   (world state dims)
   (sidebar state dims)
   (diagram-banner state)
   (nav-label state)
   (branch-banner state)
   (edge-popup (:hover state) (:pointer state) dims)])

(defn detail-row-color [row]
  (case (:kind row)
    (:name :module :field :rel :stats) ink
    (:crap :heading) gold
    :changed changed
    muted))

(defn- count-ink [n]
  (if (pos? (or n 0)) violation good))

(defn cell-color [row col]
  (case (:id col)
    :cov (palette/coverage-ink (:coverage row))
    :crap (palette/stroke-for (config/crap-grade (:crap-n row)))
    :killed white
    :survived (count-ink (:survived row))
    :uncovered (count-ink (:uncovered row))
    muted))

(defn- detail-cells [row]
  (let [header? (= :col-header (:kind row))
        y (:y row)]
    (concat
      (for [col (detail/column-layout)
            :when (or header? (not (and (:mut-note row) (= :mutation (:group col)))))
            :let [s (if header? (:label col) (get row (:key col)))]
            :when s]
        (text s (:right col) y {:size 13 :ink (if header? gold (cell-color row col)) :align [:right :top]}))
      (when (and (not header?) (:mut-note row))
        (when-let [g (first (filter #(= :mutation (:id %)) (detail/group-layout)))]
          [(text (:mut-note row) (:right g) y {:size 13 :ink muted :align [:right :top]})])))))

(defn- detail-groups [y]
  (for [g (detail/group-layout)
        :when (:label g)]
    (text (:label g) (/ (+ (:left g) (:right g)) 2.0) y {:size 13 :ink gold :align [:center :top]})))

(defn clip
  "`s` cut to about `px` wide at font `size`, with an ellipsis."
  [s px size]
  (let [n (max 1 (int (/ px (* 0.56 size))))]
    (if (> (count s) n) (str (subs s 0 (max 0 (dec n))) "…") s)))

(defn- row-label [row]
  (when (:text row)
    (let [cols (detail/column-layout)
          right (if (seq cols)
                  (- (:left (first cols)) detail/col-gap)
                  (- detail/width detail/pad))
          size (if (= :name (:kind row)) 20 13)
          full? (not= :stats (:kind row))
          px (- (if full? (- detail/width detail/pad) right) detail/pad)]
      [:text (merge {:class "label" :x detail/pad :y (:y row) :font-size size
                     :dominant-baseline "hanging"}
                    (paint (cond
                             (:changed row) (palette/change-ink (:changed row))
                             (:private row) muted
                             :else (detail-row-color row))))
       (clip (:text row) px size)])))

(defn hot-row?
  "A card row that opens source or another card."
  [row]
  (boolean (or (:op-name row) (:module row) (= :rel (:kind row)))))

(defn detail-row [row]
  [:g (when (hot-row? row) {:class "hot"})
   (when (hot-row? row)
     (rect {:x 0 :y (:y row) :w detail/width :h (:h row)} 0 (merge (paint gold) {:class "wash" :fill-opacity 0})))
   (case (:kind row)
     :group-header (detail-groups (:y row))
     :col-header (detail-cells row)
     :stats (list (row-label row) (detail-cells row))
     (row-label row))])

(defn card
  "The class card for `model`, as tall as its rows."
  [model]
  (let [rows (detail/rows model)]
    [:svg {:xmlns "http://www.w3.org/2000/svg" :width detail/width :height (detail/content-h rows)
           :font-family "Helvetica Neue, Helvetica, Arial, sans-serif"}
     (map detail-row rows)]))
