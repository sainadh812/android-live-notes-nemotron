#!/usr/bin/env python3
"""Exercise the production Room summary query against SQLite, without an SDK."""
from pathlib import Path
import re
import sqlite3

repo = Path(__file__).resolve().parent.parent
source = (repo / 'app/src/main/java/com/sainadh/livenotes/data/NotesDatabase.kt').read_text()
query = re.search(r'@Query\("""(.*?)"""\)\s*suspend fun forSummary', source, re.S).group(1)
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE transcript_chunks (id INTEGER PRIMARY KEY, dateKey TEXT, text TEXT, isFinal INTEGER, createdAtEpochMs INTEGER)')

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
print('Transcript window checks passed: backlog, partial replacement, timestamp ties, day isolation.')
