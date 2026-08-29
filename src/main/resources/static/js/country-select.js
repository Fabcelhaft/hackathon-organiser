// Accessible combobox filtering for the built-in Country custom field (FR-013, FR-045,
// research.md §1). Filters the server-rendered `role="listbox"` as the user types in the
// `role="combobox"` text input, updates `aria-expanded`/`aria-activedescendant`, and writes the
// chosen ISO alpha-2 code into the hidden submit input. No server round-trip: the full country
// list is already rendered server-side.
(function () {
    'use strict';

    function initCountryField(container) {
        var search = container.querySelector('input[role="combobox"]');
        var listbox = container.querySelector('ul[role="listbox"]');
        var hiddenInput = container.querySelector('input[type="hidden"]');
        if (!search || !listbox || !hiddenInput) {
            return;
        }
        var options = Array.prototype.slice.call(listbox.querySelectorAll('li[role="option"]'));
        var activeIndex = -1;

        function displayNameForCode(code) {
            var match = options.find(function (option) {
                return option.getAttribute('data-code') === code;
            });
            return match ? match.textContent : '';
        }

        // Pre-fill: show the stored country's display name, not its bare code.
        var initialCode = search.getAttribute('data-selected-code');
        if (initialCode) {
            search.value = displayNameForCode(initialCode);
        }

        function openListbox() {
            listbox.hidden = false;
            search.setAttribute('aria-expanded', 'true');
        }

        function closeListbox() {
            listbox.hidden = true;
            search.setAttribute('aria-expanded', 'false');
            search.removeAttribute('aria-activedescendant');
            activeIndex = -1;
        }

        function filter() {
            var query = search.value.trim().toLowerCase();
            var anyVisible = false;
            options.forEach(function (option) {
                var visible = query.length === 0 || option.textContent.toLowerCase().indexOf(query) !== -1;
                option.hidden = !visible;
                if (visible) {
                    anyVisible = true;
                }
            });
            activeIndex = -1;
            search.removeAttribute('aria-activedescendant');
            if (anyVisible) {
                openListbox();
            } else {
                closeListbox();
            }
        }

        function visibleOptions() {
            return options.filter(function (option) {
                return !option.hidden;
            });
        }

        function setActive(index) {
            var visible = visibleOptions();
            if (visible.length === 0) {
                return;
            }
            activeIndex = (index + visible.length) % visible.length;
            search.setAttribute('aria-activedescendant', visible[activeIndex].id);
        }

        function choose(option) {
            hiddenInput.value = option.getAttribute('data-code');
            search.value = option.textContent;
            options.forEach(function (candidate) {
                candidate.setAttribute('aria-selected', candidate === option ? 'true' : 'false');
            });
            closeListbox();
        }

        search.addEventListener('input', filter);
        search.addEventListener('focus', filter);

        search.addEventListener('keydown', function (event) {
            var visible = visibleOptions();
            if (event.key === 'ArrowDown') {
                event.preventDefault();
                openListbox();
                setActive(activeIndex + 1);
            } else if (event.key === 'ArrowUp') {
                event.preventDefault();
                openListbox();
                setActive(activeIndex - 1);
            } else if (event.key === 'Enter') {
                if (activeIndex >= 0 && activeIndex < visible.length) {
                    event.preventDefault();
                    choose(visible[activeIndex]);
                }
            } else if (event.key === 'Escape') {
                closeListbox();
            }
        });

        options.forEach(function (option) {
            option.addEventListener('mousedown', function (event) {
                event.preventDefault();
                choose(option);
            });
        });

        document.addEventListener('click', function (event) {
            if (!container.contains(event.target)) {
                closeListbox();
            }
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var containers = document.querySelectorAll('[data-country-field]');
        containers.forEach(initCountryField);
    });
})();
