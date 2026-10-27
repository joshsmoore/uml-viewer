(ns uml-viewer.adapters.web-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.viewer :as viewer]
            [uml-viewer.adapters.web :as web]
            [uml-viewer.application.document :as document])
  (:import [java.net HttpURLConnection URL]
           [java.util.concurrent Executors]))

(defn- private [sym]
  (ns-resolve 'uml-viewer.adapters.web sym))

(defn- no-exit [it]
  (with-redefs [uml-viewer.adapters.web/halt! (fn [] (throw (Exception. "halted")))
                companion/shutdown-children! (fn [_] (throw (Exception. "companion stopped")))]
    (it)))

(defn- temp-root []
  (doto (io/file (System/getProperty "java.io.tmpdir") (str "uv-web-" (System/nanoTime)))
    (.mkdirs)))

(defn- request
  ([port method path] (request port method path nil))
  ([port method path body]
   (let [c ^HttpURLConnection (.openConnection (URL. (str "http://127.0.0.1:" port path)))]
     (.setRequestMethod c method)
     (when body
       (.setDoOutput c true)
       (with-open [out (.getOutputStream c)]
         (.write out (.getBytes ^String body "UTF-8"))))
     (let [status (.getResponseCode c)
           stream (if (< status 400) (.getInputStream c) (.getErrorStream c))]
       {:status status
        :type (.getHeaderField c "Content-Type")
        :body (if stream (slurp stream) "")}))))

(describe "web wire format"
  (around [it] (no-exit it))

  (it "parses a URL-encoded body"
    (should= {"type" "press" "x" "1.5" "v" "a b&c" "empty" ""}
             (web/parse-form "type=press&x=1.5&v=a+b%26c&empty&=skip"))
    (should= {} (web/parse-form nil)))

  (it "writes a named server-sent event, one data line per text line"
    (should= "event: frame\ndata: <svg/>\n\n" (web/sse-message "frame" "<svg/>"))
    (should= "event: card\ndata: a\ndata: b\n\n" (web/sse-message "card" "a\nb")))

  (it "sends only the parts that changed"
    (should= [] (web/changed-messages {:frame "f" :card {:svg ""}} {:frame "f" :card {:svg ""}}))
    (should= ["event: frame\ndata: g\n\n"]
             (web/changed-messages {:frame "f" :card {:svg ""}} {:frame "g" :card {:svg ""}}))
    (should= ["event: card\ndata: {\"svg\":\"x\"}\n\n"]
             (web/changed-messages {:frame "f"} {:frame "f" :card {:svg "x"}})))

  (it "picks a port per project and names the page"
    (let [p (web/preferred-port "/tmp/a")]
      (should (<= 20000 p 29999))
      (should= p (web/preferred-port "/tmp/a")))
    (should= "http://127.0.0.1:5/" (web/url 5))
    (should (str/ends-with? (str (web/web-file "/r")) ".uml-viewer/web.edn"))))

(describe "web server"
  (around [it] (no-exit it))

  (it "serves the page, its assets, and events, and 404s the rest"
    (let [root (temp-root)
          port ((private 'serve!) root)]
      (reset! web/!runtime {:state (viewer/on-resize {} 800 600) :shown nil :clients #{}})
      (let [page (request port "GET" "/")]
        (should= 200 (:status page))
        (should (str/starts-with? (:type page) "text/html"))
        (should (str/includes? (:body page) "/app.js")))
      (should (str/includes? (:body (request port "GET" "/app.js")) "EventSource"))
      (should (str/includes? (:body (request port "GET" "/app.css")) "#stage"))
      (should= 404 (:status (request port "GET" "/nope")))
      (should= 404 (:status (request port "DELETE" "/")))
      (let [reply (request port "POST" "/event" "type=resize&w=1000&h=700")]
        (should= 200 (:status reply))
        (should= "{}" (:body reply))
        (should= {:w 1000 :h 700} (get-in @web/!runtime [:state :window])))))

  (it "answers an event with the handler's reply as JSON"
    (let [port ((private 'serve!) (temp-root))]
      (with-redefs [viewer/handle (fn [s _ params] {:state s :reply {:echo (get params "type")}})]
        (should= "{\"echo\":\"key\"}" (:body (request port "POST" "/event" "type=key"))))))

  (it "quits on a quit event after answering"
    (let [port ((private 'serve!) (temp-root))
          quit (promise)]
      (with-redefs [web/quit! (fn [] (deliver quit true))
                    viewer/handle (fn [& _] (throw (Exception. "should not handle")))]
        (should= "{}" (:body (request port "POST" "/event" "type=quit")))
        (should (deref quit 1000 false)))))

  (it "fails an event that throws without killing the server"
    (let [port ((private 'serve!) (temp-root))]
      (with-redefs [viewer/handle (fn [& _] (throw (Exception. "boom")))
                    uml-viewer.domain.log/log-exception! (fn [& _])]
        (should= 500 (:status (request port "POST" "/event" "type=key"))))
      (should= 200 (:status (request port "GET" "/")))))

  (it "streams the current views to a new page and then each change"
    (let [port ((private 'serve!) (temp-root))]
      (reset! web/!runtime {:state {} :shown {:frame "<svg>one</svg>" :card {:svg ""}} :clients #{}})
      (let [c ^HttpURLConnection (.openConnection (URL. (str "http://127.0.0.1:" port "/events")))
            r (io/reader (.getInputStream c))
            lines (doall (take 9 (line-seq r)))]
        (should= ["event: kinds"
                  (str "data: [\"dependency\",\"db-association\",\"association\",\"aggregation\","
                       "\"composition\",\"implements\",\"inheritance\"]")
                  ""
                  "event: frame" "data: <svg>one</svg>" "" "event: card" "data: {\"svg\":\"\"}" ""]
                 lines)
        (should= 1 (count (:clients @web/!runtime)))
        ((private 'broadcast!) [(web/sse-message "frame" "two")])
        (should= ["event: frame" "data: two"] (doall (take 2 (line-seq r))))
        (.disconnect c)))))

(describe "web state thread"
  (around [it] (no-exit it))

  (it "applies a step and keeps its state"
    (reset! web/!runtime {:state {:n 1} :clients #{}})
    (should= :r (web/step! (fn [s] {:state (update s :n inc) :reply :r})))
    (should= {:n 2} (:state @web/!runtime)))

  (it "pushes views only when the state is new"
    (let [sent (atom [])]
      (with-redefs [viewer/views (fn [s] {:frame (str s) :card {:svg ""}})
                    uml-viewer.adapters.web/broadcast! (fn [m] (swap! sent into m))]
        (reset! web/!runtime {:state {:a 1} :shown nil :clients #{}})
        ((private 'push-views!))
        (should= 2 (count @sent))
        ((private 'push-views!))
        (should= 2 (count @sent))
        (swap! web/!runtime assoc :state {:a 2})
        ((private 'push-views!))
        (should= 3 (count @sent)))))

  (it "ticks, and quits for restart when mail says so, keeping the companion"
    (let [saved (atom nil)
          halted (atom 0)
          shut (atom 0)]
      (with-redefs [viewer/tick (fn [s] (assoc s :quit-for-restart true))
                    document/save-session! (fn [s] (reset! saved s))
                    uml-viewer.adapters.web/halt! (fn [] (swap! halted inc))
                    companion/shutdown-children! (fn [_] (swap! shut inc))]
        (reset! web/!runtime {:state {:path "x.edn"} :clients #{} :keep-agent false})
        ((private 'tick!))
        (should (:quit-for-restart @saved))
        (should= 1 @halted)
        (should= 0 @shut))))

  (it "ticks and pushes views while running"
    (let [pushed (atom 0)]
      (with-redefs [viewer/tick (fn [s] (assoc s :ticked true))
                    uml-viewer.adapters.web/push-views! (fn [] (swap! pushed inc))
                    uml-viewer.adapters.web/halt! (fn [] (throw (Exception. "should not halt")))]
        (reset! web/!runtime {:state {} :clients #{}})
        ((private 'tick!))
        (should= 1 @pushed)
        (should (:ticked (:state @web/!runtime))))))

  (it "logs a tick that throws"
    (let [logged (atom nil)]
      (with-redefs [viewer/tick (fn [_] (throw (Exception. "bad")))
                    uml-viewer.domain.log/log-exception! (fn [_ where] (reset! logged where))]
        ((private 'tick!))
        (should= "tick" @logged))))

  (it "quits, stopping the companion unless it should outlive the JVM"
    (let [shut (atom [])]
      (with-redefs [uml-viewer.adapters.web/halt! (fn [])
                    companion/shutdown-children! (fn [root] (swap! shut conj root))]
        (reset! web/!runtime {:state {:path "/tmp/x.edn"} :keep-agent false})
        (web/quit!)
        (should= 1 (count @shut))
        (swap! web/!runtime assoc :keep-agent true)
        (web/quit!)
        (should= 1 (count @shut))))))

(describe "web start"
  (around [it] (no-exit it))

  (it "starts the companion, serves, records the port, and opens the page"
    (let [root (temp-root)
          _ (.mkdirs (io/file root ".metrics"))
          path (str root "/d.edn")
          calls (atom [])
          pool (Executors/newSingleThreadScheduledExecutor)]
      (with-redefs [companion/open-in-terminal! (fn [r] (swap! calls conj [:open r]))
                    companion/remember-companion! (fn [r] (swap! calls conj [:remember r]))
                    viewer/initial-state (fn [p restart? pr] {:path p :restart? restart? :pr pr})
                    uml-viewer.adapters.web/serve! (fn [_] 4321)
                    uml-viewer.adapters.web/worker pool
                    web/open-browser! (fn [u] (swap! calls conj [:browse u]))]
        (should= "http://127.0.0.1:4321/" (web/start! path :src false 7))
        (Thread/sleep 100)
        (should= {:port 4321 :url "http://127.0.0.1:4321/"}
                 (read-string (slurp (io/file root ".uml-viewer" "web.edn"))))
        (should= {:path path :restart? false :pr 7} (:state @web/!runtime))
        (should= :src (:source @web/!runtime))
        (should (some #(= :open (first %)) @calls))
        (should (some #(= [:browse "http://127.0.0.1:4321/"] %) @calls))
        (swap! web/!runtime assoc :clients #{:page})
        (web/start! path :src true nil)
        (should (some #(= :remember (first %)) @calls))
        (.shutdownNow pool)))))
