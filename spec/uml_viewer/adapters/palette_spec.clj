(ns uml-viewer.adapters.palette-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.adapters.palette :as palette]))

(describe "red-green grades"
  (it "uses a default fill when the grade is missing"
    (should= [36 52 48] (palette/fill-for nil)))

  (it "is red at 0, gold at 5, green at 10"
    (should= [74 40 24] (palette/fill-for 0))
    (should= [61 58 24] (palette/fill-for 5))
    (should= [30 74 56] (palette/fill-for 10))
    (should= [46 66 40] (palette/fill-for 7.5)))

  (it "strokes the same 0–10 ramp"
    (should= [90 110 100] (palette/stroke-for nil))
    (should= [224 122 74] (palette/stroke-for 0))
    (should= [212 192 90] (palette/stroke-for 5))
    (should= [95 181 138] (palette/stroke-for 10))
    (should= [95 181 138] (palette/stroke-for 20)))

  (it "averages CRAP and mutation grades on a box"
    (let [good {:crap {:mu 8 :sigma 0} :killed 10 :survived 0}
          mixed {:crap {:mu 8 :sigma 0} :killed 8 :survived 2}
          bad {:crap {:mu 20 :sigma 0} :killed 8 :survived 2}]
      (should= 10.0 (palette/grade-of good))
      (should= 5.5 (palette/grade-of mixed))
      (should= 1.0 (palette/grade-of bad))
      (should= 5.5 (palette/grade-of {:crap {:mu 1 :sigma 0}}))
      (should= 5.5 (palette/grade-of {:killed 10 :survived 0}))
      (should= 1.0 (palette/grade-of {}))
      (should= 1.0 (palette/crap-grade-of {}))
      (should= 1.0 (palette/mutation-grade-of {}))
      (should= 1.0 (palette/mutation-grade-of {:killed 0 :survived 0})))))

(describe "theme inks"
  (it "bands ink by coverage, high to low"
    (should= palette/muted (palette/coverage-ink nil))
    (should= [95 181 138] (palette/coverage-ink 0.8))
    (should= palette/gold (palette/coverage-ink 0.5))
    (should= [224 122 74] (palette/coverage-ink 0.49)))

  (it "colors deleted code apart from changed code"
    (should= palette/deleted (palette/change-ink :deleted))
    (should= palette/changed (palette/change-ink :modified))
    (should= palette/changed (palette/change-ink :added)))

  (it "writes a CSS rgb color"
    (should= "rgb(22,28,32)" (palette/css palette/bg))
    (should= "rgb(1,2,3)" (palette/css [1.4 2.0 3]))))
