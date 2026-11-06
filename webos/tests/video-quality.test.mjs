import assert from "node:assert/strict";
import test from "node:test";
import { loadVideoQuality, saveVideoQuality, selectQualityLevel } from "../src/services/videoQuality.ts";

test("resolution and fixed mode persist without storing source credentials", () => {
  const data = new Map();
  globalThis.localStorage = { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value) };
  assert.deepEqual(loadVideoQuality(), { height: 1080, fixed: false });
  assert.equal(saveVideoQuality({ height: 480, fixed: true }), true);
  assert.deepEqual(loadVideoQuality(), { height: 480, fixed: true });
  data.set("MegaStream.videoQuality", '{"height":99999,"fixed":true}');
  assert.deepEqual(loadVideoQuality(), { height: 1080, fixed: false });
  localStorage.setItem = () => { throw new Error("Storage full"); };
  assert.equal(saveVideoQuality({ height: null, fixed: false }), false);
});

test("rendition selection obeys the requested ceiling without assuming source order", () => {
  const levels = [{ height: 1080, bitrate: 5000000 }, { height: 360, bitrate: 400000 },
    { height: 720, bitrate: 2500000 }, { height: 480, bitrate: 900000 }];
  for (const [height, expected] of [[null, 0], [480, 3], [720, 2], [2160, 0], [240, 1]]) {
    assert.equal(selectQualityLevel(levels, height), expected);
  }
  assert.equal(selectQualityLevel([], 1080), -1);
  assert.equal(selectQualityLevel([{ height: 0 }], 480), 0);
});
