const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { join } = require("node:path");
const { runInNewContext } = require("node:vm");

test("visible refresh changes only report text; hidden, concurrent, failed and wrong-device responses preserve edits", async () => {
    const version = "[data-device-version-value]";
    const reportedAt = "[data-device-version-reported-at]";
    const url = "/Admin/DeviceDetails?id=owner";
    const fields = { [version]: { textContent: "v44 (44)" }, [reportedAt]: { textContent: "old report" } };
    const edits = { uiStyle: "studio", mode: "managed", focus: "device-ui-style" };
    const section = {
        dataset: { deviceVersionUrl: url },
        querySelector(selector) { assert.ok(selector in fields); return fields[selector]; },
        set innerHTML(_) { assert.fail("Must not replace markup or forms"); }
    };
    let source = { dataset: { deviceVersionUrl: url }, querySelector: selector =>
        ({ [version]: { textContent: "v45 (45)" }, [reportedAt]: { textContent: "new report" } })[selector] };
    const document = { hidden: false, querySelector: () => section };
    let refresh, requests = 0, pending;
    let response = { ok: true, text: async () => "server HTML" };
    const script = readFileSync(join(__dirname, "../MegaStream.Server/wwwroot/js/device-version-refresh.js"), "utf8");
    runInNewContext(script, {
        document,
        fetch: async (target, options) => {
            requests++;
            assert.equal(target, url);
            assert.equal(options.credentials, "same-origin");
            assert.equal(options.cache, "no-store");
            assert.equal(options.redirect, "error");
            return pending || response;
        },
        AbortSignal,
        DOMParser: class { parseFromString() { return { querySelector: () => source }; } },
        setInterval: (callback, interval) => { assert.equal(interval, 60000); refresh = callback; }
    });
    document.hidden = true;
    await refresh();
    assert.equal(requests, 0);
    document.hidden = false;
    await refresh();
    assert.equal(fields[version].textContent, "v45 (45)");
    assert.equal(fields[reportedAt].textContent, "new report");
    assert.deepEqual(edits, { uiStyle: "studio", mode: "managed", focus: "device-ui-style" });

    let finish;
    pending = new Promise(resolve => { finish = resolve; });
    const inFlight = refresh();
    await refresh();
    assert.equal(requests, 2);
    document.hidden = true;
    fields[version].textContent = "retain while hidden";
    finish(response);
    await inFlight;
    assert.equal(fields[version].textContent, "retain while hidden");
    document.hidden = false;
    pending = null;
    response = { ok: false };
    await refresh();
    assert.equal(fields[version].textContent, "retain while hidden");
    response = { ok: true, text: async () => "other device" };
    source.dataset.deviceVersionUrl = "/Admin/DeviceDetails?id=other";
    await refresh();
    assert.equal(fields[version].textContent, "retain while hidden");
    source.dataset.deviceVersionUrl = url;
    response = { ok: true, text: async () => { throw new Error("Connection lost"); } };
    await refresh();
    response = { ok: true, text: async () => "server HTML" };
    await refresh();
    assert.equal(fields[version].textContent, "v45 (45)");
});
