// wire-nav — prev/next + flow-map link + data-goto hotspots + arrow keys.
(function () {
  var SCREENS = [
    '00-flow-map.html',
    '01-log-home.html',
    '02-new-item.html',
    '03-evening-checks.html',
    '04-moment-checkin.html',
    '05-entry-points.html',
    '06-homepage.html',
    '07-homepage-lofi.html'
  ];
  var here = location.pathname.split('/').pop() || SCREENS[0];
  var idx = SCREENS.indexOf(here);

  function go(file) { if (file) location.href = file; }
  function byPrefix(p) {
    for (var i = 0; i < SCREENS.length; i++) if (SCREENS[i].indexOf(p) === 0) return SCREENS[i];
    return null;
  }

  var nav = document.createElement('div');
  nav.className = 'wire-nav';
  nav.innerHTML =
    '<a href="00-flow-map.html">⌂ flow map</a>' +
    '<span class="spacer"></span>' +
    '<span>' + here + ' (' + (idx + 1) + '/' + SCREENS.length + ')</span>' +
    '<a href="#" id="wn-prev">← prev</a>' +
    '<a href="#" id="wn-next">next →</a>';
  document.body.appendChild(nav);

  document.getElementById('wn-prev').addEventListener('click', function (e) {
    e.preventDefault(); if (idx > 0) go(SCREENS[idx - 1]);
  });
  document.getElementById('wn-next').addEventListener('click', function (e) {
    e.preventDefault(); if (idx < SCREENS.length - 1) go(SCREENS[idx + 1]);
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'ArrowLeft' && idx > 0) go(SCREENS[idx - 1]);
    if (e.key === 'ArrowRight' && idx < SCREENS.length - 1) go(SCREENS[idx + 1]);
  });
  document.addEventListener('click', function (e) {
    var el = e.target.closest('[data-goto]');
    if (el) { e.preventDefault(); go(byPrefix(el.getAttribute('data-goto'))); }
  });
})();
