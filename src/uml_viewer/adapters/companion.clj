(ns uml-viewer.adapters.companion
  "The companion Claude: its tmux session, its Terminal window, and mail
  that wakes it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.adapters.palette :as palette]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.domain.mailbox :as mailbox]
            [uml-viewer.domain.policy :as policy]))

(def standing-rules
  (str "You are the UML-viewer companion. The current working directory is the\n"
       "project being examined (the one whose diagram is on screen), not\n"
       "necessarily uml-viewer itself.\n"
       "On launch: inspect this directory's source, write or update a\n"
       "hierarchical policy from the real namespaces (dots after the ns prefix\n"
       "are the tree; do not invent Domain/Engine/Adapters packages), then\n"
       "regenerate the IR (`clj -M:ir` or this project's equivalent) so the\n"
       "diagram matches. Do not edit the generated EDN by hand.\n"
       "After every later source or policy change:\n"
       "1. Keep the policy as namespace nesting only. Do not re-home a ns to\n"
       "   fake a layer/component. Layer and component mean the same thing.\n"
       "   If the tree is wrong, change the requires or the ns.\n"
       "   Preserve :proposals (named components that are not namespaces). Do not\n"
       "   invent :proposals on launch. The inspector lists them; click the real\n"
       "   diagram above Proposals to return to the namespace tree. If instructed,\n"
       "   add a named proposal to :proposals in the policy (default name is a\n"
       "   timestamp) and regenerate the IR.\n"
       "TypeScript, Rust, Python, and Ruby are scanners beside Clojure. A Clojure project keeps\n"
       ":lang :clojure (the default) and the CRAP and mutation steps below.\n"
       "Another language sets :lang. More than one language sets :sources, each\n"
       "entry {:lang :root :prefix?}. The policy :prefix is stripped to make\n"
       "ids. A source :prefix is that tree's namespace root, for example\n"
       "bookwriter.rust beside TypeScript modules under bookwriter. Dots in the\n"
       "module id are still the tree. Do not invent packages. A TypeScript\n"
       "invoke(\"name\") depends on the Rust #[tauri::command] of that name.\n"
       "Python is one class per .py module. import and from-import are dependencies.\n"
       "class Child(Base) is inheritance when Base is a project module. A base of\n"
       "Protocol or ABC is implements.\n"
       "Ruby is one class per .rb file. A Rails app sets :lang :ruby and :src at\n"
       "the project root (app and lib are scanned) with :prefix \"app\".\n"
       "Constants resolve as Zeitwerk autoloads them. class X < Base is inheritance,\n"
       "include/extend/prepend is implements, and associations are dependencies.\n"
       "For a non-Clojure language, run that language's CRAP and mutation tools\n"
       "when they exist. Snapshots join by the class :ns, then by the class id.\n"
       "Then regenerate the IR.\n"
       "2. Run clj -M:crap.\n"
       "3. Run clj -M:mutate on each changed file under src/ (differential).\n"
       "   Uncovered mutants are coverage gaps: keep the snapshot; do not\n"
       "   re-run the file or pass --mutate-all because of them.\n"
       "4. Regenerate the IR so the EDN mtime updates.\n"
       "Mailbox: .uml-viewer/to-agent.edn and to-viewer.edn are queues\n"
       "{:next-id n :queue [cmd …]} (atomic: tmp then rename). Pop the head of\n"
       ":queue as you handle it (rewrite the file). Oldest first. Ops are\n"
       "{:id n :op :display :path \"...\"}, {:id n :op :regen},\n"
       "{:id n :op :quit-for-restart}, {:id n :op :context ...},\n"
       "{:id n :op :review-pr :pr 2245} (diagram GitHub PR 2245 against its\n"
       " base without checking it out; omit :pr to return to the working tree),\n"
       "and right-click element ops:\n"
       "{:id n :op :refresh-crap :target {...}}, :refresh-mutate,\n"
       ":refresh-mutate-all, :omit. :target is {:id :ns :kind :class|:component\n"
       " :proposal-id?}. For :refresh-crap run clj -M:crap (that class or the\n"
       " files under that component) then IR. For :refresh-mutate run\n"
       " clj -M:mutate on those src files (differential). For\n"
       " :refresh-mutate-all pass --mutate-all on those files. For :omit, if\n"
       " :proposal-id is set add :id to that proposal's :omit; otherwise add it\n"
       " to policy :omit. Then regenerate the IR.\n"
       ":context means the inspector selection is the discussion context:\n"
       "{:context :real} for the namespace tree, or {:context :proposal\n"
       " :proposal-id id :name \"...\"} for a named proposal. Treat that as\n"
       "the architecture under discussion until a later :context arrives.\n"
       "A tmux wake-up means mail is waiting. If idle, pop and handle each\n"
       "to-agent command in order. If busy, finish first. Do not send tmux yourself.\n"
       "After regen, write :display with the generated EDN path.\n"
       "Prefer ./uml (fresh start: spawn this companion, wait for :display)\n"
       "and ./uml --restart (new JVM, keep this session, restore last view).\n"
       "If ./uml is missing, use aliases :uml-viewer and :uml-viewer-restart,\n"
       "or tell the user to run get-uml-viewer in this directory (it clones\n"
       "uml-viewer into gitignored .uml-viewer/uml-viewer/).\n"
       "Do not start the viewer on launch; it reloads EDN when the file mtime\n"
       "changes. To restart it: write :quit-for-restart, wait for the JVM to\n"
       "exit, then ./uml --restart. Do not pass --restart except through that\n"
       "wrapper (or :uml-viewer-restart). Do not SIGKILL. The viewer is a local\n"
       "web page; ./uml opens it. Quit on that page kills only this companion's\n"
       "tmux session, not other Claude agents. If\n"
       "this Claude process dies, tmux respawns it in the same pane.\n"
       "Do not commit or push unless asked.\n"))

(def launch-prompt
  (str "On launch: from this working directory, update the hierarchical policy "
       "to match the project's namespaces (no invented layers/components), regenerate the "
       "IR, then wait for directives."))

(defn claude-executable
  []
  (let [home (System/getenv "HOME")
        named (System/getenv "CLAUDE_BIN")
        candidates (filter identity
                           [named
                            (when home (str home "/.local/bin/claude"))
                            (when home (str home "/.claude/local/claude"))
                            "/usr/local/bin/claude"
                            "/opt/homebrew/bin/claude"])]
    (or (first (filter (fn [p]
                         (let [f (io/file p)]
                           (and (.isFile f) (.canExecute f))))
                       candidates))
        "claude")))

(defonce !session-name (atom nil))

(defn session-id
  "Tmux session unique to this project directory."
  [cwd]
  (let [path (.getCanonicalPath (io/file (or cwd ".")))
        base (-> (.getName (io/file path))
                 (str/replace #"[^A-Za-z0-9_-]" "-"))
        h (Integer/toHexString (hash path))]
    (str "uml-viewer-" base "-" h)))

(defn current-session
  "Session name for this project, from companion.edn or the live atom."
  ([] (current-session (System/getProperty "user.dir")))
  ([root]
   (or (:session (mailbox/read-companion root))
       @!session-name
       (session-id root))))

(defn tmux!
  "Run tmux with `args`. Returns the process exit code (1 if tmux is missing)."
  [& args]
  (try
    (let [p (.start (ProcessBuilder. (into-array String (cons "tmux" args))))]
      (.waitFor p))
    (catch Exception _ 1)))

(defn session-candidates
  "Names to try: companion.edn, the live atom, then this project's id."
  [root]
  (->> [(:session (mailbox/read-companion root))
        @!session-name
        (when root (session-id root))]
       (filter seq)
       distinct
       vec))

(defn live-session
  "First candidate that tmux currently has, or nil."
  [root]
  (first (filter #(zero? (tmux! "has-session" "-t" %))
                 (session-candidates root))))

(defn rgb-16
  "Terminal.app AppleScript colors are 16-bit (0–65535)."
  [[r g b]]
  [(* (int r) 257) (* (int g) 257) (* (int b) 257)])

(defn- applescript-rgb [c]
  (let [[r g b] (rgb-16 c)]
    (str "{" r ", " g ", " b "}")))

(defn new-session-args
  ([cwd] (new-session-args cwd (session-id cwd)))
  ([cwd session]
   ["new-session" "-d" "-s" session "-c" cwd
    "-e" "COLORTERM=truecolor"
    (claude-executable) "--dangerously-skip-permissions"
    "--append-system-prompt" standing-rules
    launch-prompt]))

(defn kill-session-args
  ([] (kill-session-args (current-session)))
  ([session]
   ["kill-session" "-t" session]))

(def wake-message
  "You have mail from the viewer. If idle, read .uml-viewer/to-agent.edn.")

(defn notify-steps
  "Wake-up: literal text, pause, then CR. Claude Code reads LF (C-j) as a
  newline in the prompt, so none is sent."
  ([] (notify-steps (current-session)))
  ([session]
   [["send-keys" "-t" session "-l" wake-message]
    [:sleep 150]
    ["send-keys" "-t" session "C-m"]]))

(defn notify-agent!
  "Wake the companion Claude session. Returns false if tmux/session is missing."
  ([] (notify-agent! (System/getProperty "user.dir")))
  ([root]
   (if-let [session (live-session root)]
     (do
       (reset! !session-name session)
       (doseq [step (notify-steps session)]
         (if (= :sleep (first step))
           (Thread/sleep (long (second step)))
           (apply tmux! step)))
       true)
     false)))

(defn request-agent!
  "Queue `op` for the companion and wake Claude. Returns {:cmd :woke?}."
  [root op extra]
  (let [cmd (mailbox/write-command! (mailbox/to-agent root) op extra)]
    {:cmd cmd :woke? (notify-agent! root)}))

(defn request-regen!
  "Queue a :regen command and wake Claude. Returns {:cmd :woke?}."
  [root]
  (request-agent! root :regen {}))

(defn mail-context!
  "Tell the companion which diagram is under discussion."
  [state]
  (when-let [path (:path state)]
    (let [root (overlay/metrics-root path)]
      (if-let [id (:proposal-id state)]
        (request-agent! root :context
                        {:context :proposal
                         :proposal-id id
                         :name (:name (policy/proposal-by-id (:doc state) id))})
        (request-agent! root :context {:context :real}))))
  state)

(defn terminal-title
  ([] (terminal-title (current-session)))
  ([session]
   (or session "UML Claude")))

(defonce !terminal-window-id (atom nil))

(defn attach-command
  ([] (attach-command (current-session)))
  ([session]
   (str "tmux attach -t " session "; exit")))

(defn osascript
  "AppleScript that opens a Terminal window on `shell-cmd`, painted like the diagram.
  Raises only that window, not every Terminal window. Returns the new window id."
  ([shell-cmd] (osascript shell-cmd (current-session)))
  ([shell-cmd session]
   (let [title (terminal-title session)]
     (str "tell application \"Terminal\"\n"
          "launch\n"
          "set claudeTab to do script " (pr-str shell-cmd) "\n"
          "set background color of claudeTab to " (applescript-rgb palette/bg) "\n"
          "set normal text color of claudeTab to " (applescript-rgb palette/ink) "\n"
          "set bold text color of claudeTab to " (applescript-rgb palette/gold) "\n"
          "set cursor color of claudeTab to " (applescript-rgb palette/gold) "\n"
          "set font name of claudeTab to \"Menlo\"\n"
          "set font size of claudeTab to 13\n"
          "set custom title of claudeTab to \"" title "\"\n"
          "set title displays custom title of claudeTab to true\n"
          "set title displays device name of claudeTab to false\n"
          "set title displays shell path of claudeTab to false\n"
          "set title displays settings name of claudeTab to false\n"
          "set winID to id of front window\n"
          "end tell\n"
          "tell application \"System Events\"\n"
          "tell process \"Terminal\"\n"
          "try\n"
          "perform action \"AXRaise\" of (first window whose name contains \"" title "\")\n"
          "end try\n"
          "end tell\n"
          "end tell\n"
          "return winID"))))

(defn close-terminal-script
  "AppleScript that closes only this viewer's Terminal window by id."
  ([] (close-terminal-script nil))
  ([win-id]
   (str "tell application \"System Events\"\n"
        "if not (exists process \"Terminal\") then return\n"
        "end tell\n"
        "tell application \"Terminal\"\n"
        (when win-id
          (str "try\n"
               "close (first window whose id is " win-id ") saving no\n"
               "end try\n"))
        "end tell")))

(defn run-osascript
  "Run `script` with osascript. Returns trimmed stdout, or \"\"."
  [script]
  (try
    (let [p (.start (doto (ProcessBuilder. (into-array String ["osascript" "-e" script]))
                      (.redirectErrorStream true)))
          out (slurp (.getInputStream p))]
      (.waitFor p)
      (str/trim out))
    (catch Exception _ "")))

(defn close-terminal-window!
  "Close the Terminal window that attached to the Claude session."
  []
  (run-osascript (close-terminal-script @!terminal-window-id))
  (reset! !terminal-window-id nil))

(defn kill-companion-session!
  "Drop this project's tmux session only. Unhook respawn so the pane stays dead."
  [session]
  (when (seq session)
    (tmux! "set-hook" "-t" session "-u" "pane-died")
    (apply tmux! (kill-session-args session))))

(defn- arm-respawn!
  "If Claude dies, tmux restarts that pane only — not other agents."
  [session]
  (let [pane (str session ":0.0")]
    (tmux! "set-option" "-p" "-t" pane "remain-on-exit" "on")
    (tmux! "set-hook" "-t" session "pane-died" "respawn-pane -k")
    (tmux! "set-option" "-t" session "status" "off")))

(defn open-in-terminal!
  "Start this project's companion Claude in its own tmux session."
  ([] (open-in-terminal! (System/getProperty "user.dir")))
  ([cwd]
   (let [session (session-id cwd)
         previous (:session (mailbox/read-companion cwd))]
     (reset! !session-name session)
     (when (and previous (not= previous session))
       (kill-companion-session! previous))
     (kill-companion-session! session)
     (let [code (apply tmux! (new-session-args cwd session))]
       (when-not (zero? code)
         (binding [*out* *err*]
           (println "UML viewer: could not start tmux session" session)))
       (when (zero? code)
         (arm-respawn! session)))
     (let [script (osascript (attach-command session) session)
           out (run-osascript script)
           win-id (re-find #"\d+" out)]
       (reset! !terminal-window-id win-id)
       (mailbox/write-companion! cwd {:session session :window-id win-id})
       {:script script :session session :window-id win-id}))))

(defn shutdown-children!
  "Kill only this viewer's tmux session and its Terminal window."
  ([] (shutdown-children! (System/getProperty "user.dir")))
  ([root]
   (let [info (mailbox/read-companion root)
         session (or (:session info) @!session-name)
         win (or (:window-id info) @!terminal-window-id)]
     (kill-companion-session! session)
     (when win
       (run-osascript (close-terminal-script win)))
     (reset! !terminal-window-id nil)
     (reset! !session-name nil))))

(defn remember-companion!
  "On --restart, bind this JVM to the existing tmux session so mail can wake it."
  [root]
  (let [info (mailbox/read-companion root)
        session (live-session root)]
    (when session
      (reset! !session-name session)
      (when-let [win (:window-id info)]
        (reset! !terminal-window-id win))
      (mailbox/write-companion! root (merge (or info {}) {:session session}))
      session)))
