#!/usr/bin/env python3
"""生成真机验证用的测试小说。

覆盖几类最容易出问题的输入：
  - GBK 中文（含一行 ASCII 标记，方便用 adb 输入法搜索）
  - UTF-8 中文
  - UTF-16LE 带 BOM（验证导入时自动转码成 UTF-8）
  - 整本没有换行的单行文件（验证超长行不会被读进内存）
  - 100MB 巨无霸（验证索引速度与内存）

用法：
    python tools/make_test_novels.py --out C:\\Harness\\InkReader\\testdata
    python tools/make_test_novels.py --out ... --huge-mb 100
"""
import argparse
import os
import random

PARAGRAPHS = [
    "夜色像一匹浸了水的绸子，沉沉地压在江面上。船家收了帆，只留一支橹在水里慢慢划着。",
    "沈砚坐在船头，膝上摊着一本已经翻烂了的书。书页边缘卷起，像被水汽泡软又晒干的叶子。",
    "他想起父亲说过的话：一个人若能在看不清的时候还愿意看，那这个人这辈子就丢不了自己。",
    "江风忽然紧了。船身轻轻一斜，橹声停了。前方水面上浮着一团黑，正随着水流朝这边漂过来。",
    "那团黑靠近了，原来是条翻了的船，船底朝天，缆绳还挂着一截断桅，桅上搭着个人。",
    "水很冷，冷得像有人拿一把钝刀，从他的脚踝一路往上刮。他咬着牙游过去。",
    "那人突然睁开眼。沈砚后来想过很多次，如果那时他知道这一眼会带来什么，他还会不会伸手。",
    "等他能说话了，他问的第一句话不是谢谢，而是：书还在吗。",
]
MARKER = "MARKER-ALPHA 这一行用来验证搜索功能，纯 ASCII 便于 adb 输入。"


def chapter_text(index: int, paragraphs: int) -> str:
    lines = ["第%d章 第%d节的风雪" % (index, index)]
    for i in range(paragraphs):
        if i == 3:
            lines.append("　　" + MARKER)
        lines.append("　　" + random.choice(PARAGRAPHS))
    return "\n".join(lines) + "\n"


def write_chaptered(path: str, encoding: str, chapters: int, paragraphs: int) -> int:
    # 注意：Python 的 'utf-16' 编解码器**自己会写 BOM**，这里绝不能再手写一个，
    # 否则文件头会变成 ff fe ff fe，解出来正文开头会多一个 U+FEFF。
    with open(path, "w", encoding=encoding, newline="") as handle:
        for index in range(1, chapters + 1):
            handle.write(chapter_text(index, paragraphs))
    return os.path.getsize(path)


def write_single_line(path: str, encoding: str, target_mb: int) -> int:
    chunk = "".join(PARAGRAPHS) * 4
    target = target_mb * 1024 * 1024
    written = 0
    with open(path, "w", encoding=encoding, newline="") as handle:
        while written < target:
            handle.write(chunk)
            written += len(chunk.encode(encoding))
    return os.path.getsize(path)


def write_volume_collection(path: str, encoding: str, volumes: int, chapters_per_volume: int) -> int:
    """合集类样本，专门用来回归「章节丢失」这个 bug。

    三个特征同时具备，缺一个都测不出问题：
      1. 标题行前面是两个**全角空格** U+3000
      2. 每一卷都从「第1章」**重新编号**（任何「序号必须递增」的过滤都会丢掉后面的卷）
      3. 卷标题（第N卷）也占一行
    """
    with open(path, "w", encoding=encoding, newline="") as handle:
        for volume in range(1, volumes + 1):
            handle.write("\u3000\u3000第%d卷 风起\n" % volume)
            for chapter in range(1, chapters_per_volume + 1):
                handle.write("\u3000\u3000第%d章 第%d节\n" % (chapter, chapter))
                for index in range(4):
                    handle.write("\u3000\u3000" + random.choice(PARAGRAPHS) + "\n")
    size = os.path.getsize(path)
    expected = volumes * (chapters_per_volume + 1)
    print(
        "生成 %-22s %8.1f MB  (GBK, %d 卷 × %d 章，全角缩进 + 每卷重新编号，期望 %d 章)"
        % (os.path.basename(path), size / 1024.0 / 1024.0, volumes, chapters_per_volume, expected)
    )
    return size


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="输出目录")
    parser.add_argument("--huge-mb", type=int, default=0, help="再生成一个这么大的 GBK 文件（0 表示不生成）")
    args = parser.parse_args()

    random.seed(20260101)
    os.makedirs(args.out, exist_ok=True)

    jobs = [
        ("sample-gbk.txt", "gbk", 30, 12),
        ("sample-utf8.txt", "utf-8", 10, 12),
        ("sample-utf16.txt", "utf-16", 5, 8),
    ]
    for name, encoding, chapters, paragraphs in jobs:
        path = os.path.join(args.out, name)
        size = write_chaptered(path, encoding, chapters, paragraphs)
        print("生成 %-22s %8.1f KB  (%s, %d 章)" % (name, size / 1024.0, encoding, chapters))

    single = os.path.join(args.out, "sample-singleline.txt")
    size = write_single_line(single, "gbk", 20)
    print("生成 %-22s %8.1f MB  (GBK, 整本无换行)" % ("sample-singleline.txt", size / 1024.0 / 1024.0))

    write_volume_collection(
        os.path.join(args.out, "sample-volumes.txt"),
        "gbk",
        volumes=10,
        chapters_per_volume=300,
    )

    if args.huge_mb > 0:
        huge = os.path.join(args.out, "sample-huge.txt")
        size = write_chaptered(huge, "gbk", args.huge_mb * 22, 12)
        print(
            "生成 %-22s %8.1f MB  (GBK, %d 章)"
            % ("sample-huge.txt", size / 1024.0 / 1024.0, args.huge_mb * 22)
        )

    print("\n完成，输出目录：%s" % os.path.abspath(args.out))


if __name__ == "__main__":
    main()
