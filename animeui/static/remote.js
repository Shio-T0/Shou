// AnimeUI phone remote — sends control commands to the server and mirrors the
// kiosk state live. Everything is gated by a shared token carried in the URL.

const TOKEN =
  window.ANIMEUI_TOKEN ||
  new URLSearchParams(location.search).get("k") ||
  "";

const el = {
  conn: document.getElementById("conn"),
  bg: document.getElementById("mirror-bg"),
  cover: document.getElementById("mirror-cover"),
  view: document.getElementById("mirror-view"),
  title: document.getElementById("mirror-title"),
  episode: document.getElementById("mirror-episode"),
  toast: document.getElementById("toast"),
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
  el.conn.textContent = "live";
  el.conn.classList.add("live");
  setTimeout(() => el.conn.classList.add("hide"), 900);
});
socket.on("disconnect", () => {
  el.conn.textContent = "reconnecting…";
  el.conn.classList.remove("live", "hide");
});

socket.on("state", (s) => {
  el.view.textContent = VIEW_LABEL[s.view] || s.view;

  if (s.view === "grid" && s.items && s.items.length) {
    const it = s.items[s.cursor] || s.items[0];
    el.title.textContent = it.title;
    el.episode.textContent = it.episodeText + (it.caughtUp ? "  ·  caught up" : "");
    setCover(it.cover, it.banner, it.color);
  } else if (s.view === "sequel" && s.sequel) {
    el.title.textContent = s.sequel.sequel_title;
    el.episode.textContent = "Finished " + s.sequel.finished + " — Select to watch";
  } else if (s.view === "playing") {
    el.title.textContent = s.playing ? s.playing.title : "Playing";
    el.episode.textContent = s.playing ? "Episode " + s.playing.episode : s.message || "";
  } else {
    el.title.textContent = s.message || VIEW_LABEL[s.view] || "AnimeUI";
    el.episode.textContent = "";
    setCover("", "", "#15172a");
  }
});

let lastCover = null;
function setCover(cover, banner, color) {
  if (cover !== lastCover) {
    el.cover.src = cover || "";
    lastCover = cover;
  }
  el.bg.style.backgroundColor = color || "#15172a";
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
