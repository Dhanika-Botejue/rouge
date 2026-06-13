// Rouge sketch canvas — freehand pen strokes + draggable component markers,
// posted to the mod's localhost bridge for AI compilation.

const BRIDGE = "http://127.0.0.1:25599/build";

const board = document.getElementById("board");
const ctx = board.getContext("2d");
const statusEl = document.getElementById("status");

// Visual style per component type.
const COMPONENTS = {
  lever:    { color: "#2faf5a", letter: "L", label: "lever" },
  button:   { color: "#2f9faf", letter: "B", label: "button" },
  lamp:     { color: "#e0a92a", letter: "O", label: "lamp" },
  repeater: { color: "#3a6fd0", letter: "R", label: "repeater" },
  torch:    { color: "#d04a3a", letter: "T", label: "torch" },
  dust:     { color: "#8a2b2b", letter: "·", label: "dust" },
};

let tool = "pen";
let strokes = [];        // array of strokes; each stroke is array of [x,y]
let components = [];      // { type, x, y } in canvas pixels
let drawing = false;
let current = null;

// --- Tool selection ---
document.querySelectorAll(".tool").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tool").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    tool = btn.dataset.tool;
  });
});

document.getElementById("clear").addEventListener("click", () => {
  strokes = [];
  components = [];
  redraw();
  setStatus("", "");
});

// --- Pointer handling ---
function pos(e) {
  const r = board.getBoundingClientRect();
  return [
    ((e.clientX - r.left) / r.width) * board.width,
    ((e.clientY - r.top) / r.height) * board.height,
  ];
}

board.addEventListener("pointerdown", (e) => {
  board.setPointerCapture(e.pointerId);
  const [x, y] = pos(e);
  if (tool === "pen") {
    drawing = true;
    current = [[x, y]];
  } else if (tool === "erase") {
    eraseNear(x, y);
    redraw();
  } else if (COMPONENTS[tool]) {
    components.push({ type: tool, x, y });
    redraw();
  }
});

board.addEventListener("pointermove", (e) => {
  if (!drawing) return;
  const [x, y] = pos(e);
  current.push([x, y]);
  redraw();
  drawStroke(current);
});

board.addEventListener("pointerup", () => {
  if (drawing && current && current.length > 1) strokes.push(current);
  drawing = false;
  current = null;
});

function eraseNear(x, y) {
  // Remove the nearest stroke or component within a small radius.
  const R = 22;
  let bestIdx = -1, bestDist = R * R, kind = null;
  components.forEach((c, i) => {
    const d = (c.x - x) ** 2 + (c.y - y) ** 2;
    if (d < bestDist) { bestDist = d; bestIdx = i; kind = "comp"; }
  });
  strokes.forEach((s, i) => {
    for (const [px, py] of s) {
      const d = (px - x) ** 2 + (py - y) ** 2;
      if (d < bestDist) { bestDist = d; bestIdx = i; kind = "stroke"; }
    }
  });
  if (bestIdx >= 0 && kind === "comp") components.splice(bestIdx, 1);
  else if (bestIdx >= 0 && kind === "stroke") strokes.splice(bestIdx, 1);
}

// --- Rendering ---
function drawStroke(s) {
  if (s.length < 2) return;
  ctx.strokeStyle = "#444";
  ctx.lineWidth = 3;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.beginPath();
  ctx.moveTo(s[0][0], s[0][1]);
  for (let i = 1; i < s.length; i++) ctx.lineTo(s[i][0], s[i][1]);
  ctx.stroke();
}

function drawComponent(c) {
  const spec = COMPONENTS[c.type];
  ctx.fillStyle = spec.color;
  ctx.beginPath();
  ctx.arc(c.x, c.y, 14, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillStyle = "#fff";
  ctx.font = "bold 15px sans-serif";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText(spec.letter, c.x, c.y);
  ctx.fillStyle = "#333";
  ctx.font = "11px sans-serif";
  ctx.fillText(spec.label, c.x, c.y + 24);
}

function redraw() {
  ctx.clearRect(0, 0, board.width, board.height);
  strokes.forEach(drawStroke);
  components.forEach(drawComponent);
}

// --- Build / send ---
function setStatus(msg, kind) {
  statusEl.textContent = msg;
  statusEl.className = "status" + (kind ? " " + kind : "");
}

function norm(v, max) {
  return Math.round((v / max) * 1000) / 1000;
}

document.getElementById("build").addEventListener("click", async () => {
  const note = document.getElementById("note").value.trim();
  const difficulty = document.getElementById("difficulty").value;

  if (components.length === 0 && strokes.length === 0) {
    setStatus("Draw something first — add a couple of components and a wire.", "err");
    return;
  }

  const payload = {
    note,
    difficulty,
    components: components.map((c) => ({ type: c.type, x: norm(c.x, board.width), y: norm(c.y, board.height) })),
    strokes: strokes.map((s) => s.map(([x, y]) => [norm(x, board.width), norm(y, board.height)])),
    image: board.toDataURL("image/png"),
  };

  const btn = document.getElementById("build");
  btn.disabled = true;
  setStatus("Sending to Minecraft…", "");

  try {
    const res = await fetch(BRIDGE, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    if (res.ok) {
      setStatus("Sent! Rouge is compiling your circuit — watch the in-game chat.", "ok");
    } else {
      setStatus("Bridge responded " + res.status + ". Is Minecraft running with Rouge?", "err");
    }
  } catch (e) {
    setStatus("Couldn't reach Minecraft on " + BRIDGE + ". Launch the game (Rouge starts the bridge).", "err");
  } finally {
    btn.disabled = false;
  }
});

redraw();
