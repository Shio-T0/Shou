// Shou phone remote — sends control commands to the server and mirrors the
// kiosk state live. Everything is gated by a shared token carried in the URL.

const TOKEN =
  window.SHOU_TOKEN ||
  new URLSearchParams(location.search).get("k") ||
  "";

const el = {
  conn: document.getElementById("conn"),
  connText: document.getElementById("conn-text"),
  hero: document.getElementById("hero"),
  bg: document.getElementById("mirror-bg"),
  cover: document.getElementById("mirror-cover"),
  view: document.getElementById("mirror-view"),
  index: document.getElementById("mirror-index"),
  title: document.getElementById("mirror-title"),
  episode: document.getElementById("mirror-episode"),
  progress: document.getElementById("mirror-progress"),
  toast: document.getElementById("toast"),
  seg: document.getElementById("listseg"),
  segBtns: Array.from(document.querySelectorAll("#listseg .seg-btn")),
  resume: document.getElementById("resume"),
  resumeRail: document.getElementById("resume-rail"),
};

let toastTimer = null;
function toast(msg) {
  el.toast.textContent = msg;
  el.toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.toast.classList.remove("show"), 1100);
}

// --- Commands ---------------------------------------------------------------
const LABELS = {
  open: "Opening…",
  left: "◀",
  right: "▶",
  select: "Select",
  back: "Back to UI",
  pause: "Play / Pause",
  prev: "Previous episode",
  next: "Next episode",
  rew: "« 30s",
  fwd: "30s »",
};

async function send(act) {
  if (navigator.vibrate) navigator.vibrate(12);
  toast(LABELS[act] || act);
  try {
    await fetch(`/${act}?k=${encodeURIComponent(TOKEN)}`, { method: "POST" });
  } catch (e) {
    toast("⚠ no connection");
  }
}

document.querySelectorAll("[data-act]").forEach((btn) => {
  btn.addEventListener("click", () => send(btn.dataset.act));
});

// List switcher (Watching / Planned) -> POST /list?to=<mode>
const LIST_LABEL = { watching: "Watching", planned: "Planned" };
async function switchList(mode) {
  if (navigator.vibrate) navigator.vibrate(12);
  toast(LIST_LABEL[mode] || mode);
  try {
    await fetch(`/list?to=${mode}&k=${encodeURIComponent(TOKEN)}`, { method: "POST" });
  } catch (e) {
    toast("⚠ no connection");
  }
}
el.segBtns.forEach((btn) => {
  btn.addEventListener("click", () => switchList(btn.dataset.list));
});

// --- Continue watching ------------------------------------------------------
function escapeHtml(s) {
  const d = document.createElement("div");
  d.textContent = s == null ? "" : String(s);
  return d.innerHTML;
}
function fmtTime(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const mm = String(m).padStart(h ? 2 : 1, "0");
  const ss = String(s).padStart(2, "0");
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

async function resume(mediaId, ep, title) {
  if (navigator.vibrate) navigator.vibrate(12);
  toast("Resuming " + (title || ""));
  try {
    await fetch(
      `/resume?media_id=${encodeURIComponent(mediaId)}&episode=${encodeURIComponent(ep)}&k=${encodeURIComponent(TOKEN)}`,
      { method: "POST" }
    );
  } catch (e) {
    toast("⚠ no connection");
  }
}

let resumeSig = "";
function renderResume(history) {
  history = history || [];
  const sig = history
    .map((e) => `${e.media_id}:${e.episode}:${Math.round(e.position || 0)}`)
    .join("|");
  if (sig === resumeSig) return;
  resumeSig = sig;

  if (!history.length) {
    el.resume.classList.add("hidden");
    el.resumeRail.innerHTML = "";
    return;
  }
  el.resumeRail.innerHTML = "";
  history.forEach((e) => {
    const pct = e.duration
      ? Math.min(100, Math.round((e.position / e.duration) * 100))
      : Math.round(e.percent || 0);
    const coverStyle = e.cover
      ? `background-image:url(${e.cover})`
      : `background-color:${e.color || "#1f2233"}`;
    const card = document.createElement("button");
    card.type = "button";
    card.className = "rcard";
    card.innerHTML = `
      <span class="rcard-cover" style="${coverStyle}">
        <span class="rcard-play"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg></span>
      </span>
      <span class="rcard-meta">
        <span class="rcard-title">${escapeHtml(e.title)}</span>
        <span class="rcard-ep">EP ${escapeHtml(e.episode)} · ${fmtTime(e.position)}</span>
      </span>
      <span class="rcard-bar"><span style="width:${pct}%"></span></span>`;
    card.addEventListener("click", () => resume(e.media_id, e.episode, e.title));
    el.resumeRail.appendChild(card);
  });
  el.resume.classList.remove("hidden");
}

// --- Live mirror via SocketIO ----------------------------------------------
const VIEW_LABEL = {
  grid: "Browsing",
  sequel: "Sequel found",
  playing: "Now playing",
  loading: "Loading",
  empty: "Nothing to watch",
  error: "Error",
};

const socket = io({ query: { k: TOKEN } });

socket.on("connect", () => {
  el.conn.classList.add("live");
  el.conn.classList.remove("down");
  el.connText.textContent = "live";
});
socket.on("disconnect", () => {
  el.conn.classList.remove("live");
  el.conn.classList.add("down");
  el.connText.textContent = "offline";
});

socket.on("state", (s) => {
  el.view.textContent = VIEW_LABEL[s.view] || s.view;

  renderResume(s.history);

  if (s.list) {
    el.segBtns.forEach((b) => b.classList.toggle("active", b.dataset.list === s.list));
    el.seg.classList.toggle("is-planned", s.list === "planned");
  }

  setIndex("");
  setProgress(0);
  el.hero.classList.remove("is-caughtup");

  if (s.view === "grid" && s.items && s.items.length) {
    const it = s.items[s.cursor] || s.items[0];
    el.title.textContent = it.title;
    el.episode.textContent = it.episodeText + (it.caughtUp ? "  ·  caught up" : "");
    setCover(it.cover, it.banner, it.color);
    setIndex(s.cursor + 1 + " / " + s.items.length);
    const denom = it.available || it.total;
    setProgress(denom ? Math.min(100, Math.round((it.progress / denom) * 100)) : 0);
    el.hero.classList.toggle("is-caughtup", !!it.caughtUp);
  } else if (s.view === "sequel" && s.sequel) {
    el.title.textContent = s.sequel.sequel_title;
    el.episode.textContent = "Finished " + s.sequel.finished + " — Select to watch";
  } else if (s.view === "playing") {
    el.title.textContent = s.playing ? s.playing.title : "Playing";
    el.episode.textContent = s.playing ? "Episode " + s.playing.episode : s.message || "";
    setProgress(100);
  } else {
    el.title.textContent = s.message || VIEW_LABEL[s.view] || "Shou";
    el.episode.textContent = "";
    setCover("", "", "#100f13");
  }
});

function setIndex(text) {
  if (el.index) el.index.textContent = text;
}
function setProgress(pct) {
  if (el.progress) el.progress.style.width = pct + "%";
}

let lastCover = null;
function setCover(cover, banner, color) {
  if (cover !== lastCover) {
    el.cover.src = cover || "";
    lastCover = cover;
  }
  el.bg.style.backgroundColor = color || "#17161c";
  el.bg.style.backgroundImage = banner
    ? `url(${banner})`
    : cover
    ? `url(${cover})`
    : "none";
}

// --- PWA service worker (registers only in a secure context; no-ops on http) -
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}
