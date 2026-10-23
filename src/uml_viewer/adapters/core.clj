(ns uml-viewer.adapters.core
  (:require [uml-viewer.adapters.web :as web]))

(def help-text
  (str "Usage: clj -M:run [options] [edn-file]\n"
       "\n"
       "  edn-file          Diagram to watch (default: examples/library.edn).\n"
       "                    A fresh start waits for the companion Claude to send\n"
       "                    :display unless the associated agent recycles\n"
       "                    the viewer with :uml-viewer-restart, or you press R.\n"
       "                    The viewer opens in the browser.\n"
       "\n"
       "  --pr N            Review GitHub pull request N without checking it\n"
       "                    out: fetch its head and diagram it against its base.\n"
       "                    With --restart, --pr local returns to the working tree.\n"
       "\n"
       "  --restart         Associated agent only (via :uml-viewer-restart).\n"
       "                    New JVM, keep the existing Claude tmux session.\n"
       "                    Reloads the last view (depth, pan, zoom, proposal).\n"
       "                    Do not use this if no companion is attached.\n"
       "\n"
       "  -h, --help        Print this help and exit.\n"))

(defn- pr-arg [v]
  (cond
    (= "local" v) :local
    (and v (re-matches #"\d+" v)) (parse-long v)
    :else nil))

(def ^:private pr-flags #{"--pr" "-pr"})

(defn- without-pr [args]
  (loop [[a & more] args acc []]
    (cond
      (nil? a) acc
      (pr-flags a) (recur (rest more) acc)
      :else (recur more (conj acc a)))))

(defn parse-args
  "EDN path and flags. `--restart` skips spawning a new agent. `--pr N`
  reviews pull request N."
  [args]
  (let [args (vec (keep identity args))
        help? (boolean (some #{"--help" "-h"} args))
        restart? (boolean (some #{"--restart"} args))
        pr (some->> (partition-all 2 1 args) (some #(when (pr-flags (first %)) (second %))) pr-arg)
        path (->> (without-pr args) (remove #{"--help" "-h" "--restart"}) first)]
    {:help? help?
     :restart? restart?
     :pr pr
     :path (or path "examples/library.edn")}))

(defn start!
  "Launch the viewer. `source-impl` satisfies `LanguageSource`."
  [source-impl & args]
  (let [{:keys [path restart? help? pr]} (parse-args args)]
    (if help?
      (do (print help-text) :help)
      (do
        (let [u (web/start! path source-impl restart? pr)]
          (println "Watching" path)
          (println "Viewer at" u)
          (println "Double-click a class for its card. Scroll to pan (Shift-scroll for horizontal). Ctrl+/− zoom; Ctrl+0 fits the window. R reloads. Click the real diagram above Proposals, or a proposal to show it."))))))
