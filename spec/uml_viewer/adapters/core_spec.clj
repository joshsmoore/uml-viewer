(ns uml-viewer.adapters.core-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.adapters.core :as core]
            [uml-viewer.adapters.web :as web]))

(describe "cli args"
  (it "defaults the path and does not restart"
    (should= {:path "examples/library.edn" :restart? false :help? false :pr nil}
             (core/parse-args nil))
    (should= {:path "examples/library.edn" :restart? false :help? false :pr nil}
             (core/parse-args [])))

  (it "takes a path and the --restart flag in either order"
    (should= {:path "doc.edn" :restart? false :help? false :pr nil}
             (core/parse-args ["doc.edn"]))
    (should= {:path "examples/library.edn" :restart? true :help? false :pr nil}
             (core/parse-args ["--restart"]))
    (should= {:path "doc.edn" :restart? true :help? false :pr nil}
             (core/parse-args ["--restart" "doc.edn"]))
    (should= {:path "doc.edn" :restart? true :help? false :pr nil}
             (core/parse-args ["doc.edn" "--restart"])))

  (it "takes a pull request number, or local, after --pr"
    (should= {:path "doc.edn" :restart? false :help? false :pr 2245}
             (core/parse-args ["--pr" "2245" "doc.edn"]))
    (should= {:path "doc.edn" :restart? true :help? false :pr :local}
             (core/parse-args ["--restart" "--pr" "local" "doc.edn"]))
    (should= {:path "examples/library.edn" :restart? false :help? false :pr 2249}
             (core/parse-args ["-pr" "2249"]))
    (should= {:path "examples/library.edn" :restart? false :help? false :pr nil}
             (core/parse-args ["--pr" "nope"]))
    (should (re-find #"--pr N" core/help-text)))

  (it "prints a description of the arguments on --help"
    (should= {:path "examples/library.edn" :restart? false :help? true :pr nil}
             (core/parse-args ["--help"]))
    (should (:help? (core/parse-args ["-h" "doc.edn"])))
    (should (re-find #"edn-file" core/help-text))
    (should (re-find #"--restart" core/help-text))
    (with-redefs [web/start! (fn [& _] (throw (Exception. "should not start")))]
      (let [ret (atom nil)
            out (with-out-str (reset! ret (core/start! :unused "--help")))]
        (should= :help @ret)
        (should (re-find #"Usage: clj -M:run" out)))))

  (it "starts the web viewer when not asking for help"
    (let [args (atom nil)]
      (with-redefs [web/start! (fn [& a] (reset! args a) "http://127.0.0.1:1/")]
        (let [out (with-out-str (core/start! :src "doc.edn"))]
          (should= ["doc.edn" :src false nil] @args)
          (should (re-find #"Watching" out))
          (should (re-find #"Viewer at http://127.0.0.1:1/" out))
          (should (re-find #"real diagram above Proposals" out)))))))
