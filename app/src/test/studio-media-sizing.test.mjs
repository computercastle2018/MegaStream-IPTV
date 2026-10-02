import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = (path) => readFileSync(new URL(`../main/java/com/streamvault/app/${path}`, import.meta.url), 'utf8');

test('Studio media stays compact in shared movie and series layouts', () => {
  const hero = source('ui/components/shell/StudioCatalogHero.kt');
  const layouts = source('ui/screens/vod/StudioVodLayouts.kt');
  assert.match(hero, /heightIn\(min = if \(compact\) 180\.dp else 160\.dp\)/);
  assert.doesNotMatch(hero, /min = 220\.dp/);
  assert.match(layouts, /GridCells\.Adaptive\(112\.dp\)/);
  assert.match(layouts, /heroHeight = if \(compact\) 240\.dp else 200\.dp/);
  assert.match(layouts, /heightIn\(min = heroHeight\)/);
  assert.match(layouts, /top = 64\.dp/);
  assert.equal((hero.match(/Modifier\.matchParentSize\(\)/g) || []).length, 2);
  assert.equal((layouts.match(/Modifier\.matchParentSize\(\)/g) || []).length, 2);
});

test('Studio dashboard leaves space for recent channels without changing classic cards', () => {
  const dashboard = source('ui/screens/dashboard/DashboardScreen.kt');
  const cards = source('ui/components/Cards.kt');
  assert.match(dashboard, /heightIn\(min = if \(compact\) 180\.dp else 200\.dp\)/);
  assert.match(dashboard, /top = if \(compact\) 24\.dp else 40\.dp/);
  assert.match(dashboard, /compact = isStudio/);
  assert.match(cards, /compact: Boolean = false/);
  assert.match(cards, /width = if \(compact\) 180\.dp else 220\.dp/);
  assert.match(cards, /height = if \(compact\) 104\.dp else 124\.dp/);
});
