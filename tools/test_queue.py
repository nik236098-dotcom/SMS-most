"""Exercise the actual Store SQL against SQLite: independent recipients, FIFO per recipient."""
from pathlib import Path
import re,sqlite3
source=(Path(__file__).resolve().parents[1]/'app/src/main/java/ru/smsbridge/app/Store.java').read_text()
schema=re.search(r'db.execSQL\("(CREATE TABLE outbox [^"\n]+)"\)',source).group(1)
query=re.search(r'rawQuery\("(SELECT id,payload,part,attempts,next_try [^"\n]+)"',source).group(1)
db=sqlite3.connect(':memory:');db.execute(schema)
def add(route,when=0):
    return db.execute("INSERT INTO outbox(payload,created,route,next_try) VALUES('{}',0,?,?)",(route,when)).lastrowid
def next_id():
    row=db.execute(query,(1000,)).fetchone();return row[0] if row else None
a1=add('A',2000);b1=add('B');a2=add('A');b2=add('B')
assert next_id()==b1, 'A retry must not block B'
db.execute("UPDATE outbox SET state='sent' WHERE id=?",(b1,))
assert next_id()==b2, 'B remains ordered while A is delayed'
db.execute("UPDATE outbox SET state='sent' WHERE id=?",(b2,))
assert next_id() is None, 'A2 must wait for A1, not overtake it'
db.execute('UPDATE outbox SET next_try=0 WHERE id=?',(a1,))
assert next_id()==a1, 'A1 retries before A2'
db.execute("UPDATE outbox SET state='sent' WHERE id=?",(a1,))
assert next_id()==a2, 'A2 follows A1'
print('Passed 5 SQLite recipient queue checks')
