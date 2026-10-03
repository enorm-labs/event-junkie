package de.norm.events.common

import java.io.StringWriter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/**
 * Writes the XML documents the API renders: the sitemaps and the event feed. StAX escapes and
 * declares the namespaces. What it lets through is dropped here: every character XML 1.0 forbids,
 * such as a stray control character in a scraped title, because a reader rejects the whole document
 * for one of them.
 */
class XmlWriter private constructor(
    private val writer: XMLStreamWriter
) {
    /** An element holding only [text]. */
    fun element(
        name: String,
        text: String,
        vararg attributes: Pair<String, String>
    ) {
        writer.writeStartElement(name)
        writeAttributes(attributes)
        writer.writeCharacters(sanitize(text))
        writer.writeEndElement()
    }

    /** An element whose children [content] writes. */
    fun element(
        name: String,
        content: XmlWriter.() -> Unit
    ) {
        writer.writeStartElement(name)
        content()
        writer.writeEndElement()
    }

    /** An empty element in the [namespace] the document root bound to [prefix]. */
    fun emptyElement(
        prefix: String,
        namespace: String,
        name: String,
        vararg attributes: Pair<String, String>
    ) {
        writer.writeEmptyElement(prefix, name, namespace)
        writeAttributes(attributes)
    }

    private fun writeAttributes(attributes: Array<out Pair<String, String>>) {
        attributes.forEach { (name, value) -> writer.writeAttribute(name, sanitize(value)) }
    }

    companion object {
        /** A UTF-8 document under [root], which declares [defaultNamespace] and every prefix in [namespaces]. */
        fun document(
            root: String,
            defaultNamespace: String? = null,
            namespaces: Map<String, String> = emptyMap(),
            attributes: Map<String, String> = emptyMap(),
            content: XmlWriter.() -> Unit
        ): String {
            val out = StringWriter()
            // The JDK's own writer, whatever else the classpath brings.
            val writer = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(out)
            writer.writeStartDocument("UTF-8", "1.0")
            writer.writeStartElement(root)
            attributes.forEach { (name, value) -> writer.writeAttribute(name, value) }
            defaultNamespace?.let(writer::writeDefaultNamespace)
            namespaces.forEach { (prefix, namespace) -> writer.writeNamespace(prefix, namespace) }
            XmlWriter(writer).content()
            writer.writeEndDocument()
            writer.close()
            return out.toString()
        }

        private fun sanitize(value: String): String = if (value.all(::isAllowed)) value else value.filter(::isAllowed)

        /** XML 1.0's `Char` production. A surrogate half passes, because a pair is two of them. */
        private fun isAllowed(char: Char): Boolean = char == '\t' || char == '\n' || char == '\r' || (char >= ' ' && char != '￾' && char != '￿')
    }
}
