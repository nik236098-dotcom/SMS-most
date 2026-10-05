"""Exercise production Store SQL with SQLite, including the v2 -> v3 queue upgrade."""
from pathlib import Path
import re, sqlite3
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
print(f'Passed {count} SQLite delivery queue checks')
