package org.geocrew.core.formats

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Small DOM helpers shared by the XML formats. Namespace prefixes are ignored. */
internal object Xml {
    fun parse(input: InputStream): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isExpandEntityReferences = false
        // Refuse DTDs and external entities (XXE) where the platform allows it.
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        return try {
            f.newDocumentBuilder().parse(input)
        } catch (e: Exception) {
            throw FormatException("not a valid XML file", e)
        }
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (c.code < 0x20 && c != '\n' && c != '\r' && c != '\t') Unit else append(c)
        }
    }
}

internal val Node.name: String get() = localName ?: nodeName.substringAfter(':')

internal fun Element.children(name: String? = null): List<Element> {
    val out = ArrayList<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n is Element && (name == null || n.name == name)) out += n
    }
    return out
}

internal fun Element.child(name: String): Element? = children(name).firstOrNull()

internal fun Element.text(name: String): String? = child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

/** All descendant elements with the given local name, in document order. */
internal fun Element.descendants(name: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(e: Element) {
        for (c in e.children()) {
            if (c.name == name) out += c
            walk(c)
        }
    }
    walk(this)
    return out
}
