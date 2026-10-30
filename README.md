# UML viewer
## Abstract

This project displays a project as UML. The UML is *dynamic*. You can click
elements to drill down — all the way to source code. You can also play
what-if games by proposing architecture changes.

The app is coupled to an agent assistant. The two communicate, so you can
display the UML, drill down, tell the agent what you don't like, and watch
it make the changes. Describe a proposal and the agent will build a new
diagram. Keep revising that proposal until you like it, then tell the
agent to make the code match.

Color coding comes from CRAP and mutation metrics. You can adjust the
thresholds in config if you like.

The viewer and agent treat namespaces as components. Modules inside those
namespaces are the components' elements. Nesting can be arbitrarily deep.
You build an architecture by moving modules into the right namespaces with
the right dependencies.

You choose architectural levels by telling the agent what coupling and
cohesion criteria to use. For example, I tell my agent to group modules
into components by the Common Closure Principle.

Dependencies that point from a higher level to a lower level are colored
red, in accordance with the Dependency Rule of Clean Architecture.

The bottom line: you can view the structure of your code, see how well
tests cover it, play what-if games, and manipulate the system from a high
level.

## Tech

A local web app that lays out and draws UML from an EDN IR. A **policy** plus
a language-specific parser write the topology; this tool displays it, routes
the arrows, colors CRAP, and lets you click.

The IR is **topology**: the namespace tree, classes, and edges. **Metrics**
(CC, coverage, CRAP, killed/survived/uncovered) come from `.metrics/`
snapshots produced by [crap4clj](https://github.com/unclebob/crap4clj) and
[clj-mutate](https://github.com/unclebob/clj-mutate). The viewer overlays
those files at load, keyed by namespace and function name. Agents edit the
policy, not the IR. See [Policy](#policy).

## Run

### Prerequisites (macOS, Homebrew)

The viewer needs Java 21+ and the Clojure CLI. The companion needs tmux
and Claude Code. Pull-request review needs `gh`, signed in.

```bash
brew install openjdk@21 clojure/tools/clojure tmux gh
sudo ln -sfn "$(brew --prefix openjdk@21)/libexec/openjdk.jdk" \
  /Library/Java/JavaVirtualMachines/openjdk-21.jdk
java -version                  # 21 or newer
clojure -version
gh auth login                  # once, for --pr
```

`claude` must be on your `PATH` (set `CLAUDE_BIN` to use another binary).
The project must be a git repository; branch and PR review read `origin`.

### Install

Clone uml-viewer once, anywhere, and put its `bin` on your `PATH`:

```bash
git clone https://github.com/joshsmoore/uml-viewer.git ~/uml-viewer
export PATH="$HOME/uml-viewer/bin:$PATH"   # in your shell rc
```

`uml` runs from the root of the project you want to view. It launches the
viewer from the checkout it lives in, so `git pull` there updates every
project. In each project, gitignore what the viewer writes:

```
.uml-viewer/
uml-viewer-log.txt
```

Commit `examples/<project>.policy.edn` (layers, prefix, omits, edge
kinds, proposals). The generated `examples/<project>.edn` and `.metrics/`
are derived from the code; gitignore them too. The companion gets its
rules from the viewer at spawn, so the project needs no rules file.

### Start, and find the port

```bash
cd /path/to/the-project
uml                            # viewer + companion; opens the browser
uml --pr 2245                  # review GitHub PR 2245 (see below)
cat .uml-viewer/web.edn        # {:port 2xxxx, :url "http://127.0.0.1:2xxxx/"}
open "$(sed -E 's/.*:url "([^"]+)".*/\1/' .uml-viewer/web.edn)"
```

`uml` returns immediately; the viewer is its own JVM running in the
background. It serves the diagram on `127.0.0.1` and opens it in your
browser. The port is fixed per project, in 20000–29999 (the next free one
within 20 if that is taken, else any), and written with the URL to
**`.uml-viewer/web.edn`**. Reopen the page from there after closing the tab:
closing the tab does not stop the viewer. A restarted viewer keeps the
port, so an open page reconnects on its own. The server renders SVG and
pushes each frame over server-sent events; the page sends clicks, keys,
and scrolls back. Exceptions and JVM output append to
**`uml-viewer-log.txt`** in the project (gitignored).

A fresh start also opens a **tmux** session unique to the project
(`uml-viewer-<project>-<hash>`, recorded in `.uml-viewer/companion.edn`)
running interactive Claude Code there, in a Terminal window. On start it
writes a hierarchical policy from the project's namespaces and regenerates
the IR; until it sends `:display` the canvas reads **Waiting for agent to
create diagram.** (`R` loads the current EDN without waiting). Type to the
companion in that window; Esc is the real TUI interrupt. It also runs CRAP,
mutation, and IR generation after later changes. **Quit** on the page kills
**only that** tmux session and its window, not other Claude agents; if the
Claude process dies, tmux respawns it in the same pane. Project-wide rules
live in `.claude/rules/uml-viewer.md`.

| Command | Who | What |
|---------|-----|------|
| `uml` | anyone | Fresh viewer in a background JVM, opened in the browser. Starts the companion. Waits for `:display`. |
| `uml --pr N` (or `-pr N`) | anyone | Same, reviewing pull request N against its base. |
| `uml --restart [--pr N\|local]` | **associated agent only** | New JVM, same companion. Restores the last view, including the reviewed PR; `--pr` switches it. |
| `uml --help` | anyone | Usage. |

Do **not** pass `--restart` unless you are that companion recycling the
viewer. A stray `--restart` skips spawning Claude and leaves a diagram
with no agent. To recycle: write `:quit-for-restart` to
`.uml-viewer/to-viewer.edn`, wait for the JVM to exit, then
`uml --restart`. The new JVM restores depth, pan, zoom, proposal, the
B and C toggles, and the PR under review (`.uml-viewer/session.edn`). Do
not SIGKILL. Aliases `:uml-viewer` / `:uml-viewer-restart` still work where
present.

A missing or unreadable file shows `file not found: …` in the inspector
instead of throwing.

### Developing uml-viewer

From **this repo**:

```bash
clj -M:ir                            # policy → examples/uml-viewer.edn
clj -M:run                           # holds the terminal; prefer uml elsewhere
clj -M:run examples/library.edn
clj -M:run --help
clj -M:spec
clj -M:cov
clj -M:crap                          # writes .metrics/crap.edn
clj -M:mutate src/uml_viewer/engine/layout.clj
```

This project's `:crap` alias uses `../clojure/crap4clj`. `:mutate` pins
[clj-mutate](https://github.com/unclebob/clj-mutate) by git SHA. This repo
commits its own `.metrics/` and generated IR so a clone shows its diagram
with numbers; a project using the viewer should not.

Rename or move of a function is a new form: overlay does not match old names.

## Reviewing a branch or pull request

On a branch, or for a pull request, the diagram marks what changed since
the work left its base. The base is the merge-base, so later work on the
base is not counted.

**Where the base comes from.** On a local branch it is policy
`:base-branch`, else the remote's default branch (`origin/HEAD`), else
`main`, else `master`. Uncommitted edits and new files count. On the base
itself, or outside git, nothing is marked. A checkout, commit, or stage
refreshes the marks.

**Pull requests, without a checkout.** `uml --pr 2245`, or ask the
companion (it sends `{:op :review-pr :pr 2245}`). The viewer fetches
`pull/2245/head` into `refs/uml/pr/2245`, takes the PR's base from `gh`
(else `:base-branch`, else the default base), scans the PR head with the
diagram's own policy, and marks it against the merge-base exactly as for
a local branch. A stacked PR is compared with the branch it targets. The
working tree is never touched, and source views show files as of the PR
head. **R** re-fetches; offline, the last fetch is used. A PR that cannot be
read shows the working tree with an error. `--pr local` on restart, or
`:review-pr` without `:pr`, returns to the working tree. The reviewed PR
survives a restart.

**What is marked.**

| Mark | Meaning |
|------|---------|
| Cyan outline | Class whose file changed |
| Cyan outline, `new` badge | Class added |
| Magenta outline, `deleted` badge | Class removed: shown again from the base |
| `n changed` on a component | How many changed classes are inside, so you can drill toward the work |
| Cyan arrow | New relationship, or a component arrow bundling some changed pairs |
| Magenta arrow | Relationship removed: shown again from the base |
| Dimmed box (with **C**) | Unchanged class kept only as the far end of a changed arrow |

The banner names the base and commit (`PR #2245 vs origin/main @ 5bc30d0 ·
3 classes changed`). Hovering an arrow lists each class pair with
`· new`, `· removed`, or `· changed`.

**Keys.** **B** hides or shows every mark. **C** switches between the full
diagram and only the changes: changed classes, the unchanged far ends of
changed arrows (dimmed), and arrows that touch a change.

**The class card.** A changed class's card says *Changed*, *New*, or
*Deleted on this branch*, and tags each member `· changed`, `· new`, or
`· deleted`, including private ones. A member spans from its declaration
to where it ends by indentation, never into the next member. Ruby Grape
endpoints are members too (`GET auth/callback`), so a change inside an
endpoint marks that endpoint. Deleted members return where they stood in
the base.

**Source.** Clicking a member of a changed file opens it with the branch
diff: added and changed lines green, removed lines red. A deleted file
opens as all-removed; a deleted member opens its file with the diff.

Deleted classes, members, and arrows come from scanning the merge-base with
the same policy. Each scanned commit is cached in
`.uml-viewer/base/<commit>-<stamp>.edn`; the stamp changes when the policy
or the language scanner does.

## Navigation

**Layer** and **component** mean the same thing: a namespace grouping
(the first segment after the prefix, or a named proposal group).

- First view: **namespace components** (layers). Dependencies between them
  collapse to one arrow. Each component lists nested namespaces.
- Double-click a component to open the next level. Esc or the ← label goes
  up a level, including after drilling a proposal group. Esc does not quit.
  **Quit** (top right) or a `:quit-for-restart` mail message exits the app.
- Hover an arrow for a popup of every class-level `from -> to` it bundles,
  in any declutter mode. Click the arrow to pin that list (Esc or a click
  elsewhere drops it). A pinned list shows each pair's **call sites** under
  it, as `member:line → call`. Click a pair or a site to open the `from`
  file with every site of that pair underlined in the arrow's color (red
  violating, cyan new, magenta removed, grey otherwise), scrolled to the
  one clicked. Under review, the file is read as of the PR head. Ruby and
  Python record sites; for other languages, or a pair with none, the line
  that first refers to the `to` class is underlined instead: a line naming
  its full namespace, else an import naming its last segment, else its
  class name. Violating pairs are red and listed first; a long
  list ends with `… n more`. Inside a drilled view, a port (the stub for an
  arrow that leaves the view) is red when any pair through it is violating,
  and hovering it shows the same list.
- On a branch or a pull request, changed, new, and deleted classes,
  members, and arrows are marked; **B** hides the marks and **C** shows only
  the changes. See [Reviewing a branch or pull request](#reviewing-a-branch-or-pull-request).
- Right-click a class or component for **Refresh CRAP**, **Refresh
  Mutation**, **Refresh All Mutation**, or **Omit**. Each writes
  `to-agent.edn` and wakes the companion. CRAP runs on that class or the
  files under that component. Mutation is differential `clj -M:mutate` on
  those src files; All Mutation passes `--mutate-all`. Omit adds the id to
  the current proposal's `:omit`, or to policy `:omit` on the real diagram.
- Component titles paint **on top of** crossing arrows.
- The inspector lists the **real diagram** (the namespace tree) above
  **Proposals**. Click the real row to return to the tree; click a proposal
  to show it (marked as not in the code). Either click writes `:context`
  so the companion treats that diagram as the discussion. **New Proposal**
  adds a timestamp-named proposal. Right-click to rename or delete.
  **Declutter** cycles Declutter arrows / Remove arrows / Declutter
  elements / Declutter classes / Declutter none. **Remove arrows** hides
  the lines and puts a triangle on the top (incoming) and bottom
  (outgoing) of each box, **including nested classes** as well as their
  packages. A triangle is red if any bundled pair is violating. Hover it
  for the `from -> to` list.
- Double-click a leaf module for its **class card**.
- The class card names the **module** (`:ns`). Click it to open that source
  file at the top. Hover a member to highlight it; click it to open the same
  file at the defn. See [Source extractors](#source-extractors).
- Methods on the card are `+` public and `-` private. Private methods
  (`defn-`, Ruby `private`, Python `_name`) are not drawn on the class box.
- Abstract classes show a white **α** in the upper-right; interfaces a white
  **I**. Names of rectangles that are not classes (components/layers,
  interfaces, enumerations, package banners) are italic. Foreign libraries
  listed in policy are ovals outside the components.
- The page fills the browser window and follows its size. The diagram is
  zoomed to fit the window (up to 150%) and refits when the window is
  resized, a diagram loads, or you drill in or out.
- Scroll to pan vertically; Shift-scroll (or left/right arrows) for
  horizontal. Pan can follow arrows that bow past the origin.
- **Ctrl+** (or **Ctrl+=**) zooms in 10%; **Ctrl-** zooms out 10%. Zooming
  by hand stops the fitting; **Ctrl+0** fits the window again. **Cmd** works
  the same, and a trackpad pinch zooms. Zoom keeps the view center still.
- **Regen** in the inspector asks the companion to rewrite policy and IR
  (see [Companion mailbox](#companion-mailbox)).
- `R` reloads the current EDN. The watcher also reloads on save, and when
  `.metrics/` snapshots change. An open class card updates with the new
  numbers.
- The class card and source views are panels on the page; drag them by
  their title bar. `Esc` closes the source view, then the class card (it
  does not quit the viewer).

## Policy

This project's diagram is **generated**. Do not edit `examples/uml-viewer.edn`.
Edit `examples/uml-viewer.policy.edn`, then run `clj -M:ir` (or press Regen).

The **parser** (`LanguageGraph`) reads source and emits facts. Clojure is
one class per namespace. A `:require` or `:use` of another project
namespace is a `:dependency`. `requiring-resolve` of a quoted var is a
`:dependency` on that var's namespace. `defprotocol` is
`:stereotype :interface`. `defrecord` or `deftype` of a protocol is
`:implements`. External `:require`s and `:import`s become **foreign**
classes. The overlay fills Clojure members from `.metrics/`. TypeScript,
Rust, Python, and Ruby are separate scanners (see
[Language graphs](#language-graphs)): one class per module, with exported
members as `:ops`. The overlay joins each snapshot by `:ns`, then by
the class id.

### Do not invent layers (components)

The tree **is** the namespaces. After `:prefix`, every `.` is a nesting
level. `uml-viewer.engine.layout` is a child of `engine`.
`uml-viewer.clojure-language.source-clojure` is a child of `clojure-language`.
The policy does **not** assign nses to invented packages. If you want Domain /
Engine / Adapters boxes **in the source tree**, those segments must exist as
namespaces. To **view** a grouping that is not in the code, use `:proposals`
(see [Proposed components](#proposed-components)) — do not rewrite namespaces.

To write a policy for a project:

1. Set `:prefix` to the namespace prefix (`foo` for `foo.bar.baz`). A
   single language also sets `:src` (default `src`) and `:lang` when the
   language is not Clojure. More than one language sets `:sources`
   instead of `:src`.
2. Set `:hierarchical true` (or omit `:packages` and `:diagrams`).
3. List top-level **segments** in `:order` — the first dotted part after
   the prefix, in the order you want the boxes. Do not invent names.
4. List real libraries in `:foreign` if they should appear as ovals.
5. Optionally override a require with `:edge-kinds {[:from :to] :association}`
   using the **leaf** ids (`clojure-language.source-clojure`, not
   `clojure-language`).
6. Set `:levels` so the generator can mark dependency-rule violations
   (see [Dependency rule](#dependency-rule)).
7. Optionally set `:proposals` to name design components that are not namespaces
   (see [Proposed components](#proposed-components)).
8. Run `clj -M:ir` (or Regen).

If `foo.bar` and `foo.bar.baz` both exist, the `bar` box lists `bar` (the
module) and `baz` (the child). Module titles are the last namespace
segment (`layout`, not `engine.layout`). A hyphenated segment capitalizes
each part (`source-clojure` → `SourceClojure`). A segment that already
contains a capital keeps that spelling and capitalizes the first letter
(`tauriFs` → `TauriFs`). Double-click the component to open that level;
double-click the `bar` module line for its class card.

Wrong (invented partitions):

```edn
:packages [{:id :domain :nses [ir geom source]}
           {:id :engine :nses [layout route]}]
```

Right (the ns tree):

```edn
{:title "UML viewer"
 :src "src"
 :prefix "uml-viewer"
 :lang :clojure
 :out "examples/uml-viewer.edn"
 :hierarchical true
 :foreign [quil]
 :order [main adapters application engine source graph
         clojure-language typescript-language rust-language python-language
         domain]
 :levels [[domain source graph clojure-language typescript-language
           rust-language python-language]
          [engine]
          [application]
          [adapters]
          [main]]
 :edge-kinds {[:engine.compose :engine.layout] :association}}
```

A project with more than one language lists each tree under `:sources`.
The policy `:prefix` is stripped from ids. A source `:prefix` is that
tree's namespace root. `src/model.ts` becomes `bookwriter.model`
(`:model`). `src-tauri/src/lib.rs` becomes `bookwriter.rust` (`:rust`).
`main.rs` becomes `bookwriter.rust.main` (`:rust.main`).

```edn
{:title "Bookwriter"
 :prefix "bookwriter"
 :sources [{:lang :typescript :root "src"}
           {:lang :rust :root "src-tauri/src" :prefix "bookwriter.rust"}]
 :foreign [at.tauri-apps markdown-it highlight.js node tauri serde std]
 :hierarchical true}
```

| Key | Role |
|-----|------|
| `:prefix` | Strip this from each ns; remaining dots are the tree |
| `:hierarchical` | Namespace tree (default when `:packages` is omitted) |
| `:order` | Order of **existing** top-level ns segments, not new component names |
| `:levels` | Groups of those segments, **inner (higher-level) first**. Same group = same rank |
| `:proposals` | Named groupings of real segments; **not** instantiated in source. Inspector **Real diagram** row returns to the ns tree |
| `:omit` | On a proposal or the policy: nses (and their children) left off the diagram |
| `:edge-kinds` | Override parser kind for `[from to]` (usually `:association`) |
| `:omit-edges` | Drop `[from to]` |
| `:lang` | Scanner when `:sources` is absent (default `:clojure`) |
| `:src` | Source root when `:sources` is absent |
| `:sources` | One scan per `{:lang :root :prefix?}`. The entry `:prefix` is that tree's namespace root |
| `:base-branch` | Branch to compare against for change highlighting. Default: `origin/HEAD`, then `main`, then `master` |
| `:foreign` | External libs as ovals. A listed prefix collapses `quil.core` to `quil`. Unlisted externals are dropped |

**Viewer Claude loop** (passed with `--append-system-prompt` to the companion session only)

On launch: from the examined directory, write or update the hierarchical
policy and regenerate the IR, then wait.

After **every** later source or policy change, refresh metrics and
regenerate the IR.

For Clojure that is `clj -M:crap`, then `clj -M:mutate` on the changed
`src/` files, then `clj -M:ir`. Uncovered mutants that remain are coverage
gaps. Keep the snapshot. Do not re-run the file or force a full mutation
because mutate exited non-zero.

For another language, run that language's CRAP and mutation tools when
they exist. The overlay joins each snapshot by `:ns`, then by the class
id. Missing CRAP or mutation data is red. Then regenerate the IR.

- Add/rename/delete a namespace: the tree updates on `clj -M:ir`. Put a new
  top-level **segment** in `:order` if you care about box order.
- Nested nses appear as contents of the parent component.
- “This require is really an association”: one `:edge-kinds` entry.
- Show a library like quil as an oval: add it to `:foreign`.
- Do not add `:packages` to fake Clean Architecture components. Use
  `:proposals` to view a grouping that is not in the code.
- Preserve `:proposals` when rewriting policy. Do not invent them on launch.
  If instructed, add a named proposal (default name is a timestamp).

Hand-written sample IRs (e.g. `examples/library.edn`) are still valid; they
are not generated.

### Dependency rule

A `:dependency` edge is **violating** when it runs from a **higher-level**
(inner) component to a **lower-level** (outer) one. That is the Clean
Architecture dependency rule: source-code dependencies point inward.

Evaluation is deterministic given `:levels`:

1. Take the first dotted segment of each end (`engine.layout` → `engine`).
2. Look up that segment in `:levels`. Rank is the group's index; **smaller
   is inner / higher-level**.
3. If both ends have a rank and `from-rank < to-rank`, the edge is
   `:violating true`. Same rank is allowed. `:implements` and
   `:association` are never violating. Foreign / unranked ends are not
   compared.
4. Collapsed component arrows keep the flag if any bundled leaf dependency
   was violating. Remapping a pair to `:association` clears it.

`:order` is visual box order, not rank. Nesting is not layering: you cannot
infer inner vs outer from the namespace tree alone, so `:levels` must group
segments that sit at the same architectural level (e.g. `domain`, `source`,
and `graph`). Omit `:levels` and nothing is marked. If `:levels` is omitted
and `:proposals` is set, rank follows the first proposal's component order.

### Proposed components

`:proposals` is a list of named groupings of **existing** top-level segments.
Those names are not namespaces. Each item is `{:id :name :layers [...] :omit [...]}`
(`:layers` here are named components; `:omit` keeps listed nses off
**Unassigned**). The as-is diagram stays the ns tree.
The inspector lists the real diagram (the namespace tree) just above
**Proposals**; click it to return to the tree. Click a proposal to show it
(canvas marked **PROPOSAL — not instantiated in code**). Either click
tells the companion that diagram is the context of discussion. **New
Proposal** adds an empty proposal named with a timestamp. Right-click a
name to rename or delete it. Double-click a ns box to drill; **←** at the
top returns to the proposal.

The **Declutter** button cycles **Declutter arrows** (one arrow per
component pair per direction) → **Remove arrows** (triangles on each box
and on nested classes instead of lines; hover lists deps; red if any pair
is violating) →
**Declutter elements** (also hide nested names, members, and ports) →
**Declutter classes** (also hide classes inside components) →
**Declutter none**.

When a proposal is shown, rank follows **that** proposal's component order
and violating arrows are re-evaluated. The real diagram uses `:levels`.
A component inherits the **max** level of its elements.
Class boxes show the current view's rank (innermost **0**)
at the upper left; the class card repeats **Level n**. Level 0 is drawn at
the **bottom**. Good arrows (outer → inner) point down; violating arrows
(inner → outer) point up and stay red. When arrows are collapsed,
selecting a class highlights the component arrows it belongs to.
Collapsed components keep their color
and C/M dots; double-click still opens a component.

```edn
:proposals [{:id :ccp
             :name "2026-09-18 10:30:00"
             :layers [{:id :playfield :label "Playfield"
                       :nses [entities world missiles cities batteries flyers]}
                      {:id :hosts :label "Hosts" :nses [jvm browser]}
                      {:id :jvm :label "JVM"
                       :nses [jvm.cli jvm.main
                              {:id :quil-swing :label "Quil/Swing"
                               :nses [jvm.sketch jvm.window]}]}]
             :omit [cli]}]
```

Companion Claude must not invent `:proposals` on launch and must keep them when
updating `:order`. If instructed, add a named proposal and regenerate the IR.

The viewer draws a violating arrow **red**, and **bold red** when a selected
element highlights it. Hand-written IR may set `:violating true` directly.

## Companion mailbox

The viewer and the companion Claude talk through `.uml-viewer/` in the examined
project (gitignored). The file is the mail; tmux is only a doorbell.

| File | Direction |
|------|-----------|
| `.uml-viewer/to-viewer.edn` | Claude → viewer |
| `.uml-viewer/to-agent.edn` | viewer → Claude |

Each mailbox file is a small queue `{:next-id n :queue [cmd …]}` (tmp-then-rename).
Commands have a rising `:id`. Append; do not overwrite. Handling a command
**pops** it from `:queue` and rewrites the file (oldest first). The viewer
does this itself on `to-viewer.edn`. The companion must pop each
`to-agent.edn` command as it handles it.

`.uml-viewer/session.edn` is the last view (depth, pan, zoom, proposal) written
on `:quit-for-restart` and restored by `--restart`. `.uml-viewer/companion.edn`
records this viewer's tmux session and Terminal window id so close/kill never
touches another project's agent.

| `:op` | Meaning |
|-------|---------|
| `:display` | Viewer loads `:path` (relative to the project root) |
| `:regen` | Claude rewrites hierarchical policy, regenerates IR, then `:display` |
| `:quit-for-restart` | Viewer exits the JVM without killing Claude. The associated agent then runs `clj -M:uml-viewer-restart`. |
| `:refresh-crap` | Run CRAP on `:target` (class or component), then IR |
| `:refresh-mutate` | Differential mutate `:target`'s src files, then IR |
| `:refresh-mutate-all` | `clj -M:mutate --mutate-all` on `:target`'s src files, then IR |
| `:omit` | Add `:target` `:id` to proposal or policy `:omit`, then IR |
| `:context` | Inspector selection is the discussion context: `{:context :real}` or `{:context :proposal :proposal-id id :name "…"}` |

Clicking **Real diagram** or a proposal (including **New Proposal**) writes
`:context` and wakes Claude. Stay on that architecture until the next
`:context`.

Right-click ops include `:target {:id :ns :kind :class|:component :proposal-id?}`.
`:kind` is `:component` for a layer box (and its nested nses) and `:class`
for a module.

**Regen** in the inspector queues `:regen` and wakes Claude with literal text, a
150ms pause, then `C-m`. No `C-j` follows: Claude Code reads it as a
newline in the prompt. The wake-up
does not contain the command. If Claude is busy, it finishes first, then reads
the mailbox. If tmux is missing, the button still writes the file and the
inspector says the session is not attached.

## Language graphs

Generating the IR asks `uml-viewer.graph` to scan a source tree. `:lang`
selects the scanner (default `:clojure`). `:sources` scans each root and
merges them. Register another implementation with
`(graph/register! :java my-java-scanner)`. The scanner must satisfy
`LanguageGraph`:

| method | role |
|--------|------|
| `scan` | From a root and `{:prefix :ns-prefix?}`, return `{:classes :edges}` |

`:commands` and `:invokes` are optional. `merge-scans` turns a matching
command name into a `:dependency` and does not copy those keys onto a
class.

Classes are `{:id :name :ns :stereotype :lang :file}`. Edges are
`{:from :to :kind}` (`:dependency` or `:implements`). The policy layer is
language-neutral. Do not special-case a language in `policy`. Main
requires each scanner so it registers; `ir-generator` looks the scanner
up.

**Clojure** (`uml-viewer.clojure-language.graph-clojure`) reads `ns` forms
(including prefix lists), `requiring-resolve` of a quoted var (including
nested calls), `defprotocol`, `defrecord`, and `deftype`. It does not emit
`:ops`. Project classes carry `:lang :clojure` and `:file`.

**TypeScript** (`uml-viewer.typescript-language.graph-typescript`) emits
one class per module file (`.ts`, `.tsx`, `.js`, `.jsx`, `.mjs`, `.cjs`).
It skips `*.test.*`, `*.spec.*`, `*.d.ts`, and paths under `node_modules`,
`dist`, and `target`. A relative import is a dependency, and `import type`
counts. An asset import (CSS, images, JSON, and the like) is ignored. A
bare specifier is foreign: `@tauri-apps/api/core` becomes
`:at.tauri-apps.api.core`. A listed `:foreign` prefix such as
`at.tauri-apps` collapses that id. `export function f(): T` and
`export const x: T` are `:implements` when `T` is an imported project
name. `Promise<Book>` and `TreeNode[]` are not. A module that exports an
interface and no values is `:stereotype :interface`. Exported functions,
constants, classes, and enums are `:ops`. `invoke("name")` is recorded
for the cross-language link.

**Rust** (`uml-viewer.rust-language.graph-rust`) walks the modules
reachable from `lib.rs` and `main.rs` through `mod`. The `[lib] name` in
`Cargo.toml` is the crate name, so `bookwriter_lib::run()` in `main.rs`
depends on the lib class. Every Rust class is named by its source
file. The directory box keeps the namespace segment. `pub fn` functions
are `:ops`. `pub(crate)` functions are not,
unless they are `#[tauri::command]` functions, which are `:ops` even when
private. `impl Trait for Type` is `:implements` when the trait resolves
to a project module. The first segment of a `use`, other than `crate`,
`self`, `super`, or the lib crate, is a foreign crate.

**Python** (`uml-viewer.python-language.graph-python`) emits one class per
`.py` module. `__init__.py` is the package. Point `:src` at the package
directory or at its parent. A path that already begins with the
prefix stays as written, so `src/tools/parse.py` and
`src/tools/__init__.py` become `tools.parse` (`:parse`) and `tools`
(`:tools`). It skips `test_*.py`, `*_test.py`, `*_tests.py`,
`conftest.py`, and paths under `__pycache__`, `tests`, virtualenvs,
`dist`, and `build`. `import` and
`from ... import` are dependencies, including an import nested in a
function. A relative import (`from . import`, `from ..mod import`) stays
inside the package. A name that is not a project module is foreign
(`os.path` stays `os.path`, and a listed `:foreign` prefix such as `os`
collapses it). `class Dog(Animal)` is `:inheritance` when `Animal` is a
project module. A base of `Protocol` or `ABC` is `:implements`.
`def load() -> Animal` is `:implements` when `Animal` is a bare imported
project name. `list[Animal]` is not. Public module-level functions and
classes are `:ops`; each class (`:kind :class`) is followed by its
methods, named `Class.method` (`PartnersView.create`) with `:owner` the
class. The card lists `class PartnersView` with its methods indented under
it; the box shows `class PartnersView` and `PartnersView.create`. A `_name` function, method, or
class (and every method of a `_Name` class) is `:private`; a dunder
such as `__init__` is public. Nested functions and `def` inside a
docstring are skipped. A module whose public classes are only `Protocol` or
`ABC` bases, with no public functions, is `:stereotype :interface`. A
package's `__init__.py` importing a module inside that package is a
re-export, shown by nesting, not a dependency; its imports from outside
the package stay. Each later use of a name an import bound (or of a dotted
path a plain `import` named) is a **call site** on its edge, with the
function or `Class.method` it sits in and an attribute read off it, as
`:sites [{:line :in :call}]`. Uses inside strings and imports do not count.
At most 20 per edge.

**Ruby** (`uml-viewer.ruby-language.graph-ruby`) emits one class per `.rb`
file. Point `:src` at a Rails project root and it scans `app` and `lib`.
Pointed anywhere else, it scans that directory. With `:prefix "app"`,
`app/models/resume.rb` is `:models.resume` and `lib/tasks_helper.rb` is
`:lib.tasks-helper`. Underscores become hyphens. It skips `*_spec.rb`,
`*_test.rb`, and paths under `spec`, `test`, `config`, `db`, `views`,
`assets`, `javascript`, and `vendor`. The class is named by the constant
the file declares, so `ruby_llm_adapter.rb` is `RubyLLMAdapter`.

A `.rake` file is a class too: `lib/tasks/email_provider.rake` is
`:lib.tasks.email-provider`, named `email_provider.rake`. Its ops are its
methods, then its tasks named through their namespaces, such as
`email_provider:sync_hubspot`. It depends on the constants it uses, but it
defines no constant, so nothing depends on it.

Rails autoloads constants, so a reference needs no `require`. The scanner
reads each file's constant the way Zeitwerk does. Each `app/*` directory,
its `concerns`, and `lib` are roots. A constant resolves lexically,
innermost namespace first, so `StubAdapter.new` inside `module
RecurlyAdapter` depends on `RecurlyAdapter::StubAdapter` before any
top-level `StubAdapter`. `RubyLLMAdapter` and `ruby_llm_adapter` agree.
A module that only wraps files, such as `module PublicApi` with no
`public_api.rb`, is a namespace: a reference through it draws no edge to
whichever file happens to wrap it.
Constants in comments, strings, heredocs, and `%w[]` literals are ignored.

`class Resume < ApplicationRecord` is `:inheritance`. `include`, `extend`,
and `prepend` are `:implements`. A module that something includes, or
that extends `ActiveSupport::Concern`, is `:stereotype :interface`.
`belongs_to`, `has_one`, `has_many`, and `has_and_belongs_to_many` depend
on the target, by `class_name:` or the singular camelized name.
Polymorphic associations are skipped. `require_relative` depends on that
file. A superclass or mixin that is not in the project is foreign
(`ActiveRecord::Base` is `active-record.base`), unless it is Ruby core
such as `StandardError` or `Enumerable`. `require "json"` is foreign
`json`. Methods are `:ops`. Methods after a bare `private` or
`protected`, or written `private def`, are `:private`; `initialize` is
public. `def self.x` and methods in
`class << self` are `self.x`. HTTP endpoints declared as Grape (or
Sinatra-style) verb blocks are `:ops` too, after the methods, named by
verb and nested path: `get :callback do` inside `namespace :auth do` is
`GET auth/callback`, `route_param :id` adds `:id`, and a bare `get do`
is `GET /`. Their parsing lives in `uml-viewer.ruby-language.grape`, so a
branch change inside an endpoint marks that endpoint. Each constant
reference and association is a **call site** on its edge: `:sites
[{:line :in :call}]`, the line, the method or endpoint it sits in, and a
`.method` called on the constant. At most 20 per edge.

```clojure
{:lang :ruby :src "." :prefix "app"
 :foreign [active-record action-controller]}
```

`merge-scans` links a TypeScript `invoke("read_text")` to the Rust class
that owns `#[tauri::command] fn read_text`, as a `:dependency`. Two
project classes with the same id are an error. When a dependency and an
`:implements` edge join the same pair, the IR keeps `:implements`.

CRAP and mutation for TypeScript, Rust, Python, and Ruby come from separate
tools. The overlay joins a snapshot to the class whose `:ns` equals that
namespace. Otherwise the class id owns that name (`bookwriter.model`
owns `model`), a dotted child rolls up (`pdf` owns `pdf.Layout`), and
the policy prefix belongs to the single undotted Rust class. `::` is
read as `.`. A source file drawn inside its directory keeps its own
CRAP and mutation scores. The directory box pools the functions under
it, one weight per function. A file with no snapshot stays red, and in
that pool it counts as one red function per op, or one function when it
lists none. A class with no CRAP or mutation data is red.

## IR

A hierarchical policy writes one EDN document of all classes and edges
(`:hierarchical true`). The viewer builds each screen from the namespace tree
at the current drill level. A hand-written IR with `:packages` (or
`:diagrams`) is still a static diagram, e.g. `examples/library.edn`.

Metrics on the class card do not have to be authored. If `.metrics/` is
present, the overlay fills CC, coverage, CRAP, killed/survived/uncovered,
and any functions found in the snapshots (including privates). Authored
`:crap` / `:coverage` / `:ops` are the fallback when no snapshot exists.

The overlay joins a snapshot to the class whose `:ns` equals that
namespace. The generator writes `:ns` from the scanned ns. Hand-written
IR must set `:ns` the same way. When no class has that `:ns`, the class
id, a dotted child of that id, and the policy prefix on the single Rust
crate root are the fallbacks.

```edn
{:title "Lending library"
 :direction :tb
 :packages
 [{:id :domain
   :label "Domain"
   :classes
   [{:id :book
     :name "Book"
     :stereotype :class          ;; optional: :interface :enumeration :abstract
     :fields [{:name "isbn" :type "String"}]
     :ops [{:name "find" :args ["isbn"] :returns "Book"}]}]}
  {:id :app
   :label "Application"
   :classes
   [{:id :repo
     :name "CatalogRepo"
     :stereotype :interface
     :ops [{:name "get" :args ["isbn"] :returns "Book"}]}]}]
 :edges
 [{:from :sql-repo :to :repo :kind :implements}
  {:from :loan :to :book :kind :association :label "borrows"}]}
```

Optional authored metrics, used when snapshots are missing:

- `:crap` — a number (`μ`) or `{:mu :max :sigma}`
- `:coverage` — ratio 0–1 on a class or op
- `:cc`, `:killed`, `:survived`, `:uncovered`, `:private` on ops
- `:hide-members true` — compact box

Package and class **color** maps CRAP (`μ + σ`) and mutation score each onto
1–10 using `uml-viewer.domain.config` cutoffs, averages them, and paints a
0–10 red–green fill. Missing CRAP or mutation data counts as red (grade 1),
not unknown. A parent pools CRAP by function count: μ and σ of the
functions underneath, then the same grade. It pools mutation by summing
killed and survived. A child with no data tints that pool once per
function. It does not replace the parent. A **C** and **M** dot in
the upper-right show the two scores. The boxes no longer print μ / max / σ.

On the class card, a `Crap μ … max … σ …` line sits above the table (max is
the worst function in the namespace, not a sum). Column groups are labeled
`--crap--` (Crap, CC, Cov) and `--mutation--` (killed, survived, uncovered).
The class row shows average CRAP with a `μ` suffix and omits CC. Killed is
white. Survived and uncovered are green at 0 and red when nonzero. A row
with no mutation operators (`:sites` 0) shows `---no mutation sites---`.
A form with operators still shows killed/survived/uncovered, including zeros.

Edge `:kind` values:

| kind | line | head |
|------|------|------|
| `:inheritance` | solid grey | empty triangle |
| `:implements` | solid grey | empty triangle |
| `:association` | solid grey | open arrow |
| `:dependency` | solid grey | open arrow |
| `:aggregation` | solid grey | empty diamond |
| `:composition` | solid grey | filled diamond |

Layout follows Mermaid's three stages:

1. **Size** each class from its text (padding 12).
2. **Place** packages in document order; classes Sugiyama-ranked inside a
   package; ~40px spacing.
3. **Route** like Mermaid/ELK: ports on facing sides, orthogonal tracks in
   the rank gap, short same-rank connections through the stack gap (local U
   only when a sibling sits in the way), then stroke with D3 `curveBasis`
   cubics. Ordinary arrows are solid grey; violating dependencies are red
   (bold red when selected). Component titles draw after the arrows so
   names stay visible. Paths that pass the target and reverse are rejected.

## Source extractors

Clicking a member asks `uml-viewer.source` for the **whole file** and a
**start line**. The IR (and the class card) only supply an **identity
map**; a language-specific extractor turns that into
`{:title :file :body :line}`. The source view opens on that file and
scrolls to the member (highlighted). Clicking the module name opens the same
file at the top (`:line` omitted).

```clojure
(source/member-source {:lang :clojure
                       :ns "uml-viewer.engine.layout"
                       :name "layout"})
```

`:lang` selects the extractor (default `:clojure`). Register another
implementation with `(source/register! :java my-java-extractor)`. The
extractor must satisfy `LanguageSource`:

| method | role |
|--------|------|
| `locate` | path to the file that should contain the member |
| `extract` | slice that member out of the file text |
| `title` | window title |

**Clojure** (`uml-viewer.clojure-language.source-clojure`) maps `:ns` to
`src/...clj` (or `.cljc` / `.cljs`) and finds the top-level `(defn name …)` /
`(defn- name …)` so the window can jump to that line. It does not read
`:file`.

**TypeScript** (`uml-viewer.typescript-language.source-typescript`),
**Rust** (`uml-viewer.rust-language.source-rust`), **Python**
(`uml-viewer.python-language.source-python`), and **Ruby**
(`uml-viewer.ruby-language.source-ruby`) open `:file` and find the
exported declaration, the `fn`, the `def`, the `class`/`module`, or (Ruby)
the endpoint's verb block. The class card passes
`:lang` and `:file` from the class. A class with no `:lang` still uses
the Clojure extractor that Main passes in. The protocol is the seam; do
not special-case languages in the class card.

The UI lives in the adapters: `adapters.svg` renders, `adapters.viewer`
applies page events, `adapters.web` serves the page, and
`adapters.companion` runs the tmux session. Nothing depends on Quil, Swing,
or AWT.
