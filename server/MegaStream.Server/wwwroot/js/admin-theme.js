// Per-viewer admin panel appearance, stored in localStorage:
//   theme:  auto (follow OS) / light / dark   (ms-theme)
//   accent: green (default) / blue / violet    (ms-accent)
(function () {
    var root = document.documentElement;

    // Generic switcher. `btnProp` = buttons' dataset key (data-*-choice),
    // `rootProp` = the attribute set on <html> (data-theme / data-accent),
    // `key` = localStorage key, `def` = default choice that clears both.
    function wire(selector, btnProp, rootProp, key, def, valid) {
        var buttons = document.querySelectorAll(selector);
        if (!buttons.length) return;
        var get = function (b) { return b.dataset[btnProp]; };

        function stored() {
            try {
                var v = localStorage.getItem(key);
                return valid.indexOf(v) >= 0 ? v : def;
            } catch (e) { return def; }
        }
        function apply(choice) {
            if (choice === def) delete root.dataset[rootProp];
            else root.dataset[rootProp] = choice;
            buttons.forEach(function (b) {
                b.setAttribute("aria-pressed", String(get(b) === choice));
            });
        }
        buttons.forEach(function (b) {
            b.addEventListener("click", function () {
                var choice = get(b);
                try {
                    if (choice === def) localStorage.removeItem(key);
                    else localStorage.setItem(key, choice);
                } catch (e) {}
                apply(choice);
            });
        });
        apply(stored());
    }

    wire("[data-theme-choice]", "themeChoice", "theme", "ms-theme", "auto", ["light", "dark"]);
    wire("[data-accent-choice]", "accentChoice", "accent", "ms-accent", "green", ["blue", "violet"]);
})();
