// Feature 013 — in-place upvoting on the two Topic list screens (FR-006a to FR-006d, FR-008a to
// FR-008e; contracts/vote-control-fragment.md).
//
// A progressive enhancement, not a rewrite. Every control on the page is a real <form> that works
// without this file; all this does is intercept the submit, apply the change immediately, and
// reconcile against what the server says. If the file fails to load, is blocked, or throws, the
// forms keep posting exactly as they did before feature 013 (FR-006b) — which is why the listener
// is attached per form rather than replacing any markup.
//
// Two rules shape the rest of it:
//
//  1. The button element is NEVER replaced (research.md §2). Swapping a focused node destroys
//     focus and silently sends it to <body> — a regression only keyboard and screen-reader users
//     would notice. Instead the four contract values are copied onto the live nodes.
//
//  2. The display updates optimistically, but ANNOUNCEMENTS WAIT for the server (FR-008d). Sighted
//     users get the instant response; nobody is told a vote succeeded that did not. A reverted
//     optimistic change announces the reversal instead.
(function () {
    'use strict';

    var FRAGMENT_HEADER = 'X-Vote-Fragment';

    function liveRegion() {
        return document.getElementById('vote-live-region');
    }

    // FR-008b: polite, so it never interrupts what a screen reader is already reading, and never
    // moves focus. Re-setting the text is what triggers the announcement.
    function announce(message) {
        var region = liveRegion();
        if (region) {
            region.textContent = message;
        }
    }

    function countNode(form) {
        return form.querySelector('[data-vote-count]');
    }

    function buttonNode(form) {
        return form.querySelector('[data-vote-button]');
    }

    function readState(form) {
        var count = countNode(form);
        var button = buttonNode(form);
        return {
            count: count ? count.textContent : '0',
            pressed: button ? button.getAttribute('aria-pressed') : 'false',
            label: button ? button.getAttribute('aria-label') : '',
            action: form.getAttribute('action'),
            cls: button ? button.className : ''
        };
    }

    function applyState(form, state) {
        var count = countNode(form);
        var button = buttonNode(form);
        if (count) {
            count.textContent = state.count;
        }
        if (button) {
            button.setAttribute('aria-pressed', state.pressed);
            if (state.label) {
                button.setAttribute('aria-label', state.label);
            }
            button.className = state.cls;
            var glyph = button.querySelector('.vote-glyph');
            if (glyph) {
                glyph.textContent = state.pressed === 'true' ? '▲' : '△';
            }
        }
        if (state.action) {
            form.setAttribute('action', state.action);
        }
    }

    // The optimistic guess. It is only a guess: the server's answer replaces it wholesale.
    function predictedState(current) {
        var nowPressed = current.pressed !== 'true';
        var n = parseInt(current.count, 10);
        if (isNaN(n)) {
            n = 0;
        }
        return {
            count: String(Math.max(0, nowPressed ? n + 1 : n - 1)),
            pressed: nowPressed ? 'true' : 'false',
            label: '',
            action: null,
            cls: nowPressed
                ? (current.cls.indexOf('is-voted') === -1 ? current.cls + ' is-voted' : current.cls)
                : current.cls.replace(/\s*is-voted/, '')
        };
    }

    // Reads the server's authoritative control out of the returned fragment. Parsing into a
    // detached document means nothing from the response is ever attached to the live page — only
    // the four contract values are copied across.
    function stateFromFragment(html) {
        var holder = document.implementation.createHTMLDocument('');
        holder.body.innerHTML = html;
        var form = holder.querySelector('[data-vote-form]');
        if (!form) {
            return null;
        }
        return readState(form);
    }

    function dismissNotice(cell) {
        var existing = cell.querySelector('.vote-notice');
        if (existing) {
            existing.parentNode.removeChild(existing);
        }
    }

    // FR-006c1/c2/c3 and FR-008e: the explanation sits beside the control that failed, overlays
    // rather than reflows (the positioning lives in app.css), and role="alert" means making it
    // visible is itself the announcement — no second mechanism needed for assistive technology.
    function showNotice(cell, message) {
        dismissNotice(cell);
        var notice = document.createElement('p');
        notice.className = 'vote-notice';
        notice.setAttribute('role', 'alert');
        notice.appendChild(document.createTextNode(message));

        var dismiss = document.createElement('button');
        dismiss.setAttribute('type', 'button');
        dismiss.setAttribute('aria-label', 'Dismiss this message');
        dismiss.appendChild(document.createTextNode('×'));
        dismiss.addEventListener('click', function () {
            dismissNotice(cell);
        });
        notice.appendChild(dismiss);

        cell.appendChild(notice);
    }

    function enhance(form) {
        var cell = form.closest('.vote-cell') || form.parentNode;
        // FR-006d: overlapping activations must settle on the LAST one, not on whichever response
        // happens to arrive last. Each submit takes the next ticket; a response whose ticket is no
        // longer current is discarded rather than applied.
        var ticket = 0;

        form.addEventListener('submit', function (event) {
            event.preventDefault();
            dismissNotice(cell);

            var before = readState(form);
            var mine = ++ticket;
            applyState(form, predictedState(before));

            var request = new XMLHttpRequest();
            request.open('POST', before.action, true);
            request.setRequestHeader(FRAGMENT_HEADER, 'true');
            request.setRequestHeader('X-Requested-With', 'XMLHttpRequest');

            request.onload = function () {
                if (mine !== ticket) {
                    return; // superseded by a later activation
                }
                var confirmed = request.status === 200 ? stateFromFragment(request.responseText) : null;
                if (!confirmed) {
                    applyState(form, before);
                    showNotice(cell, 'Your vote could not be saved. Please try again.');
                    return;
                }
                applyState(form, confirmed);
                // FR-008d: only now, with the server's answer in hand, is anything announced.
                announce((confirmed.pressed === 'true' ? 'Upvoted. ' : 'Upvote withdrawn. ')
                    + (confirmed.label || '') + ' ' + confirmed.count + ' upvotes.');
            };

            request.onerror = function () {
                if (mine !== ticket) {
                    return;
                }
                applyState(form, before);
                showNotice(cell, 'Your vote could not be saved. Please check your connection.');
            };

            // No CSRF token: it is disabled project-wide in SecurityConfig (research.md §8.5). If
            // that ever changes, this is one of the places that must learn to send one.
            request.send(new FormData(form));
        });
    }

    function init() {
        var forms = document.querySelectorAll('[data-vote-form]');
        for (var i = 0; i < forms.length; i++) {
            enhance(forms[i]);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
