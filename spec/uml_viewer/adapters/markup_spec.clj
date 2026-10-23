(ns uml-viewer.adapters.markup-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.adapters.markup :as markup]))

(describe "markup"
  (it "writes elements, attributes, and children"
    (should= "<g id=\"a\"><rect width=\"3\"/>hi</g>"
             (markup/html [:g {:id "a"} [:rect {:width 3}] "hi"]))
    (should= "<text>x</text>" (markup/html [:text "x"]))
    (should= "<circle/>" (markup/html [:circle])))

  (it "drops nil attributes and nil children, and flattens seqs"
    (should= "<g><a/><b/></g>"
             (markup/html [:g {:fill nil} nil (list [:a] (list nil [:b]))])))

  (it "escapes text and attribute values"
    (should= "<t v=\"&quot;&lt;&amp;&#10;\">a&lt;b&gt;</t>"
             (markup/html [:t {:v "\"<&\n"} "a<b>"])))

  (it "names keyword values and keeps numbers short"
    (should= "<g a=\"start\" b=\"2\" c=\"2.50\" d=\"-1\"/>"
             (markup/html [:g {:a :start :b 2.0 :c 2.5 :d -1.001}]))
    (should= "3" (markup/num-str 2.999))
    (should= "0.01" (markup/num-str 0.006))))

(describe "json"
  (it "writes values"
    (should= "null" (markup/json nil))
    (should= "true" (markup/json true))
    (should= "1.5" (markup/json 1.5))
    (should= "\"k\"" (markup/json :k))
    (should= "[1,\"a\"]" (markup/json [1 "a"]))
    (should= "[2]" (markup/json (list 2)))
    (should= "{\"a\":{\"b\":[]}}" (markup/json {:a {"b" []}})))

  (it "escapes strings, including a closing script tag"
    (should= "\"q\\\"\\\\\\n\\r\\t\\u003c/script>\""
             (markup/json "q\"\\\n\r\t</script>"))
    (should= "\"#{}\"" (markup/json #{}))))
