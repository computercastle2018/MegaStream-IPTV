import gzip
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import time
import urllib.request
from urllib.parse import urlsplit

root = Path('/opt/megastream')
release = sys.argv[1] if len(sys.argv) > 1 else '20261029T173816Z'
stage = root / ('staging-' + release)
backup = root / 'backups' / release
app = root / 'app'
pid = subprocess.check_output(['systemctl', 'show', 'megastream', '-p', 'MainPID', '--value'], text=True).strip()
environment = dict(item.decode().split('=', 1) for item in Path('/proc/' + pid + '/environ').read_bytes().split(b'\0') if b'=' in item)
connection = environment.get('MYSQL_CONNECTION_STRING') or environment['ConnectionStrings__DefaultConnection']
parser = shlex.shlex(connection, posix=True)
parser.whitespace = ';'
parser.whitespace_split = True
parser.commenters = ''
database = dict((key.strip().lower(), value.strip()) for key, value in (part.split('=', 1) for part in parser if part.strip()))
backup.mkdir(mode=0o700)
shutil.copytree(app, backup / 'app')
shutil.copy2(root / 'megastream.env', backup / 'megastream.env')
os.chmod(backup / 'megastream.env', 0o600)
dump = ['mysqldump', '--single-transaction', '--quick', '--routines', '--triggers', '--events',
        '--host=' + database.get('server', database.get('host', 'localhost')),
        '--port=' + database.get('port', '3306'),
        '--user=' + database.get('user', database.get('user id', database.get('uid', ''))), database['database']]
dump_env = dict(os.environ, MYSQL_PWD=database.get('password', database.get('pwd', '')))
with (backup / 'database.sql').open('wb') as target:
    subprocess.run(dump, env=dump_env, stdout=target, stderr=subprocess.DEVNULL, check=True)
assert (backup / 'database.sql').stat().st_size > 100
with (backup / 'database.sql').open('rb') as source, gzip.open(backup / 'database.sql.gz', 'wb') as target:
    shutil.copyfileobj(source, target)
(backup / 'database.sql').unlink()
print('Application and database backup complete', flush=True)

port = urlsplit(environment.get('ASPNETCORE_URLS', 'http://127.0.0.1:8080').split(';')[0]).port or 8080
health = 'http://127.0.0.1:' + str(port) + '/health/ready'

def ready():
    for _ in range(30):
        try:
            with urllib.request.urlopen(health, timeout=3) as response:
                if response.status == 200:
                    return True
        except (OSError, TimeoutError):
            pass
        time.sleep(1)
    return False

subprocess.run(['systemctl', 'stop', 'megastream'], check=True)
try:
    if '--migrate' in sys.argv:
        with (backup / 'migration.log').open('wb') as log:
            subprocess.run(['/usr/bin/dotnet', str(stage / 'MegaStream.Server.dll'), '--migrate-only'],
                           cwd=stage, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
    shutil.copytree(stage, app, dirs_exist_ok=True)
    subprocess.run(['systemctl', 'start', 'megastream'], check=True)
    if not ready():
        raise RuntimeError('Readiness check failed')
except Exception:
    subprocess.run(['systemctl', 'stop', 'megastream'], check=True)
    app.rename(root / ('failed-' + release))
    shutil.copytree(backup / 'app', app)
    subprocess.run(['systemctl', 'start', 'megastream'], check=True)
    print('Rollback restored; ready=' + str(ready()), flush=True)
    raise
print('Deployment ready; assembly SHA256=' + hashlib.sha256((app / 'MegaStream.Server.dll').read_bytes()).hexdigest(), flush=True)
print('Backup: ' + str(backup), flush=True)
