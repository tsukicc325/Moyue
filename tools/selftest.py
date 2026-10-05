#!/usr/bin/env python3
"""校验器自身的自测。

判定真机证据的脚本如果自己就是错的，会把「数据不一致」误报成通过。
所以这里造两个数据库：
  - 一个完全一致的：必须返回 0
  - 一个故意有问题的（章节之间有空隙、进度指向不存在的章节、会话结束早于开始）：必须返回 1
并检查它确实指出了那些具体问题。

用法：
    python tools/selftest.py
"""
import os
import sqlite3
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dump_db  # noqa: E402

SCHEMA = """
CREATE TABLE books (
    id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, storageMode TEXT, fileSize INTEGER,
    encoding TEXT, encodingManual INTEGER, totalBytes INTEGER, chapterCount INTEGER,
    usedVirtualChapters INTEGER, indexed INTEGER
);
CREATE TABLE chapters (
    bookId INTEGER, idx INTEGER, title TEXT, startByte INTEGER, endByte INTEGER,
    PRIMARY KEY (bookId, idx)
);
CREATE TABLE progress (
    bookId INTEGER PRIMARY KEY, chapterIdx INTEGER, blockIndex INTEGER,
    charOffsetInBlock INTEGER, percent REAL, updatedAt INTEGER
);
CREATE TABLE bookmarks (
    id INTEGER PRIMARY KEY AUTOINCREMENT, bookId INTEGER, chapterIdx INTEGER,
    blockIndex INTEGER, charOffsetInBlock INTEGER, preview TEXT, createdAt INTEGER
);
CREATE TABLE annotations (
    id INTEGER PRIMARY KEY AUTOINCREMENT, bookId INTEGER, chapterIdx INTEGER, blockIndex INTEGER,
    startOffsetInBlock INTEGER, endOffsetInBlock INTEGER, selectedText TEXT, note TEXT,
    color INTEGER, createdAt INTEGER
);
CREATE TABLE reading_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT, bookId INTEGER, startedAt INTEGER, endedAt INTEGER,
    charsRead INTEGER, dayEpoch INTEGER
);
"""


def build(path, broken=False):
    # 先在 Python 里把条件算好，不能把 if/else 写进 SQL 字符串
    chapter2_start = 1100 if broken else 1000
    chapter2_end = 1900 if broken else 2000
    chapter3_start = chapter2_end
    progress_chapter = 9 if broken else 1
    progress_percent = 1.5 if broken else 0.42
    started, ended = (30000, 20000) if broken else (20000, 80000)

    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA)
    conn.execute(
        "INSERT INTO books (id, title, storageMode, fileSize, encoding, encodingManual,"
        " totalBytes, chapterCount, usedVirtualChapters, indexed)"
        " VALUES (1, 'sample-gbk', 'copy', 4096, 'GB18030', 0, 4000, 3, 0, 1)"
    )
    conn.execute("INSERT INTO chapters VALUES (1, 0, '第1章', 0, 1000)")
    conn.execute("INSERT INTO chapters VALUES (1, 1, '第2章', ?, ?)", (chapter2_start, chapter2_end))
    conn.execute("INSERT INTO chapters VALUES (1, 2, '第3章', ?, 4000)", (chapter3_start,))
    conn.execute(
        "INSERT INTO progress VALUES (1, ?, 0, 12, ?, 1)",
        (progress_chapter, progress_percent),
    )
    conn.execute("INSERT INTO bookmarks VALUES (1, 1, 0, 0, 5, '书签', 1)")
    conn.execute(
        "INSERT INTO annotations VALUES (1, 1, 0, 0, 10, 26, '选中的一句话', '笔记', 0, 1)"
    )
    conn.execute(
        "INSERT INTO reading_sessions VALUES (1, 1, ?, ?, 1200, 20000)",
        (started, ended),
    )
    conn.commit()
    conn.close()


def main():
    workdir = tempfile.mkdtemp(prefix="moyue-selftest-")
    good = os.path.join(workdir, "good.db")
    bad = os.path.join(workdir, "bad.db")
    build(good, broken=False)
    build(bad, broken=True)

    print("### 一致的数据库（应当通过） ###")
    good_code = dump_db.run(good)
    good_failures = len(dump_db.FAILURES)

    print("\n### 故意做坏的数据库（应当失败） ###")
    bad_code = dump_db.run(bad)
    bad_failures = list(dump_db.FAILURES)

    print("\n== 自测结论 ==")
    problems = []
    if good_code != 0:
        problems.append("一致的数据库没有被判为通过")
    if bad_code == 0:
        problems.append("做坏的数据库居然通过了")

    expected_markers = ["首尾相接", "进度指向存在的章节", "进度百分比", "结束时间"]
    for marker in expected_markers:
        if not any(marker in item for item in bad_failures):
            problems.append("没有检测出问题：%s" % marker)

    print("  一致库：退出码 %s，未通过项 %d" % (good_code, good_failures))
    print("  做坏库：退出码 %s，检出 %d 项" % (bad_code, len(bad_failures)))
    for item in bad_failures:
        print("    - %s" % item)

    if problems:
        print("\n  自测失败：")
        for item in problems:
            print("    - %s" % item)
        return 1
    print("\n  校验器工作正常")
    return 0


if __name__ == "__main__":
    sys.exit(main())
