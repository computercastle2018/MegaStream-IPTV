import assert from 'node:assert/strict';
import test from 'node:test';
import { playbackTime, seekTarget } from '../src/services/playerControls.ts';

test('TV Pro transport formats actual elapsed and unknown/live time', () => {
  assert.equal(playbackTime(0), '00:00');
  assert.equal(playbackTime(83.9), '01:23');
  assert.equal(playbackTime(3723), '1:02:03');
  for (const value of [NaN, Infinity, -1]) assert.equal(playbackTime(value), '--:--');
});

test('TV Pro seek clamps to VOD and live windows and rejects unavailable ranges', () => {
  assert.equal(seekTarget(-10, 0, 120), 0);
  assert.equal(seekTarget(130, 0, 120), 120);
  assert.equal(seekTarget(5, 50, 120), 50);
  assert.equal(seekTarget(90, 50, 120), 90);
  for (const args of [[5, 0, 0], [5, 0, Infinity], [NaN, 0, 20], [5, 20, 10], [5, -1, 10]]) {
    assert.equal(seekTarget(...args), null);
  }
});
