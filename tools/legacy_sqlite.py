"""Minimal ctypes SQLite test adapter; uses an explicitly selected native SQLite library."""
import ctypes as c
import os

class DatabaseError(RuntimeError): pass
lib=c.CDLL(os.environ['SMS_BRIDGE_SQLITE_LIBRARY'])
def api(name,restype,args):
    f=getattr(lib,name);f.restype=restype;f.argtypes=args;return f
open_db=api('sqlite3_open',c.c_int,[c.c_char_p,c.POINTER(c.c_void_p)])
close_db=api('sqlite3_close',c.c_int,[c.c_void_p])
prepare=api('sqlite3_prepare_v2',c.c_int,[c.c_void_p,c.c_char_p,c.c_int,c.POINTER(c.c_void_p),c.POINTER(c.c_char_p)])
bind_text=api('sqlite3_bind_text',c.c_int,[c.c_void_p,c.c_int,c.c_char_p,c.c_int,c.c_void_p])
bind_int=api('sqlite3_bind_int64',c.c_int,[c.c_void_p,c.c_int,c.c_longlong])
bind_null=api('sqlite3_bind_null',c.c_int,[c.c_void_p,c.c_int])
step=api('sqlite3_step',c.c_int,[c.c_void_p]);finalize=api('sqlite3_finalize',c.c_int,[c.c_void_p])
columns=api('sqlite3_column_count',c.c_int,[c.c_void_p])
kind=api('sqlite3_column_type',c.c_int,[c.c_void_p,c.c_int])
integer=api('sqlite3_column_int64',c.c_longlong,[c.c_void_p,c.c_int])
real=api('sqlite3_column_double',c.c_double,[c.c_void_p,c.c_int])
text=api('sqlite3_column_text',c.c_char_p,[c.c_void_p,c.c_int])
last_id=api('sqlite3_last_insert_rowid',c.c_longlong,[c.c_void_p])
error=api('sqlite3_errmsg',c.c_char_p,[c.c_void_p])
version=api('sqlite3_libversion',c.c_char_p,[])
class Cursor:
    def __init__(self,rows,insert_id):self.rows=iter(rows);self.lastrowid=insert_id
    def fetchone(self):return next(self.rows,None)
class Connection:
    def __init__(self,path):
        self.db=c.c_void_p()
        if open_db(str(path).encode(),c.byref(self.db)):raise DatabaseError('sqlite3_open failed')
    def execute(self,sql,params=()):
        stmt=c.c_void_p()
        rc=prepare(self.db,sql.encode(),-1,c.byref(stmt),None)
        if rc:raise DatabaseError(error(self.db).decode())
        rows=[]
        try:
            for i,v in enumerate(params,1):
                rc=bind_null(stmt,i) if v is None else bind_int(stmt,i,v) if isinstance(v,int) else bind_text(stmt,i,str(v).encode(),-1,c.c_void_p(-1))
                if rc:raise DatabaseError(error(self.db).decode())
            while True:
                rc=step(stmt)
                if rc==101:break
                if rc!=100:raise DatabaseError(error(self.db).decode())
                row=[]
                for i in range(columns(stmt)):
                    t=kind(stmt,i)
                    row.append(None if t==5 else integer(stmt,i) if t==1 else real(stmt,i) if t==2 else text(stmt,i).decode())
                rows.append(tuple(row))
            return Cursor(rows,last_id(self.db))
        finally:finalize(stmt)
    def close(self):
        if self.db:close_db(self.db);self.db=None
    def __del__(self):self.close()
def connect(path):return Connection(path)
