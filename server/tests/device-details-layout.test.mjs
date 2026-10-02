import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const view = readFileSync(new URL("../MegaStream.Server/Views/Admin/DeviceDetails.cshtml", import.meta.url), "utf8");
const css = readFileSync(new URL("../MegaStream.Server/wwwroot/css/admin.css", import.meta.url), "utf8");

// Source layout contracts; server integration tests cover rendered forms and CSRF.
test("section navigation resolves and lengthy logs are native collapsibles", () => {
  const links = [...view.matchAll(/href="#(device-[^"]+)"/g)].map(match => match[1]);
  assert.equal(links.length, 7);
  for (const id of links) assert.ok(view.includes(`id="${id}"`), id);
  const ids = [...view.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  assert.equal(new Set(ids).size, ids.length, "Element IDs must be unique");
  assert.match(view, /label for="device-license-choice"/);
  assert.match(view, /select id="device-license-choice"/);
  for (const id of ["device-audit", "device-sessions", "device-diagnostics"]) {
    assert.match(view, new RegExp(`<details id="${id}"[^>]*>\\s*<summary>`));
  }
  assert.ok(view.indexOf('id="device-summary"') < view.indexOf('id="device-quality"'));
});

test("each table has its own card, subscriptions start open, and long histories start closed", () => {
  const cards = [...view.matchAll(/<details id="(device-[^"]+)" class="device-table-card"([^>]*)>/g)];
  assert.equal(cards.length, 5);
  for (const [, id, attributes] of cards) {
    assert.equal(attributes.includes("open"), id === "device-subscriptions-table", id);
  }
  assert.match(view, /<summary>اشتراكات الجهاز <span class="badge">@Model\.LocalSubscriptions\.Count/);
  for (const property of ["UpdateCommands", "PolicyAudit", "Sessions", "Diagnostics"]) {
    assert.ok(view.includes(`class="badge">@Model.${property}.Count`), property);
  }
});

test("version refresh hooks remain unique and read-only; shared subscription partial is retained", () => {
  const report = view.match(/<dl data-device-version-refresh[\s\S]*?<\/dl>/)?.[0];
  assert.ok(report);
  assert.doesNotMatch(report, /<form|<select/);
  for (const hook of ["data-device-version-refresh", "data-device-version-value", "data-device-version-reported-at"]) {
    assert.equal(view.split(hook).length - 1, 1, hook);
  }
  assert.match(view, /js\/device-version-refresh\.js/);
  assert.match(view, /js\/provider-credentials\.js/);
  assert.match(view, /RemoteProviders\/Views\/_ProviderAccess\.cshtml/);
  assert.match(view, /LocalCredentialIds\.Contains\(subscription\.LocalId\)/);
});

test("all existing mutations retain POST forms and quality CSS stays route-scoped", () => {
  for (const action of ["SetDevicePlaybackQuality", "SetDeviceUiStyle", "SetSubscriptionDetailsPolicy", "AssignDeviceLicense", "RevokeDevice"]) {
    assert.match(view, new RegExp(`<form asp-action="${action}" method="post">`));
  }
  assert.match(view, /action="\/admin\/updates\/send"/);
  assert.match(view, /@Html\.AntiForgeryToken\(\)/);
  assert.match(view, /partial name="_DevicePolicyForm"/);
  const scoped = css.slice(css.indexOf("/* Device details:"));
  assert.match(scoped, /\.device-detail \.device-section-nav/);
  assert.match(scoped, /grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/);
  assert.match(scoped, /@media \(max-width: 480px\)/);
  assert.doesNotMatch(scoped, /^\s*(?:input|form|select|nav|\.panel)\s*\{/m);
});
