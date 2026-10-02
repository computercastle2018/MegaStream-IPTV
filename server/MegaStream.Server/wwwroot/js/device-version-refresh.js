(() => {
    const section = document.querySelector("[data-device-version-refresh]");
    if (!section) return;
    let refreshing = false;

    async function refresh() {
        if (document.hidden || refreshing) return;
        refreshing = true;
        try {
            const response = await fetch(section.dataset.deviceVersionUrl, {
                credentials: "same-origin", cache: "no-store", redirect: "error",
                signal: AbortSignal.timeout(10000)
            });
            if (!response.ok) return;
            const html = await response.text();
            if (document.hidden) return;
            const source = new DOMParser().parseFromString(html, "text/html")
                .querySelector("[data-device-version-refresh]");
            if (source?.dataset.deviceVersionUrl !== section.dataset.deviceVersionUrl) return;
            const fields = ["[data-device-version-value]", "[data-device-version-reported-at]"];
            if (fields.some(field => !source.querySelector(field) || !section.querySelector(field))) return;
            for (const field of fields) {
                const target = section.querySelector(field);
                const value = source.querySelector(field).textContent;
                if (target.textContent !== value) target.textContent = value;
            }
        } catch {
            // Keep the last report on transient errors or an expired admin session.
        } finally {
            refreshing = false;
        }
    }

    setInterval(refresh, 60000);
})();
