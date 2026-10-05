/*
 * Evaluation DRD: clicking a decision on the diagram jumps to its result row
 * (a plain fragment link) and also opens the row's matched-rules table; clicking an input focuses its field.
 */
(function () {
    'use strict';

    function reveal(hash) {
        var target = hash && hash.length > 1 ? document.getElementById(decodeURIComponent(hash.substring(1))) : null;
        if (!target) {
            return;
        }
        var details = target.querySelector('details');
        if (details) {
            details.open = true;
        }
        if (target.tagName === 'INPUT') {
            target.focus({preventScroll: true});
        }
    }

    document.addEventListener('click', function (evt) {
        var a = evt.target.closest ? evt.target.closest('svg.drd a') : null;
        var href = a ? a.getAttribute('href') : null;
        if (href && href.charAt(0) === '#') {
            reveal(href);
        }
    });
    window.addEventListener('hashchange', function () {
        reveal(location.hash);
    });
    reveal(location.hash);
})();

/*
 * Progressive enhancement for the server-rendered DRD (edit mode only).
 * - drag a shape to move it (POST .../elements/{id}/bounds)
 * - Shift+drag from one shape onto another to connect them (POST .../connections)
 * - click a shape to open it
 * Every change is persisted on the server, then the page reloads to show the re-rendered SVG.
 */
(function () {
    'use strict';
    var svg = document.querySelector('svg.drd[data-editable="true"]');
    if (!svg) {
        return;
    }
    var base = svg.getAttribute('data-base');
    var modelUrl = svg.getAttribute('data-model-url');
    var diagramId = svg.getAttribute('data-diagram') || '';
    var GRID = 10;
    var drag = null;

    function toSvg(evt) {
        var pt = svg.createSVGPoint();
        pt.x = evt.clientX;
        pt.y = evt.clientY;
        return pt.matrixTransform(svg.getScreenCTM().inverse());
    }

    function nodeAt(evt) {
        var el = document.elementFromPoint(evt.clientX, evt.clientY);
        return el && el.closest ? el.closest('g.node') : null;
    }

    function post(url, data) {
        var body = new URLSearchParams(data);
        return fetch(url, {
            method: 'POST',
            headers: {'X-Requested-With': 'fetch', 'Content-Type': 'application/x-www-form-urlencoded'},
            body: body,
            credentials: 'same-origin'
        }).then(function (res) {
            if (res.ok) {
                window.location.reload();
                return;
            }
            return res.text().then(function (html) {
                var doc = new DOMParser().parseFromString(html, 'text/html');
                var msg = doc.querySelector('.message');
                window.alert(msg ? msg.textContent : 'The change could not be saved.');
                window.location.reload();
            });
        }).catch(function () {
            window.alert('The server could not be reached.');
        });
    }

    svg.addEventListener('click', function (evt) {
        if (evt.target.closest && evt.target.closest('g.node a')) {
            evt.preventDefault(); // navigation is handled on pointerup
        }
    });

    svg.addEventListener('pointerdown', function (evt) {
        var node = evt.target.closest ? evt.target.closest('g.node') : null;
        if (!node || evt.button !== 0 || node.classList.contains('node-external')) {
            return;
        }
        evt.preventDefault();
        var start = toSvg(evt);
        drag = {node: node, start: start, moved: false, connect: evt.shiftKey, line: null, target: null};
        if (drag.connect) {
            var cx = parseFloat(node.dataset.x) + parseFloat(node.dataset.w) / 2;
            var cy = parseFloat(node.dataset.y) + parseFloat(node.dataset.h) / 2;
            var line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
            line.setAttribute('class', 'connect-line');
            line.setAttribute('x1', cx);
            line.setAttribute('y1', cy);
            line.setAttribute('x2', start.x);
            line.setAttribute('y2', start.y);
            svg.appendChild(line);
            drag.line = line;
        }
        svg.setPointerCapture(evt.pointerId);
    });

    svg.addEventListener('pointermove', function (evt) {
        if (!drag) {
            return;
        }
        var p = toSvg(evt);
        var dx = p.x - drag.start.x;
        var dy = p.y - drag.start.y;
        if (!drag.moved && Math.abs(dx) + Math.abs(dy) > 4) {
            drag.moved = true;
            drag.node.classList.add('dragging');
        }
        if (!drag.moved) {
            return;
        }
        if (drag.connect) {
            drag.line.setAttribute('x2', p.x);
            drag.line.setAttribute('y2', p.y);
            var over = nodeAt(evt);
            if (drag.target && drag.target !== over) {
                drag.target.classList.remove('drop-target');
            }
            drag.target = over && over !== drag.node ? over : null;
            if (drag.target) {
                drag.target.classList.add('drop-target');
            }
        } else {
            drag.node.setAttribute('transform', 'translate(' + dx + ' ' + dy + ')');
        }
    });

    function finish(evt, cancelled) {
        if (!drag) {
            return;
        }
        var d = drag;
        drag = null;
        if (svg.hasPointerCapture(evt.pointerId)) {
            svg.releasePointerCapture(evt.pointerId);
        }
        d.node.classList.remove('dragging');
        if (d.line) {
            d.line.remove();
        }
        if (d.target) {
            d.target.classList.remove('drop-target');
        }
        if (cancelled) {
            d.node.removeAttribute('transform');
            return;
        }
        var id = d.node.dataset.id;
        if (!d.moved) {
            var link = d.node.querySelector('a');
            if (link && link.getAttribute('href')) {
                window.location.href = link.getAttribute('href');
            }
            return;
        }
        if (d.connect) {
            var target = nodeAt(evt);
            if (target && target !== d.node && !target.classList.contains('node-external')) {
                post(modelUrl + '/connections', {source: id, target: target.dataset.id, drd: diagramId});
            }
            return;
        }
        var p = toSvg(evt);
        var x = Math.round((parseFloat(d.node.dataset.x) + p.x - d.start.x) / GRID) * GRID;
        var y = Math.round((parseFloat(d.node.dataset.y) + p.y - d.start.y) / GRID) * GRID;
        post(base + encodeURIComponent(id) + '/bounds', {drd: diagramId, x: x, y: y});
    }

    svg.addEventListener('pointerup', function (evt) { finish(evt, false); });
    svg.addEventListener('pointercancel', function (evt) { finish(evt, true); });
    document.addEventListener('keydown', function (evt) {
        if (evt.key === 'Escape' && drag) {
            finish({pointerId: -1}, true);
        }
    });
})();
