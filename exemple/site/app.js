/* MineNorth Stats - tableau de bord statique. Aucune dépendance externe (Chart.js est embarqué). */
(function () {
  'use strict';
  var D = window.MN_DATA;
  var view = document.getElementById('view');
  if (!D) { view.innerHTML = '<p class="pad">Données introuvables (data.js). Lancez /stats site sur le serveur.</p>'; return; }

  var COL = { pink: '#ff4d8d', orange: '#ffa23a', cyan: '#3ad1c9', violet: '#8a7bff', green: '#6ee07a', red: '#ff6b6b', grid: '#2b2f5c', txt: '#9aa0c8' };
  var JOURS = ['Lun', 'Mar', 'Mer', 'Jeu', 'Ven', 'Sam', 'Dim'];
  var LABELS = {
    casses: 'Blocs cassés', poses: 'Blocs posés', kills_mobs: 'Créatures tuées', kills_joueurs: 'Joueurs tués', morts: 'Morts',
    craft: 'Objets fabriqués', trajets_vehicule: 'Trajets en véhicule', vehicule_s: 'Temps en véhicule (s)',
    service_police_s: 'Service police (s)', service_secours_s: 'Service secours (s)',
    depenses_c: 'Dépenses (c)', revenus_c: 'Revenus (c)', salaires_c: 'Salaires (c)', paiements: 'Paiements'
  };
  var COUNT_KEYS = ['casses', 'poses', 'kills_mobs', 'kills_joueurs', 'morts', 'craft', 'trajets_vehicule', 'paiements'];
  var charts = [];
  var state = { tab: 'overview', period: 30, ret: 'exact', sort: { k: 'last', d: -1 }, q: '', mapDim: null, mapSpan: 'all', actKey: 'casses' };

  // ------------------------------------------------------------------ utilitaires
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function el(html) { var t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstChild; }
  function nf(n, d) { return Number(n).toLocaleString('fr-FR', { maximumFractionDigits: d == null ? 0 : d }); }
  function eur(c) { return (c / 100).toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' €'; }
  function hm(min) { return min >= 60 ? Math.floor(min / 60) + ' h ' + String(Math.round(min % 60)).padStart(2, '0') : Math.round(min) + ' min'; }
  function pct(a, b) { return b ? Math.round(1000 * a / b) / 10 : 0; }
  function dparse(s) { var p = s.split('-'); return Date.UTC(+p[0], +p[1] - 1, +p[2]); }
  function dstr(ms) { return new Date(ms).toISOString().slice(0, 10); }
  function addDays(s, n) { return dstr(dparse(s) + n * 86400000); }
  function dow(s) { return (new Date(dparse(s)).getUTCDay() + 6) % 7; }
  function fdate(ms) { return ms ? new Date(ms).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' }) : '—'; }
  function fday(s) { var p = s.split('-'); return p[2] + '/' + p[1]; }
  function itemName(id) { var i = id.indexOf(':'); return i < 0 ? id : id.slice(i + 1).replace(/_/g, ' '); }
  function itemNs(id) { var i = id.indexOf(':'); return i < 0 ? '' : id.slice(0, i); }
  function sum(a) { return a.reduce(function (x, y) { return x + y; }, 0); }

  var today = D.today;
  var P = D.players;
  P.forEach(function (p) { p.adSet = new Set(p.ad); p.firstDay = p.ad.length ? p.ad.slice().sort()[0] : (p.first ? dstr(p.first) : today); });
  function periodDays() {
    var all = P.reduce(function (m, p) { return p.firstDay < m ? p.firstDay : m; }, today);
    var n = state.period || Math.round((dparse(today) - dparse(all)) / 86400000) + 1;
    n = Math.max(2, Math.min(n, 3650));
    var out = [];
    for (var i = n - 1; i >= 0; i--) out.push(addDays(today, -i));
    return out;
  }
  function activeOn(day) { return P.filter(function (p) { return p.adSet.has(day); }); }
  function activeWin(day, w) {
    return P.filter(function (p) { for (var i = 0; i < w; i++) if (p.adSet.has(addDays(day, -i))) return true; return false; });
  }

  // ------------------------------------------------------------------ graphiques
  function destroyCharts() { charts.forEach(function (c) { c.destroy(); }); charts = []; }
  function chart(parent, cfg, cls) {
    var wrap = el('<div class="chart ' + (cls || '') + '"><canvas></canvas></div>');
    parent.appendChild(wrap);
    cfg.options = Object.assign({ responsive: true, maintainAspectRatio: false, animation: false }, cfg.options || {});
    var o = cfg.options;
    o.plugins = Object.assign({ legend: { labels: { color: COL.txt, usePointStyle: true, boxWidth: 8 } } }, o.plugins || {});
    if (cfg.type !== 'doughnut') {
      o.scales = o.scales || {};
      ['x', 'y'].forEach(function (a) {
        o.scales[a] = Object.assign({ ticks: { color: COL.txt, maxRotation: 0, autoSkip: true }, grid: { color: COL.grid + '88' } }, o.scales[a] || {});
      });
    }
    if (cfg.type === 'bar') cfg.data.datasets.forEach(function (s) { s.borderWidth = 0; });
    var c = new Chart(wrap.firstChild, cfg);
    charts.push(c);
    return c;
  }
  function ds(label, data, color, extra) {
    return Object.assign({ label: label, data: data, borderColor: color, backgroundColor: color + '55', borderWidth: 2.5, pointRadius: 0, tension: 0.3, borderRadius: 8 }, extra || {});
  }
  function card(title, sub, cls) {
    return el('<section class="card ' + (cls || '') + '"><h3>' + esc(title) + '</h3>' + (sub ? '<p class="sub">' + esc(sub) + '</p>' : '') + '</section>');
  }
  function kpi(v, l, d) { return el('<div class="card kpi"><div class="v">' + esc(v) + '</div><div class="l">' + esc(l) + '</div>' + (d ? '<div class="d">' + esc(d) + '</div>' : '') + '</div>'); }
  function grid(cls) { var g = document.createElement('div'); g.className = 'grid ' + cls; return g; }

  // ------------------------------------------------------------------ données dérivées
  function hoursMatrix(days) { // 7 x 24 en joueur-heures moyennes par jour de la semaine
    var m = []; var n = [];
    for (var i = 0; i < 7; i++) { m.push(new Array(24).fill(0)); n.push(0); }
    days.forEach(function (d) {
      var h = D.hours[d]; var w = dow(d);
      n[w]++;
      if (h) for (var k = 0; k < 24; k++) m[w][k] += h[k] / 3600;
    });
    for (var w2 = 0; w2 < 7; w2++) for (var k2 = 0; k2 < 24; k2++) m[w2][k2] = n[w2] ? m[w2][k2] / n[w2] : 0;
    return m;
  }
  function sumCounter(p, key, days) {
    var t = 0; days.forEach(function (d) { var r = p.days[d]; if (r && r.c[key]) t += r.c[key]; }); return t;
  }
  function gini(vals) {
    var a = vals.filter(function (v) { return v >= 0; }).sort(function (x, y) { return x - y; });
    var n = a.length, s = sum(a); if (n < 2 || s <= 0) return 0;
    var acc = 0; a.forEach(function (v, i) { acc += (i + 1) * v; });
    return (2 * acc) / (n * s) - (n + 1) / n;
  }

  // ------------------------------------------------------------------ onglets
  var TABS = [['overview', 'Vue d’ensemble'], ['retention', 'Rétention'], ['activity', 'Activités'], ['economy', 'Économie'], ['items', 'Objets'], ['map', 'Carte'], ['players', 'Joueurs']];

  function renderOverview(root) {
    var days = periodDays();
    var dau = days.map(function (d) { return activeOn(d).length; });
    var wau = days.map(function (d) { return activeWin(d, 7).length; });
    var mau = days.map(function (d) { return activeWin(d, 30).length; });
    var online = P.filter(function (p) { return p.online; }).length;
    var newWeek = P.filter(function (p) { return p.firstDay > addDays(today, -7); }).length;
    var allMin = sum(P.map(function (p) { return p.playMin; }));
    var sess = []; P.forEach(function (p) { p.sessions.forEach(function (s) { sess.push((s[1] - s[0]) / 60000); }); });
    var avgSess = sess.length ? sum(sess) / sess.length : 0;
    var k = grid('kpis');
    k.appendChild(kpi(nf(P.length), 'Joueurs suivis', nf(newWeek) + ' nouveaux sur 7 jours'));
    k.appendChild(kpi(nf(online), 'En ligne (dernier relevé)'));
    k.appendChild(kpi(nf(dau[dau.length - 1]), 'Actifs aujourd’hui (DAU)', 'hier : ' + nf(dau[dau.length - 2] || 0)));
    k.appendChild(kpi(nf(wau[wau.length - 1]), 'Actifs sur 7 jours (WAU)'));
    k.appendChild(kpi(nf(mau[mau.length - 1]), 'Actifs sur 30 jours (MAU)', 'DAU/MAU : ' + pct(dau[dau.length - 1], mau[mau.length - 1]) + ' %'));
    k.appendChild(kpi(hm(avgSess), 'Durée moyenne d’une session', nf(sess.length) + ' sessions connues'));
    k.appendChild(kpi(nf(allMin / 60) + ' h', 'Temps de jeu cumulé'));
    root.appendChild(k);

    var g = grid('cols2');
    var c1 = card('Joueurs actifs', 'DAU = jour, WAU = 7 jours glissants, MAU = 30 jours glissants'); g.appendChild(c1);
    chart(c1, { type: 'line', data: { labels: days.map(fday), datasets: [ds('DAU', dau, COL.pink), ds('WAU', wau, COL.orange), ds('MAU', mau, COL.cyan)] }, options: { scales: { y: { beginAtZero: true } } } });
    var c2 = card('Nouveaux joueurs par jour', 'Premier jour de connexion'); g.appendChild(c2);
    var nw = days.map(function (d) { return P.filter(function (p) { return p.firstDay === d; }).length; });
    chart(c2, { type: 'bar', data: { labels: days.map(fday), datasets: [ds('Nouveaux', nw, COL.violet)] }, options: { scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } } });

    var m = hoursMatrix(days);
    var mx = Math.max.apply(null, [0.0001].concat(m.map(function (r) { return Math.max.apply(null, r); })));
    var c3 = card('Heures de pointe', 'Joueurs en ligne en moyenne (joueur-heures) par jour de la semaine et par heure, sur la période', 'wide'); c3.style.gridColumn = '1 / -1'; g.appendChild(c3);
    var h = document.createElement('div'); h.className = 'heat';
    h.appendChild(el('<div class="h"></div>'));
    for (var i = 0; i < 24; i++) h.appendChild(el('<div class="h">' + i + '</div>'));
    for (var w = 0; w < 7; w++) {
      h.appendChild(el('<div class="h">' + JOURS[w] + '</div>'));
      for (var j = 0; j < 24; j++) {
        var a = m[w][j] / mx;
        var cell = el('<div title="' + JOURS[w] + ' ' + j + ' h : ' + nf(m[w][j], 2) + ' joueur en moyenne"></div>');
        cell.style.background = a < 0.01 ? '#1b1d3a' : 'rgba(255,77,141,' + (0.15 + 0.85 * a).toFixed(2) + ')';
        h.appendChild(cell);
      }
    }
    c3.appendChild(h);
    var byHour = new Array(24).fill(0); m.forEach(function (r) { r.forEach(function (v, i2) { byHour[i2] += v / 7; }); });
    var c4 = card('Profil horaire moyen', 'Joueurs en ligne en moyenne, toute la semaine'); g.appendChild(c4);
    chart(c4, { type: 'bar', data: { labels: byHour.map(function (_, i3) { return i3 + ' h'; }), datasets: [ds('Joueurs', byHour.map(function (v) { return +v.toFixed(2); }), COL.orange)] }, options: { scales: { y: { beginAtZero: true } } } });
    var buckets = [['< 15 min', 0, 15], ['15–30', 15, 30], ['30–60', 30, 60], ['1–2 h', 60, 120], ['2–4 h', 120, 240], ['> 4 h', 240, 1e9]];
    var c5 = card('Durée des sessions', 'Répartition des sessions connues (30 dernières par joueur)'); g.appendChild(c5);
    chart(c5, { type: 'bar', data: { labels: buckets.map(function (b) { return b[0]; }), datasets: [ds('Sessions', buckets.map(function (b) { return sess.filter(function (x) { return x >= b[1] && x < b[2]; }).length; }), COL.green)] }, options: { scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } } });
    root.appendChild(g);
  }

  function retention(p, n, mode) { // vrai/faux/null (pas encore observable)
    var target = addDays(p.firstDay, n);
    if (target > today) return null;
    if (mode === 'exact') return p.adSet.has(target);
    return p.ad.some(function (d) { return d >= target; });
  }

  function renderRetention(root) {
    var seg = el('<div class="seg"><button data-m="exact">Jour exact (J+n)</button><button data-m="roll">Revenu à J+n ou plus tard</button></div>');
    [].forEach.call(seg.children, function (b) { if (b.dataset.m === state.ret) b.className = 'on'; b.onclick = function () { state.ret = b.dataset.m; render(); }; });
    root.appendChild(seg);
    var cohorts = {};
    P.forEach(function (p) { var d = p.firstDay; var mon = addDays(d, -dow(d)); (cohorts[mon] = cohorts[mon] || []).push(p); });
    var keys = Object.keys(cohorts).sort().reverse();
    var N = [1, 7, 30];
    var c = card('Rétention par cohorte d’arrivée', 'Cohorte = semaine du premier passage. Une case est vide tant que le jour J+n n’est pas arrivé.');
    var html = '<div class="scroll"><table class="cohort"><thead><tr><th>Semaine du</th><th class="num">Joueurs</th><th class="num">J1</th><th class="num">J7</th><th class="num">J30</th></tr></thead><tbody>';
    keys.forEach(function (k) {
      var ps = cohorts[k];
      html += '<tr><td>' + esc(k.split('-').reverse().join('/')) + '</td><td class="num">' + ps.length + '</td>';
      N.forEach(function (n) {
        var el_ = ps.filter(function (p) { return retention(p, n, state.ret) !== null; });
        if (!el_.length) { html += '<td class="c muted">—</td>'; return; }
        var ok = el_.filter(function (p) { return retention(p, n, state.ret); }).length;
        var v = pct(ok, el_.length);
        html += '<td class="c" style="background:rgba(58,209,201,' + (0.08 + 0.7 * v / 100).toFixed(2) + ')" title="' + ok + ' / ' + el_.length + '">' + nf(v, 1) + ' %</td>';
      });
      html += '</tr>';
    });
    c.insertAdjacentHTML('beforeend', html + '</tbody></table></div>');
    root.appendChild(c);
    var c2 = card('Courbe de rétention globale', 'Part des joueurs encore (ou de nouveau) actifs n jours après leur arrivée'); root.appendChild(c2);
    c2.style.marginTop = '16px';
    var xs = [], ys = [];
    for (var n = 0; n <= 30; n++) {
      var el2 = P.filter(function (p) { return retention(p, n, state.ret) !== null; });
      if (!el2.length) break;
      xs.push('J' + n); ys.push(pct(el2.filter(function (p) { return retention(p, n, state.ret); }).length, el2.length));
    }
    chart(c2, { type: 'line', data: { labels: xs, datasets: [ds('Rétention %', ys, COL.pink, { fill: true })] }, options: { scales: { y: { beginAtZero: true, max: 100 } } } });
  }

  function renderActivity(root) {
    var days = periodDays();
    var seg = el('<div class="seg"></div>');
    COUNT_KEYS.concat(['depenses_c', 'revenus_c', 'salaires_c']).forEach(function (k) {
      var b = el('<button>' + esc(LABELS[k].replace(' (c)', '')) + '</button>');
      if (k === state.actKey) b.className = 'on';
      b.onclick = function () { state.actKey = k; render(); };
      seg.appendChild(b);
    });
    root.appendChild(seg);
    var isMoney = /_c$/.test(state.actKey);
    var g = grid('cols2');
    var c1 = card(LABELS[state.actKey].replace(' (c)', ''), 'Total par jour, tous joueurs (relevés des 60 derniers jours)'); g.appendChild(c1);
    var per = days.map(function (d) { return sum(P.map(function (p) { var r = p.days[d]; return r && r.c[state.actKey] ? r.c[state.actKey] : 0; })) / (isMoney ? 100 : 1); });
    chart(c1, { type: 'bar', data: { labels: days.map(fday), datasets: [ds(LABELS[state.actKey].replace(' (c)', ''), per, COL.pink)] }, options: { scales: { y: { beginAtZero: true } } } });
    var c2 = card('Services et véhicules', 'Heures par jour passées en service ou en véhicule'); g.appendChild(c2);
    function hrs(key) { return days.map(function (d) { return +(sum(P.map(function (p) { var r = p.days[d]; return r && r.c[key] ? r.c[key] : 0; })) / 3600).toFixed(2); }); }
    chart(c2, { type: 'bar', data: { labels: days.map(fday), datasets: [ds('Police', hrs('service_police_s'), '#5b8cff'), ds('Secours', hrs('service_secours_s'), COL.red), ds('Véhicule', hrs('vehicule_s'), COL.green)] }, options: { scales: { x: { stacked: true }, y: { stacked: true, beginAtZero: true } } } });
    root.appendChild(g);
    var c3 = card('Classement', 'Top 15 de la période — ' + LABELS[state.actKey].replace(' (c)', '')); c3.style.marginTop = '16px';
    var rows = P.map(function (p) { return { p: p, v: sumCounter(p, state.actKey, days) }; }).filter(function (r) { return r.v > 0; }).sort(function (a, b) { return b.v - a.v; }).slice(0, 15);
    var mx = rows.length ? rows[0].v : 1;
    var html = '<div class="scroll"><table><tbody>';
    rows.forEach(function (r, i) { html += '<tr class="click" data-id="' + esc(r.p.id) + '"><td>' + (i + 1) + '</td><td>' + esc(r.p.label) + '</td><td class="num">' + (isMoney ? eur(r.v) : nf(r.v)) + '</td><td><div class="bar"><i style="width:' + (100 * r.v / mx) + '%"></i></div></td></tr>'; });
    c3.insertAdjacentHTML('beforeend', html + '</tbody></table></div>' + (rows.length ? '' : '<p class="muted">Aucune donnée sur la période.</p>'));
    bindRows(c3);
    root.appendChild(c3);
    var c4 = card('Totaux de la période', 'Toutes activités confondues'); c4.style.marginTop = '16px';
    var t = '<table><tbody>';
    Object.keys(LABELS).forEach(function (k) {
      var v = sum(P.map(function (p) { return sumCounter(p, k, days); }));
      var shown = /_c$/.test(k) ? eur(v) : /_s$/.test(k) ? nf(v / 3600, 1) + ' h' : nf(v);
      t += '<tr><td>' + esc(LABELS[k]) + '</td><td class="num">' + shown + '</td></tr>';
    });
    c4.insertAdjacentHTML('beforeend', t + '</tbody></table>');
    root.appendChild(c4);
  }

  function renderEconomy(root) {
    var ecoDays = Object.keys(D.eco).sort();
    var bals = P.filter(function (p) { return p.bal >= 0; });
    var tot = sum(bals.map(function (p) { return p.bal; }));
    var sorted = bals.map(function (p) { return p.bal; }).sort(function (a, b) { return a - b; });
    var med = sorted.length ? sorted[Math.floor(sorted.length / 2)] : 0;
    var k = grid('kpis');
    k.appendChild(kpi(eur(tot), 'Argent en banque (joueurs suivis)'));
    k.appendChild(kpi(nf(bals.length), 'Comptes'));
    k.appendChild(kpi(eur(med), 'Solde médian'));
    k.appendChild(kpi(nf(gini(sorted), 2), 'Indice de Gini', '0 = égalité, 1 = un seul riche'));
    k.appendChild(kpi(nf(P.filter(function (p) { return p.police; }).length), 'Policiers'));
    k.appendChild(kpi(nf(P.filter(function (p) { return p.secours; }).length), 'Secouristes'));
    root.appendChild(k);
    var g = grid('cols2');
    var c1 = card('Masse monétaire', 'Total des soldes, un point par jour'); g.appendChild(c1);
    chart(c1, { type: 'line', data: { labels: ecoDays.map(fday), datasets: [ds('Total (€)', ecoDays.map(function (d) { return D.eco[d].total / 100; }), COL.green, { fill: true })] }, options: { scales: { y: { beginAtZero: true } } } });
    var c2 = card('Solde médian et concentration', 'Médiane (€) et part des 10 % les plus riches (%)'); g.appendChild(c2);
    chart(c2, { type: 'line', data: { labels: ecoDays.map(fday), datasets: [ds('Médiane (€)', ecoDays.map(function (d) { return D.eco[d].median / 100; }), COL.cyan, { yAxisID: 'y' }), ds('Top 10 % (%)', ecoDays.map(function (d) { return D.eco[d].top10 / 10; }), COL.orange, { yAxisID: 'y2' })] },
      options: { scales: { y: { beginAtZero: true }, y2: { position: 'right', min: 0, max: 100, grid: { display: false }, ticks: { color: COL.txt } } } } });
    var bk = [['< 100 €', 0, 1e4], ['100–1 k€', 1e4, 1e5], ['1–10 k€', 1e5, 1e6], ['10–100 k€', 1e6, 1e7], ['> 100 k€', 1e7, 1e18]];
    var c3 = card('Répartition de la richesse', 'Nombre de comptes par tranche de solde'); g.appendChild(c3);
    chart(c3, { type: 'bar', data: { labels: bk.map(function (b) { return b[0]; }), datasets: [ds('Comptes', bk.map(function (b) { return bals.filter(function (p) { return p.bal >= b[1] && p.bal < b[2]; }).length; }), COL.violet)] }, options: { scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } } });
    var days = periodDays();
    var c4 = card('Flux d’argent', 'Revenus, dépenses et salaires par jour (€, historique bancaire)'); g.appendChild(c4);
    function flow(key) { return days.map(function (d) { return sum(P.map(function (p) { var r = p.days[d]; return r && r.c[key] ? r.c[key] : 0; })) / 100; }); }
    chart(c4, { type: 'bar', data: { labels: days.map(fday), datasets: [ds('Revenus', flow('revenus_c'), COL.green), ds('Dépenses', flow('depenses_c'), COL.red), ds('Salaires', flow('salaires_c'), COL.cyan)] }, options: { scales: { y: { beginAtZero: true } } } });
    root.appendChild(g);
    var g2 = grid('cols2'); g2.style.marginTop = '16px';
    var c5 = card('Les plus gros patrimoines', 'Top 20 des soldes'); g2.appendChild(c5);
    var top = bals.slice().sort(function (a, b) { return b.bal - a.bal; }).slice(0, 20);
    var html = '<div class="scroll"><table><tbody>';
    top.forEach(function (p, i) { html += '<tr class="click" data-id="' + esc(p.id) + '"><td>' + (i + 1) + '</td><td>' + esc(p.label) + '</td><td class="num">' + eur(p.bal) + '</td></tr>'; });
    c5.insertAdjacentHTML('beforeend', html + '</tbody></table></div>');
    bindRows(c5);
    var c6 = card('Entreprises', 'Membres et solde cumulé de leurs membres'); g2.appendChild(c6);
    var comp = D.companies.slice().sort(function (a, b) { return b.members - a.members; });
    var h2 = '<div class="scroll"><table><thead><tr><th>Entreprise</th><th class="num">Membres</th><th class="num">Solde cumulé</th></tr></thead><tbody>';
    comp.forEach(function (c) { h2 += '<tr><td>' + esc(c.name) + '</td><td class="num">' + c.members + '</td><td class="num">' + eur(c.balance) + '</td></tr>'; });
    c6.insertAdjacentHTML('beforeend', h2 + '</tbody></table></div>' + (comp.length ? '' : '<p class="muted">Aucune donnée d’entreprise.</p>'));
    g2.appendChild(c6);
    root.appendChild(g2);
  }

  function renderItems(root) {
    var items = D.items;
    var g = grid('cols2');
    var c1 = card('Objets les plus possédés', 'Quantité totale en inventaire + coffre de l’Ender (dernier inventaire connu de chaque joueur)'); g.appendChild(c1);
    var top = items.slice(0, 20);
    chart(c1, { type: 'bar', data: { labels: top.map(function (i) { return itemName(i[0]); }), datasets: [ds('Quantité', top.map(function (i) { return i[1]; }), COL.orange)] }, options: { indexAxis: 'y', scales: { x: { beginAtZero: true } } } }, 'tall');
    var c2 = card('Recherche', 'Filtrer les 150 premiers objets'); g.appendChild(c2);
    var inp = el('<input type="search" placeholder="Rechercher un objet…">'); c2.appendChild(inp);
    var box = el('<div class="scroll" style="margin-top:10px"></div>'); c2.appendChild(box);
    function draw() {
      var q = inp.value.toLowerCase();
      var h = '<table><thead><tr><th>Objet</th><th class="num">Quantité</th><th class="num">Joueurs</th></tr></thead><tbody>';
      items.filter(function (i) { return !q || i[0].toLowerCase().indexOf(q) >= 0; }).forEach(function (i) {
        h += '<tr><td>' + esc(itemName(i[0])) + ' <span class="muted small">' + esc(itemNs(i[0])) + '</span></td><td class="num">' + nf(i[1]) + '</td><td class="num">' + i[2] + '</td></tr>';
      });
      box.innerHTML = h + '</tbody></table>';
    }
    inp.oninput = draw; draw();
    root.appendChild(g);
  }

  function renderMap(root) {
    var dims = Object.keys(D.heat.all);
    var c = card('Carte de chaleur des positions', 'Passages agrégés par zone de 16×16 blocs (échantillon toutes les 30 s). Molette = zoom, glisser = déplacer.');
    root.appendChild(c);
    if (!dims.length) { c.insertAdjacentHTML('beforeend', '<p class="muted">Pas encore de position enregistrée.</p>'); return; }
    if (!state.mapDim || dims.indexOf(state.mapDim) < 0) state.mapDim = dims.indexOf('minecraft:overworld') >= 0 ? 'minecraft:overworld' : dims[0];
    var bar = el('<div class="seg"></div>');
    dims.forEach(function (d) { var b = el('<button>' + esc(d.replace('minecraft:', '')) + '</button>'); if (d === state.mapDim) b.className = 'on'; b.onclick = function () { state.mapDim = d; render(); }; bar.appendChild(b); });
    [['all', 'Toute la période'], ['d7', '7 derniers jours']].forEach(function (s) { var b = el('<button>' + s[1] + '</button>'); if (s[0] === state.mapSpan) b.className = 'on'; b.onclick = function () { state.mapSpan = s[0]; render(); }; bar.appendChild(b); });
    c.appendChild(bar);
    var cv = el('<canvas class="map"></canvas>'); c.appendChild(cv);
    var info = el('<p class="muted small" id="mapinfo">Survolez la carte.</p>'); c.appendChild(info);
    var cells = (D.heat[state.mapSpan][state.mapDim]) || [];
    var map = {}; var mx = 1, sx = 0, sz = 0, tw = 0;
    cells.forEach(function (c2) { map[c2[0] + ',' + c2[1]] = c2[2]; if (c2[2] > mx) mx = c2[2]; sx += c2[0] * c2[2]; sz += c2[1] * c2[2]; tw += c2[2]; });
    var view_ = { x: tw ? sx / tw : 0, z: tw ? sz / tw : 0, s: 10 };
    function size() { var r = cv.getBoundingClientRect(); cv.width = Math.max(300, r.width * devicePixelRatio); cv.height = r.height * devicePixelRatio; }
    function draw() {
      var ctx = cv.getContext('2d'); var W = cv.width, H = cv.height, s = view_.s * devicePixelRatio;
      ctx.fillStyle = '#0b0c18'; ctx.fillRect(0, 0, W, H);
      ctx.strokeStyle = '#1d2040'; ctx.lineWidth = 1;
      var step = s < 6 ? 16 : 4; // grille tous les 64 blocs (ou 256)
      for (var gx = Math.floor((view_.x - W / 2 / s) / step) * step; gx < view_.x + W / 2 / s + step; gx += step) { var px = (gx - view_.x) * s + W / 2; ctx.beginPath(); ctx.moveTo(px, 0); ctx.lineTo(px, H); ctx.stroke(); }
      for (var gz = Math.floor((view_.z - H / 2 / s) / step) * step; gz < view_.z + H / 2 / s + step; gz += step) { var pz = (gz - view_.z) * s + H / 2; ctx.beginPath(); ctx.moveTo(0, pz); ctx.lineTo(W, pz); ctx.stroke(); }
      cells.forEach(function (c3) {
        var a = Math.log(1 + c3[2]) / Math.log(1 + mx);
        var x = (c3[0] - view_.x) * s + W / 2, y = (c3[1] - view_.z) * s + H / 2;
        if (x < -s || y < -s || x > W || y > H) return;
        ctx.fillStyle = a > 0.66 ? 'rgba(255,162,58,' + (0.5 + a / 2) + ')' : a > 0.33 ? 'rgba(255,77,141,' + (0.4 + a) + ')' : 'rgba(138,123,255,' + (0.3 + a) + ')';
        ctx.fillRect(x, y, Math.max(1, s - 1), Math.max(1, s - 1));
      });
      ctx.fillStyle = '#9aa0c8'; ctx.font = (11 * devicePixelRatio) + 'px sans-serif';
      ctx.fillText('x ' + Math.round(view_.x * 16) + ' · z ' + Math.round(view_.z * 16) + ' (centre) · échelle ' + Math.round(view_.s) + ' px / zone', 10, H - 10);
    }
    size(); draw();
    var drag = null;
    cv.onmousedown = function (e) { drag = { x: e.clientX, z: e.clientY, vx: view_.x, vz: view_.z }; cv.style.cursor = 'grabbing'; };
    window.onmouseup = function () { drag = null; cv.style.cursor = 'grab'; };
    cv.onmousemove = function (e) {
      var r = cv.getBoundingClientRect();
      if (drag) { view_.x = drag.vx - (e.clientX - drag.x) / view_.s; view_.z = drag.vz - (e.clientY - drag.z) / view_.s; draw(); return; }
      var cx = Math.floor(view_.x + (e.clientX - r.left - r.width / 2) / view_.s), cz = Math.floor(view_.z + (e.clientY - r.top - r.height / 2) / view_.s);
      info.textContent = 'Zone x ' + cx * 16 + '…' + (cx * 16 + 15) + ', z ' + cz * 16 + '…' + (cz * 16 + 15) + ' : ' + nf(map[cx + ',' + cz] || 0) + ' passages';
    };
    cv.onwheel = function (e) { e.preventDefault(); view_.s = Math.max(1.5, Math.min(80, view_.s * (e.deltaY < 0 ? 1.2 : 1 / 1.2))); draw(); };
    var total = sum(cells.map(function (x) { return x[2]; }));
    c.appendChild(el('<p class="muted small">' + nf(cells.length) + ' zones, ' + nf(total) + ' relevés (≈ ' + nf(total / 120, 1) + ' h-joueur).</p>'));
  }

  function renderPlayers(root) {
    var c = card('Joueurs', nf(P.length) + ' joueurs · identification : ' + D.mode + ' · cliquez sur une ligne pour ouvrir la fiche');
    var inp = el('<input type="search" placeholder="Rechercher (nom, identifiant, entreprise)…" style="min-width:320px">'); inp.value = state.q; c.appendChild(inp);
    var box = el('<div class="scroll" style="margin-top:10px;max-height:680px"></div>'); c.appendChild(box);
    var cols = [['label', 'Joueur'], ['online', 'État'], ['first', 'Arrivé'], ['last', 'Dernier passage'], ['playMin', 'Temps de jeu'], ['nSessions', 'Sessions'], ['bal', 'Solde'], ['companies', 'Métiers']];
    function draw() {
      var q = (state.q = inp.value).toLowerCase();
      var rows = P.filter(function (p) { return !q || (p.label + ' ' + p.id + ' ' + p.companies.join(' ')).toLowerCase().indexOf(q) >= 0; });
      var k = state.sort.k, d = state.sort.d;
      rows.sort(function (a, b) { var x = k === 'companies' ? a.companies.join() : a[k], y = k === 'companies' ? b.companies.join() : b[k]; return (x > y ? 1 : x < y ? -1 : 0) * d; });
      var h = '<table><thead><tr>' + cols.map(function (c2) { return '<th class="sort ' + (/(Min|sessions|bal|first|last)/i.test(c2[0]) ? 'num' : '') + '" data-k="' + c2[0] + '">' + c2[1] + (state.sort.k === c2[0] ? (d > 0 ? ' ▲' : ' ▼') : '') + '</th>'; }).join('') + '</tr></thead><tbody>';
      rows.slice(0, 500).forEach(function (p) {
        h += '<tr class="click" data-id="' + esc(p.id) + '"><td>' + esc(p.label) + '</td><td>' + (p.online ? '<span class="pill on">en ligne</span>' : '<span class="pill">hors ligne</span>') + '</td><td class="num">' + fdate(p.first) + '</td><td class="num">' + fdate(p.last) + '</td><td class="num">' + hm(p.playMin) + '</td><td class="num">' + p.nSessions + '</td><td class="num">' + (p.bal >= 0 ? eur(p.bal) : '—') + '</td><td>' + p.companies.map(function (n) { return '<span class="pill">' + esc(n) + '</span>'; }).join('') + (p.police ? '<span class="pill pol">police</span>' : '') + (p.secours ? '<span class="pill sec">secours</span>' : '') + '</td></tr>';
      });
      box.innerHTML = h + '</tbody></table>' + (rows.length > 500 ? '<p class="muted pad">500 premières lignes sur ' + rows.length + ' — affinez la recherche.</p>' : '');
      [].forEach.call(box.querySelectorAll('th.sort'), function (th) { th.onclick = function () { var kk = th.dataset.k; state.sort = { k: kk, d: state.sort.k === kk ? -state.sort.d : -1 }; draw(); }; });
      bindRows(box);
    }
    inp.oninput = draw; draw();
    root.appendChild(c);
  }

  function bindRows(root) {
    [].forEach.call(root.querySelectorAll('tr[data-id]'), function (tr) { tr.onclick = function () { openFiche(tr.dataset.id); }; });
  }

  var dlg = document.getElementById('fiche');
  var ficheCharts = [];
  function openFiche(id) {
    var p = P.filter(function (x) { return x.id === id; })[0]; if (!p) return;
    ficheCharts.forEach(function (c) { c.destroy(); }); ficheCharts = [];
    var days = []; for (var i = 59; i >= 0; i--) days.push(addDays(today, -i));
    dlg.innerHTML = '<button class="close">Fermer</button><h2></h2><p class="muted" id="fsub"></p><div class="grid kpis" id="fk"></div><div class="grid cols2" id="fg"></div>';
    dlg.querySelector('h2').textContent = p.label;
    dlg.querySelector('#fsub').textContent = (p.online ? 'En ligne · ' : '') + 'arrivé le ' + fdate(p.first) + ' · dernier passage ' + fdate(p.last);
    dlg.querySelector('.close').onclick = function () { dlg.close(); };
    var fk = dlg.querySelector('#fk');
    fk.appendChild(kpi(hm(p.playMin), 'Temps de jeu', p.nSessions + ' sessions'));
    fk.appendChild(kpi(p.bal >= 0 ? eur(p.bal) : '—', 'Solde bancaire'));
    fk.appendChild(kpi(nf(p.ad.length), 'Jours actifs'));
    var fg = dlg.querySelector('#fg');
    var c1 = card('Temps de jeu par jour', '60 derniers jours (minutes)'); fg.appendChild(c1);
    dlg.showModal();
    function ch(parent, cfg) { var wrap = el('<div class="chart"><canvas></canvas></div>'); parent.appendChild(wrap); cfg.options = Object.assign({ responsive: true, maintainAspectRatio: false, animation: false, plugins: { legend: { display: false } } }, cfg.options || {}); cfg.options.scales = { x: { ticks: { color: COL.txt, maxTicksLimit: 8 }, grid: { color: COL.grid + '88' } }, y: { beginAtZero: true, ticks: { color: COL.txt }, grid: { color: COL.grid + '88' } } }; ficheCharts.push(new Chart(wrap.firstChild, cfg)); }
    ch(c1, { type: 'bar', data: { labels: days.map(fday), datasets: [ds('Minutes', days.map(function (d) { return p.days[d] ? Math.round(p.days[d].p / 60) : 0; }), COL.pink)] } });
    var c2 = card('Solde', '60 derniers jours (€)'); fg.appendChild(c2);
    var last = null; var bs = days.map(function (d) { if (p.days[d] && p.days[d].b != null) last = p.days[d].b / 100; return last; });
    ch(c2, { type: 'line', data: { labels: days.map(fday), datasets: [ds('Solde', bs, COL.green, { spanGaps: true })] } });
    var c3 = card('Activités', 'Cumul depuis le premier passage'); fg.appendChild(c3);
    var t = '<table><tbody>';
    Object.keys(LABELS).forEach(function (k) { var v = p.tot[k]; if (!v) return; t += '<tr><td>' + esc(LABELS[k]) + '</td><td class="num">' + (/_c$/.test(k) ? eur(v) : /_s$/.test(k) ? hm(v / 60) : nf(v)) + '</td></tr>'; });
    c3.insertAdjacentHTML('beforeend', t + '</tbody></table>');
    var c4 = card('Possessions', 'Inventaire et coffre Ender connus, métiers, permis'); fg.appendChild(c4);
    var pills = p.companies.map(function (n) { return '<span class="pill">' + esc(n) + '</span>'; }).join('') + (p.police ? '<span class="pill pol">police</span>' : '') + (p.secours ? '<span class="pill sec">secours</span>' : '') + p.licences.map(function (n) { return '<span class="pill">' + esc(n) + '</span>'; }).join('');
    c4.insertAdjacentHTML('beforeend', '<p>' + (pills || '<span class="muted">Aucun métier ni permis connu.</span>') + '</p>');
    var its = Object.keys(p.items).map(function (k) { return [k, p.items[k]]; }).sort(function (a, b) { return b[1] - a[1]; }).slice(0, 15);
    c4.insertAdjacentHTML('beforeend', '<table><tbody>' + its.map(function (i) { return '<tr><td>' + esc(itemName(i[0])) + '</td><td class="num">' + nf(i[1]) + '</td></tr>'; }).join('') + '</tbody></table>');
    var c5 = card('Dernières sessions', 'Début, fin et durée'); fg.appendChild(c5);
    c5.insertAdjacentHTML('beforeend', '<table><tbody>' + p.sessions.slice().reverse().slice(0, 12).map(function (s) { return '<tr><td>' + fdate(s[0]) + '</td><td>' + fdate(s[1]) + '</td><td class="num">' + hm((s[1] - s[0]) / 60000) + '</td></tr>'; }).join('') + '</tbody></table>');
  }

  // ------------------------------------------------------------------ rendu
  function render() {
    destroyCharts();
    view.innerHTML = '';
    var t = document.getElementById('tabs');
    t.innerHTML = '';
    TABS.forEach(function (x) { var b = el('<button>' + x[1] + '</button>'); if (x[0] === state.tab) b.className = 'on'; b.onclick = function () { state.tab = x[0]; location.hash = x[0]; render(); }; t.appendChild(b); });
    ({ overview: renderOverview, retention: renderRetention, activity: renderActivity, economy: renderEconomy, items: renderItems, map: renderMap, players: renderPlayers })[state.tab](view);
  }
  document.getElementById('period').onchange = function (e) { state.period = +e.target.value; render(); };
  var h = location.hash.replace('#', ''); if (TABS.some(function (x) { return x[0] === h; })) state.tab = h;
  document.getElementById('foot').textContent = 'Généré le ' + fdate(D.generated) + ' · relevés toutes les ' + D.sampleMinutes + ' min · conservation ' + D.retentionDays + ' jours · identification : ' + D.mode + ' · données confidentielles, ne pas diffuser.';
  render();
})();
