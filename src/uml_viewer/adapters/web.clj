(ns uml-viewer.adapters.web
  "The viewer as a local web page: an HTTP server, one state owned by a
  single thread, and frames pushed to every open page over SSE."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.markup :as markup]
            [uml-viewer.adapters.viewer :as viewer]
            [uml-viewer.application.document :as document]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.domain.log :as log]
            [uml-viewer.domain.mailbox :as mailbox])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io IOException OutputStream]
           [java.net BindException InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CountDownLatch Executors ScheduledExecutorService TimeUnit]))

(def tick-ms 50)
(def ping-ms 15000)

(defonce !runtime
  (atom {:state nil :shown nil :clients #{} :source nil :keep-agent false}))

(defonce ^ScheduledExecutorService worker
  (Executors/newSingleThreadScheduledExecutor))

(defn parse-form
  "Map of a URL-encoded body's names to values."
  [body]
  (into {}
        (keep (fn [pair]
                (let [[k v] (str/split pair #"=" 2)]
                  (when (seq k)
                    [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")]))))
        (str/split (or body "") #"&")))

(defn sse-message
  "One server-sent event named `event` carrying `data`."
  [event data]
  (str "event: " event "\n"
       (apply str (map #(str "data: " % "\n") (str/split (str data) #"\n" -1)))
       "\n"))

(defn changed-messages
  "SSE messages for the parts of `views` that differ from `shown`."
  [shown views]
  (cond-> []
    (not= (:frame shown) (:frame views)) (conj (sse-message "frame" (:frame views)))
    (not= (:card shown) (:card views)) (conj (sse-message "card" (markup/json (:card views))))))

(defn- send! [^OutputStream out ^String msg]
  (.write out (.getBytes msg StandardCharsets/UTF_8))
  (.flush out))

(defn- broadcast!
  "Write `msgs` to every page; drop pages that have gone away."
  [msgs]
  (doseq [{:keys [out done] :as client} (:clients @!runtime)]
    (try
      (doseq [m msgs] (send! out m))
      (catch IOException _
        (swap! !runtime update :clients disj client)
        (.countDown ^CountDownLatch done)))))

(defn- push-views! []
  (let [{:keys [state shown]} @!runtime]
    (when-not (identical? state (:state shown))
      (let [views (viewer/views state)
            msgs (changed-messages shown views)]
        (swap! !runtime assoc :shown (assoc views :state state))
        (when (seq msgs) (broadcast! msgs))))))

(defn- halt! []
  (System/exit 0))

(defn quit!
  "Stop the viewer, and the companion unless it should outlive this JVM."
  []
  (when-not (:keep-agent @!runtime)
    (companion/shutdown-children! (overlay/metrics-root (get-in @!runtime [:state :path]))))
  (halt!))

(defn- quit-for-restart! [state]
  (document/save-session! state)
  (swap! !runtime assoc :keep-agent true)
  (quit!))

(defn step!
  "Run `f` on the state thread and return its reply."
  [f]
  (.get (.submit worker ^Callable
                 (fn []
                   (let [{:keys [state reply]} (f (:state @!runtime))]
                     (swap! !runtime assoc :state state)
                     reply)))))

(defn- tick! []
  (try
    (let [state (viewer/tick (:state @!runtime))]
      (swap! !runtime assoc :state state)
      (if (:quit-for-restart state)
        (quit-for-restart! state)
        (push-views!)))
    (catch Throwable t
      (log/log-exception! t "tick"))))

(defn- respond! [^HttpExchange ex status ^String content-type ^bytes body]
  (.add (.getResponseHeaders ex) "Content-Type" content-type)
  (.add (.getResponseHeaders ex) "Cache-Control" "no-store")
  (.sendResponseHeaders ex status (if (pos? (alength body)) (alength body) -1))
  (with-open [out (.getResponseBody ex)]
    (.write out body)))

(defn- text! [ex status content-type s]
  (respond! ex status content-type (.getBytes (str s) StandardCharsets/UTF_8)))

(def ^:private assets
  {"/" ["index.html" "text/html; charset=utf-8"]
   "/app.js" ["app.js" "text/javascript; charset=utf-8"]
   "/app.css" ["app.css" "text/css; charset=utf-8"]})

(defn- asset! [ex [file content-type]]
  (text! ex 200 content-type (slurp (io/resource (str "uml_viewer/web/" file)))))

(defn- events! [^HttpExchange ex]
  (let [headers (.getResponseHeaders ex)
        done (CountDownLatch. 1)
        out (.getResponseBody ex)
        client {:out out :done done}
        {:keys [frame card]} (:shown @!runtime)]
    (.add headers "Content-Type" "text/event-stream")
    (.add headers "Cache-Control" "no-store")
    (.sendResponseHeaders ex 200 0)
    (try
      (send! out (str (sse-message "frame" (or frame "")) (sse-message "card" (markup/json (or card {})))))
      (swap! !runtime update :clients conj client)
      (catch IOException _ (.countDown done)))
    (.await done)
    (.close ex)))

(defn- event! [^HttpExchange ex]
  (let [params (parse-form (slurp (.getRequestBody ex) :encoding "UTF-8"))
        source (:source @!runtime)]
    (if (= "quit" (get params "type"))
      (do (text! ex 200 "application/json" "{}")
          (quit!))
      (let [reply (step! #(viewer/handle % source params))]
        (text! ex 200 "application/json" (markup/json (or reply {})))))))

(defn route! [^HttpExchange ex]
  (let [path (.getPath (.getRequestURI ex))
        method (.getRequestMethod ex)]
    (try
      (cond
        (and (= "GET" method) (assets path)) (asset! ex (assets path))
        (and (= "GET" method) (= "/events" path)) (events! ex)
        (and (= "POST" method) (= "/event" path)) (event! ex)
        :else (text! ex 404 "text/plain" "not found"))
      (catch Throwable t
        (log/log-exception! t (str method " " path))
        (try (text! ex 500 "text/plain" "error") (catch Throwable _))))))

(defn preferred-port
  "A port in 20000–29999 fixed by the project, so a restarted viewer
  comes back where its pages are already pointed."
  [root]
  (+ 20000 (mod (hash (.getCanonicalPath (io/file root))) 10000)))

(defn- bind [port]
  (try
    (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)
    (catch BindException _ nil)))

(defn- serve! [root]
  (let [port (preferred-port root)
        server (or (some bind (range port (+ port 20)))
                   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0))]
    (.createContext server "/" (reify HttpHandler (handle [_ ex] (route! ex))))
    (.setExecutor server (Executors/newCachedThreadPool))
    (.start server)
    (.getPort (.getAddress server))))

(defn web-file [root]
  (io/file (mailbox/dir root) "web.edn"))

(defn url [port]
  (str "http://127.0.0.1:" port "/"))

(defn open-browser! [u]
  (let [cmd (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac") "open" "xdg-open")]
    (try (.start (ProcessBuilder. (into-array String [cmd u])))
         (catch Exception e (log/log-exception! e "open browser")))))

(defn- open-unless-reconnected! [u restart?]
  (future
    (when restart? (Thread/sleep 3000))
    (when (empty? (:clients @!runtime))
      (open-browser! u))))

(defn start!
  "Serve the viewer for `path` and open it in the browser. A restart
  keeps the companion and reuses any page that reconnects."
  [path source-impl restart? pr]
  (let [root (overlay/metrics-root path)]
    (if restart?
      (companion/remember-companion! root)
      (companion/open-in-terminal! root))
    (swap! !runtime assoc :source source-impl :state (viewer/initial-state path restart? pr))
    (let [port (serve! root)
          u (url port)]
      (io/make-parents (web-file root))
      (spit (web-file root) (pr-str {:port port :url u}))
      (.scheduleWithFixedDelay worker ^Runnable tick! 0 tick-ms TimeUnit/MILLISECONDS)
      (.scheduleWithFixedDelay worker ^Runnable #(broadcast! [": ping\n\n"]) ping-ms ping-ms TimeUnit/MILLISECONDS)
      (open-unless-reconnected! u restart?)
      u)))
