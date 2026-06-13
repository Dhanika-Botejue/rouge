#!/usr/bin/env python3
"""
Redstone Schematic Designer
===========================

Opens an interactive 2D circuit/PCB-style schematic editor in your default
browser. Drag Minecraft redstone parts (dust, torches, repeaters, comparators,
pistons, etc.) from the palette onto a grid, move/rotate them, and wire them
together with redstone connections.

Just run:

    python3 redstone_schematic.py

A tiny local web server is started (so canvas->PNG export and localStorage work
cleanly), your browser is opened automatically, and the server keeps running
until you press Ctrl+C in the terminal.

No third-party dependencies — standard library only.
"""

import functools
import http.server
import socket
import socketserver
import sys
import tempfile
import threading
import time
import webbrowser
from pathlib import Path

HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>Redstone Schematic Designer</title>
<style>
  :root{
    --bg:#0d1117; --panel:#161b22; --panel2:#1c2330; --line:#2b3340;
    --accent:#ff4d4d; --accent2:#36c2ff; --text:#e6edf3; --muted:#8b98a8;
  }
  *{box-sizing:border-box}
  html,body{height:100%;margin:0}
  body{
    font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
    background:var(--bg); color:var(--text); overflow:hidden;
    display:flex; flex-direction:column; user-select:none;
  }
  header{
    display:flex; align-items:center; gap:14px; padding:8px 14px;
    background:linear-gradient(180deg,#1b2330,#141a23);
    border-bottom:1px solid var(--line); flex:0 0 auto; z-index:5;
  }
  header h1{font-size:15px; margin:0; letter-spacing:.3px; display:flex; align-items:center; gap:8px}
  header h1 .dot{width:11px;height:11px;border-radius:50%;background:var(--accent);box-shadow:0 0 10px var(--accent)}
  .toolbar{display:flex; gap:6px; align-items:center; flex-wrap:wrap}
  .spacer{flex:1}
  button.tool{
    background:var(--panel2); color:var(--text); border:1px solid var(--line);
    padding:6px 11px; border-radius:8px; font-size:13px; cursor:pointer;
    display:inline-flex; align-items:center; gap:6px; transition:.12s;
  }
  button.tool:hover{border-color:#3d6cff66; background:#222c3c}
  button.tool.active{background:#27406b; border-color:var(--accent2); color:#fff}
  button.tool.danger:hover{border-color:#ff5a5a; background:#3a2024}
  .hint{color:var(--muted); font-size:12px}
  main{flex:1; display:flex; min-height:0}
  .palette{
    width:184px; flex:0 0 auto; background:var(--panel); border-right:1px solid var(--line);
    overflow-y:auto; padding:10px;
  }
  .palette h2{font-size:11px; text-transform:uppercase; letter-spacing:.12em; color:var(--muted); margin:6px 4px 10px}
  .pgrid{display:grid; grid-template-columns:1fr 1fr; gap:8px}
  .pitem{
    background:var(--panel2); border:1px solid var(--line); border-radius:10px;
    padding:7px 4px 5px; text-align:center; cursor:grab; transition:.12s;
  }
  .pitem:hover{border-color:var(--accent2); transform:translateY(-1px); background:#212a39}
  .pitem:active{cursor:grabbing}
  .pitem svg{width:44px; height:44px; display:block; margin:0 auto; pointer-events:none}
  .pitem .lbl{font-size:10px; color:var(--muted); margin-top:3px; line-height:1.15}
  .stage{position:relative; flex:1; min-width:0; background:var(--bg)}
  svg#canvas{width:100%; height:100%; display:block; cursor:default}
  svg#canvas.panning{cursor:grabbing}
  svg#canvas.wire{cursor:crosshair}
  .ghost{
    position:fixed; pointer-events:none; z-index:999; width:44px; height:44px;
    opacity:.85; transform:translate(-50%,-50%); filter:drop-shadow(0 4px 6px #000a);
  }
  .footer{
    position:absolute; left:12px; bottom:10px; font-size:12px; color:var(--muted);
    background:#0d1117cc; padding:6px 10px; border:1px solid var(--line); border-radius:8px;
    pointer-events:none; line-height:1.5;
  }
  .footer b{color:var(--text)}
  kbd{background:#222c3c;border:1px solid var(--line);border-bottom-width:2px;border-radius:5px;
      padding:0 5px; font-size:11px; font-family:inherit; color:var(--text)}
  .count{position:absolute; right:12px; bottom:10px; font-size:12px; color:var(--muted);
    background:#0d1117cc; padding:6px 10px; border:1px solid var(--line); border-radius:8px}
</style>
</head>
<body>
<header>
  <h1><span class="dot"></span> Redstone Schematic Designer</h1>
  <div class="toolbar">
    <button class="tool active" id="t-select" title="Select / move (V)">▣ Select</button>
    <button class="tool" id="t-wire" title="Draw redstone wire (W)">⟿ Wire</button>
    <button class="tool" id="b-rotate" title="Rotate selected (R)">⟳ Rotate</button>
    <button class="tool danger" id="b-delete" title="Delete selected (Del)">🗑 Delete</button>
  </div>
  <div class="spacer"></div>
  <div class="toolbar">
    <button class="tool" id="b-fit" title="Fit view to content (F)">⤢ Fit</button>
    <button class="tool" id="b-png" title="Export PNG">🖼 PNG</button>
    <button class="tool" id="b-save" title="Save .json">💾 Save</button>
    <button class="tool" id="b-load" title="Load .json">📂 Load</button>
    <button class="tool danger" id="b-clear" title="Clear all">✦ Clear</button>
  </div>
  <input type="file" id="file-in" accept="application/json" style="display:none"/>
</header>
<main>
  <aside class="palette">
    <h2>Components</h2>
    <div class="pgrid" id="palette"></div>
  </aside>
  <div class="stage">
    <svg id="canvas" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <pattern id="grid" width="44" height="44" patternUnits="userSpaceOnUse">
          <path d="M44 0 H0 V44" fill="none" stroke="#ffffff0d" stroke-width="1"/>
          <circle cx="0" cy="0" r="1.1" fill="#ffffff1f"/>
        </pattern>
      </defs>
      <g id="viewport">
        <rect id="bg" x="-20000" y="-20000" width="40000" height="40000" fill="url(#grid)"/>
        <g id="wires"></g>
        <g id="nodes"></g>
        <line id="rubber" x1="0" y1="0" x2="0" y2="0" stroke="#ff4d4d" stroke-width="3"
              stroke-dasharray="6 6" opacity="0" pointer-events="none"/>
      </g>
    </svg>
    <div class="footer">
      <div><b>Drag</b> parts from the left onto the grid · <b>drag</b> a part to move it</div>
      <div><kbd>W</kbd> wire mode &nbsp; <kbd>R</kbd> rotate &nbsp; <kbd>Del</kbd> delete &nbsp;
           <kbd>double-click</kbd> label &nbsp; <kbd>scroll</kbd> zoom &nbsp; drag empty space to pan</div>
    </div>
    <div class="count" id="count">0 parts · 0 wires</div>
  </div>
</main>

<script>
"use strict";

/* ---------- component icons (drawn in a 100x100 box) ---------- */
function tile(c, s){ return `<rect x="6" y="6" width="88" height="88" rx="12" fill="${c}" stroke="${s||'#0008'}" stroke-width="3"/>`; }

const PARTS = {
  dust: { label:"Redstone Dust", dir:false, icon:()=>tile('#2a1414','#000a')+`
    <rect x="45" y="13" width="10" height="74" fill="#c01616"/>
    <rect x="13" y="45" width="74" height="10" fill="#c01616"/>
    <circle cx="50" cy="50" r="12" fill="#ff4d4d"/><circle cx="50" cy="50" r="5" fill="#ffd2d2"/>`},
  wire_cross:{ label:"Dust (X)", dir:false, icon:()=>tile('#2a1414','#000a')+`
    <rect x="13" y="45" width="74" height="10" fill="#c01616"/>
    <rect x="45" y="13" width="10" height="74" fill="#7a0f0f"/>
    <circle cx="50" cy="50" r="9" fill="#ff4d4d"/>`},
  torch:{ label:"Redstone Torch", dir:false, icon:()=>tile('#241a14','#000a')+`
    <rect x="46" y="46" width="8" height="38" fill="#6e4a28"/>
    <circle cx="50" cy="40" r="13" fill="#ff3b30"/><circle cx="50" cy="40" r="6" fill="#ffb6b2"/>
    <circle cx="50" cy="40" r="18" fill="#ff3b3033"/>`},
  block:{ label:"Redstone Block", dir:false, icon:()=>tile('#8e1c1c','#3a0c0c')+`
    <g fill="#a82626">
      <rect x="14" y="14" width="33" height="33"/><rect x="53" y="14" width="33" height="33"/>
      <rect x="14" y="53" width="33" height="33"/><rect x="53" y="53" width="33" height="33"/></g>
    <g fill="#ff6b6b"><circle cx="26" cy="26" r="3"/><circle cx="70" cy="34" r="3"/>
      <circle cx="34" cy="70" r="3"/><circle cx="74" cy="66" r="3"/></g>`},
  lamp:{ label:"Redstone Lamp", dir:false, icon:()=>tile('#3a2f1a','#1d1810')+`
    <rect x="20" y="20" width="60" height="60" rx="8" fill="#e8b94b"/>
    <rect x="20" y="48" width="60" height="4" fill="#a07820"/><rect x="48" y="20" width="4" height="60" fill="#a07820"/>
    <rect x="20" y="20" width="60" height="60" rx="8" fill="none" stroke="#a07820" stroke-width="3"/>
    <rect x="26" y="26" width="14" height="14" fill="#fff4c0" opacity=".7"/>`},
  repeater:{ label:"Repeater", dir:true, icon:()=>tile('#bdbdbd','#7c7c7c')+`
    <polygon points="50,16 61,32 39,32" fill="#444"/>
    <rect x="46" y="32" width="8" height="38" fill="#8a8a8a"/>
    <circle cx="50" cy="40" r="6.5" fill="#d42"/><rect x="46" y="36" width="8" height="9" fill="#6e4a28"/>
    <circle cx="50" cy="76" r="6.5" fill="#d42"/>`},
  comparator:{ label:"Comparator", dir:true, icon:()=>tile('#c7c7c7','#7c7c7c')+`
    <polygon points="50,16 61,32 39,32" fill="#444"/>
    <circle cx="38" cy="74" r="5.5" fill="#d42"/><circle cx="62" cy="74" r="5.5" fill="#d42"/>
    <circle cx="50" cy="40" r="6.5" fill="#5a1010"/><rect x="46" y="36" width="8" height="9" fill="#3a0c0c"/>`},
  lever:{ label:"Lever", dir:false, icon:()=>tile('#6f6f6f','#3c3c3c')+`
    <rect x="32" y="58" width="36" height="15" rx="4" fill="#3f3f3f"/>
    <g transform="rotate(20 50 65)"><rect x="45.5" y="28" width="9" height="38" rx="4" fill="#caa46a"/>
    <circle cx="50" cy="28" r="8" fill="#e0c089"/></g>`},
  button:{ label:"Button", dir:false, icon:()=>tile('#7a7a7a','#3c3c3c')+`
    <rect x="36" y="42" width="28" height="16" rx="7" fill="#9a9a9a" stroke="#555" stroke-width="2"/>`},
  piston:{ label:"Piston", dir:true, icon:()=>tile('#7a5c34','#3a2c18')+`
    <rect x="6" y="6" width="88" height="24" rx="4" fill="#cccccc" stroke="#888" stroke-width="2"/>
    <rect x="6" y="30" width="88" height="6" fill="#5a4528"/>
    <rect x="20" y="44" width="60" height="8" fill="#5a4528"/><rect x="20" y="62" width="60" height="8" fill="#5a4528"/>`},
  sticky_piston:{ label:"Sticky Piston", dir:true, icon:()=>tile('#7a5c34','#3a2c18')+`
    <rect x="6" y="6" width="88" height="24" rx="4" fill="#86c06a" stroke="#4f7d3a" stroke-width="2"/>
    <rect x="44" y="6" width="12" height="24" fill="#6fa854"/>
    <rect x="6" y="30" width="88" height="6" fill="#5a4528"/>
    <rect x="20" y="48" width="60" height="8" fill="#5a4528"/>`},
  observer:{ label:"Observer", dir:true, icon:()=>tile('#5a5a5a','#2c2c2c')+`
    <rect x="22" y="14" width="15" height="15" rx="3" fill="#1c1c1c"/>
    <rect x="63" y="14" width="15" height="15" rx="3" fill="#1c1c1c"/>
    <rect x="30" y="36" width="40" height="9" rx="4" fill="#1c1c1c"/>
    <circle cx="50" cy="78" r="7" fill="#c0392b"/>`},
  target:{ label:"Target", dir:false, icon:()=>tile('#d8c8b0','#9a8a72')+`
    <circle cx="50" cy="50" r="34" fill="#cf3a2f"/><circle cx="50" cy="50" r="24" fill="#f0e6d8"/>
    <circle cx="50" cy="50" r="14" fill="#cf3a2f"/><circle cx="50" cy="50" r="6" fill="#7a1a14"/>`},
  note_block:{ label:"Note Block", dir:false, icon:()=>tile('#7a5c34','#3a2c18')+`
    <rect x="6" y="34" width="88" height="4" fill="#5a4528"/><rect x="48" y="6" width="4" height="88" fill="#5a4528"/>
    <text x="50" y="68" font-size="46" text-anchor="middle" fill="#1f140a" font-family="serif">&#9834;</text>`},
  dispenser:{ label:"Dispenser", dir:true, icon:()=>tile('#6f6f6f','#3c3c3c')+`
    <g fill="#4a4a4a"><circle cx="26" cy="26" r="3"/><circle cx="74" cy="26" r="3"/>
      <circle cx="26" cy="74" r="3"/><circle cx="74" cy="74" r="3"/></g>
    <circle cx="50" cy="50" r="17" fill="#1c1c1c"/><circle cx="50" cy="50" r="9" fill="#000"/>`},
  hopper:{ label:"Hopper", dir:false, icon:()=>tile('#4a4a4a','#222')+`
    <polygon points="18,28 82,28 62,56 38,56" fill="#2c2c2c"/>
    <rect x="42" y="56" width="16" height="26" fill="#2c2c2c"/>
    <rect x="18" y="28" width="64" height="6" fill="#1c1c1c"/>`},
};

/* ---------- state ---------- */
const CELL = 44;                   // grid + node size
const HALF = CELL/2;
let nodes = [];                    // {id,type,x,y,rot,label}
let wires = [];                    // {id,a,b}
let seq = 1;
let tool = "select";
let sel = null;                    // {kind:'node'|'wire', id}
let view = { x: 0, y: 0, k: 1 };
let wireFrom = null;               // node id while drawing wire

const svg     = document.getElementById("canvas");
const viewport= document.getElementById("viewport");
const gWires  = document.getElementById("wires");
const gNodes  = document.getElementById("nodes");
const rubber  = document.getElementById("rubber");
const SVGNS   = "http://www.w3.org/2000/svg";

/* ---------- helpers ---------- */
function applyView(){ viewport.setAttribute("transform", `translate(${view.x} ${view.y}) scale(${view.k})`); }
function screenToWorld(sx, sy){
  const r = svg.getBoundingClientRect();
  return { x:(sx - r.left - view.x)/view.k, y:(sy - r.top - view.y)/view.k };
}
function snap(v){ return Math.round(v/CELL)*CELL; }
function nodeById(id){ return nodes.find(n=>n.id===id); }
function updateCount(){ document.getElementById("count").textContent = `${nodes.length} parts · ${wires.length} wires`; }

/* ---------- rendering ---------- */
function buildNodeEl(n){
  const g = document.createElementNS(SVGNS, "g");
  g.setAttribute("class","node"); g.dataset.id = n.id; g.style.cursor="grab";
  const inner = document.createElementNS(SVGNS,"g");
  inner.setAttribute("transform", `translate(${-HALF} ${-HALF}) scale(${CELL/100})`);
  inner.innerHTML = PARTS[n.type].icon();
  const selbox = document.createElementNS(SVGNS,"rect");
  selbox.setAttribute("class","selbox");
  selbox.setAttribute("x",-HALF-4); selbox.setAttribute("y",-HALF-4);
  selbox.setAttribute("width",CELL+8); selbox.setAttribute("height",CELL+8);
  selbox.setAttribute("rx",8); selbox.setAttribute("fill","none");
  selbox.setAttribute("stroke","#36c2ff"); selbox.setAttribute("stroke-width","2");
  selbox.setAttribute("stroke-dasharray","5 4"); selbox.setAttribute("visibility","hidden");
  const label = document.createElementNS(SVGNS,"text");
  label.setAttribute("class","label"); label.setAttribute("text-anchor","middle");
  label.setAttribute("font-size","11"); label.setAttribute("fill","#cdd9e5");
  label.setAttribute("y", HALF+14); label.setAttribute("paint-order","stroke");
  label.setAttribute("stroke","#0d1117"); label.setAttribute("stroke-width","3");
  g.appendChild(selbox); g.appendChild(inner); g.appendChild(label);
  n.el = g; n._inner = inner; n._sel = selbox; n._label = label;
  positionNode(n); refreshNodeLabel(n);
  return g;
}
function positionNode(n){ n.el.setAttribute("transform", `translate(${n.x} ${n.y})`);
  n._inner.setAttribute("transform", `translate(${-HALF} ${-HALF}) scale(${CELL/100}) rotate(${n.rot} 50 50)`); }
function refreshNodeLabel(n){ n._label.textContent = n.label || ""; }

function buildWireEl(w){
  const g = document.createElementNS(SVGNS,"g");
  g.setAttribute("class","wire"); g.dataset.id = w.id; g.style.cursor="pointer";
  const hit = document.createElementNS(SVGNS,"path");
  hit.setAttribute("fill","none"); hit.setAttribute("stroke","#000"); hit.setAttribute("stroke-opacity","0");
  hit.setAttribute("stroke-width","16"); hit.setAttribute("stroke-linecap","round");
  const outer = document.createElementNS(SVGNS,"path");
  outer.setAttribute("fill","none"); outer.setAttribute("stroke","#7a0f0f");
  outer.setAttribute("stroke-width","9"); outer.setAttribute("stroke-linecap","round");
  const inner = document.createElementNS(SVGNS,"path");
  inner.setAttribute("fill","none"); inner.setAttribute("stroke","#ff4d4d");
  inner.setAttribute("stroke-width","3.5"); inner.setAttribute("stroke-linecap","round");
  g.appendChild(hit); g.appendChild(outer); g.appendChild(inner);
  w.el=g; w._paths=[hit,outer,inner];
  positionWire(w);
  return g;
}
function positionWire(w){
  const a=nodeById(w.a), b=nodeById(w.b); if(!a||!b) return;
  const d=`M ${a.x} ${a.y} L ${b.x} ${b.y}`;
  w._paths.forEach(p=>p.setAttribute("d",d));
}
function wiresOf(id){ return wires.filter(w=>w.a===id||w.b===id); }

function renderAll(){
  gNodes.innerHTML=""; gWires.innerHTML="";
  wires.forEach(w=>gWires.appendChild(buildWireEl(w)));
  nodes.forEach(n=>gNodes.appendChild(buildNodeEl(n)));
  applySelection(); updateCount(); autosave();
}

/* ---------- selection ---------- */
function setSelection(kind, id){
  sel = id==null ? null : {kind, id};
  applySelection();
}
function applySelection(){
  nodes.forEach(n=>{ if(n._sel) n._sel.setAttribute("visibility",
    (sel&&sel.kind==="node"&&sel.id===n.id)?"visible":"hidden"); });
  wires.forEach(w=>{ if(w._paths){ const on=sel&&sel.kind==="wire"&&sel.id===w.id;
    w._paths[2].setAttribute("stroke", on?"#36c2ff":"#ff4d4d"); }});
}

/* ---------- mutations ---------- */
function addNode(type, x, y){
  const n = {id:seq++, type, x:snap(x), y:snap(y), rot:0, label:""};
  nodes.push(n); gNodes.appendChild(buildNodeEl(n));
  setSelection("node", n.id); updateCount(); autosave();
  return n;
}
function deleteSelected(){
  if(!sel) return;
  if(sel.kind==="node"){
    wires = wires.filter(w=>{ if(w.a===sel.id||w.b===sel.id){ w.el&&w.el.remove(); return false;} return true; });
    const n=nodeById(sel.id); n&&n.el&&n.el.remove();
    nodes = nodes.filter(n=>n.id!==sel.id);
  } else {
    const w=wires.find(w=>w.id===sel.id); w&&w.el&&w.el.remove();
    wires = wires.filter(w=>w.id!==sel.id);
  }
  setSelection(null); updateCount(); autosave();
}
function rotateSelected(){
  if(sel&&sel.kind==="node"){ const n=nodeById(sel.id); n.rot=(n.rot+90)%360; positionNode(n); autosave(); }
}
function addWire(a,b){
  if(a===b) return;
  if(wires.some(w=>(w.a===a&&w.b===b)||(w.a===b&&w.b===a))) return;
  const w={id:"w"+(seq++), a, b}; wires.push(w); gWires.appendChild(buildWireEl(w));
  updateCount(); autosave();
}

/* ---------- tools ---------- */
function setTool(t){
  tool=t; wireFrom=null; rubber.setAttribute("opacity","0");
  document.getElementById("t-select").classList.toggle("active", t==="select");
  document.getElementById("t-wire").classList.toggle("active", t==="wire");
  svg.classList.toggle("wire", t==="wire");
  if(t==="wire") setSelection(null);
}

/* ---------- pointer: nodes ---------- */
let drag=null;   // {n, dx, dy, moved}
gNodes.addEventListener("pointerdown", (e)=>{
  const g=e.target.closest(".node"); if(!g) return;
  const n=nodeById(+g.dataset.id); if(!n) return;
  e.stopPropagation();
  if(tool==="wire"){
    if(wireFrom==null){ wireFrom=n.id; setSelection("node",n.id);
      rubber.setAttribute("x1",n.x); rubber.setAttribute("y1",n.y);
      rubber.setAttribute("x2",n.x); rubber.setAttribute("y2",n.y);
      rubber.setAttribute("opacity","1");
    } else { addWire(wireFrom, n.id); wireFrom=null; rubber.setAttribute("opacity","0"); setSelection(null); }
    return;
  }
  setSelection("node", n.id);
  const w=screenToWorld(e.clientX,e.clientY);
  drag={ n, dx:w.x-n.x, dy:w.y-n.y, moved:false };
  g.setPointerCapture(e.pointerId); g.style.cursor="grabbing";
});
gNodes.addEventListener("pointermove",(e)=>{
  if(!drag) return;
  const w=screenToWorld(e.clientX,e.clientY);
  drag.n.x=snap(w.x-drag.dx); drag.n.y=snap(w.y-drag.dy); drag.moved=true;
  positionNode(drag.n); wiresOf(drag.n.id).forEach(positionWire);
});
gNodes.addEventListener("pointerup",(e)=>{
  if(drag){ drag.n.el.style.cursor="grab"; if(drag.moved) autosave(); drag=null; }
});
gNodes.addEventListener("dblclick",(e)=>{
  const g=e.target.closest(".node"); if(!g) return;
  const n=nodeById(+g.dataset.id); if(!n) return;
  const v=prompt("Label for this part:", n.label||""); if(v===null) return;
  n.label=v.trim(); refreshNodeLabel(n); autosave();
});

/* ---------- pointer: wires (delete/select) ---------- */
gWires.addEventListener("pointerdown",(e)=>{
  const g=e.target.closest(".wire"); if(!g) return;
  e.stopPropagation();
  if(tool==="wire") return;
  setSelection("wire", g.dataset.id);
});

/* ---------- pointer: background pan ---------- */
let pan=null;
svg.addEventListener("pointerdown",(e)=>{
  if(e.target.closest(".node")||e.target.closest(".wire")) return;
  if(tool==="wire"){ wireFrom=null; rubber.setAttribute("opacity","0"); setSelection(null); return; }
  setSelection(null);
  pan={ sx:e.clientX, sy:e.clientY, vx:view.x, vy:view.y };
  svg.classList.add("panning"); svg.setPointerCapture(e.pointerId);
});
svg.addEventListener("pointermove",(e)=>{
  if(pan){ view.x=pan.vx+(e.clientX-pan.sx); view.y=pan.vy+(e.clientY-pan.sy); applyView(); }
  if(tool==="wire" && wireFrom!=null){
    const w=screenToWorld(e.clientX,e.clientY);
    rubber.setAttribute("x2",w.x); rubber.setAttribute("y2",w.y);
  }
});
svg.addEventListener("pointerup",()=>{ pan=null; svg.classList.remove("panning"); });

/* ---------- wheel zoom ---------- */
svg.addEventListener("wheel",(e)=>{
  e.preventDefault();
  const r=svg.getBoundingClientRect();
  const mx=e.clientX-r.left, my=e.clientY-r.top;
  const wx=(mx-view.x)/view.k, wy=(my-view.y)/view.k;
  const f=e.deltaY<0?1.12:1/1.12;
  view.k=Math.min(3.5, Math.max(0.25, view.k*f));
  view.x=mx-wx*view.k; view.y=my-wy*view.k; applyView();
},{passive:false});

/* ---------- palette drag-to-place ---------- */
function buildPalette(){
  const p=document.getElementById("palette");
  for(const [type,def] of Object.entries(PARTS)){
    const d=document.createElement("div"); d.className="pitem"; d.dataset.type=type;
    d.innerHTML=`<svg viewBox="0 0 100 100">${def.icon()}</svg><div class="lbl">${def.label}</div>`;
    p.appendChild(d);
  }
}
let place=null;  // {type, ghost}
document.getElementById("palette").addEventListener("pointerdown",(e)=>{
  const it=e.target.closest(".pitem"); if(!it) return;
  e.preventDefault();
  const type=it.dataset.type;
  const ghost=document.createElement("div"); ghost.className="ghost";
  ghost.innerHTML=`<svg viewBox="0 0 100 100" width="44" height="44">${PARTS[type].icon()}</svg>`;
  document.body.appendChild(ghost);
  moveGhost(ghost,e.clientX,e.clientY);
  place={type, ghost};
});
function moveGhost(g,x,y){ g.style.left=x+"px"; g.style.top=y+"px"; }
window.addEventListener("pointermove",(e)=>{ if(place) moveGhost(place.ghost,e.clientX,e.clientY); });
window.addEventListener("pointerup",(e)=>{
  if(!place) return;
  const r=svg.getBoundingClientRect();
  const inside = e.clientX>=r.left&&e.clientX<=r.right&&e.clientY>=r.top&&e.clientY<=r.bottom;
  if(inside){ const w=screenToWorld(e.clientX,e.clientY); addNode(place.type, w.x, w.y); }
  place.ghost.remove(); place=null;
});

/* ---------- persistence ---------- */
function serialize(){ return JSON.stringify({
  v:1, seq,
  nodes:nodes.map(n=>({id:n.id,type:n.type,x:n.x,y:n.y,rot:n.rot,label:n.label})),
  wires:wires.map(w=>({id:w.id,a:w.a,b:w.b})),
  view
}); }
function load(data){
  nodes=(data.nodes||[]).map(n=>({...n,label:n.label||""}));
  wires=(data.wires||[]).map(w=>({...w}));
  seq=data.seq||Math.max(1,...nodes.map(n=>(+n.id||0)+1));
  if(data.view) view={...view,...data.view};
  applyView(); renderAll(); setSelection(null);
}
function autosave(){ try{ localStorage.setItem("redstone-schematic", serialize()); }catch(_){} }
function tryRestore(){ try{ const s=localStorage.getItem("redstone-schematic");
  if(s) load(JSON.parse(s)); }catch(_){} }

/* ---------- save / load files ---------- */
document.getElementById("b-save").onclick=()=>{
  const blob=new Blob([serialize()],{type:"application/json"});
  const a=document.createElement("a"); a.href=URL.createObjectURL(blob);
  a.download="redstone-schematic.json"; a.click(); URL.revokeObjectURL(a.href);
};
document.getElementById("b-load").onclick=()=>document.getElementById("file-in").click();
document.getElementById("file-in").onchange=(e)=>{
  const f=e.target.files[0]; if(!f) return;
  const rd=new FileReader(); rd.onload=()=>{ try{ load(JSON.parse(rd.result)); }
    catch(err){ alert("Could not read file: "+err.message); } }; rd.readAsText(f);
  e.target.value="";
};

/* ---------- fit view ---------- */
function contentBBox(){
  if(!nodes.length) return null;
  let minx=Infinity,miny=Infinity,maxx=-Infinity,maxy=-Infinity;
  nodes.forEach(n=>{ minx=Math.min(minx,n.x-HALF); miny=Math.min(miny,n.y-HALF);
    maxx=Math.max(maxx,n.x+HALF); maxy=Math.max(maxy,n.y+HALF); });
  return {minx,miny,maxx,maxy};
}
function fitView(){
  const b=contentBBox(); if(!b) return;
  const pad=60, r=svg.getBoundingClientRect();
  const bw=(b.maxx-b.minx)+pad*2, bh=(b.maxy-b.miny)+pad*2;
  view.k=Math.min(3.5, Math.max(0.25, Math.min(r.width/bw, r.height/bh)));
  view.x=r.width/2 - (b.minx+b.maxx)/2*view.k;
  view.y=r.height/2 - (b.miny+b.maxy)/2*view.k;
  applyView();
}

/* ---------- export PNG ---------- */
document.getElementById("b-png").onclick=()=>{
  const b=contentBBox(); if(!b){ alert("Nothing to export yet — add some parts first."); return; }
  const pad=40, scale=2;
  const x=b.minx-pad, y=b.miny-pad, w=(b.maxx-b.minx)+pad*2, h=(b.maxy-b.miny)+pad*2;
  const out=document.createElementNS(SVGNS,"svg");
  out.setAttribute("xmlns",SVGNS); out.setAttribute("width",w*scale); out.setAttribute("height",h*scale);
  out.setAttribute("viewBox",`${x} ${y} ${w} ${h}`);
  const bg=document.createElementNS(SVGNS,"rect");
  bg.setAttribute("x",x); bg.setAttribute("y",y); bg.setAttribute("width",w); bg.setAttribute("height",h);
  bg.setAttribute("fill","#0d1117"); out.appendChild(bg);
  out.appendChild(gWires.cloneNode(true)); out.appendChild(gNodes.cloneNode(true));
  out.querySelectorAll(".selbox").forEach(e=>e.remove());
  const str=new XMLSerializer().serializeToString(out);
  const img=new Image();
  img.onload=()=>{
    const c=document.createElement("canvas"); c.width=w*scale; c.height=h*scale;
    c.getContext("2d").drawImage(img,0,0);
    c.toBlob(bl=>{ const a=document.createElement("a"); a.href=URL.createObjectURL(bl);
      a.download="redstone-schematic.png"; a.click(); URL.revokeObjectURL(a.href); });
  };
  img.src="data:image/svg+xml;charset=utf-8,"+encodeURIComponent(str);
};

/* ---------- buttons & keys ---------- */
document.getElementById("t-select").onclick=()=>setTool("select");
document.getElementById("t-wire").onclick=()=>setTool("wire");
document.getElementById("b-rotate").onclick=rotateSelected;
document.getElementById("b-delete").onclick=deleteSelected;
document.getElementById("b-fit").onclick=fitView;
document.getElementById("b-clear").onclick=()=>{
  if(nodes.length||wires.length){ if(!confirm("Clear the whole schematic?")) return; }
  nodes=[]; wires=[]; setSelection(null); renderAll();
};
svg.addEventListener("contextmenu",(e)=>{
  const g=e.target.closest(".node")||e.target.closest(".wire");
  if(g){ e.preventDefault();
    setSelection(g.classList.contains("node")?"node":"wire",
      g.classList.contains("node")?+g.dataset.id:g.dataset.id);
    deleteSelected();
  }
});
window.addEventListener("keydown",(e)=>{
  if(/INPUT|TEXTAREA/.test(document.activeElement.tagName)) return;
  const k=e.key.toLowerCase();
  if(k==="delete"||k==="backspace"){ e.preventDefault(); deleteSelected(); }
  else if(k==="r"){ rotateSelected(); }
  else if(k==="w"){ setTool(tool==="wire"?"select":"wire"); }
  else if(k==="v"){ setTool("select"); }
  else if(k==="f"){ fitView(); }
  else if(k==="escape"){ wireFrom=null; rubber.setAttribute("opacity","0"); setSelection(null); }
});

/* ---------- init ---------- */
buildPalette(); applyView(); tryRestore(); updateCount();
</script>
</body>
</html>
"""


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass  # keep the terminal clean


def main():
    tmp = Path(tempfile.mkdtemp(prefix="redstone_schematic_"))
    (tmp / "index.html").write_text(HTML, encoding="utf-8")

    port = find_free_port()
    handler = functools.partial(QuietHandler, directory=str(tmp))
    httpd = socketserver.TCPServer(("127.0.0.1", port), handler)
    url = f"http://127.0.0.1:{port}/index.html"

    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    print("Redstone Schematic Designer")
    print("===========================")
    print(f"  Serving at {url}")
    print("  Opening your default browser...  (press Ctrl+C here to stop)\n")

    # Give the server a beat, then open the browser.
    time.sleep(0.3)
    webbrowser.open(url)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nShutting down. Bye!")
        httpd.shutdown()
        sys.exit(0)


if __name__ == "__main__":
    main()
