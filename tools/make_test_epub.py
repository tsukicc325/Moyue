"""生成一个合规的 EPUB 样本，用于真机验证 EPUB 导入。

用法：
    python tools/make_test_epub.py --out testdata --chapters 12

产出 `sample.epub`：EPUB3（nav 目录 + 封面图），每章两段正文，
章标题在目录里是「第 N 章 目录标题」，正文小标题故意不同 ——
这样真机上看到的目录标题就能证明「用的是 EPUB 自己的目录」，而不是正文首行。
"""
import argparse
import os
import zipfile

# 1×1 透明 PNG（封面用，够验证「抽封面」这一步）
TINY_PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000a49444154789c63000100000500010d0a2db40000000049454e44ae426082"
)


def build(out_path, chapters):
    container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    items = "\n".join(
        '    <item id="c%d" href="chap%d.xhtml" media-type="application/xhtml+xml"/>' % (n, n)
        for n in range(1, chapters + 1)
    )
    spine = "\n".join('    <itemref idref="c%d"/>' % n for n in range(1, chapters + 1))
    opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>夜航船（EPUB 测试本）</dc:title>
    <dc:creator>测试作者</dc:creator>
    <dc:language>zh-CN</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
%s
  </manifest>
  <spine>
%s
  </spine>
</package>
""" % (items, spine)

    nav_items = "\n".join(
        '        <li><a href="chap%d.xhtml">第 %d 章 目录标题%d</a></li>' % (n, n, n)
        for n in range(1, chapters + 1)
    )
    nav = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
  <head><title>目录</title></head>
  <body>
    <nav epub:type="toc"><ol>
%s
    </ol></nav>
  </body>
</html>
""" % nav_items

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
        # 规范：mimetype 必须是第一个条目且不压缩
        zf.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", zipfile.ZIP_STORED)
        zf.writestr("META-INF/container.xml", container)
        zf.writestr("OEBPS/content.opf", opf)
        zf.writestr("OEBPS/nav.xhtml", nav)
        zf.writestr("OEBPS/cover.png", TINY_PNG)
        for n in range(1, chapters + 1):
            chapter = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head><title>第 %d 章</title><style>p{text-indent:2em}</style></head>
  <body>
    <h2>正文小标题%d</h2>
    <p>这是第 %d 章的第一段。&nbsp;带一个命名实体。</p>
    <p>第二段：罗盘指向东北偏北&mdash;&mdash;他记下了这句话。</p>
  </body>
</html>
""" % (n, n, n)
            zf.writestr("OEBPS/chap%d.xhtml" % n, chapter)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="testdata")
    parser.add_argument("--chapters", type=int, default=12)
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    target = os.path.join(args.out, "sample.epub")
    build(target, args.chapters)
    print("生成 sample.epub   %d 章  %.1f KB" % (args.chapters, os.path.getsize(target) / 1024))


if __name__ == "__main__":
    main()
