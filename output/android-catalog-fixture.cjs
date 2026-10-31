const http = require('node:http');
const fs = require('node:fs');
const groups = ['NEWS - الأخبار', 'أخبار عربية', 'BEIN SPORTS HD', 'رياضة عربية', 'Kids', 'وثائقيات', 'ISLAMIC', 'Music', 'KSA - السعودية'];
const server = http.createServer((request, response) => {
  if (request.url === '/icon.png') {
    response.setHeader('Content-Type', 'image/png');
    response.end(fs.readFileSync(require('node:path').join(__dirname, '../webos/public/icon.png')));
    return;
  }
  if (request.url !== '/live.m3u') { response.writeHead(404); response.end(); return; }
  const base = `http://10.0.2.2:${server.address().port}`;
  response.setHeader('Content-Type', 'audio/x-mpegurl; charset=utf-8');
  response.end('#EXTM3U\n' + groups.map((group, index) =>
    `#EXTINF:-1 tvg-logo="${base}/icon.png" group-title="${group}",QA Channel ${index + 1}\n${base}/unavailable-${index + 1}.ts`
  ).join('\n') + '\n');
});
server.listen(0, '127.0.0.1', () => console.log(`Catalog-only QA fixture: http://10.0.2.2:${server.address().port}/live.m3u`));
