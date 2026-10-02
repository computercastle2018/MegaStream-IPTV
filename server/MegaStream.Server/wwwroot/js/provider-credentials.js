(() => {
    let visibilityRevision = 0;
    const labels = { serverUrl: "رابط الخادم", username: "اسم المستخدم", password: "كلمة المرور",
        m3uUrl: "رابط M3U", portalUrl: "رابط البوابة", stalkerMacAddress: "عنوان MAC لدى المزود",
        epgUrl: "رابط EPG", httpUserAgent: "User-Agent", httpHeaders: "ترويسات HTTP" };
    const clear = (root) => {
        const output = root.querySelector('[data-provider-credentials]');
        output.replaceChildren(); output.hidden = true;
        const button = root.querySelector('[data-provider-reveal] button');
        button.textContent = "إظهار بيانات الدخول"; button.setAttribute('aria-expanded', 'false');
    };
    const render = (value, parent) => {
        for (const [key, item] of Object.entries(value)) {
            if (item === null) continue;
            if (typeof item === 'object') { render(item, parent); continue; }
            const label = document.createElement('dt'); label.textContent = labels[key] || key;
            const field = document.createElement('dd');
            const text = document.createElement('bdi'); text.textContent = String(item);
            field.append(text); parent.append(label, field);
        }
    };
    document.querySelectorAll('[data-provider-access]').forEach(root => {
        const form = root.querySelector('[data-provider-reveal]');
        const output = root.querySelector('[data-provider-credentials]');
        const button = form.querySelector('button');
        form.addEventListener('submit', async event => {
            event.preventDefault();
            if (!output.hidden) { clear(root); return; }
            root.querySelector('[data-provider-error]').textContent = '';
            const requestedRevision = visibilityRevision;
            button.disabled = true;
            try {
                const response = await fetch(form.action, { method: 'POST', body: new FormData(form),
                    credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(10000) });
                if (!response.ok) throw new Error('unavailable');
                const value = await response.json();
                if (document.hidden || requestedRevision !== visibilityRevision) return;
                const details = document.createElement('dl'); render(value, details);
                output.replaceChildren(details); output.hidden = false;
                button.textContent = "إخفاء بيانات الدخول"; button.setAttribute('aria-expanded', 'true');
            } catch {
                clear(root);
                root.querySelector('[data-provider-error]').textContent = "تعذر إظهار بيانات الدخول.";
            } finally { button.disabled = false; }
        });
        const exportForm = root.querySelector('[data-provider-export]');
        if (exportForm) exportForm.addEventListener('submit', async event => {
            event.preventDefault();
            const downloadButton = exportForm.querySelector('button');
            const error = root.querySelector('[data-provider-error]');
            downloadButton.disabled = true; downloadButton.textContent = "جارٍ تنزيل M3U…"; error.textContent = '';
            try {
                const response = await fetch(exportForm.action, { method: 'POST', body: new FormData(exportForm),
                    credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(190000) });
                if (!response.ok || !response.headers.get('Content-Type')?.startsWith('audio/x-mpegurl')) throw new Error('unavailable');
                const blob = await response.blob();
                const disposition = response.headers.get('Content-Disposition') || '';
                const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i);
                const name = encodedName ? decodeURIComponent(encodedName[1]) : 'subscription.m3u';
                const link = document.createElement('a');
                const url = URL.createObjectURL(blob);
                try { link.href = url; link.download = name; link.click(); }
                finally { setTimeout(() => URL.revokeObjectURL(url), 1000); }
            } catch { error.textContent = "تعذر تنزيل قائمة M3U. تحقق من توفر المزود ثم أعد المحاولة."; }
            finally { downloadButton.disabled = false; downloadButton.textContent = "تنزيل M3U"; }
        });
    });
    const hideAll = () => {
        visibilityRevision++;
        document.querySelectorAll('[data-provider-access]').forEach(clear);
    };
    window.addEventListener('pagehide', hideAll);
    document.addEventListener('visibilitychange', () => { if (document.hidden) hideAll(); });
})();
