package com.harness.inkreader.engine

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** 解析出来的一章（标题 + 正文纯文本）。 */
data class EpubChapter(val title: String, val text: String)

/** EPUB 解析结果。 */
data class EpubBook(
    val title: String?,
    val author: String?,
    val chapters: List<EpubChapter>,
    /** 封面原图字节（没有就是 null）。 */
    val coverBytes: ByteArray?,
) {
    // data class 带 ByteArray 要手写 equals/hashCode，否则编译器警告且语义不对
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpubBook) return false
        return title == other.title &&
            author == other.author &&
            chapters == other.chapters &&
            coverBytes.contentEquals(other.coverBytes)
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (author?.hashCode() ?: 0)
        result = 31 * result + chapters.hashCode()
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * EPUB 解析器。
 *
 * 只用 JDK / Android 自带的 `java.util.zip` 与 `javax.xml`，**不引入任何第三方依赖**：
 * 这台开发机拉 Maven Central 只有 25KB/s，能不拉就不拉；而且这样测试可以在纯 JVM 上跑。
 *
 * 流程：zip 容器 → `META-INF/container.xml` 找 OPF → 解析 OPF（metadata/manifest/spine）
 * → 目录（EPUB3 的 nav 优先，退回 EPUB2 的 NCX）→ 逐个 spine 文档抽正文 → 可选封面。
 *
 * 需要 `ZipFile`（随机访问）而不是 `ZipInputStream`：OPF 在 zip 里的位置不固定，
 * 顺序流读不到「后面的」清单文件。所以导入时会先把原文件复制到私有目录再解析。
 */
object EpubParser {

    private const val CONTAINER_ENTRY = "META-INF/container.xml"
    private const val MIMETYPE_ENTRY = "mimetype"
    private const val EPUB_MIMETYPE = "application/epub+zip"

    /** 常见 HTML 命名实体。DOM 只认 5 个预定义实体 + 数字实体，其余要自己先换掉。 */
    private val NAMED_ENTITIES = mapOf(
        "nbsp" to "\u00A0", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "copy" to "©", "middot" to "·", "times" to "×", "laquo" to "«", "raquo" to "»",
        "bull" to "•", "deg" to "°", "plusmn" to "±", "amp" to "&", "lt" to "<",
        "gt" to ">", "quot" to "\"", "apos" to "'", "emsp" to "\u2003", "ensp" to "\u2002",
    )

    /** 这些元素自带换行语义（遇到就断段）。 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "li", "tr", "blockquote", "section", "article", "figure",
        "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "pre", "table", "td",
    )

    /** 这些元素里的文本要丢掉。 */
    private val SKIP_TAGS = setOf("script", "style", "head", "title", "svg", "math")

    /** 是不是 EPUB：先看 zip 里的 mimetype 条目，再退回扩展名。 */
    fun looksLikeEpub(file: File): Boolean {
        val name = file.name.lowercase()
        if (name.endsWith(".epub")) {
            // 扩展名对了也要确认不是改名来的普通 zip
            return runCatching {
                ZipFile(file).use { zip -> zip.getEntry(CONTAINER_ENTRY) != null }
            }.getOrDefault(false)
        }
        return runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(MIMETYPE_ENTRY) ?: return@use false
                val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }.trim()
                text == EPUB_MIMETYPE
            }
        }.getOrDefault(false)
    }

    fun parse(file: File): EpubBook {
        ZipFile(file).use { zip ->
            val container = zip.getEntry(CONTAINER_ENTRY)
                ?: error("不是有效的 EPUB：缺少 $CONTAINER_ENTRY")
            val opfPath = readOpfPath(readEntryText(zip, container))
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opfEntry = zip.getEntry(opfPath)
                ?: error("EPUB 里找不到清单文件：$opfPath")
            val manifest = parseOpf(readEntryText(zip, opfEntry))

            val toc = readTocTitles(zip, manifest, opfDir)

            val chapters = ArrayList<EpubChapter>(manifest.spine.size)
            manifest.spine.forEachIndexed { index, idref ->
                val item = manifest.items[idref]
                if (item == null) {
                    chapters.add(EpubChapter("第 ${index + 1} 部分", ""))
                    return@forEachIndexed
                }
                val entry = zip.getEntry(resolvePath(opfDir, item.href))
                val raw = if (entry != null) readEntryText(zip, entry) else ""
                val body = extractText(raw)
                val fallbackTitle = body.lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.take(60)
                    ?: "第 ${index + 1} 部分"
                chapters.add(EpubChapter(toc[idref] ?: fallbackTitle, body))
            }

            val cover = manifest.coverHref?.let { href ->
                zip.getEntry(resolvePath(opfDir, href))?.let { readEntryBytes(zip, it) }
            }

            return EpubBook(
                title = manifest.title,
                author = manifest.creator,
                chapters = chapters,
                coverBytes = cover,
            )
        }
    }

    // ------------------------------------------------------------------ OPF

    private data class ManifestItem(val href: String, val mediaType: String, val properties: String)

    private data class Opf(
        val title: String?,
        val creator: String?,
        val items: Map<String, ManifestItem>,
        val spine: List<String>,
        val tocId: String?,
        val coverHref: String?,
    )

    private fun readOpfPath(containerXml: String): String {
        val doc = parseXml(containerXml)
        // <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
        val rootfiles = doc.getElementsByTagName("rootfile")
        for (index in 0 until rootfiles.length) {
            val element = rootfiles.item(index) as? Element ?: continue
            val path = element.getAttribute("full-path")
            if (path.isNotBlank()) return path
        }
        error("EPUB 的 container.xml 里没有 rootfile")
    }

    private fun parseOpf(opfXml: String): Opf {
        val doc = parseXml(opfXml)
        val items = LinkedHashMap<String, ManifestItem>()
        val spines = ArrayList<String>()
        var tocId: String? = null
        var coverHref: String? = null
        var coverMetaId: String? = null

        val itemNodes = doc.getElementsByTagName("item")
        for (index in 0 until itemNodes.length) {
            val element = itemNodes.item(index) as? Element ?: continue
            val id = element.getAttribute("id")
            val href = element.getAttribute("href")
            if (id.isBlank() || href.isBlank()) continue
            items[id] = ManifestItem(
                href = href,
                mediaType = element.getAttribute("media-type"),
                properties = element.getAttribute("properties"),
            )
            if (element.getAttribute("properties").split(' ').contains("cover-image")) {
                coverHref = href
            }
        }

        val itemRefs = doc.getElementsByTagName("itemref")
        for (index in 0 until itemRefs.length) {
            val element = itemRefs.item(index) as? Element ?: continue
            val idref = element.getAttribute("idref")
            // linear="no" 的条目（封面页、广告页）不进正文
            if (element.getAttribute("linear") == "no") continue
            if (idref.isNotBlank()) spines.add(idref)
        }

        val spineNodes = doc.getElementsByTagName("spine")
        if (spineNodes.length > 0) {
            val toc = (spineNodes.item(0) as? Element)?.getAttribute("toc").orEmpty()
            if (toc.isNotBlank()) tocId = toc
        }

        // EPUB2 用 <meta name="cover" content="封面图 id"/> 指定封面
        val metaNodes = doc.getElementsByTagName("meta")
        for (index in 0 until metaNodes.length) {
            val element = metaNodes.item(index) as? Element ?: continue
            if (element.getAttribute("name") == "cover") coverMetaId = element.getAttribute("content")
        }
        if (coverHref == null && coverMetaId != null) coverHref = items[coverMetaId]?.href

        return Opf(
            title = firstTextOf(doc, "dc:title") ?: firstTextOf(doc, "title"),
            creator = firstTextOf(doc, "dc:creator") ?: firstTextOf(doc, "creator"),
            items = items,
            spine = spines,
            tocId = tocId,
            coverHref = coverHref,
        )
    }

    private fun firstTextOf(doc: org.w3c.dom.Document, tag: String): String? {
        val nodes = doc.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        val text = nodes.item(0).textContent?.trim()
        return text?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ 目录

    /**
     * idref → 章节标题。
     *
     * EPUB3 的 nav 文档（`properties="nav"`）优先；没有再退回 EPUB2 的 NCX
     * （`spine` 的 toc 属性指向的那个 manifest 项）。两条都拿不到就返回空表，
     * 由调用方用正文首行当标题。
     *
     * 两个容易错的地方（都踩过）：
     *  - 目录里的 href 是**相对目录文件自己**的位置解析的，不是相对 OPF；
     *  - 解析出来的键是 zip 内路径，而 spine 用的是 manifest id，中间必须翻译一次。
     */
    private fun readTocTitles(
        zip: ZipFile,
        opf: Opf,
        opfDir: String,
    ): Map<String, String> {
        val pathToId = HashMap<String, String>()
        for ((id, item) in opf.items) pathToId[resolvePath(opfDir, item.href)] = id

        fun translate(byPath: Map<String, String>): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for ((path, title) in byPath) {
                val id = pathToId[path] ?: continue
                result[id] = title
            }
            return result
        }

        val navItem = opf.items.values.firstOrNull {
            it.properties.split(' ').contains("nav")
        }
        if (navItem != null) {
            val navPath = resolvePath(opfDir, navItem.href)
            val xml = readEntryTextOrNull(zip, navPath)
            if (xml != null) {
                val titles = translate(parseNavTitles(xml, navPath))
                if (titles.isNotEmpty()) return titles
            }
        }
        val ncxItem = opf.tocId?.let { opf.items[it] }
            ?: opf.items.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val ncxPath = resolvePath(opfDir, ncxItem.href)
            val xml = readEntryTextOrNull(zip, ncxPath)
            if (xml != null) {
                val titles = translate(parseNcxTitles(xml, ncxPath))
                if (titles.isNotEmpty()) return titles
            }
        }
        return emptyMap()
    }

    /** EPUB3 nav：`<nav epub:type="toc"><ol><li><a href="chap1.xhtml">标题</a>` */
    private fun parseNavTitles(navXml: String, navPath: String): Map<String, String> {
        val doc = runCatching { parseXml(navXml) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val anchors = doc.getElementsByTagName("a")
        for (index in 0 until anchors.length) {
            val element = anchors.item(index) as? Element ?: continue
            val href = element.getAttribute("href")
            val title = element.textContent?.trim().orEmpty()
            if (href.isBlank() || title.isEmpty()) continue
            val target = resolvePath(navPath.substringBeforeLast('/', ""), href.substringBefore('#'))
            result[target] = title
        }
        return result
    }

    /** EPUB2 NCX：`<navPoint><navLabel><text>标题</text></navLabel><content src="chap1.xhtml"/>` */
    private fun parseNcxTitles(ncxXml: String, ncxPath: String): Map<String, String> {
        val doc = runCatching { parseXml(ncxXml) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val points = doc.getElementsByTagName("navPoint")
        for (index in 0 until points.length) {
            val point = points.item(index) as? Element ?: continue
            val label = point.getElementsByTagName("text")
            val src = point.getElementsByTagName("content")
            val title = if (label.length > 0) label.item(0).textContent?.trim().orEmpty() else ""
            val contentSrc = if (src.length > 0) {
                (src.item(0) as? Element)?.getAttribute("src").orEmpty()
            } else {
                ""
            }
            if (title.isEmpty() || contentSrc.isEmpty()) continue
            val target = resolvePath(ncxPath.substringBeforeLast('/', ""), contentSrc.substringBefore('#'))
            result[target] = title
        }
        return result
    }

    // ------------------------------------------------------------------ 正文提取

    /**
     * XHTML → 纯文本。
     *
     * 规则和 TXT 一致：一段一行、段落不缩进（缩进交给阅读器的排版设置），
     * 这样 EPUB 和 TXT 在阅读页里的表现完全相同。
     * 先去掉 DOCTYPE 并把命名实体换成字符 —— 否则 DOM 会去抓 DTD（联网）或直接报错。
     */
    internal fun extractText(xhtml: String): String {
        if (xhtml.isBlank()) return ""
        val cleaned = prepareForXml(xhtml)
        val doc = runCatching { parseXml(cleaned) }.getOrNull()
        val builder = StringBuilder()
        if (doc != null) {
            collectText(doc.documentElement, builder)
        } else {
            // 有些 EPUB 的 XHTML 并不合法（标签没闭合）。退化成「按标签切」的宽松提取。
            collectTextLenient(cleaned, builder)
        }
        return normalize(builder.toString())
    }

    private fun prepareForXml(xhtml: String): String {
        var text = xhtml
        // DOCTYPE（可能带实体定义）直接删掉，避免解析器去取外部 DTD
        text = text.replace(Regex("(?s)<!DOCTYPE[^>]*>"), "")
        // 命名实体换成字符
        text = text.replace(Regex("&([A-Za-z][A-Za-z0-9]{1,10});")) { match ->
            NAMED_ENTITIES[match.groupValues[1]] ?: match.value
        }
        return text
    }

    private fun collectText(node: Node?, builder: StringBuilder) {
        if (node == null) return
        when (node.nodeType) {
            Node.TEXT_NODE -> builder.append(node.nodeValue)
            Node.ELEMENT_NODE -> {
                val tag = node.nodeName.lowercase().substringAfter(':')
                if (tag in SKIP_TAGS) return
                val block = tag in BLOCK_TAGS
                if (block) builder.append('\n')
                var child = node.firstChild
                while (child != null) {
                    collectText(child, builder)
                    child = child.nextSibling
                }
                if (block) builder.append('\n')
            }
            else -> {
                var child = node.firstChild
                while (child != null) {
                    collectText(child, builder)
                    child = child.nextSibling
                }
            }
        }
    }

    /** DOM 解析失败时的退化路径：直接按标签切，去掉尖括号内容。 */
    private fun collectTextLenient(xhtml: String, builder: StringBuilder) {
        var index = 0
        val length = xhtml.length
        while (index < length) {
            val open = xhtml.indexOf('<', index)
            if (open < 0) {
                builder.append(xhtml, index, length)
                break
            }
            builder.append(xhtml, index, open)
            val close = xhtml.indexOf('>', open)
            if (close < 0) break
            val tag = xhtml.substring(open + 1, close).trim().lowercase()
            val tagName = tag.trimStart('/').substringBefore(' ').substringBefore('/')
            if (tagName in BLOCK_TAGS) builder.append('\n')
            if (tagName in SKIP_TAGS) {
                // 跳过 script/style 的内容
                val end = xhtml.indexOf("</$tagName", close)
                index = if (end < 0) close + 1 else xhtml.indexOf('>', end).let { if (it < 0) length else it + 1 }
                continue
            }
            index = close + 1
        }
    }

    /** 收敛空白：行内多余空格合成一个，空行丢掉，每段一行。 */
    private fun normalize(raw: String): String {
        val lines = ArrayList<String>()
        for (line in raw.split('\n')) {
            val collapsed = line.replace('\u00A0', ' ').replace(Regex("[ \\t\\u000B\\f\\r]+"), " ").trim()
            if (collapsed.isNotEmpty()) lines.add(collapsed)
        }
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ XML / zip 工具

    private fun parseXml(xml: String): org.w3c.dom.Document {
        // XML 声明必须出现在文档最开头：BOM 或前置空白都会让解析器报
        // 「不允许有匹配 [xX][mM][lL] 的处理指令目标」。真实 EPUB 里这两种情况都有，
        // 所以先剥掉再解析，而不是要求上游必须干净。
        val body = xml.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // 不联网、不取外部 DTD：这是个不联网的应用，也不能因为恶意 EPUB 去发请求
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(ByteArrayInputStream(ByteArray(0))) }
        }.parse(ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun readEntryText(zip: ZipFile, entry: ZipEntry): String =
        zip.getInputStream(entry).use { it.readBytes().decodeToString() }

    private fun readEntryTextOrNull(zip: ZipFile, path: String): String? =
        zip.getEntry(path)?.let { runCatching { readEntryText(zip, it) }.getOrNull() }

    private fun readEntryBytes(zip: ZipFile, entry: ZipEntry): ByteArray =
        zip.getInputStream(entry).use(InputStream::readBytes)

    /** 相对路径解析（OPF 里的 href 是相对 OPF 所在目录的）。 */
    internal fun resolvePath(baseDir: String, href: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        if (decoded.startsWith("/")) return decoded.trimStart('/')
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/').filter { it.isNotEmpty() })
        for (segment in decoded.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }
}
