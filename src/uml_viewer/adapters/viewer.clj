(ns uml-viewer.adapters.viewer
  "Browser input applied to the viewer state. Each handler returns
  `{:state s :reply r}`; the reply tells the page to open a menu or a
  source view."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.markup :as markup]
            [uml-viewer.adapters.palette :as palette]
            [uml-viewer.adapters.source-window :as source-window]
            [uml-viewer.adapters.svg :as svg]
            [uml-viewer.application.branch-diff :as branch-diff]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.application.document :as document]
            [uml-viewer.application.events :as events]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.domain.policy :as policy]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.source :as source]))

(def default-window {:w 1500 :h 920})

(defn window [state]
  (or (:window state) default-window))

(defn dims
  "Window sizes in the shape events/on-scroll and on-key read."
  [state]
  (let [{:keys [w h]} (window state)]
    {:window-w w :window-h h :view-w (max 0 (- w layout/sidebar-w))}))

(defn initial-state
  "The first state: the last view on restart, else waiting for :display."
  [path restart? pr]
  (if restart?
    (document/restart-state path pr)
    (document/waiting-state path (overlay/metrics-root path) (when-not (= :local pr) pr))))

(defn- done
  ([state] {:state state :reply nil})
  ([state reply] {:state state :reply reply}))

(defn- root-of [state]
  (overlay/metrics-root (:path state)))

(defn element-target [state sel]
  (let [id (:id sel)
        c (when id (hit/class-by-id (:scene state) id))
        component? (boolean
                     (or (events/layer-id sel)
                         (:dummy? c)
                         (seq (:contents c))
                         (= :package (:kind sel))))]
    (cond-> {:id id :kind (if component? :component :class)}
      (:ns c) (assoc :ns (:ns c))
      (:proposal-id state) (assoc :proposal-id (:proposal-id state)))))

(defn- open-menu [state items]
  (let [id (inc (or (get-in state [:menu :id]) 0))]
    (done (assoc state :menu {:id id :items items})
          {:menu {:id id
                  :items (mapv #(select-keys % [:label :prompt :default]) items)}})))

(defn element-menu [state sel]
  (let [target (element-target state sel)]
    (open-menu state
               (mapv (fn [[label op]] {:label label :agent op :target target})
                     [["Refresh CRAP" :refresh-crap]
                      ["Refresh Mutation" :refresh-mutate]
                      ["Refresh All Mutation" :refresh-mutate-all]
                      ["Omit" :omit]]))))

(defn proposal-menu [state id]
  (let [pname (:name (policy/proposal-by-id (:doc state) id))]
    (open-menu state
               [{:label "Rename" :rename id :prompt "Rename proposal" :default (str pname)}
                {:label "Delete" :delete id}])))

(defn- run-menu-item [state item value]
  (cond
    (:agent item) (do (companion/request-agent! (root-of state) (:agent item) {:target (:target item)})
                      state)
    (:rename item) (let [n (some-> value str/trim)]
                     (if (seq n) (events/rename-proposal state (:rename item) n) state))
    (:delete item) (events/delete-proposal state (:delete item))
    :else state))

(defn on-menu
  "The page chose item `index` of menu `id`, with `value` from its prompt."
  [state id index value]
  (let [menu (:menu state)
        item (when (= id (:id menu)) (get (:items menu) index))]
    (done (cond-> (dissoc state :menu)
            item (run-menu-item item value)))))

(defn regen-press [state]
  (let [{:keys [woke?]} (companion/request-regen! (root-of state))]
    (assoc state :mail-status (if woke?
                                "Regen requested."
                                "Regen queued; Claude session not attached."))))

(defn- sidebar-press [state right? x y w]
  (let [hit (events/inspector-hit state x y w)]
    (cond
      (and right? (= :proposal (:kind hit))) (proposal-menu state (:id hit))
      hit (let [next (events/on-inspector-press state hit)]
            (when (#{:real-diagram :proposal :new-proposal} (:kind hit))
              (companion/mail-context! next))
            (done next))
      :else (done state))))

(defn- element-menu? [right? sel]
  (and right?
       (or (#{:class :child} (:kind sel))
           (events/layer-id sel))))

(defn- card-hit? [sel n]
  (or (#{:port :child} (:kind sel))
      (and (>= n 2) (= :class (:kind sel)))))

(defn- pins? [right? sel]
  (and (not right?) (#{:edge :dep} (:kind sel)) (seq (:deps sel))))

(defn- canvas-press [state right? x y n]
  (let [state (events/on-press (dissoc state :pinned) x y)
        sel (:selected state)]
    (cond
      (element-menu? right? sel) (element-menu state sel)
      (pins? right? sel) (done (assoc state :pinned {:hit sel :pointer [x y]}))
      (and (>= n 2) (events/layer-id sel)) (done (events/drill state (events/layer-id sel)))
      (card-hit? sel n) (done (events/select-class state (:id sel)))
      :else (done state))))

(defn on-move [state x y]
  (done (events/on-move state x y)))

(defn on-wheel
  "Pan by `amount` wheel notches; across when `horizontal?`."
  [state amount horizontal?]
  (done (events/on-scroll state amount (assoc (dims state) :horizontal? horizontal?))))

(def ^:private key-names
  {"ArrowLeft" :left "ArrowRight" :right "ArrowUp" :up "ArrowDown" :down "Escape" :esc})

(defn key-of
  "Browser `KeyboardEvent.key` as the keyword events/on-key reads."
  [k]
  (or (key-names k)
      (when (= 1 (count (str k))) (keyword (str/lower-case k)))
      :unknown-key))

(defn on-key [state k control?]
  (let [kw (key-of k)]
    (done
      (cond
        (and (= :esc kw) (:pinned state) (not control?)) (dissoc state :pinned)
        (and (= :esc kw) (:detail-id state) (not control?)) (events/close-detail state)
        :else (events/on-key state kw (assoc (dims state) :control? control?))))))

(defn on-resize [state w h]
  (done (assoc state :window {:w (max 320 w) :h (max 240 h)})))

(defn card-model [state]
  (when-let [id (:detail-id state)]
    (detail/model (events/card-scene state) id)))

(defn with-branch-diff
  "`ident` plus the branch's diff of its file, when the file changed or a
  pull request is under review."
  [state ident]
  (if-let [d (branch-diff/member-diff "." (:branch state) (:file ident))]
    (assoc ident :diff d)
    ident))

(defn- source-reply [state source-impl ident]
  (done state
        (when-let [page (source-window/member-page source-impl (with-branch-diff state ident))]
          {:source page})))

(defn- class-of [state id]
  (some #(when (= id (:id %)) %) (get-in state [:doc :classes])))

(defn dep-color
  "CSS color of the arrow a pair rides: violating, changed, or plain."
  [dep]
  (palette/css (cond
                 (:violating dep) palette/violation
                 (:changed dep) (palette/change-ink (:changed dep))
                 :else palette/muted)))

(defn dep-ident
  "Source ident for a class pair: the `from` class's file (read at the PR
  head when one is under review) with each call site underlined in the
  arrow's color, scrolled to `site`, else the first site, else the first
  line that refers to the `to` class. Nil without a file."
  ([state dep] (dep-ident state dep nil))
  ([state {:keys [from to sites] :as dep} site]
   (when-let [c (class-of state from)]
     (when (:file c)
       (let [ident (with-branch-diff state (cond-> {:ns (:ns c) :file (:file c)}
                                             (:lang c) (assoc :lang (:lang c))))
             text (or (get-in ident [:diff :text])
                      (let [f (io/file (:file c))] (when (.isFile f) (slurp f))))
             line (or (:line site)
                      (:line (first sites))
                      (source/reference-line text (or (class-of state to) {:id to})))
             lines (if (seq sites) (set (map :line sites)) (when line #{line}))]
         (cond-> ident
           line (assoc :line line)
           (seq lines) (assoc :marks {:lines lines :color (dep-color dep)})))))))

(defn- pinned-press
  "A press while a pair list is pinned: a pair row opens its source; a
  press elsewhere in the list keeps it; outside, nil."
  [state source-impl x y]
  (let [{:keys [hit pointer]} (:pinned state)
        layout (svg/popup-layout (:deps hit) pointer (window state) true)]
    (cond
      (svg/popup-row-at layout x y)
      (let [row (svg/popup-row-at layout x y)]
        (if-let [ident (dep-ident state (:dep row) (:site row))]
          (source-reply state source-impl ident)
          (done state)))
      (geom/inside? (:box layout) x y) (done state)
      :else nil)))

(defn on-press
  "Mouse-down at window `x`,`y`. `right?` for the context button, `n`
  clicks."
  ([state x y right? n] (on-press state nil x y right? n))
  ([state source-impl x y right? n]
   (let [{:keys [w h]} (window state)
         state (dissoc state :menu)]
     (or (when (and (:pinned state) (not right?))
           (pinned-press state source-impl x y))
         (cond
           (events/regen-hit? x y w h) (done (regen-press state))
           (>= x (- w layout/sidebar-w)) (sidebar-press state right? x y w)
           :else (canvas-press state right? x y n))))))

(defn on-card-press
  "Click at content `y` on the class card: a member or the module opens
  its source; a relationship opens that class's card."
  [state source-impl y]
  (if-let [model (card-model state)]
    (let [rows (detail/rows model)]
      (cond
        (detail/module-at rows y) (source-reply state source-impl (detail/member-ident model))
        (detail/member-at rows y) (source-reply state source-impl
                                                (detail/member-ident model (detail/member-at rows y)))
        (detail/rel-at rows y) (done (events/select-class state (detail/rel-at rows y)))
        :else (done state)))
    (done state)))

(defn on-card-close [state]
  (done (events/close-detail state)))

(defn keep-fit
  "Refit `state` to its window when the window or the diagram's size
  changed since the last fit, unless it was zoomed by hand."
  [state]
  (let [fit-key [(get-in state [:scene :size]) (window state)]]
    (if (or (:zoomed state) (nil? (:scene state)) (= fit-key (:fit-key state)))
      state
      (assoc (events/fit-view state (dims state)) :fit-key fit-key))))

(defn tick
  "Reload the diagram if its files changed and read the companion's mail.
  The page's window size survives a newly loaded diagram, and the view
  refits when the window or diagram changed."
  [state]
  (let [w (:window state)]
    (cond-> (-> state document/maybe-reload document/poll-mail)
      w (assoc :window w)
      true keep-fit)))

(defn views
  "What the page shows: the window SVG, and the card with its title."
  [state]
  (let [model (card-model state)]
    {:frame (markup/html (svg/frame state (window state)))
     :card (if model
             {:title (or (get-in model [:class :name]) "Class")
              :svg (markup/html (svg/card model))}
             {:title "" :svg ""})}))

(defn- number-of [v]
  (try (Double/parseDouble (str v)) (catch Exception _ 0.0)))

(defn- int-of [v]
  (long (number-of v)))

(defn- yes? [v]
  (contains? #{"1" "true"} (str v)))

(defn handle
  "Apply one page event (string params from the browser) to `state`."
  [state source-impl {:strs [type x y w h button count amount horizontal key ctrl id index value]}]
  (case type
    "resize" (on-resize state (int-of w) (int-of h))
    "move" (on-move state (number-of x) (number-of y))
    "press" (on-press state source-impl (number-of x) (number-of y) (= "2" (str button)) (max 1 (int-of count)))
    "wheel" (on-wheel state (number-of amount) (yes? horizontal))
    "key" (on-key state key (yes? ctrl))
    "card-press" (on-card-press state source-impl (number-of y))
    "card-close" (on-card-close state)
    "menu" (on-menu state (int-of id) (int-of index) value)
    (done state)))
