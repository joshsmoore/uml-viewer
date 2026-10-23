(() => {
  const $ = (id) => document.getElementById(id);
  const stage = $("stage");
  const card = $("card");
  const menu = $("menu");
  const source = $("source");

  function post(params) {
    return fetch("/event", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(params).toString(),
    })
      .then((r) => r.json())
      .then(handleReply)
      .catch(() => {});
  }

  let lastMove = null;
  let moveQueued = false;
  function queueMove(x, y) {
    lastMove = { x, y };
    if (moveQueued) return;
    moveQueued = true;
    requestAnimationFrame(() => {
      moveQueued = false;
      post({ type: "move", x: lastMove.x, y: lastMove.y });
    });
  }

  function sendSize() {
    post({ type: "resize", w: window.innerWidth, h: window.innerHeight });
  }

  function hideMenu() {
    menu.hidden = true;
    menu.replaceChildren();
  }

  let pointer = { x: 0, y: 0 };

  function showMenu(m) {
    menu.replaceChildren(
      ...m.items.map((item, index) => {
        const b = document.createElement("button");
        b.type = "button";
        b.textContent = item.label;
        b.addEventListener("click", () => {
          hideMenu();
          let value = "";
          if (item.prompt) {
            value = window.prompt(item.prompt, item.default || "");
            if (value === null) return;
          }
          post({ type: "menu", id: m.id, index, value });
        });
        return b;
      })
    );
    menu.hidden = false;
    const r = menu.getBoundingClientRect();
    menu.style.left = Math.min(pointer.x, window.innerWidth - r.width - 8) + "px";
    menu.style.top = Math.min(pointer.y, window.innerHeight - r.height - 8) + "px";
  }

  function showSource(page) {
    $("source-title").textContent = page.title;
    const frame = $("source-frame");
    frame.onload = () => {
      const here = frame.contentDocument && frame.contentDocument.querySelector("a[name=here]");
      if (here) here.scrollIntoView({ block: "center" });
    };
    frame.srcdoc = page.html;
    source.hidden = false;
  }

  function handleReply(reply) {
    if (!reply) return;
    if (reply.menu) showMenu(reply.menu);
    if (reply.source) showSource(reply.source);
  }

  stage.addEventListener("mousemove", (e) => queueMove(e.clientX, e.clientY));

  stage.addEventListener("mousedown", (e) => {
    pointer = { x: e.clientX, y: e.clientY };
    hideMenu();
    post({ type: "press", x: e.clientX, y: e.clientY, button: e.button, count: e.detail });
  });

  stage.addEventListener("contextmenu", (e) => e.preventDefault());

  stage.addEventListener(
    "wheel",
    (e) => {
      e.preventDefault();
      if (e.ctrlKey) {
        post({ type: "key", key: e.deltaY < 0 ? "+" : "-", ctrl: 1 });
        return;
      }
      const horizontal = e.shiftKey || Math.abs(e.deltaX) > Math.abs(e.deltaY);
      const delta = e.shiftKey && !e.deltaX ? e.deltaY : horizontal ? e.deltaX : e.deltaY;
      const px = e.deltaMode === 1 ? delta * 48 : delta;
      post({ type: "wheel", amount: px / 48, horizontal: horizontal ? 1 : 0 });
    },
    { passive: false }
  );

  window.addEventListener("keydown", (e) => {
    if (e.target && e.target.closest && e.target.closest("input, textarea")) return;
    if (e.key === "Escape" && !menu.hidden) return hideMenu();
    if (e.key === "Escape" && !source.hidden) {
      source.hidden = true;
      return;
    }
    const ctrl = e.ctrlKey || e.metaKey;
    if (ctrl && !["+", "=", "-", "_", "0"].includes(e.key)) return;
    if (e.key.startsWith("Arrow") || ctrl) e.preventDefault();
    post({ type: "key", key: e.key, ctrl: ctrl ? 1 : 0 });
  });

  window.addEventListener("resize", sendSize);
  document.addEventListener("mousedown", (e) => {
    if (!menu.hidden && !menu.contains(e.target)) hideMenu();
  });

  $("card-body").addEventListener("click", (e) => {
    const svg = $("card-body").querySelector("svg");
    if (!svg) return;
    const r = svg.getBoundingClientRect();
    post({ type: "card-press", y: e.clientY - r.top });
  });
  $("card-close").addEventListener("click", () => post({ type: "card-close" }));
  $("source-close").addEventListener("click", () => (source.hidden = true));
  $("quit").addEventListener("click", () => {
    if (window.confirm("Quit the viewer and its Claude session?")) post({ type: "quit" });
  });

  function draggable(panel, handle) {
    handle.addEventListener("mousedown", (e) => {
      if (e.target.closest("button")) return;
      const start = { x: e.clientX, y: e.clientY, left: panel.offsetLeft, top: panel.offsetTop };
      const move = (m) => {
        panel.style.left = start.left + m.clientX - start.x + "px";
        panel.style.top = Math.max(0, start.top + m.clientY - start.y) + "px";
      };
      const up = () => {
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
      };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
      e.preventDefault();
    });
  }
  draggable(card, $("card-head"));
  draggable(source, source.querySelector("header"));

  let shownCard = "";
  function showCard(c) {
    if (!c.svg) {
      card.hidden = true;
      shownCard = "";
      return;
    }
    $("card-title").textContent = c.title;
    if (c.title !== shownCard) $("card-body").scrollTop = 0;
    shownCard = c.title;
    $("card-body").innerHTML = c.svg;
    card.hidden = false;
  }

  function connect() {
    const events = new EventSource("/events");
    events.addEventListener("open", () => {
      $("status").hidden = true;
      sendSize();
    });
    events.addEventListener("frame", (e) => {
      if (e.data) stage.innerHTML = e.data;
    });
    events.addEventListener("card", (e) => showCard(JSON.parse(e.data || "{}")));
    events.addEventListener("error", () => {
      $("status").hidden = false;
    });
  }

  connect();
})();
