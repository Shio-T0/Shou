// AnimeUI front-end — a pure live view driven by SocketIO "state" events.
// All control comes from the phone (KDE Connect -> server REST -> SocketIO).

const socket = io();

const el = {
  backdrop: document.getElementById("backdrop"),
  count: document.getElementById("count"),
  listlabel: document.getElementById("listlabel"),
  stage: document.getElementById("stage"),
  carousel: document.getElementById("carousel"),
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

let cardEls = [];
let renderedSig = "";

// How many neighbours to keep visible on each side of the centre card.
const VISIBLE = 3;

function buildCards(items) {
  el.carousel.innerHTML = "";
  cardEls = items.map((it) => {
    const card = document.createElement("div");
    card.className = "card";

    if (it.cover) {
      const img = document.createElement("img");
      img.src = it.cover;
      img.alt = it.title;
      card.appendChild(img);
    } else {
      card.style.background = it.color || "#15172a";
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

    el.carousel.appendChild(card);
    return card;
  });
}

function layout(items, cursor) {
  const spacing = 19; // vw between adjacent cards
  cardEls.forEach((card, i) => {
    const offset = i - cursor;
    const abs = Math.abs(offset);
    card.classList.remove("active", "dim", "hiddenCard");

    if (abs > VISIBLE) {
      card.classList.add("hiddenCard");
      card.style.transform =
        `translateX(${offset * spacing}vw) scale(0.4)`;
      return;
    }

    const scale = offset === 0 ? 1.18 : Math.max(0.66, 0.92 - abs * 0.08);
    const translate = offset * spacing;
    const rotate = offset === 0 ? 0 : (offset > 0 ? -22 : 22);
    const z = -abs * 40;
    card.style.transform =
      `translateX(${translate}vw) translateZ(${z}px) rotateY(${rotate}deg) scale(${scale})`;
    card.style.zIndex = String(100 - abs);

    if (offset === 0) card.classList.add("active");
    else card.classList.add("dim");
  });
}

function updateInfo(items, cursor) {
  const it = items[cursor];
  if (!it) return;
  el.infoTitle.textContent = it.title;
  el.infoEpisode.textContent = it.episodeText;
  const denom = it.available || it.total;
  const pct = denom ? Math.min(100, Math.round((it.progress / denom) * 100)) : 0;
  el.infoBar.style.width = pct + "%";
  el.backdrop.style.backgroundColor = it.color || "#15172a";
  el.backdrop.style.backgroundImage = it.banner
    ? `url(${it.banner})`
    : it.cover
    ? `url(${it.cover})`
    : "none";
}

function showOnly(view) {
  el.stage.classList.toggle("hidden", view !== "grid");
  el.sequelView.classList.toggle("hidden", view !== "sequel");
  el.playingView.classList.toggle("hidden", view !== "playing");
  const isStatus = view === "loading" || view === "empty" || view === "error";
  el.statusView.classList.toggle("hidden", !isStatus);
}

const LIST_LABEL = { watching: "WATCHING", planned: "PLAN TO WATCH" };

socket.on("state", (s) => {
  showOnly(s.view);
  if (s.list && el.listlabel) el.listlabel.textContent = LIST_LABEL[s.list] || s.list;

  if (s.view === "grid") {
    const sig = s.items.map((i) => i.id).join(",");
    if (sig !== renderedSig) {
      buildCards(s.items);
      renderedSig = sig;
    }
    el.count.textContent = `${s.cursor + 1} / ${s.items.length}`;
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
  }
});
