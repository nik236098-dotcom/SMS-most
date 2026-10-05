"""Exercise production Store SQL with SQLite, including the v2 -> v3 queue upgrade."""
from pathlib import Path
import re, sqlite3, os, tempfile
if os.environ.get('SMS_BRIDGE_SQLITE_LIBRARY'):
    import legacy_sqlite as sqlite3
source=(Path(__file__).resolve().parents[1]/'app/src/main/java/ru/smsbridge/app/Store.java').read_text()
def sql(prefix):
    return next(x for x in re.findall(r'"([^"\n]+)"',source) if x.startswith(prefix))
schema=sql('CREATE TABLE outbox ');wait_schema=sql('CREATE TABLE delivery_wait ')
query=sql('SELECT id,payload,part,attempts,next_try ')
db=sqlite3.connect(':memory:');db.execute(schema);db.execute(wait_schema)
count=0
def check(value,message):
    global count
    count+=1
    assert value,message
def add(route,when=0):
    return db.execute("INSERT INTO outbox(payload,created,route,next_try) VALUES('preserved',0,?,?)",(route,when)).lastrowid
def next_id(now=1000):
    row=db.execute(query,(now,now)).fetchone();return row[0] if row else None
def sent(i): db.execute("UPDATE outbox SET state='sent' WHERE id=?",(i,))
a1=add('A',2000);b1=add('B');a2=add('A');b2=add('B')
check(next_id()==b1,'Delayed A1 must not block B1');sent(b1)
check(next_id()==a2,'Delayed A1 must not block new A2 for same recipient');sent(a2)
check(next_id()==b2,'Remaining ready message must send');sent(b2)
check(next_id() is None,'Not yet due A1 must wait')
check(next_id(2000)==a1,'Failed message must remain retryable');sent(a1)
a3=add('A');b3=add('B')
db.execute("INSERT INTO delivery_wait VALUES('A',3000,'recipient')")
check(next_id()==b3,'Blocked recipient A must not block B');sent(b3)
check(next_id() is None,'Recipient backoff must apply to new messages too')
check(next_id(3000)==a3,'Recipient backoff must expire');sent(a3)
a4=add('A');b4=add('B')
db.execute("INSERT INTO delivery_wait VALUES('*',4000,'telegram')")
check(next_id(3000) is None,'429 must block all recipients')
db.execute(sql("UPDATE outbox SET next_try=0 WHERE state='pending'"))
check(next_id(3000) is None,'Retry button must not override Telegram retry_after')
check(next_id(4000)==a4,'Oldest ready message resumes at Telegram deadline');sent(a4);sent(b4)
# Migration must retain message payload, multipart progress and explicit 429 cooldown.
db.execute('DROP TABLE delivery_wait')
a5=add('A',86400000);a6=add('A',8000)
db.execute("UPDATE outbox SET error='Telegram отклонил запрос',part=1 WHERE id=?",(a5,))
db.execute("UPDATE outbox SET error='Telegram ограничил частоту отправки' WHERE id=?",(a6,))
db.execute(wait_schema)
db.execute(sql('INSERT INTO delivery_wait(route,until_time,reason) SELECT '))
db.execute(sql("UPDATE outbox SET next_try=0 WHERE state='pending'"))
check(db.execute('SELECT payload,part,next_try FROM outbox WHERE id=?',(a5,)).fetchone()==('preserved',1,0),'Upgrade must release old local delay and preserve payload/progress')
check(next_id(7000) is None,'Upgrade must preserve known Telegram cooldown')
check(next_id(8000)==a5,'After cooldown the previously stuck SMS must retry');sent(a5)
check(next_id(8000)==a6,'429 message must also retry');sent(a6)
check(next_id(9000) is None,'Delivered messages must never be resent')
# Empty migration must create a harmless zero deadline, not fail or insert NULL.
empty=sqlite3.connect(':memory:');empty.execute(schema);empty.execute(wait_schema)
empty.execute(sql('INSERT INTO delivery_wait(route,until_time,reason) SELECT '))
check(empty.execute("SELECT until_time FROM delivery_wait WHERE route='*'").fetchone()==(0,),'Empty migration must be valid on Android SQLite')
empty.close()
if os.environ.get('SMS_BRIDGE_SQLITE_LIBRARY'):
    # SQLiteOpenHelper runs upgrade and user_version update in one transaction.
    # Reproduce failure in 0.12, rollback, then recover without deleting saved data.
    with tempfile.TemporaryDirectory() as folder:
        path=str(Path(folder)/'upgrade.db');upgrade=sqlite3.connect(path)
        upgrade.execute(schema);upgrade.execute('PRAGMA user_version=2')
        upgrade.execute("INSERT INTO outbox(payload,created,route,next_try,part) VALUES('saved SMS',0,'A',86400000,1)")
        upgrade.execute('BEGIN');upgrade.execute(wait_schema)
        old="INSERT INTO delivery_wait(route,until_time,reason) SELECT '*',MAX(next_try),'telegram' FROM outbox WHERE state='pending' AND error='Telegram ограничил частоту отправки' HAVING MAX(next_try)>0"
        try:upgrade.execute(old);raise AssertionError('Old migration unexpectedly succeeded')
        except sqlite3.DatabaseError as error:
            check('GROUP BY' in str(error),'Reproduce the actual old Android migration error')
            print('Reproduced v0.12 failure:',error)
        upgrade.execute('ROLLBACK')
        check(upgrade.execute('PRAGMA user_version').fetchone()==(2,),'Failed upgrade must leave schema version 2')
        check(upgrade.execute("SELECT name FROM sqlite_master WHERE name='delivery_wait'").fetchone() is None,'Failed upgrade must roll back new table')
        upgrade.execute('BEGIN');upgrade.execute(wait_schema)
        upgrade.execute(sql('INSERT INTO delivery_wait(route,until_time,reason) SELECT '))
        upgrade.execute(sql("UPDATE outbox SET next_try=0 WHERE state='pending'"))
        upgrade.execute('PRAGMA user_version=3');upgrade.execute('COMMIT');upgrade.close()
        reopened=sqlite3.connect(path)
        check(reopened.execute('PRAGMA user_version').fetchone()==(3,),'Corrected upgrade must persist schema version 3')
        check(reopened.execute('SELECT payload,part,next_try FROM outbox').fetchone()==('saved SMS',1,0),'Recovery must preserve SMS and acknowledged parts after reopening')
        check(reopened.execute(query,(1000,1000)).fetchone()[0]==1,'Recovered queue must be readable by sender')
        reopened.close()
print(f'Passed {count} SQLite delivery queue checks')
