// Shou kiosk — a pure live view driven by SocketIO "state" events.
// All control comes from the phone (KDE Connect / web remote -> server REST -> SocketIO).

const socket = io();

const el = {
  backdropA: document.getElementById("backdrop-a"),
  backdropB: document.getElementById("backdrop-b"),
  count: document.getElementById("count"),
  listlabel: document.getElementById("listlabel"),
  stage: document.getElementById("stage"),
  carousel: document.getElementById("carousel"),
  info: document.getElementById("info"),
  infoKicker: document.getElementById("info-kicker"),
  infoTitle: document.getElementById("info-title"),
  infoEpisode: document.getElementById("info-episode"),
  infoBar: document.getElementById("info-bar"),
  sequelView: document.getElementById("sequel-view"),
  seqFinished: document.getElementById("seq-finished"),
  seqTitle: document.getElementById("seq-title"),
  playingView: document.getElementById("playing-view"),
  playingMsg: document.getElementById("playing-msg"),
  statusView: document.getElementById("status-view"),
  statusSpinner: document.getElementById("status-spinner"),
  statusMsg: document.getElementById("status-msg"),
};

const LIST_LABEL = { watching: "WATCHING", planned: "PLAN TO WATCH" };

let cardEls = [];
let renderedSig = "";

// How many neighbours to keep visible on each side of the centre card.
const VISIBLE = 3;

function buildCards(items) {
  el.carousel.innerHTML = "";
  cardEls = items.map((it, i) => {
    const card = document.createElement("div");
    card.className = "card intro";
    card.style.animationDelay = Math.min(i, 8) * 55 + "ms";

    if (it.cover) {
      const img = document.createElement("img");
      img.src = it.cover;
      img.alt = it.title;
      card.appendChild(img);
    } else {
      card.style.background = it.color || "#17161c";
    }

    const badge = document.createElement("div");
    if (it.caughtUp) {
      badge.className = "badge done";
      badge.textContent = "CAUGHT UP";
    } else {
      badge.className = "badge";
      badge.textContent = "EP " + ((it.progress || 0) + 1);
    }
    card.appendChild(badge);

    // Drop .intro once the cascade-in finishes — a *filling* animation keeps
    // overriding `transform` in the cascade, which would suppress the coverflow
    // glide transition on every later selection change.
    card.addEventListener("animationend", () => card.classList.remove("intro"), { once: true });

    el.carousel.appendChild(card);
    return card;
  });
}

function layout(items, cursor) {
  const spacing = 18; // vw between adjacent cards
  cardEls.forEach((card, i) => {
    const offset = i - cursor;
    const abs = Math.abs(offset);
    card.classList.remove("active", "dim", "hiddenCard");

    if (abs > VISIBLE) {
      card.classList.add("hiddenCard");
      card.style.transform = `translateX(${offset * spacing}vw) scale(0.4)`;
      return;
    }

    const scale = offset === 0 ? 1.2 : Math.max(0.64, 0.9 - abs * 0.08);
    const translate = offset * spacing;
    const rotate = offset === 0 ? 0 : (offset > 0 ? -24 : 24);
    const z = -abs * 44;
    card.style.transform =
      `translateX(${translate}vw) translateZ(${z}px) rotateY(${rotate}deg) scale(${scale})`;
    card.style.zIndex = String(100 - abs);

    if (offset === 0) card.classList.add("active");
    else card.classList.add("dim");
  });
}

function setTitle(text) {
  if (el.infoTitle.textContent === text) return;
  el.infoTitle.textContent = text;
  el.infoTitle.classList.remove("swap");
  void el.infoTitle.offsetWidth; // reflow to restart the animation
  el.infoTitle.classList.add("swap");
}

// Crossfade between two stacked backdrop layers (background-image can't transition).
let frontBd = el.backdropA;
let backBd = el.backdropB;
let lastBg = null;
function setBackdrop(banner, cover, color) {
  const img = banner ? `url(${banner})` : cover ? `url(${cover})` : "none";
  const key = img + "|" + (color || "");
  if (key === lastBg) return;
  lastBg = key;
  backBd.style.backgroundColor = color || "#17161c";
  backBd.style.backgroundImage = img;
  backBd.classList.add("is-on");
  frontBd.classList.remove("is-on");
  const tmp = frontBd;
  frontBd = backBd;
  backBd = tmp;
}

function updateInfo(items, cursor) {
  const it = items[cursor];
  if (!it) return;
  el.info.classList.toggle("caughtup", !!it.caughtUp);
  el.infoKicker.textContent = it.caughtUp
    ? "CAUGHT UP"
    : (currentList === "planned" ? "PLAN TO WATCH" : "NOW WATCHING");
  setTitle(it.title);
  el.infoEpisode.textContent = it.episodeText;
  const denom = it.available || it.total;
  const pct = denom ? Math.min(100, Math.round((it.progress / denom) * 100)) : 0;
  el.infoBar.style.width = pct + "%";
  setBackdrop(it.banner, it.cover, it.color);
}

function showOnly(view) {
  el.stage.classList.toggle("hidden", view !== "grid");
  el.sequelView.classList.toggle("hidden", view !== "sequel");
  el.playingView.classList.toggle("hidden", view !== "playing");
  const isStatus = view === "loading" || view === "empty" || view === "error";
  el.statusView.classList.toggle("hidden", !isStatus);
}

let currentList = "watching";

socket.on("state", (s) => {
  showOnly(s.view);
  if (s.list) {
    currentList = s.list;
    el.listlabel.textContent = LIST_LABEL[s.list] || s.list;
  }

  if (s.view === "grid") {
    const sig = s.items.map((i) => i.id).join(",");
    if (sig !== renderedSig) {
      buildCards(s.items);
      renderedSig = sig;
    }
    el.count.textContent = s.items.length ? `${s.cursor + 1} / ${s.items.length}` : "";
    layout(s.items, s.cursor);
    updateInfo(s.items, s.cursor);
  } else if (s.view === "sequel" && s.sequel) {
    el.seqFinished.textContent = s.sequel.finished;
    el.seqTitle.textContent = s.sequel.sequel_title;
  } else if (s.view === "playing") {
    el.playingMsg.textContent = s.message || "Launching…";
  } else {
    // loading / empty / error
    el.statusSpinner.style.display = s.view === "loading" ? "block" : "none";
    el.statusMsg.textContent =
      s.message || (s.view === "empty" ? "Nothing to watch." : "Loading…");
    setBackdrop("", "", "#0e0d11");
    el.count.textContent = "";
  }
});
