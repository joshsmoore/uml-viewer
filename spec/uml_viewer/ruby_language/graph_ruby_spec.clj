(ns uml-viewer.ruby-language.graph-ruby-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.ruby-language.grape :as grape]
            [uml-viewer.ruby-language.graph-ruby :as rb]
            [uml-viewer.ruby-language.rake :as rake]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- temp-root []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "uml-rb-" (System/nanoTime))))

(defn- edge? [g from to kind]
  (boolean (some #(= {:from from :to to :kind kind} (select-keys % [:from :to :kind])) (:edges g))))

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

  (it "lists methods, marking private and protected ones"
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
      (should= [{:name "initialize" :text "initialize"}
                {:name "open?" :text "open?"}
                {:name "build" :text "self.build"}
                {:name "find" :text "self.find"}
                {:name "hidden_class" :text "self.hidden_class" :private true}
                {:name "total!" :text "total!"}
                {:name "inline" :text "inline" :private true}
                {:name "guarded" :text "guarded" :private true}
                {:name "visible" :text "visible"}
                {:name "secret" :text "secret" :private true}]
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
      (should= [["User"] ["ResumeSection"] ["Category"]] (map :segs (:associations surface)))
      (should= [6 7 9] (map :line (:associations surface)))
      (should (:concern? surface)))))

(def ^:private grape-api
  (str "module Idp\n"
       "  class AuthApi < Grape::API\n"
       "    helpers do\n"
       "      def login_url\n      end\n"
       "    end\n"
       "    # get :commented do\n"
       "    namespace :auth do\n"
       "      get :login do\n        1\n      end\n"
       "      post \"/token/refresh\" do\n      end\n"
       "      route_param :id do\n"
       "        delete do\n        end\n"
       "      end\n"
       "    end\n"
       "    get do\n    end\n"
       "    resources :users do\n"
       "      get ':user_id/roles', requirements: { user_id: /\\d+/ } do |x|\n      end\n"
       "    end\n"
       "  end\n"
       "end\n"))

(describe "grape endpoints"
  (it "names each verb block by its verb and nested path"
    (should= [{:name "GET auth/login" :line 9}
              {:name "POST auth/token/refresh" :line 12}
              {:name "DELETE auth/:id" :line 15}
              {:name "GET /" :line 19}
              {:name "GET users/:user_id/roles" :line 22}]
             (grape/endpoints grape-api)))

  (it "finds nothing without a verb block"
    (should= [] (grape/endpoints "class A\n  def get\n  end\nend\n"))
    (should= [] (grape/endpoints "get '/x', to: 'a#b'\n"))
    (should= [] (grape/endpoints nil)))

  (it "lists endpoints as ops after the methods"
    (should= ["login_url" "GET auth/login" "POST auth/token/refresh" "DELETE auth/:id"
              "GET /" "GET users/:user_id/roles"]
             (mapv :name (:ops (rb/read-file grape-api))))
    (should= {:name "GET auth/login" :text "GET auth/login"}
             (second (:ops (rb/read-file grape-api))))))

(def ^:private rake-source
  (str "namespace :email_provider do\n"
       "  desc \"Sync\"\n"
       "  task sync_hubspot: :environment do\n"
       "    Marketing::SyncHubspotJob.perform_now\n"
       "  end\n"
       "\n"
       "  namespace \"reports\" do\n"
       "    task :weekly, [:day] => :environment do\n"
       "    end\n"
       "  end\n"
       "  multitask \"all\" => [:a]\n"
       "end\n"
       "task cleanup: :environment\n"
       "def helper\n"
       "end\n"))

(describe "rake tasks"
  (it "names each task by its nested namespaces"
    (should= [{:name "email_provider:sync_hubspot" :line 3}
              {:name "email_provider:reports:weekly" :line 8}
              {:name "email_provider:all" :line 11}
              {:name "cleanup" :line 13}]
             (rake/tasks rake-source)))

  (it "finds nothing without a task"
    (should= [] (rake/tasks "namespace :x do\nend\n"))
    (should= [] (rake/tasks nil)))

  (it "recognizes .rake files"
    (should (rake/rake-file? "email_provider.rake"))
    (should-not (rake/rake-file? "email_provider.rb")))

  (it "lists tasks as ops after the methods, and sites sit in their task"
    (let [surface (rb/read-rake-file rake-source)]
      (should= ["helper" "email_provider:sync_hubspot" "email_provider:reports:weekly"
                "email_provider:all" "cleanup"]
               (mapv :name (:ops surface)))
      (should= {:segs ["Marketing" "SyncHubspotJob"] :line 4 :in "email_provider:sync_hubspot" :call "perform_now"}
               (select-keys (first (:refs surface)) [:segs :line :in :call]))))

  (it "does not read tasks from a .rb file"
    (should= [] (:ops (rb/read-file "task cleanup: :environment\n")))))

(describe "ruby call sites"
  (it "records each reference's line, enclosing member, and call"
    (let [refs (:refs (rb/read-file (str "class Scan\n"
                                         "  def work\n"
                                         "    CheckHistory.where(id: 1)\n"
                                         "  end\n"
                                         "\n"
                                         "  namespace :auth do\n"
                                         "    get :callback do\n"
                                         "      Analytics::TrackingPlan.name!(\"x\")\n"
                                         "    end\n"
                                         "  end\n"
                                         "end\n")))]
      (should= [{:segs ["CheckHistory"] :line 3 :in "work" :call "where"}
                {:segs ["Analytics" "TrackingPlan"] :line 8 :in "GET auth/callback" :call "name!"}]
               (map #(select-keys % [:segs :line :in :call]) (rest refs)))))

  (it "puts the sites on the edge to the class referred to"
    (let [root (temp-root)]
      (spit-file root "app/models/check_history.rb" "class CheckHistory\nend\n")
      (spit-file root "app/services/scan.rb" "class Scan\n  def work\n    CheckHistory.where(id: 1)\n  end\nend\n")
      (let [g (graph/scan (graph/lookup :ruby) (str root) {:prefix "app"})
            e (first (filter #(= [:services.scan :models.check-history] [(:from %) (:to %)]) (:edges g)))]
        (should= [{:line 3 :in "work" :call "where"}] (:sites e))))))

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
      (spit-file root "lib/tasks/email_provider.rake"
                 (str "namespace :email_provider do\n"
                      "  task sync: :environment do\n"
                      "    User.find_each { |u| Billing::Charge.new }\n"
                      "  end\n"
                      "end\n"))
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

  (it "draws a rake file as a class of its tasks"
    (let [g @scanned
          c (class-of g :lib.tasks.email-provider)]
      (should= "email_provider.rake" (:name c))
      (should= "app.lib.tasks.email-provider" (:ns c))
      (should= [{:name "email_provider:sync" :text "email_provider:sync"}] (:ops c))
      (should (edge? g :lib.tasks.email-provider :models.user :dependency))
      (should (edge? g :lib.tasks.email-provider :services.billing.charge :dependency))
      (should-not (some #(= :lib.tasks.email-provider (:to %)) (:edges g)))))

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
        (should (edge? g :a :b :dependency)))))

  (it "does not bind a wrapper module to a file that only wraps it"
    (let [root (temp-root)]
      (spit-file root "a/public_api/foo.rb" "module PublicApi\n  class Foo\n  end\nend\n")
      (spit-file root "b/public_api/bar.rb"
                 "module PublicApi\n  class Bar\n    def x\n      PublicApi::Baz\n    end\n  end\nend\n")
      (spit-file root "c/public_api/qux.rb"
                 "module PublicApi\n  class Qux\n    def x\n      Foo\n    end\n  end\nend\n")
      (let [g (graph/scan (rb/->RubyGraph) root {:prefix ""})]
        (should-not (some #(= :b.public-api.bar (:from %)) (:edges g)))
        (should (edge? g :c.public-api.qux :a.public-api.foo :dependency)))))

  (it "binds a module to the file that defines it"
    (let [root (temp-root)]
      (spit-file root "public_api.rb" "module PublicApi\nend\n")
      (spit-file root "public_api/foo.rb" "module PublicApi\n  class Foo\n  end\nend\n")
      (spit-file root "user.rb" "class User\n  def x\n    PublicApi\n  end\nend\n")
      (let [g (graph/scan (rb/->RubyGraph) root {:prefix ""})]
        (should (edge? g :user :public-api :dependency)))))

  (it "binds a second class to the file that nests it"
    (let [root (temp-root)]
      (spit-file root "shop/cart.rb" "module Shop\n  class Cart\n  end\n  class Extra\n  end\nend\n")
      (spit-file root "user.rb" "class User\n  def x\n    Shop::Extra\n  end\nend\n")
      (let [g (graph/scan (rb/->RubyGraph) root {:prefix ""})]
        (should (edge? g :user :shop.cart :dependency))))))
