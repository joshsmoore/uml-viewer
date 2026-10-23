(ns uml-viewer.adapters.viewer-spec
  (:require [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.source-window :as source-window]
            [uml-viewer.adapters.viewer :as viewer]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.application.document :as document]
            [uml-viewer.application.events :as events]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.engine.layout :as layout]))

(defn- scene []
  (compose/compile-diagram
    (ir/normalize
      {:packages
       [{:id :p :label "P"
         :classes [{:id :a :name "A" :ns "demo.a"
                    :ops [{:name "go" :args ["x"] :returns "void"}]}
                   {:id :b :name "B"}]}]
       :edges [{:from :a :to :b :kind :association}]})))

(defn- state []
  {:scene (scene) :selected nil :hover nil :cam-x 0 :cam-y 0 :path nil :mtime 0})

(defn- center [s id]
  (let [c (first (filter #(= id (:id %)) (:classes (:scene s))))]
    [(geom/cx (:rect c)) (geom/cy (:rect c))]))

(defn- press [s x y & {:keys [button count] :or {button 0 count 1}}]
  (viewer/handle s :src {"type" "press" "x" (str x) "y" (str y)
                         "button" (str button) "count" (str count)}))

(def ^:private w 1500)
(def ^:private h 920)

(defn- inspector-xy [r]
  [(inc (:x r)) (inc (:y r))])

(describe "viewer window"
  (it "defaults the window and derives scroll dims from it"
    (should= {:w 1500 :h 920} (viewer/window {}))
    (should= {:window-w 1000 :window-h 500 :view-w 720}
             (viewer/dims {:window {:w 1000 :h 500}})))

  (it "resizes, but not below a usable minimum"
    (should= {:w 900 :h 700} (:window (:state (viewer/handle {} :src {"type" "resize" "w" "900" "h" "700"}))))
    (should= {:w 320 :h 240} (:window (:state (viewer/handle {} :src {"type" "resize" "w" "1" "h" "x"})))))

  (it "starts waiting, or restores the last view on restart"
    (with-redefs [document/restart-state (fn [path pr] [:restart path pr])
                  document/waiting-state (fn [path _ pr] [:waiting path pr])]
      (should= [:restart "d.edn" 7] (viewer/initial-state "d.edn" true 7))
      (should= [:waiting "d.edn" 7] (viewer/initial-state "d.edn" false 7))
      (should= [:waiting "d.edn" nil] (viewer/initial-state "d.edn" false :local))))

  (it "ignores an unknown event"
    (should= {:state {:a 1} :reply nil} (viewer/handle {:a 1} :src {"type" "nope"}))))

(describe "viewer pointer"
  (it "hovers what is under the pointer"
    (let [[x y] (center (state) :a)
          s (:state (viewer/handle (state) :src {"type" "move" "x" (str x) "y" (str y)}))]
      (should= :a (get-in s [:hover :id]))
      (should= [(double x) (double y)] (:pointer s))))

  (it "selects on one click and opens the card on a double click"
    (let [[x y] (center (state) :a)
          one (:state (press (state) x y))
          two (:state (press (state) x y :count 2))]
      (should= {:kind :class :id :a} (select-keys (:selected one) [:kind :id]))
      (should-be-nil (:detail-id one))
      (should= :a (:detail-id two))))

  (it "drills a layer on double click"
    (with-redefs [events/on-press (fn [s _ _] (assoc s :selected {:kind :child :parent :demo.x}))
                  events/drill (fn [s id] (assoc s :drilled id))]
      (should= :demo.x (:drilled (:state (press (state) 10 100 :count 2))))))

  (it "opens a card from a port or child row on one click"
    (with-redefs [events/on-press (fn [s _ _] (assoc s :selected {:kind :port :id :b}))]
      (should= :b (:detail-id (:state (press (state) 10 100)))))
    (with-redefs [events/on-press (fn [s _ _] (assoc s :selected {:kind :edge}))]
      (should-be-nil (:detail-id (:state (press (state) 10 100))))))

  (it "pans with the wheel"
    (let [s (assoc-in (state) [:scene :size] {:w 5000 :h 5000})]
      (should= 96.0 (:cam-y (:state (viewer/handle s :src {"type" "wheel" "amount" "2"}))))
      (should= 48.0 (:cam-x (:state (viewer/handle s :src {"type" "wheel" "amount" "1" "horizontal" "1"}))))
      (should= 0 (:cam-x (:state (viewer/handle s :src {"type" "wheel" "amount" "1" "horizontal" "0"})))))))

(describe "viewer keys"
  (it "maps browser key names onto viewer keys"
    (should= :left (viewer/key-of "ArrowLeft"))
    (should= :esc (viewer/key-of "Escape"))
    (should= :r (viewer/key-of "R"))
    (should= :+ (viewer/key-of "+"))
    (should= :unknown-key (viewer/key-of "Shift")))

  (it "closes an open card on Escape before going back"
    (let [s (assoc (state) :detail-id :a :selected {:kind :class :id :a})]
      (should-be-nil (:detail-id (:state (viewer/handle s :src {"type" "key" "key" "Escape"}))))
      (should= {:kind :class :id :a} (:selected (:state (viewer/handle s :src {"type" "key" "key" "Escape"}))))
      (should-be-nil (:selected (:state (viewer/handle (dissoc s :detail-id) :src {"type" "key" "key" "Escape"}))))))

  (it "zooms with control and toggles with letters"
    (should= 1.1 (:zoom (:state (viewer/handle (state) :src {"type" "key" "key" "=" "ctrl" "1"}))))
    (should (:hide-changes (:state (viewer/handle (state) :src {"type" "key" "key" "b"}))))))

(describe "viewer inspector"
  (it "requests a regen and says whether Claude woke"
    (let [[x y] (inspector-xy (layout/regen-button w h))]
      (with-redefs [companion/request-regen! (fn [_] {:woke? true})]
        (should= "Regen requested." (:mail-status (:state (press (state) x y)))))
      (with-redefs [companion/request-regen! (fn [_] {:woke? false})]
        (should= "Regen queued; Claude session not attached." (:mail-status (:state (press (state) x y)))))))

  (it "shows the real diagram and tells the companion"
    (let [mailed (atom nil)
          [x y] (inspector-xy (layout/real-diagram-rect w))]
      (with-redefs [companion/mail-context! (fn [s] (reset! mailed s) s)
                    events/on-inspector-press (fn [s hit] (assoc s :hit (:kind hit)))]
        (should= :real-diagram (:hit (:state (press (state) x y))))
        (should= :real-diagram (:hit @mailed)))))

  (it "ignores a click on empty inspector space"
    (should= (dissoc (state) :menu) (:state (press (state) (- w 10) 600)))))

(def ^:private proposal-doc {:proposals [{:id :p1 :name "One" :layers []}]})

(defn- prop-xy [] (inspector-xy (layout/proposal-row-rect w 0)))

(describe "viewer menus"
    (it "offers rename and delete on a right-clicked proposal"
      (let [{:keys [state reply]} (press (assoc (state) :doc proposal-doc) (first (prop-xy)) (second (prop-xy)) :button 2)]
        (should= [{:label "Rename" :prompt "Rename proposal" :default "One"} {:label "Delete"}]
                 (get-in reply [:menu :items]))
        (should= 1 (get-in reply [:menu :id]))
        (should= 1 (get-in state [:menu :id]))))

    (it "renames with the prompt value and deletes, ignoring a blank name"
      (with-redefs [events/rename-proposal (fn [s id n] (assoc s :renamed [id n]))
                    events/delete-proposal (fn [s id] (assoc s :deleted id))]
        (let [s (:state (press (assoc (state) :doc proposal-doc) (first (prop-xy)) (second (prop-xy)) :button 2))
              pick (fn [index value]
                     (:state (viewer/handle s :src {"type" "menu" "id" "1" "index" (str index) "value" value})))]
          (should= [:p1 "Two"] (:renamed (pick 0 "  Two ")))
          (should-be-nil (:renamed (pick 0 "  ")))
          (should= :p1 (:deleted (pick 1 "")))
          (should-be-nil (:menu (pick 1 ""))))))

    (it "mails the companion from a right-clicked class"
      (let [[x y] (center (state) :a)
            sent (atom nil)
            {:keys [state reply]} (press (state) x y :button 2)]
        (should= ["Refresh CRAP" "Refresh Mutation" "Refresh All Mutation" "Omit"]
                 (map :label (get-in reply [:menu :items])))
        (with-redefs [companion/request-agent! (fn [_ op extra] (reset! sent [op extra]))]
          (viewer/handle state :src {"type" "menu" "id" "1" "index" "3"})
          (should= [:omit {:target {:id :a :kind :class :ns "demo.a"}}] @sent))))

    (it "ignores a stale menu"
      (let [s (assoc (state) :menu {:id 2 :items [{:delete :p1}]})]
        (should= (dissoc s :menu) (:state (viewer/handle s :src {"type" "menu" "id" "1" "index" "0"})))))

    (it "names a component target for a layer, dummy, or package"
      (should= {:id :x :kind :component} (viewer/element-target (state) {:kind :package :id :x}))
      (should= {:id :a :kind :class :ns "demo.a" :proposal-id :p}
               (viewer/element-target (assoc (state) :proposal-id :p) {:kind :class :id :a}))))

(defn- card-state [] (assoc (state) :detail-id :a))

(defn- y-of [pred]
  (:y (first (filter pred (detail/rows (viewer/card-model (card-state)))))))

(describe "viewer card"
    (it "opens a member's source"
      (with-redefs [source-window/member-page (fn [impl ident] {:impl impl :ident ident})]
        (let [reply (:reply (viewer/handle (card-state) :src {"type" "card-press" "y" (str (inc (y-of :op-name)))}))]
          (should= :src (get-in reply [:source :impl]))
          (should= "go" (get-in reply [:source :ident :name])))))

    (it "opens the module's source"
      (with-redefs [source-window/member-page (fn [_ ident] {:ident ident})
                    detail/module-at (fn [_ y] (= y 3.0))]
        (should-be-nil (get-in (:reply (viewer/handle (card-state) :src {"type" "card-press" "y" "3"}))
                               [:source :ident :name]))))

    (it "replies nothing when the source cannot be found"
      (with-redefs [source-window/member-page (fn [_ _] nil)]
        (should-be-nil (:reply (viewer/handle (card-state) :src {"type" "card-press" "y" (str (inc (y-of :op-name)))})))))

    (it "follows a relationship to its card"
      (let [rel-y (y-of #(= :rel (:kind %)))]
        (should= :b (:detail-id (:state (viewer/handle (card-state) :src {"type" "card-press" "y" (str (inc rel-y))}))))))

    (it "does nothing off a row or without a card"
      (should= (card-state) (:state (viewer/handle (card-state) :src {"type" "card-press" "y" "-50"})))
      (should= (state) (:state (viewer/handle (state) :src {"type" "card-press" "y" "5"}))))

    (it "closes"
      (should-be-nil (:detail-id (:state (viewer/handle (card-state) :src {"type" "card-close"})))))

    (it "attaches the branch diff of a changed file"
      (with-redefs [uml-viewer.application.branch-diff/member-diff (fn [_ _ f] (when (= "a.clj" f) {:hunks []}))]
        (should= {:file "a.clj" :diff {:hunks []}} (viewer/with-branch-diff (card-state) {:file "a.clj"}))
        (should= {:file "b.clj"} (viewer/with-branch-diff (card-state) {:file "b.clj"})))))

(describe "viewer views"
  (it "renders the window and an empty card"
    (let [{:keys [frame card]} (viewer/views (state))]
      (should (str/starts-with? frame "<svg"))
      (should= {:title "" :svg ""} card)))

  (it "renders the open card with its class name"
    (let [{:keys [card]} (viewer/views (assoc (state) :detail-id :a))]
      (should= "A" (:title card))
      (should (str/starts-with? (:svg card) "<svg"))))

  (it "reloads and reads mail on a tick"
    (with-redefs [document/maybe-reload #(conj % :reloaded)
                  document/poll-mail #(conj % :polled)]
      (should= [:reloaded :polled] (viewer/tick []))))

  (it "refits on a tick when the window or diagram changed, unless zoomed by hand"
    (with-redefs [document/maybe-reload identity
                  document/poll-mail identity]
      (let [s {:scene {:size {:w 2000 :h 400 :min-x 0 :min-y 0}} :window {:w 1280 :h 900}}
            fitted (viewer/tick s)]
        (should= 0.5 (:zoom fitted))
        (should= fitted (viewer/tick fitted))
        (should= 0.25 (:zoom (viewer/tick (assoc fitted :window {:w 780 :h 900}))))
        (should= 3.0 (:zoom (viewer/tick (assoc s :zoomed true :zoom 3.0))))
        (should-be-nil (:zoom (viewer/tick {:window {:w 1280 :h 900}}))))))

  (it "keeps the window size when mail loads a new diagram"
    (with-redefs [document/maybe-reload identity
                  document/poll-mail (fn [_] {:path "new.edn"})]
      (should= {:path "new.edn" :window {:w 1800 :h 1100}}
               (viewer/tick {:path "old.edn" :window {:w 1800 :h 1100}}))
      (should= {:path "new.edn"} (viewer/tick {:path "old.edn"})))))
