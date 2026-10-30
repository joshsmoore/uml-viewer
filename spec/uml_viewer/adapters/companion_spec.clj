(ns uml-viewer.adapters.companion-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.palette :as palette]
            [uml-viewer.domain.mailbox :as mailbox]))

(describe "companion session"
  (it "mails discussion context for the real diagram and a proposal"
    (let [got (atom nil)]
      (with-redefs [companion/request-agent! (fn [_ op extra]
                                            (reset! got {:op op :extra extra})
                                            {:cmd extra :woke? true})]
        (companion/mail-context! {:path "examples/library.edn" :doc {}})
        (should= :context (:op @got))
        (should= :real (get-in @got [:extra :context]))
        (companion/mail-context!
          {:path "examples/library.edn"
           :proposal-id :ccp
           :doc {:proposals [{:id :ccp :name "CCP"}]}})
        (should= :proposal (get-in @got [:extra :context]))
        (should= :ccp (get-in @got [:extra :proposal-id]))
        (should= "CCP" (get-in @got [:extra :name])))))

  (it "queues element mail and wakes the companion"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-mail-" (System/nanoTime))
          woke (atom false)]
      (with-redefs [companion/notify-agent! (fn [& _] (reset! woke true) true)]
        (let [{:keys [cmd woke?]}
              (companion/request-agent! root :refresh-crap {:target {:id :a}})]
          (should= :refresh-crap (:op cmd))
          (should= {:id :a} (:target cmd))
          (should woke?)
          (should @woke)))))

  (it "gives each project its own tmux session"
    (let [a (companion/session-id "/tmp/proj-a")
          b (companion/session-id "/tmp/proj-b")]
      (should (re-find #"^uml-viewer-proj-a-" a))
      (should (re-find #"^uml-viewer-proj-b-" b))
      (should-not= a b)
      (should= a (companion/session-id "/tmp/proj-a"))
      (should-not (re-find #"uml-viewer-grok" a))))

  (it "names a tmux session and attaches Terminal to it"
    (let [cwd "/tmp/proj"
          sid (companion/session-id cwd)
          args (companion/new-session-args cwd)
          script (companion/osascript (companion/attach-command sid) sid)
          [br bg bb] (companion/rgb-16 palette/bg)
          [gr gg gb] (companion/rgb-16 palette/gold)]
      (should (some #{sid} args))
      (should= ["kill-session" "-t" sid] (companion/kill-session-args sid))
      (should (some #{"new-session"} args))
      (should (some #{"--dangerously-skip-permissions"} args))
      (should (some #{"--append-system-prompt"} args))
      (should (re-find #"claude$" (companion/claude-executable)))
      (should (some #{companion/standing-rules} args))
      (should (some #{companion/launch-prompt} args))
      (should (re-find #"On launch" companion/standing-rules))
      (should (re-find #"invent" companion/standing-rules))
      (should (re-find #":proposals" companion/standing-rules))
      (should (re-find #"to-agent.edn" companion/standing-rules))
      (should (re-find #":refresh-crap" companion/standing-rules))
      (should (re-find #":refresh-mutate-all" companion/standing-rules))
      (should (re-find #":omit" companion/standing-rules))
      (should (re-find #":context" companion/standing-rules))
      (should (re-find #":queue" companion/standing-rules))
      (should (re-find #":quit-for-restart" companion/standing-rules))
      (should (re-find #"uml --restart" companion/standing-rules))
      (should (re-find #"kills only this companion" companion/standing-rules))
      (should (re-find #"respawns" companion/standing-rules))
      (should-not (re-find #":reload" companion/standing-rules))
      (should-not (some #(re-find #"GROK" %) args))
      (should (some #{"COLORTERM=truecolor"} args))
      (should-not (some #{"status"} args))
      (should (re-find (re-pattern (str "tmux attach -t " sid)) (companion/attach-command sid)))
      (should (re-find #"tell application \"Terminal\"" script))
      (should-not (re-find #"activate" script))
      (should (re-find #"^tell application \"Terminal\"\nlaunch" script))
      (should (re-find #"AXRaise" script))
      (should (re-find (re-pattern (str "custom title of claudeTab to \"" sid "\"")) script))
      (should-not (re-find #"custom title of claudeTab to \"Claude\"" script))
      (should (re-find #"return winID" script))
      (should (re-find (re-pattern (str "background color of claudeTab to \\{" br ", " bg ", " bb "\\}"))
                       script))
      (should (re-find (re-pattern (str "cursor color of claudeTab to \\{" gr ", " gg ", " gb "\\}"))
                       script))))

  (it "closes only this viewer's Terminal window by id"
    (let [script (companion/close-terminal-script "42")]
      (should (re-find #"exists process \"Terminal\"" script))
      (should (re-find #"whose id is 42" script))
      (should-not (re-find #"custom title" script))
      (should-not (re-find #"Claude" script))
      (should-not (re-find #"repeat with w" script))))

  (it "does not scan other Terminal windows when id is missing"
    (let [script (companion/close-terminal-script)]
      (should-not (re-find #"close" script))
      (should-not (re-find #"Claude" script))))

  (it "wakes Claude with text, a pause, then Enter, and no stray newline"
    (let [sid "uml-viewer-proj-abc"
          steps (companion/notify-steps sid)]
      (should= ["send-keys" "-t" sid "-l" companion/wake-message]
               (first steps))
      (should= [:sleep 150] (second steps))
      (should= ["send-keys" "-t" sid "C-m"] (nth steps 2))
      (should= 3 (count steps))))

  (it "scales theme RGB into Terminal's 16-bit colors"
    (should= [5654 7196 8224] (companion/rgb-16 [22 28 32]))
    (should= [0 65535 257] (companion/rgb-16 [0 255 1])))

  (it "wakes the companion session recorded in companion.edn"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-wake2-" (System/nanoTime))
          calls (atom [])]
      (.mkdirs (java.io.File. root))
      (mailbox/write-companion! root {:session "uml-viewer-mine-abc"})
      (reset! companion/!session-name nil)
      (with-redefs [uml-viewer.adapters.companion/tmux!
                    (fn [& args]
                      (swap! calls conj (vec args))
                      (cond
                        (not= "has-session" (first args)) 0
                        (= "uml-viewer-mine-abc" (last args)) 0
                        :else 1))]
        (should (companion/notify-agent! root))
        (should (some #(= ["send-keys" "-t" "uml-viewer-mine-abc" "-l" companion/wake-message] %)
                      @calls)))))

  (it "does not wake anything when no candidate session is live"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-wake3-" (System/nanoTime))
          calls (atom [])]
      (.mkdirs (java.io.File. root))
      (reset! companion/!session-name nil)
      (with-redefs [uml-viewer.adapters.companion/tmux!
                    (fn [& args] (swap! calls conj (vec args)) 1)]
        (should-not (companion/notify-agent! root))
        (should-not (some #(= "send-keys" (first %)) @calls))
        (should-not (some #(some #{"uml-viewer-grok"} %) @calls)))))

  (it "on restart binds the live tmux session so later mail can wake it"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-remember-" (System/nanoTime))]
      (.mkdirs (java.io.File. root))
      (reset! companion/!session-name nil)
      (reset! companion/!terminal-window-id nil)
      (let [sid (companion/session-id root)]
        (with-redefs [uml-viewer.adapters.companion/tmux!
                      (fn [& args]
                        (if (and (= "has-session" (first args))
                                 (= sid (last args)))
                          0
                          1))]
          (should= sid (companion/remember-companion! root))
          (should= sid @companion/!session-name)
          (should= sid (:session (mailbox/read-companion root)))))))

  (it "kills only this project's tmux session and its Terminal window"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-comp-" (System/nanoTime))
          tmux-calls (atom [])
          scripts (atom [])]
      (mailbox/write-companion! root {:session "uml-viewer-mine-abc" :window-id "99"})
      (reset! companion/!terminal-window-id "88")
      (reset! companion/!session-name "other-session")
      (with-redefs [uml-viewer.adapters.companion/tmux! (fn [& args] (swap! tmux-calls conj (vec args)) 0)
                    uml-viewer.adapters.companion/run-osascript (fn [s] (swap! scripts conj s) "")]
        (companion/shutdown-children! root)
        (should (some #(= ["kill-session" "-t" "uml-viewer-mine-abc"] %) @tmux-calls))
        (should (some #(= ["set-hook" "-t" "uml-viewer-mine-abc" "-u" "pane-died"] %) @tmux-calls))
        (should-not (some #(some #{"uml-viewer-grok"} %) @tmux-calls))
        (should-not (some #(some #{"other-session"} %) @tmux-calls))
        (should= 1 (count @scripts))
        (should (re-find #"whose id is 99" (first @scripts)))
        (should-not (re-find #"Claude" (first @scripts)))
        (should-be-nil @companion/!terminal-window-id)
        (should-be-nil @companion/!session-name))))

  (it "starts a per-project session, arms respawn, and records companion.edn"
    (let [root (str (System/getProperty "java.io.tmpdir")
                    "/uv-open-" (System/nanoTime))
          calls (atom [])]
      (.mkdirs (java.io.File. root))
      (mailbox/write-companion! root {:session "uml-viewer-old-ffff" :window-id "1"})
      (with-redefs [uml-viewer.adapters.companion/tmux! (fn [& args] (swap! calls conj (vec args)) 0)
                    uml-viewer.adapters.companion/run-osascript (fn [_] "1234")]
        (let [sid (companion/session-id root)
              out (companion/open-in-terminal! root)
              flat (mapcat identity @calls)]
          (should= sid (:session out))
          (should= "1234" (:window-id out))
          (should= sid (:session (mailbox/read-companion root)))
          (should= "1234" (:window-id (mailbox/read-companion root)))
          (should (some #(= ["kill-session" "-t" "uml-viewer-old-ffff"] %) @calls))
          (should (some #(= ["kill-session" "-t" sid] %) @calls))
          (should (some #(= "new-session" (first %)) @calls))
          (should (some #(= ["set-option" "-p" "-t" (str sid ":0.0") "remain-on-exit" "on"] %) @calls))
          (should (some #(= ["set-hook" "-t" sid "pane-died" "respawn-pane -k"] %) @calls))
          (should-not (some #{"uml-viewer-grok"} flat)))))))
