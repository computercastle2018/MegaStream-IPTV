import assert from "node:assert/strict";
import test from "node:test";
import { clearPlaybackProgress, loadPlaybackProgress, playbackProgressEnabled,
  savePlaybackProgress, setPlaybackProgressEnabled } from "../src/services/playbackProgress.ts";

function storage() {
  const data = new Map();
  globalThis.localStorage = {
    getItem: key => data.get(key) ?? null,
    setItem: (key, value) => data.set(key, value),
    removeItem: key => data.delete(key),
  };
  return data;
}

test("movie/provider/episode positions persist independently without storing stream credentials", () => {
  const data = storage();
  assert.equal(playbackProgressEnabled(), true);
  for (const [key, position] of [["provider-a:movie:1", 15], ["provider-b:movie:1", 25], ["provider-a:series:1:2", 35]]) {
    assert.equal(savePlaybackProgress(key, position, 120), true);
    assert.deepEqual(loadPlaybackProgress(key), { position, duration: 120 });
  }
  assert.equal(data.size, 3);
  assert.equal(clearPlaybackProgress("provider-a:movie:1"), true);
  assert.equal(loadPlaybackProgress("provider-a:movie:1"), null);
  assert.equal(loadPlaybackProgress("provider-b:movie:1").position, 25);
});

test("completed and just-started content resets; invalid/live durations do not overwrite a checkpoint", () => {
  storage();
  savePlaybackProgress("movie", 20, 120);
  for (const [position, duration] of [[NaN, 120], [20, Infinity], [Infinity, 120], [-1, 120], [20, 0]]) {
    savePlaybackProgress("movie", position, duration);
    assert.equal(loadPlaybackProgress("movie").position, 20);
  }
  savePlaybackProgress("movie", 120, 120);
  assert.equal(loadPlaybackProgress("movie"), null);
  savePlaybackProgress("movie", 20, 120);
  savePlaybackProgress("movie", 1, 120);
  assert.equal(loadPlaybackProgress("movie"), null);
});

test("disabled preference survives reads and stops saving/prompting without erasing existing history", () => {
  storage();
  savePlaybackProgress("movie", 20, 120);
  assert.equal(setPlaybackProgressEnabled(false), true);
  assert.equal(playbackProgressEnabled(), false);
  assert.equal(loadPlaybackProgress("movie"), null);
  savePlaybackProgress("movie", 30, 120);
  setPlaybackProgressEnabled(true);
  assert.equal(loadPlaybackProgress("movie").position, 20);
});

test("malformed or stale storage never offers an invalid seek", () => {
  const data = storage();
  for (const value of ["not-json", "null", "{}", '{"position":"20","duration":100}', '{"position":200,"duration":100}']) {
    data.set("MegaStream.playbackProgress.movie", value);
    assert.equal(loadPlaybackProgress("movie"), null);
  }
});

test("storage failures do not stop playback and are reported to the caller", () => {
  storage();
  localStorage.setItem = () => { throw new Error("Quota exceeded"); };
  localStorage.removeItem = () => { throw new Error("Storage unavailable"); };
  assert.equal(savePlaybackProgress("movie", 20, 120), false);
  assert.equal(clearPlaybackProgress("movie"), false);
  assert.equal(setPlaybackProgressEnabled(false), false);
  localStorage.getItem = () => { throw new Error("Storage unavailable"); };
  assert.equal(loadPlaybackProgress("movie"), null);
});
