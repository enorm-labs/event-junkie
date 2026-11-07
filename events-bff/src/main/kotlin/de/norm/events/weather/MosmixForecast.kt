package de.norm.events.weather

import java.io.InputStream
import java.time.Instant
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.math.roundToInt

/** The forecast of one hour, as MOSMIX states it at the end of that hour. */
data class MosmixHour(
    /** `TTT`: the temperature two metres above ground, in degrees Celsius. */
    val temperatureCelsius: Double?,
    /** `ww`: the significant weather, as a WMO 4677 code. */
    val significantWeather: Int?,
    /** `R101`: the probability of more than 0.1 mm of precipitation in the hour, in percent. */
    val rainProbabilityPercent: Int?
)

/** One MOSMIX_L run for one station: when the DWD issued it, and its hourly steps keyed by the UTC instant that ends each hour. */
data class MosmixForecast(
    val issuedAt: Instant,
    val hours: Map<Instant, MosmixHour>
) {
    companion object {
        private const val KELVIN_OFFSET = 273.15

        /** The decompressed KML is about 340 KB. A larger entry is not a MOSMIX file, and it is not read further. */
        private const val MAX_KML_BYTES = 8L * 1024 * 1024

        private val ELEMENTS = setOf("TTT", "ww", "R101")

        /** Reads a `MOSMIX_L_LATEST_<station>.kmz`: a ZIP that holds one KML file. */
        fun fromKmz(kmz: InputStream): MosmixForecast =
            ZipInputStream(kmz).use { zip ->
                val entry = zip.nextEntry ?: throw IllegalArgumentException("The KMZ holds no file")
                require(entry.name.endsWith(".kml")) { "The KMZ holds ${entry.name}, not a KML file" }
                fromKml(BoundedInputStream(zip, MAX_KML_BYTES))
            }

        /** Reads the KML. The parser resolves no DTD and no external entity, because the file comes from outside. */
        fun fromKml(kml: InputStream): MosmixForecast {
            val factory =
                XMLInputFactory.newFactory().apply {
                    setProperty(XMLInputFactory.SUPPORT_DTD, false)
                    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                }
            val reader = factory.createXMLStreamReader(kml)
            try {
                return read(reader)
            } finally {
                reader.close()
            }
        }

        private fun read(reader: XMLStreamReader): MosmixForecast {
            var issuedAt: Instant? = null
            val steps = mutableListOf<Instant>()
            val series = mutableMapOf<String, List<String>>()
            var element: String? = null
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
                when (reader.localName) {
                    "IssueTime" -> {
                        issuedAt = Instant.parse(reader.elementText.trim())
                    }

                    "TimeStep" -> {
                        steps += Instant.parse(reader.elementText.trim())
                    }

                    "Forecast" -> {
                        element = reader.getAttributeValue(null, "elementName") ?: attribute(reader, "elementName")
                    }

                    "value" -> {
                        element?.takeIf { it in ELEMENTS }?.let { name ->
                            series[name] = reader.elementText.trim().split(Regex("\\s+"))
                        }
                    }
                }
            }
            requireNotNull(issuedAt) { "The KML has no IssueTime" }
            require(steps.isNotEmpty()) { "The KML has no TimeStep" }
            val hours =
                steps.withIndex().associate { (index, step) ->
                    step to
                        MosmixHour(
                            temperatureCelsius = series["TTT"].valueAt(index)?.minus(KELVIN_OFFSET),
                            significantWeather = series["ww"].valueAt(index)?.roundToInt(),
                            rainProbabilityPercent = series["R101"].valueAt(index)?.roundToInt()
                        )
                }
            return MosmixForecast(issuedAt, hours)
        }

        /** The attribute carries the `dwd:` namespace, so a lookup without one can miss it. */
        private fun attribute(
            reader: XMLStreamReader,
            localName: String
        ): String? = (0 until reader.attributeCount).firstOrNull { reader.getAttributeLocalName(it) == localName }?.let(reader::getAttributeValue)

        /** MOSMIX writes `-` for a value it does not forecast. */
        private fun List<String>?.valueAt(index: Int): Double? = this?.getOrNull(index)?.toDoubleOrNull()
    }
}

/** Stops reading after [limit] bytes, so a crafted archive cannot fill the heap. */
private class BoundedInputStream(
    private val delegate: InputStream,
    private val limit: Long
) : InputStream() {
    private var read = 0L

    override fun read(): Int {
        check(read < limit) { "The KML is larger than $limit bytes" }
        return delegate.read().also { if (it >= 0) read++ }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        check(read < limit) { "The KML is larger than $limit bytes" }
        return delegate.read(buffer, offset, minOf(length.toLong(), limit - read).toInt()).also { if (it > 0) read += it }
    }
}
