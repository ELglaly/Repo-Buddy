// ── MOBILE NAV ────────────────────────────────────────────────────────────────
const ham = document.getElementById('ham');
const mob = document.getElementById('mobNav');
ham.addEventListener('click', () => { ham.classList.toggle('open'); mob.classList.toggle('open'); });
function closeNav() { ham.classList.remove('open'); mob.classList.remove('open'); }

// ── SCROLL REVEAL ─────────────────────────────────────────────────────────────
document.querySelectorAll('.rv').forEach(el =>
  new IntersectionObserver((es) => es.forEach(e => e.isIntersecting && e.target.classList.add('vis')),
    { threshold: 0.1 }).observe(el));

// ── STATS COUNTER ─────────────────────────────────────────────────────────────
const statsObs = new IntersectionObserver(es => {
  if (!es[0].isIntersecting) return;
  statsObs.disconnect();
  document.querySelectorAll('[data-to]').forEach(el => {
    const target = +el.dataset.to, suf = el.dataset.suf || '', start = performance.now();
    const dur = target > 1000 ? 2200 : 1400;
    const tick = now => {
      const p = Math.min((now - start) / dur, 1);
      const v = Math.round((1 - Math.pow(1 - p, 3)) * target);
      el.textContent = v + suf;
      if (p < 1) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
}, { threshold: 0.4 });
statsObs.observe(document.getElementById('statsRow'));

// ── INSTALL TABS ──────────────────────────────────────────────────────────────
function switchTab(btn, id) {
  btn.closest('.install-card').querySelectorAll('.itab').forEach(t => t.classList.remove('on'));
  btn.closest('.install-card').querySelectorAll('.ipane').forEach(p => p.classList.remove('on'));
  btn.classList.add('on');
  document.getElementById('pane-' + id).classList.add('on');
}

// ── COPY CODE ─────────────────────────────────────────────────────────────────
function doCopy(btn, id) {
  navigator.clipboard.writeText(document.getElementById(id).innerText).then(() => {
    btn.textContent = 'Copied!'; btn.classList.add('ok');
    setTimeout(() => { btn.textContent = 'Copy'; btn.classList.remove('ok'); }, 2000);
  });
}

// ── FAQ ───────────────────────────────────────────────────────────────────────
function toggleFaq(q) { q.closest('.faq-item').classList.toggle('open'); }

// ── TERMINAL TYPING ───────────────────────────────────────────────────────────
const TERM_LINES = [
  ['c','# 1. Open Settings → Plugins → Marketplace'],
  ['g'],
  ['p','> Search: RepoBuddy'],
  ['s','  ✓ Found: RepoBuddy v1.0.7 by Sherif Elglaly'],
  ['g'],
  ['p','> Install'],
  ['i','  Downloading repoBuddy-agent.jar …'],
  ['s','  ✓ Plugin installed. Restart IDE.'],
  ['g'],
  ['c','# 2. Open your Spring Boot project'],
  ['s','  ✓ RepoBuddy detected 1 run configuration'],
  ['s','  ✓ Injected -javaagent into "MyApp [dev]"'],
  ['g'],
  ['c','# 3. Press ▶ Run as usual'],
  ['i','  RepoBuddy agent binding to port 7770 …'],
  ['s','  ● Agent Ready — click any ⌕ gutter icon'],
];
let termStarted = false;
new IntersectionObserver(es => {
  if (es[0].isIntersecting && !termStarted) { termStarted = true; runTerm(); }
}, { threshold: 0.3 }).observe(document.getElementById('termBody'));

function runTerm() {
  const body = document.getElementById('termBody');
  body.innerHTML = '';
  let li = 0, ci = 0, cur = null;
  function next() {
    if (li >= TERM_LINES.length) { body.innerHTML += '<span class="tcur"></span>'; return; }
    const [type, text] = TERM_LINES[li];
    if (type === 'g') { body.innerHTML += '<br>'; li++; return void setTimeout(next, 70); }
    if (ci === 0) {
      cur = document.createElement('div');
      if (type === 'p') cur.innerHTML = '<span class="tp">$ </span>';
      else if (type === 's') cur.className = 'ts';
      else if (type === 'i') cur.className = 'ti';
      else cur.className = 'tc';
      body.appendChild(cur);
    }
    if (ci < text.length) {
      const pfx = cur.querySelector('.tp') ? '<span class="tp">$ </span>' : '';
      cur.innerHTML = pfx + text.slice(0, ci + 1);
      ci++;
      body.scrollTop = body.scrollHeight;
      setTimeout(next, 16 + Math.random() * 22);
    } else {
      li++; ci = 0;
      setTimeout(next, type === 's' ? 220 : 55);
    }
  }
  next();
}
