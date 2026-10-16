(ns uml-viewer.engine.compose-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.engine.layout :as layout]))

(def ported
  {:title "View"
   :direction :tb
   :packages
     [{:id :view :label "View"
       :classes [{:id :a :name "A"
                  :in-deps [{:id :x :name "X"}]
                  :out-deps [{:id :y :name "Y"} {:id :z :name "Z"}]}
                 {:id :b :name "B"}]}]
   :foreign [{:id :lib :name "lib" :foreign true :shape :oval}]
   :edges [{:from :a :to :b :kind :dependency}
           {:from :a :to :lib :kind :dependency}]})

(describe "compiled scene"
  (it "keeps ports on their class after fitting the scene"
    (let [scene (compose/compile-diagram ported)
          a (first (filter #(= :a (:id %)) (:classes scene)))
          r (:rect a)]
      (should= (- (:y r) layout/port-link layout/port-h)
               (:y (:rect (first (:in-ports a)))))
      (should= (+ (geom/bottom r) layout/port-link-out)
               (:y (:rect (first (:out-ports a))))))))
