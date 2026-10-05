"""Compile official SQLite 3.22 and execute production queue/migration SQL against it.
SQLite 3.22 is the documented baseline for Android API 28 (app minSdk).
The old 0.12 migration failed before the application could read its settings.
"""
import hashlib,os,pathlib,runpy,subprocess,urllib.request,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
BUILD=ROOT/'build/legacy-sqlite';BUILD.mkdir(parents=True,exist_ok=True)
ARCHIVE=BUILD/'sqlite-amalgamation-3220000.zip'
URL='https://www.sqlite.org/2018/sqlite-amalgamation-3220000.zip'
# Published at https://www.sqlite.org/releaselog/3_22_0.html
SOURCE_SHA3='206df47ebc49cd1710ac0dd716ce5de5854826536993f4feab7a49d136b85069'
if not ARCHIVE.exists():
    with urllib.request.urlopen(URL,timeout=45) as response:ARCHIVE.write_bytes(response.read(4*1024*1024))
with zipfile.ZipFile(ARCHIVE) as archive:data=archive.read('sqlite-amalgamation-3220000/sqlite3.c')
assert hashlib.sha3_256(data).hexdigest()==SOURCE_SHA3,'Unexpected SQLite source'
source=BUILD/'sqlite3.c';source.write_bytes(data)
library=BUILD/'libsqlite322.so'
subprocess.run(['cc','-O0','-shared','-fPIC',str(source),'-lpthread','-ldl','-o',str(library)],check=True)
os.environ['SMS_BRIDGE_SQLITE_LIBRARY']=str(library)
import legacy_sqlite
assert legacy_sqlite.version()==b'3.22.0'
print('Verified official SQLite 3.22.0 source SHA3-256')
runpy.run_path(str(ROOT/'tools/test_queue.py'),run_name='__main__')
