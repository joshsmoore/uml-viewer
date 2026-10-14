(ns uml-viewer.ruby-language.graph-ruby-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.ruby-language.graph-ruby :as rb]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- temp-root []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "uml-rb-" (System/nanoTime))))

(defn- edge? [g from to kind]
  (boolean (some #(= {:from from :to to :kind kind} %) (:edges g))))

(defn- class-of [g id]
  (first (filter #(= id (:id %)) (:classes g))))

(describe "ruby masking"
  (it "hides comments, strings, heredocs, and %-literals from constant scans"
    (let [surface (rb/read-file
                    (str "# Hidden::InComment\n"
                         "=begin\nHidden::InBlock\n=end\n"
                         "class Shown < Base\n"
                         "  A = \"Hidden::InString #{1}\"\n"
                         "  B = %w[Hidden InWords]\n"
                         "  C = <<~SQL.squish\n"
                         "    Hidden::InHeredoc\n"
                         "  SQL\n"
                         "  D = 10 % Divisor\n"
                         "  def go = Real::Ref.call\n"
                         "end\n"))
          refs (set (map :segs (:refs surface)))]
      (should (contains? refs ["Real" "Ref"]))
      (should (contains? refs ["Divisor"]))
      (should-not (some #(= "Hidden" (first %)) refs))
      (should= [{:kind :class :segs ["Shown"] :super ["Base"]}] (:decls surface))))

  (it "does not read class << self as a heredoc"
    (let [surface (rb/read-file
                    (str "module Box\n"
                         "  class << self\n"
                         "    def open = Lid.new\n"
                         "  end\n"
                         "end\n"))]
      (should (some #(= ["Lid"] (:segs %)) (:refs surface))))))

(describe "ruby file surface"
  (it "nests declarations by indentation"
    (let [surface (rb/read-file
                    (str "module Billing\n"
                         "  class Invoice < ::ApplicationRecord\n"
                         "  end\n"
                         "  class Line\n"
                         "  end\n"
                         "end\n"
                         "class Billing::Tax; end\n"))]
      (should= [["Billing"] ["Billing" "Invoice"] ["Billing" "Line"] ["Billing" "Tax"]]
               (map :segs (:decls surface)))
      (should= ["ApplicationRecord"] (:super (second (:decls surface))))))

  (it "lists public methods, skipping private, protected, and initialize"
    (let [surface (rb/read-file
                    (str "class Shop\n"
                         "  def initialize; end\n"
                         "  def open?; end\n"
                         "  def self.build; end\n"
                         "  class << self\n"
                         "    def find; end\n"
                         "    private\n"
                         "    def hidden_class; end\n"
                         "  end\n"
                         "  def total!; end\n"
                         "  private def inline; end\n"
                         "  protected\n"
                         "  def guarded; end\n"
                         "  public\n"
                         "  def visible; end\n"
                         "  private\n"
                         "  def secret; end\n"
                         "end\n"))]
      (should= [{:name "open?" :text "open?"}
                {:name "build" :text "self.build"}
                {:name "find" :text "self.find"}
                {:name "total!" :text "total!"}
                {:name "visible" :text "visible"}]
               (:ops surface))))

  (it "reads mixins, requires, and association targets"
    (let [surface (rb/read-file
                    (str "class Resume < ApplicationRecord\n"
                         "  include Shares, Exportable\n"
                         "  extend ActiveSupport::Concern\n"
                         "  require \"json\"\n"
                         "  require_relative \"helpers/format\"\n"
                         "  belongs_to :user\n"
                         "  has_many :sections, -> { order(:position) },\n"
                         "           class_name: \"ResumeSection\"\n"
                         "  has_many :categories\n"
                         "  has_one :owner, polymorphic: true\n"
                         "end\n"))]
      (should= [["Shares"] ["Exportable"] ["ActiveSupport" "Concern"]] (:mixins surface))
      (should= [{:kind :require :path "json"} {:kind :require-relative :path "helpers/format"}]
               (:requires surface))
      (should= [["User"] ["ResumeSection"] ["Category"]] (:associations surface))
      (should (:concern? surface)))))

(describe "ruby names"
  (it "camelizes, keys, and kebabs constants"
    (should= "RubyLlmAdapter" (rb/camelize "ruby_llm_adapter"))
    (should= (rb/const-key ["RubyLLMAdapter"]) (rb/const-key [(rb/camelize "ruby_llm_adapter")]))
    (should= "http-client" (rb/kebab "HTTPClient"))
    (should= :active-record.base (rb/foreign-id ["ActiveRecord" "Base"])))

  (it "singularizes association names"
    (should= "category" (rb/singularize "categories"))
    (should= "address" (rb/singularize "addresses"))
    (should= "box" (rb/singularize "boxes"))
    (should= "section" (rb/singularize "sections"))
    (should= "status" (rb/singularize "status"))))

(describe "ruby scan of a rails app"
  (with-all root (temp-root))
  (with-all scanned
    (let [root @root]
      (spit-file root "app/models/application_record.rb"
                 "class ApplicationRecord < ActiveRecord::Base\nend\n")
      (spit-file root "app/models/user.rb"
                 "class User < ApplicationRecord\n  has_many :resumes\nend\n")
      (spit-file root "app/models/resume.rb"
                 (str "class Resume < ApplicationRecord\n"
                      "  include Shareable\n"
                      "  belongs_to :user\n"
                      "  def publish!\n    Billing::Charge.new\n  end\n"
                      "end\n"))
      (spit-file root "app/models/concerns/shareable.rb"
                 "module Shareable\n  extend ActiveSupport::Concern\nend\n")
      (spit-file root "app/services/billing.rb" "module Billing\nend\n")
      (spit-file root "app/services/billing/charge.rb"
                 (str "module Billing\n"
                      "  class Charge\n"
                      "    include Shapes\n"
                      "    def run = Result.new\n"
                      "  end\n"
                      "end\n"))
      (spit-file root "app/services/billing/shapes.rb" "module Billing\n  module Shapes\n  end\nend\n")
      (spit-file root "app/services/billing/result.rb" "module Billing\n  class Result\n  end\nend\n")
      (spit-file root "app/services/ruby_llm_adapter.rb"
                 "require \"ruby_llm\"\nclass RubyLLMAdapter\n  def ask = Resume.first\nend\n")
      (spit-file root "app/controllers/resumes_controller.rb"
                 "class ResumesController < ApplicationController\n  def show = Resume.find(1)\nend\n")
      (spit-file root "app/controllers/application_controller.rb"
                 "class ApplicationController < ActionController::Base\nend\n")
      (spit-file root "app/operations/result.rb" "class Result\nend\n")
      (spit-file root "app/errors/gate_error.rb"
                 "class GateError < StandardError\n  include Enumerable\nend\n")
      (spit-file root "lib/tasks_helper.rb" "module TasksHelper\nend\n")
      (spit-file root "spec/models/resume_spec.rb" "RSpec.describe Resume do\nend\n")
      (spit-file root "app/views/layouts/x.rb" "class Ignored\nend\n")
      (graph/scan (rb/->RubyGraph) root {:prefix "app"})))

  (it "names classes by path under app and lib"
    (let [g @scanned
          ids (set (map :id (remove :foreign (:classes g))))]
      (should (contains? ids :models.resume))
      (should (contains? ids :models.concerns.shareable))
      (should (contains? ids :services.billing))
      (should (contains? ids :services.billing.charge))
      (should (contains? ids :lib.tasks-helper))
      (should-not (some #(re-find #"spec|views" (name %)) ids))
      (should= "app.models.resume" (:ns (class-of g :models.resume)))
      (should= "RubyLLMAdapter" (:name (class-of g :services.ruby-llm-adapter)))
      (should= :ruby (:lang (class-of g :models.resume)))
      (should= [{:name "publish!" :text "publish!"}] (:ops (class-of g :models.resume)))))

  (it "links superclasses, mixins, and associations"
    (let [g @scanned]
      (should (edge? g :models.resume :models.application-record :inheritance))
      (should (edge? g :models.resume :models.concerns.shareable :implements))
      (should (edge? g :models.resume :models.user :dependency))
      (should (edge? g :models.user :models.resume :dependency))
      (should (edge? g :services.billing.charge :services.billing.shapes :implements))))

  (it "resolves constants lexically, innermost namespace first"
    (let [g @scanned]
      (should (edge? g :models.resume :services.billing.charge :dependency))
      (should (edge? g :services.billing.charge :services.billing.result :dependency))
      (should-not (edge? g :services.billing.charge :operations.result :dependency))
      (should (edge? g :services.ruby-llm-adapter :models.resume :dependency))
      (should (edge? g :controllers.resumes-controller :models.resume :dependency))))

  (it "makes unresolved superclasses, mixins, and requires foreign"
    (let [g @scanned
          foreign (set (map :id (filter :foreign (:classes g))))]
      (should (contains? foreign :active-record.base))
      (should (contains? foreign :action-controller.base))
      (should (contains? foreign :active-support.concern))
      (should (contains? foreign :ruby-llm))
      (should (edge? g :models.application-record :active-record.base :dependency))
      (should-not (contains? foreign :standard-error))
      (should-not (contains? foreign :enumerable))))

  (it "marks concerns and included modules as interfaces"
    (let [g @scanned]
      (should= :interface (:stereotype (class-of g :models.concerns.shareable)))
      (should= :interface (:stereotype (class-of g :services.billing.shapes)))
      (should-be-nil (:stereotype (class-of g :services.billing)))))

  (it "has no self edges"
    (should-not (some #(= (:from %) (:to %)) (:edges @scanned)))))

(describe "ruby scan of a plain library"
  (it "treats a non-rails root as its own autoload root"
    (let [root (temp-root)]
      (spit-file root "shop/cart.rb" "module Shop\n  class Cart\n    def add = Item.new\n  end\nend\n")
      (spit-file root "shop/item.rb" "module Shop\n  class Item\n  end\nend\n")
      (spit-file root "shop/cart_test.rb" "class CartTest\nend\n")
      (let [g (graph/scan (rb/->RubyGraph) root {:prefix "shop"})]
        (should= #{:cart :item} (set (map :id (:classes g))))
        (should (edge? g :cart :item :dependency)))))

  (it "follows require_relative to a project file"
    (let [root (temp-root)]
      (spit-file root "a.rb" "require_relative \"b\"\nclass A\nend\n")
      (spit-file root "b.rb" "class B\nend\n")
      (let [g (graph/scan (rb/->RubyGraph) root {:prefix ""})]
        (should (edge? g :a :b :dependency))))))
