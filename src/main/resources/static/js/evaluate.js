/*
 * Progressive enhancement for the evaluation DRD: clicking a decision on the diagram jumps to its result row
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
