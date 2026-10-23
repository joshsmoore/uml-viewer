(ns uml-viewer.python-language.source-python-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.python-language.source-python]
            [uml-viewer.source :as source]))

(describe "python extractor"
  (it "finds a function and opens the file named on the ident"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-py-src-" (System/nanoTime)))
          file (io/file dir "model.py")]
      (io/make-parents file)
      (spit file "class Animal:\n    pass\n\ndef walk(animal):\n    return animal\n")
      (let [found (source/member-source {:lang :python
                                         :ns "app.model"
                                         :file (.getPath file)
                                         :name "walk"})]
        (should= :python (:lang found))
        (should (re-find #"model\.py:4$" (:title found)))
        (should= 4 (:line found))
        (should (re-find #"def walk\(animal\)" (:body found))))))

  (it "returns nil when the member is not in the file"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-py-miss-" (System/nanoTime)))
          file (io/file dir "model.py")]
      (io/make-parents file)
      (spit file "def walk():\n    return 1\n")
      (should-be-nil (source/member-source {:lang :python
                                            :file (.getPath file)
                                            :name "missing"}))))

  (it "opens a module at the top when no member is named"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-py-mod-" (System/nanoTime)))
          file (io/file dir "model.py")]
      (io/make-parents file)
      (spit file "def walk():\n    return 1\n")
      (let [found (source/member-source {:lang :python
                                         :file (.getPath file)
                                         :ns "app.model"})]
        (should= (.getPath file) (:file found))
        (should-be-nil (:line found)))))

  (it "finds Class.method after its class, not an earlier def of the same name"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-py-method-" (System/nanoTime)))
          file (io/file dir "views.py")]
      (io/make-parents file)
      (spit file (str "def create():\n    pass\n\n"
                      "class PartnersView:\n    def create(self, body):\n        pass\n"))
      (let [at #(source/member-source {:lang :python :file (.getPath file) :name %})]
        (should= 5 (:line (at "PartnersView.create")))
        (should= 1 (:line (at "create")))
        (should= 4 (:line (at "PartnersView")))
        (should-be-nil (at "PartnersView.missing"))
        (should-be-nil (at "Missing.create"))))))
