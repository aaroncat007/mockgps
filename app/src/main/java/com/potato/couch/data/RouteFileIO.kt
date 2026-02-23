package com.potato.couch.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.OutputStream

object RouteFileIO {

    fun parseGpx(stream: InputStream): List<RoutePoint> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, null)
        val points = ArrayList<RoutePoint>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "trkpt") {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    points.add(RoutePoint(lat, lon))
                }
            }
            event = parser.next()
        }
        return points
    }

    fun parseKml(stream: InputStream): List<RoutePoint> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, null)
        val points = ArrayList<RoutePoint>()
        var event = parser.eventType
        var readingCoords = false
        val sb = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "coordinates") {
                        readingCoords = true
                        sb.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (readingCoords) sb.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.name == "coordinates") {
                        readingCoords = false
                        points.addAll(parseCoordinates(sb.toString()))
                    }
                }
            }
            event = parser.next()
        }
        return points
    }

    private fun parseCoordinates(text: String): List<RoutePoint> {
        val points = ArrayList<RoutePoint>()
        val tokens = text.trim().split(Regex("\\s+"))
        for (token in tokens) {
            val parts = token.split(",")
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lat != null && lon != null) {
                    points.add(RoutePoint(lat, lon))
                }
            }
        }
        return points
    }

    fun writeGpx(output: OutputStream, name: String, points: List<RoutePoint>) {
        output.writer().use { writer ->
            writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.append("<gpx version=\"1.1\" creator=\"MockGps\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            writer.append("  <trk>\n")
            writer.append("    <name>").append(escapeXml(name)).append("</name>\n")
            writer.append("    <trkseg>\n")
            for (point in points) {
                writer.append("      <trkpt lat=\"").append(point.latitude.toString())
                    .append("\" lon=\"").append(point.longitude.toString()).append("\"></trkpt>\n")
            }
            writer.append("    </trkseg>\n")
            writer.append("  </trk>\n")
            writer.append("</gpx>\n")
        }
    }

    fun writeKml(output: OutputStream, name: String, points: List<RoutePoint>) {
        output.writer().use { writer ->
            writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
            writer.append("  <Document>\n")
            writer.append("    <name>").append(escapeXml(name)).append("</name>\n")
            writer.append("    <Placemark>\n")
            writer.append("      <LineString>\n")
            writer.append("        <coordinates>\n")
            for (point in points) {
                writer.append("          ").append(point.longitude.toString())
                    .append(",").append(point.latitude.toString()).append(",0\n")
            }
            writer.append("        </coordinates>\n")
            writer.append("      </LineString>\n")
            writer.append("    </Placemark>\n")
            writer.append("  </Document>\n")
            writer.append("</kml>\n")
        }
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
