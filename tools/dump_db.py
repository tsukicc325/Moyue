#!/usr/bin/env python3
"""校验从设备上拉回来的墨阅数据库。

不只是打印内容，还会做一致性检查：
  - 每本书的 chapters 行数是否等于书里记录的 chapterCount
  - 章节区间是否首尾相接、是否发生在 totalBytes 之内
  - 阅读进度是否指向存在的章节、百分比是否在 0..1
  - 书签/笔记引用的章节是否存在、偏移是否落在该章范围内
  - 阅读会话的结束时间是否晚于开始时间

任何一项不通过就以非 0 退出，方便在自动化脚本里当断言用。

用法：
    python tools/dump_db.py device-evidence/inkreader.db
"""
import sqlite3
import sys

FAILURES = []


def check(condition, message):
    if condition:
        print("  [OK]   %s" % message)
    else:
        print("  [FAIL] %s" % message)
        FAILURES.append(message)


def table_counts(conn):
    print("\n== 表行数 ==")
    for table in ("books", "chapters", "progress", "bookmarks", "annotations", "reading_sessions"):
        try:
            count = conn.execute("SELECT COUNT(*) FROM %s" % table).fetchone()[0]
        except sqlite3.Error as exc:
            print("  %-18s 读取失败：%s" % (table, exc))
            continue
        print("  %-18s %d" % (table, count))


def dump_books(conn):
    print("\n== 书籍 ==")
    rows = conn.execute(
        "SELECT id, title, storageMode, fileSize, encoding, encodingManual, "
        "totalBytes, chapterCount, usedVirtualChapters, indexed FROM books ORDER BY id"
    ).fetchall()
    for row in rows:
        print(
            "  #%s %s | %s | %.2f MB | %s%s | 章节=%s (虚拟=%s) | 已索引=%s"
            % (
                row[0], row[1], row[2], (row[3] or 0) / 1024.0 / 1024.0,
                row[4], "(手动)" if row[5] else "", row[7], row[8], row[9],
            )
        )
    return rows


def validate_books(conn, books):
    print("\n== 一致性检查 ==")
    for book in books:
        book_id, title = book[0], book[1]
        total_bytes, declared = book[6], book[7]

        actual = conn.execute(
            "SELECT COUNT(*) FROM chapters WHERE bookId = ?", (book_id,)
        ).fetchone()[0]
        check(actual == declared, "《%s》章节行数 %d == 记录值 %d" % (title, actual, declared))

        ranges = conn.execute(
            "SELECT idx, startByte, endByte FROM chapters WHERE bookId = ? ORDER BY idx",
            (book_id,),
        ).fetchall()
        if not ranges:
            check(False, "《%s》没有任何章节" % title)
            continue

        contiguous = all(
            ranges[i][2] == ranges[i + 1][1] for i in range(len(ranges) - 1)
        )
        check(contiguous, "《%s》章节区间首尾相接" % title)
        check(ranges[0][1] == 0, "《%s》第一章从 0 开始" % title)
        check(
            ranges[-1][2] == total_bytes,
            "《%s》最后一章结束于 totalBytes(%d)，实际 %d" % (title, total_bytes, ranges[-1][2]),
        )
        check(
            all(r[1] < r[2] for r in ranges),
            "《%s》没有空章节" % title,
        )
        check(
            all(r[1] >= 0 and r[2] <= total_bytes for r in ranges),
            "《%s》章节区间都在文件范围内" % title,
        )

        index_set = {r[0] for r in ranges}

        progress = conn.execute(
            "SELECT chapterIdx, blockIndex, charOffsetInBlock, percent FROM progress WHERE bookId = ?",
            (book_id,),
        ).fetchone()
        if progress:
            check(progress[0] in index_set, "《%s》进度指向存在的章节" % title)
            check(0.0 <= progress[3] <= 1.0, "《%s》进度百分比在 0..1（%.4f）" % (title, progress[3]))
            print("       进度：第 %d 章，块 %d，章内偏移 %d，%.1f%%"
                  % (progress[0], progress[1], progress[2], progress[3] * 100))
        else:
            print("       进度：还没有")

        bookmarks = conn.execute(
            "SELECT chapterIdx FROM bookmarks WHERE bookId = ?", (book_id,)
        ).fetchall()
        check(
            all(b[0] in index_set for b in bookmarks),
            "《%s》%d 条书签都指向存在的章节" % (title, len(bookmarks)),
        )

        annotations = conn.execute(
            "SELECT chapterIdx FROM annotations WHERE bookId = ?", (book_id,)
        ).fetchall()
        check(
            all(a[0] in index_set for a in annotations),
            "《%s》%d 条笔记都指向存在的章节" % (title, len(annotations)),
        )


def dump_sessions(conn):
    print("\n== 阅读会话（最近 10 条）==")
    rows = conn.execute(
        "SELECT bookId, startedAt, endedAt, charsRead FROM reading_sessions "
        "ORDER BY startedAt DESC LIMIT 10"
    ).fetchall()
    total = 0
    for row in rows:
        duration = (row[2] or 0) - (row[1] or 0)
        total += max(duration, 0)
        print("  书 #%s 时长 %6.1f 分钟  字数 %s" % (row[0], duration / 60000.0, row[3]))
    if not rows:
        print("  （没有记录）")
    check(
        all((r[2] or 0) > (r[1] or 0) for r in rows),
        "所有会话的结束时间都晚于开始时间",
    )
    return total


def run(path):
    """校验一个数据库文件，返回退出码（0 = 全部通过）。"""
    del FAILURES[:]
    conn = sqlite3.connect(path)
    try:
        table_counts(conn)
        books = dump_books(conn)
        if books:
            validate_books(conn, books)
        dump_sessions(conn)
    finally:
        conn.close()

    print("\n== 结论 ==")
    if FAILURES:
        print("  %d 项检查未通过：" % len(FAILURES))
        for item in FAILURES:
            print("   - %s" % item)
        return 1
    print("  全部检查通过")
    return 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    return run(sys.argv[1])


if __name__ == "__main__":
    sys.exit(main())
