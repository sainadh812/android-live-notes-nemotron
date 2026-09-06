#!/usr/bin/env python3
"""Exercise the production Room summary query against SQLite, without an SDK."""
from pathlib import Path
import re
import sqlite3

repo = Path(__file__).resolve().parent.parent
source = (repo / 'app/src/main/java/com/sainadh/livenotes/data/NotesDatabase.kt').read_text()
def production_query(method):
    matches = re.findall(r'@Query\("""(.*?)"""\)\s*suspend fun (\w+)', source, re.S)
    return next(sql for sql, name in matches if name == method)

query = production_query('forSummary')
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE transcript_chunks (id INTEGER PRIMARY KEY, dateKey TEXT, text TEXT, isFinal INTEGER, createdAtEpochMs INTEGER)')
db.execute("INSERT INTO transcript_chunks VALUES (1, 'legacy', 'keep every original word', 0, 0)")
# Execute the exact migration statements; the original v1 rows must survive.
migration = source.split('val MIGRATION_1_2 =')[1].split('fun build(')[0]
statements = re.findall(r'db\.execSQL\((""".*?"""|"[^"\n]*")', migration, re.S)
for statement in statements:
    db.execute(statement[3:-3] if statement.startswith('"""') else statement[1:-1])
assert db.execute('SELECT text,summaryProcessed FROM transcript_chunks WHERE id=1').fetchone() == ('keep every original word', 0)
columns = {row[1]: row[5] for row in db.execute('PRAGMA table_info(transcript_segments)')}
assert columns['recordingId'] == 1 and columns['segmentId'] == 2
assert len(columns) == 10
assert db.execute("SELECT name FROM sqlite_master WHERE type='index' AND name='index_transcript_segments_dateKey'").fetchone()

def insert(day, text, final, timestamp):
    db.execute('INSERT INTO transcript_chunks(dateKey,text,isFinal,createdAtEpochMs) VALUES (?,?,?,?)',
               (day, text, final, timestamp))

def texts(day, since):
    return [row[2] for row in db.execute(query, {'dateKey': day, 'sinceEpochMs': since})]

insert('today', 'already summarized', True, 1)
insert('today', 'first unsummarized final', True, 2)
insert('today', 'second unsummarized final', True, 3)
for i in range(100):
    insert('today', f'partial {i}', False, 4 + i)
assert texts('today', 2) == ['first unsummarized final', 'second unsummarized final', 'partial 99']

# A final arriving in the same millisecond as the prior snapshot must survive.
insert('today', 'final at boundary', True, 103)
assert texts('today', 103) == ['final at boundary']
insert('tomorrow', 'other day', True, 200)
assert texts('today', 103) == ['final at boundary']
assert texts('tomorrow', 0) == ['other day']

# Immutable legacy rows are acknowledged once, including the timestamp boundary.
mark_legacy = re.search(r'@Query\("(UPDATE transcript_chunks.*?)"\)', source).group(1)
boundary_id = db.execute("SELECT id FROM transcript_chunks WHERE text='final at boundary'").fetchone()[0]
db.execute(mark_legacy, {'ids': boundary_id})
assert texts('today', 103) == []

pending = production_query('pendingSummary')
ack = production_query('markSummarized')
db.execute("INSERT INTO transcript_segments VALUES ('recording',0,'today','first guess','PARTIAL',1,1,1,1,0)")
# A newer revision arrives while an AI request uses revision 1.
db.execute("UPDATE transcript_segments SET text='corrected words',revision=2,updatedAtEpochMs=1 WHERE recordingId='recording'")
db.execute(ack, {'recordingId':'recording','segmentId':0,'revision':1})
assert len(db.execute(pending, {'dateKey':'today'}).fetchall()) == 1
db.execute(ack, {'recordingId':'recording','segmentId':0,'revision':2})
assert db.execute(pending, {'dateKey':'today'}).fetchall() == []

# Recovery stays eligible even when later utterances and recordings arrive.
db.execute("UPDATE transcript_segments SET status='INTERRUPTED',revision=3 WHERE recordingId='recording'")
db.execute("INSERT INTO transcript_segments VALUES ('recording',1,'today','next utterance','FINAL',0,2,2,1,0)")
db.execute("INSERT INTO transcript_segments VALUES ('another',0,'tomorrow','different day','FINAL',0,3,3,1,0)")
assert len(db.execute(pending, {'dateKey':'today'}).fetchall()) == 2
assert len(db.execute(pending, {'dateKey':'tomorrow'}).fetchall()) == 1
print('Transcript SQL checks passed: v1 migration, legacy retention/acknowledgment, backlog, partial replacement, timestamp ties, revision races, interrupted recovery, day isolation.')
