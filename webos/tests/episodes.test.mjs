import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

// Node's installed TypeScript support needs explicit extensions for the
// extensionless imports used by Vite.
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && context.parentURL?.endsWith(".ts")
      && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(specifier + ".ts", context);
    }
    return nextResolve(specifier, context);
  },
});

const { episodeTimestamp, selectEpisodes } = await import("../src/services/episodes.ts");
const { getEpisodes } = await import("../src/services/xtreamClient.ts");
const episode = (id, season, number, newestAt, title = id) => ({
  id, season, episode: number, newestAt, title,
  streamUrl: "https://example.invalid/episode.mp4", streamFormat: "auto",
});

test("undated episodes default to descending season/episode without mutating input", () => {
  const input = [episode("s1e2", 1, 2), episode("s2e1", 2, 1), episode("s1e3", 1, 3)];
  assert.deepEqual(selectEpisodes(input, null, "", "newest").map(e => e.id), ["s2e1", "s1e3", "s1e2"]);
  assert.deepEqual(selectEpisodes(input, null, "", "oldest").map(e => e.id), ["s1e2", "s1e3", "s2e1"]);
  assert.deepEqual(input.map(e => e.id), ["s1e2", "s2e1", "s1e3"]);
});

test("valid provider dates order first, while undated episodes use numeric fallback", () => {
  const input = [
    episode("missing", 9, 8), episode("older", 5, 1, 1000),
    episode("newer", 1, 1, 2000), episode("invalid", 9, 9, NaN),
  ];
  assert.deepEqual(selectEpisodes(input, null, "", "newest").map(e => e.id),
    ["newer", "older", "invalid", "missing"]);
  assert.deepEqual(selectEpisodes(input, null, "", "oldest").map(e => e.id),
    ["older", "newer", "missing", "invalid"]);
});

test("season and Arabic-aware search compose with ordering, including zero matches", () => {
  const input = [
    episode("a", 1, 1, undefined, "الحَلْقَة الأولى"),
    episode("b", 1, 2, undefined, "الحلقة الثانية"),
    episode("c", 2, 1, undefined, "الحلقة الأولى"),
  ];
  assert.deepEqual(selectEpisodes(input, 1, "  الحلقه الاولي  ", "newest").map(e => e.id), ["a"]);
  assert.deepEqual(selectEpisodes(input, null, "الأولى", "newest").map(e => e.id), ["c", "a"]);
  assert.deepEqual(selectEpisodes(input, 1, "no match", "newest"), []);
});

test("timestamps accept seconds, milliseconds and valid ISO dates but reject invalid values", () => {
  assert.equal(episodeTimestamp("1704067200"), 1704067200000);
  assert.equal(episodeTimestamp(1704067200000), 1704067200000);
  assert.equal(episodeTimestamp("2024-01-01"), 1704067200000);
  assert.ok(episodeTimestamp("2024-02-29"));
  for (const raw of [null, "", "0", "-1", Infinity, "2023-02-29", "2024-13-01", "not a date"]) {
    assert.equal(episodeTimestamp(raw), undefined, String(raw));
  }
});

test("Xtream mapping preserves playback URLs and exposes added/release dates for actual ordering", async () => {
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({
    episodes: { "1": [
      { id: 1, episode_num: 3, title: "Older", added: "1704067200", container_extension: "mp4" },
      { id: 2, episode_num: 1, title: "Newer", added: "bad", info: { releasedate: "2025-01-01" } },
      { id: 3, episode_num: 4, title: "Undated", info: { releasedate: "2023-02-29" } },
    ] },
  }));
  try {
    const list = await getEpisodes({
      id: "provider", type: "xtream", name: "Test",
      serverUrl: "https://example.invalid/", username: "test user", password: "test",
    }, "10");
    assert.deepEqual(selectEpisodes(list, null, "", "newest").map(e => e.id), ["2", "1", "3"]);
    assert.equal(list.find(e => e.id === "1").streamUrl,
      "https://example.invalid/series/test%20user/test/1.mp4");
    assert.equal(list.find(e => e.id === "3").newestAt, undefined);
  } finally {
    globalThis.fetch = previousFetch;
  }
});
