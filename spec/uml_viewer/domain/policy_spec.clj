(ns uml-viewer.domain.policy-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.domain.policy :as policy]))

(def graph
  {:classes [{:id :ir :name "Ir" :ns "uml-viewer.domain.ir"}
             {:id :layout :name "Layout" :ns "uml-viewer.engine.layout"}
             {:id :events :name "Events" :ns "uml-viewer.application.events"}
             {:id :compose :name "Compose" :ns "uml-viewer.engine.compose"}
             {:id :source :name "Source" :ns "uml-viewer.source"
              :stereotype :interface}
             {:id :source.clojure :name "SourceClojure"
              :ns "uml-viewer.source.clojure"}
             {:id :orphan :name "Orphan" :ns "uml-viewer.orphan"}
             {:id :quil.core :name "quil.core" :ns "quil.core" :foreign true}
             {:id :quil.middleware :name "quil.middleware" :ns "quil.middleware"
              :foreign true}
             {:id :clojure.string :name "clojure.string" :ns "clojure.string"
              :foreign true}]
   :edges [{:from :layout :to :ir :kind :dependency}
           {:from :layout :to :compose :kind :dependency}
           {:from :compose :to :ir :kind :dependency}
           {:from :events :to :layout :kind :dependency}
           {:from :source.clojure :to :source :kind :dependency}
           {:from :source.clojure :to :source :kind :implements}
           {:from :layout :to :quil.core :kind :dependency}
           {:from :layout :to :quil.middleware :kind :dependency}
           {:from :events :to :clojure.string :kind :dependency}]})

(def policy
  {:title "Demo"
   :foreign [:quil]
   :packages
   [{:id :domain :label "Domain" :nses [:ir :source :source.clojure]}
    {:id :engine :label "Engine" :nses [:layout]}
    {:id :app :label "Application" :nses [:events]}]
   :diagrams
   [{:title "Layers" :view :overview :hide-members true :direction :tb}
    {:title "Engine" :package :engine
     :edge-kinds {[:layout :ir] :association}}]})

(describe "policy"
  (it "lists scanned classes that no package claims"
    (let [extra (policy/unassigned policy graph)]
      (should= [:compose :orphan] (mapv :id extra))))

  (it "builds an overview with home packages and Unassigned"
    (let [doc (policy/apply-policy policy graph)
          layers (first (:diagrams doc))
          ids (fn [pkg] (map :id (:classes pkg)))
          by-id (into {} (map (juxt :id identity) (:packages layers)))]
      (should= "Demo" (:title doc))
      (should= :tb (:direction layers))
      (should= [:ir :source :source.clojure] (ids (by-id :domain)))
      (should (every? :hide-members (mapcat :classes (:packages layers))))
      (should= :interface (get-in by-id [:domain :classes 1 :stereotype]))
      (should= [:compose :orphan] (ids (by-id :unassigned)))
      (should (some #(= {:from :source.clojure :to :source :kind :implements} %)
                    (:edges layers)))
      (should-not (some #(= :dependency (:kind %))
                        (filter #(= :source.clojure (:from %))
                                (:edges layers))))))

  (it "adds one-hop stubs on a package diagram and honors edge-kinds"
    (let [doc (policy/apply-policy policy graph)
          engine (second (:diagrams doc))
          classes (:classes (first (:packages engine)))
          by-id (into {} (map (juxt :id identity) classes))]
      (should= :lr (:direction engine))
      (should= :layout (first (map :id classes)))
      (should (contains? by-id :ir))
      (should-not (contains? by-id :events))
      (should-be-nil (:hide-members (by-id :layout)))
      (should (:hide-members (by-id :ir)))
      (should= :association
               (:kind (first (filter #(= :ir (:to %)) (:edges engine)))))
      (should (contains? by-id :compose))
      (should-not (some #(and (= :compose (:from %)) (= :ir (:to %)))
                        (:edges engine)))))

  (it "collapses listed foreign prefixes and drops the rest"
    (let [g (policy/collapse-graph policy graph)
          by-id (into {} (map (juxt :id identity) (:classes g)))
          edges (set (map (juxt :from :to) (:edges g)))]
      (should (:foreign (by-id :quil)))
      (should= "quil" (:name (by-id :quil)))
      (should-not (contains? by-id :quil.core))
      (should-not (contains? by-id :clojure.string))
      (should (contains? edges [:layout :quil]))
      (should-not (some #(= :clojure.string (second %)) edges))))

  (it "places foreign ovals outside packages on overview and package diagrams"
    (let [doc (policy/apply-policy policy graph)
          layers (first (:diagrams doc))
          engine (second (:diagrams doc))]
      (should= [{:id :quil :name "quil" :shape :oval}] (:foreign layers))
      (should (some #(and (= :layout (:from %)) (= :quil (:to %))) (:edges layers)))
      (should-not (some #(= :quil (:id %))
                        (mapcat :classes (:packages layers))))
      (should= [{:id :quil :name "quil" :shape :oval}] (:foreign engine))
      (should-not (some #(= :quil (:id %))
                        (:classes (first (:packages engine)))))))

  (it "throws when a diagram names a missing package"
    (should-throw
      (policy/apply-policy
        (assoc policy :diagrams [{:title "X" :package :nope}])
        graph)))

  (it "keeps class :ns so overlay can key metrics on another project"
    (let [doc (policy/apply-policy policy graph)
          layers (first (:diagrams doc))
          ir (first (filter #(= :ir (:id %))
                            (mapcat :classes (:packages layers))))]
      (should= "uml-viewer.domain.ir" (:ns ir))))

  (it "treats an omitted id as itself and its descendants"
    (should (policy/omitted-id? :engine [:engine]))
    (should (policy/omitted-id? :engine.layout [:engine]))
    (should-not (policy/omitted-id? :layout [:engine])))

  (it "normalizes :proposal layers and uses them for ranks when :levels is omitted"
    (let [p {:proposal [{:id :playfield :label "Playfield" :nses [:entities :world]}
                        {:id :hosts :label "Hosts" :nses [:jvm]}]}
          n (policy/normalize-proposal (:proposal p))]
      (should= policy/proposal-notice (:notice n))
      (should= [:playfield :hosts] (mapv :id (:layers n)))
      (should= {:entities 0 :world 0 :jvm 1} (policy/level-ranks p))
      (should (policy/violating-dependency?
                {:from :world :to :jvm :kind :dependency}
                (policy/level-ranks p)))))

  (it "copies :proposal onto a hierarchical IR"
    (let [p {:title "T" :hierarchical true
             :proposal [{:id :core :label "Facade" :nses [:ir]}]
             :order [:ir]}
          doc (policy/apply-policy p graph)]
      (should= "Facade" (get-in doc [:proposal :layers 0 :label]))
      (should= [:ir] (get-in doc [:proposal :layers 0 :nses]))
      (should= [[:ir]] (:levels doc))))

  (it "copies :base-branch onto a hierarchical IR only when set"
    (let [p {:title "T" :hierarchical true :order [:ir]}]
      (should= "develop" (:base-branch (policy/apply-policy (assoc p :base-branch "develop") graph)))
      (should-not-contain :base-branch (policy/apply-policy p graph))))

  (it "copies named :proposals and stamps :level from :levels"
    (let [p {:title "T" :hierarchical true
             :levels [[:ir] [:source]]
             :proposals [{:id :ccp :name "2026-09-18 10:30:00"
                          :layers [{:id :kernel :label "Kernel" :nses [:ir]}]}]
             :order [:ir :source]}
          doc (policy/apply-policy p graph)
          ir (first (filter #(= :ir (:id %)) (:classes doc)))]
      (should= :ccp (get-in doc [:proposals 0 :id]))
      (should= "2026-09-18 10:30:00" (get-in doc [:proposals 0 :name]))
      (should= 0 (:level ir))))

  (it "marks a dependency from a higher-level segment to a lower-level one"
    (let [ranks (policy/level-ranks
                  {:levels [[:domain :source] [:engine] [:application]]})
          bad {:from :domain.ir :to :application.events :kind :dependency}
          ok {:from :application.events :to :domain.ir :kind :dependency}
          same {:from :domain.ir :to :source :kind :dependency}
          assoc {:from :domain.ir :to :application.events :kind :association}
          impl {:from :source.clojure :to :source :kind :implements}]
      (should= {:domain 0 :source 0 :engine 1 :application 2} ranks)
      (should (policy/violating-dependency? bad ranks))
      (should-not (policy/violating-dependency? ok ranks))
      (should-not (policy/violating-dependency? same ranks))
      (should-not (policy/violating-dependency? assoc ranks))
      (should-not (policy/violating-dependency? impl ranks))
      (should (:violating (first (policy/mark-violations [bad] ranks))))
      (should-be-nil (:violating (first (policy/mark-violations [ok] ranks))))))

  (it "flattens nested group maps in layer nses for ranks"
    (let [layers [{:id :jvm
                   :nses [:jvm.cli
                          {:id :quil-swing :label "Quil/Swing"
                           :nses [:jvm.sketch :jvm.window]}]}]
          ranks (policy/ranks-from-layers layers)]
      (should= {:jvm.cli 0 :jvm.sketch 0 :jvm.window 0} ranks)
      (should= [:jvm.cli :jvm.sketch :jvm.window]
               (policy/nse-ids (:nses (first layers))))))

  (it "restamps class levels and violating flags from proposal layers"
    (let [layers [{:id :engine :nses [:layout]}
                  {:id :kernel :nses [:ir]}]
          ranks (policy/ranks-from-layers layers)
          classes [{:id :ir :name "Ir" :level 0}
                   {:id :layout :name "Layout" :level 1}]
          edges [{:from :ir :to :layout :kind :dependency :violating true}
                 {:from :layout :to :ir :kind :dependency}]
          stamped (policy/restamp-ranks classes edges ranks)]
      (should= {:layout 0 :ir 1} ranks)
      (should= 1 (:level (first (filter #(= :ir (:id %)) (:classes stamped)))))
      (should= 0 (:level (first (filter #(= :layout (:id %)) (:classes stamped)))))
      (should-be-nil (:violating (first (filter #(= :ir (:from %)) (:edges stamped)))))
      (should (:violating (first (filter #(= :layout (:from %)) (:edges stamped)))))))

  (it "keeps violating on a collapsed dependency and drops it for association"
    (let [es [{:from :a :to :b :kind :dependency :violating true}
              {:from :a :to :b :kind :dependency}]
          merged (first (policy/merge-edges es))
          assoc (first (policy/apply-edge-kinds
                         [merged] {[:a :b] :association} []))]
      (should (:violating merged))
      (should= :association (:kind assoc))
      (should-be-nil (:violating assoc)))))

(describe "class pairs carried by an arrow"
  (it "re-ends an edge and remembers the pair it stood for"
    (let [e (policy/re-end {:from :a.x :to :b.y :kind :dependency :violating true} :a :b)]
      (should= [:a :b] [(:from e) (:to e)])
      (should= [{:from :a.x :to :b.y :kind :dependency :violating true}] (:leaves e))
      (should= (:leaves e) (:leaves (policy/re-end e :top :other)))))

  (it "merges the pairs of every bundled edge"
    (let [es [(policy/re-end {:from :a.x :to :b.y :kind :dependency} :a :b)
              (policy/re-end {:from :a.z :to :b.y :kind :implements} :a :b)]
          merged (first (policy/merge-edges es))]
      (should= :implements (:kind merged))
      (should= #{:a.x :a.z} (set (map :from (:leaves merged))))))

  (it "re-stamps dependency pairs with the arrow's verdict"
    (let [e (policy/re-end {:from :a.x :to :b.y :kind :dependency} :a :b)
          ranks {:a 0 :b 1}
          bad (first (policy/mark-violations [e] ranks))
          ok (first (policy/mark-violations [e] {:a 1 :b 0}))]
      (should (:violating (first (:leaves bad))))
      (should-not (:violating (first (:leaves ok))))))

  (it "clears pair violations when the arrow is remapped to association"
    (let [e (policy/re-end {:from :a.x :to :b.y :kind :dependency :violating true} :a :b)
          out (first (policy/apply-edge-kinds [e] {[:a :b] :association} []))]
      (should-not (some :violating (:leaves out)))))

  (it "judges dependency pairs by the ends even when implements won the arrow"
    (let [e (assoc (policy/re-end {:from :a.x :to :b.y :kind :implements} :a :b)
              :leaves [{:from :a.x :to :b.y :kind :implements :violating false}
                       {:from :a.z :to :b.y :kind :dependency :violating false}])
          out (first (policy/mark-violations [e] {:a 0 :b 1}))]
      (should-not (:violating out))
      (should= [false true] (mapv :violating (:leaves out))))))

(describe "hidden relationship kinds"
  (with doc {:edge-kinds {[:a :c] :association}
             :edges [{:from :a :to :b :kind :dependency}
                     {:from :a :to :c :kind :dependency}
                     {:from :b :to :c :kind :db-association}]})

  (it "keeps every edge when nothing is hidden"
    (should= @doc (policy/hide-kinds @doc #{}))
    (should= @doc (policy/hide-kinds @doc nil)))

  (it "drops edges by their kind after policy remaps"
    (should= [[:a :c] [:b :c]]
             (mapv (juxt :from :to) (:edges (policy/hide-kinds @doc #{:dependency}))))
    (should= [[:a :b] [:b :c]]
             (mapv (juxt :from :to) (:edges (policy/hide-kinds @doc #{:association}))))
    (should= [[:a :b] [:a :c]]
             (mapv (juxt :from :to) (:edges (policy/hide-kinds @doc #{:db-association}))))))

(describe "kind rank"
  (it "ranks db associations above associations and below aggregation"
    (should (< (policy/kind-rank :association) (policy/kind-rank :db-association)
               (policy/kind-rank :aggregation)))))

(describe "violations only"
  (it "keeps only the edges that break the dependency rule"
    (should= [{:from :a :to :b :violating true}]
             (:edges (policy/violations-only
                       {:edges [{:from :a :to :b :violating true}
                                {:from :b :to :c}
                                {:from :c :to :a :violating false}]})))))
