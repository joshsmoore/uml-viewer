(ns uml-viewer.ruby-language.source-ruby-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.ruby-language.source-ruby]
            [uml-viewer.source :as source]))

(defn- ruby-file [content]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "uml-rb-src-" (System/nanoTime)))
        file (io/file dir "shop.rb")]
    (io/make-parents file)
    (spit file content)
    file))

(describe "ruby extractor"
  (it "finds instance, class, and punctuated methods"
    (let [file (ruby-file (str "class Shop\n"
                               "  def open?\n    true\n  end\n"
                               "  def self.build\n  end\n"
                               "  def open!\n  end\n"
                               "end\n"))
          at (fn [n] (:line (source/member-source {:lang :ruby :file (.getPath file) :name n})))]
      (should= 2 (at "open?"))
      (should= 5 (at "build"))
      (should= 7 (at "open!"))
      (should= :ruby (:lang (source/member-source {:lang :ruby :file (.getPath file) :name "open?"})))))

  (it "finds a nested class or module by its short name"
    (let [file (ruby-file "module Billing\n  class Charge\n  end\nend\nclass Billing::Tax\nend\n")
          at (fn [n] (:line (source/member-source {:lang :ruby :file (.getPath file) :name n})))]
      (should= 1 (at "Billing"))
      (should= 2 (at "Charge"))
      (should= 5 (at "Tax"))))

  (it "returns nil when the member is not in the file"
    (let [file (ruby-file "class Shop\nend\n")]
      (should-be-nil (source/member-source {:lang :ruby :file (.getPath file) :name "missing"}))))

  (it "finds an HTTP endpoint by its verb and path"
    (let [file (ruby-file (str "class Api < Grape::API\n"
                               "  namespace :auth do\n"
                               "    get :callback do\n      1\n    end\n"
                               "  end\n"
                               "end\n"))
          found (source/member-source {:lang :ruby :file (.getPath file) :name "GET auth/callback"})]
      (should= 3 (:line found))
      (should-be-nil (source/member-source {:lang :ruby :file (.getPath file) :name "GET auth/nope"}))))

  (it "finds a rake task by its namespaced name"
    (let [file (ruby-file (str "namespace :email_provider do\n"
                               "  task sync_hubspot: :environment do\n    1\n  end\n"
                               "end\n"))
          found (source/member-source {:lang :ruby :file (.getPath file) :name "email_provider:sync_hubspot"})]
      (should= 2 (:line found)))))
