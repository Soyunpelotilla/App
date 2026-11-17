package app.leiflix

import android.app.AlertDialog
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.widget.ArrayAdapter
import android.widget.Toast
import org.xmlpull.v1.XmlPullParser
import java.io.OutputStreamWriter
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class DlnaDevice(
    val name: String,
    val location: String,
    val avTransportControlUrl: String
)

object DlnaManager {

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var proxyServer: ServerSocket? = null
    private var proxyPort: Int = 0
    @Volatile
    private var currentStreamUrl: String = ""
    @Volatile
    private var currentStreamHeaders: Map<String, String> = emptyMap()
    @Volatile
    private var activeDevice: DlnaDevice? = null

    fun showCastDialog(
        context: Context,
        streamUrl: String,
        headers: Map<String, String>,
        hasDrm: Boolean,
        title: String = "LEIFLIX Stream",
        onCastStarted: (() -> Unit)? = null
    ) {
        if (hasDrm) {
            AlertDialog.Builder(context)
                .setTitle("Aviso DRM")
                .setMessage("Este canal utiliza protección DRM (ClearKey). Muchas TVs por DLNA no pueden desencriptar DRM directamente. ¿Deseas intentar enviarlo de todos modos?")
                .setPositiveButton("Intentar") { _, _ ->
                    discoverAndShowDialog(context, streamUrl, headers, title, onCastStarted)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            discoverAndShowDialog(context, streamUrl, headers, title, onCastStarted)
        }
    }

    private fun discoverAndShowDialog(
        context: Context,
        streamUrl: String,
        headers: Map<String, String>,
        title: String,
        onCastStarted: (() -> Unit)?
    ) {
        val devices = ConcurrentHashMap<String, DlnaDevice>()
        val deviceList = mutableListOf<DlnaDevice>()
        val adapter = ArrayAdapter<String>(
            context,
            android.R.layout.simple_list_item_1,
            mutableListOf("Buscando TVs DLNA en la red local...")
        )

        val builder = AlertDialog.Builder(context)
            .setTitle("Transmitir a TV (DLNA)")
            .setAdapter(adapter) { _, which ->
                if (deviceList.isNotEmpty() && which in deviceList.indices) {
                    val selected = deviceList[which]
                    castToDevice(context, selected, streamUrl, headers, title, onCastStarted)
                }
            }
            .setNegativeButton("Cerrar", null)

        if (activeDevice != null) {
            builder.setNeutralButton("Detener TV (${activeDevice?.name})") { _, _ ->
                stopCast(context)
            }
        }

        val dialog = builder.create()
        dialog.show()

        discoverDevices(context) { foundDevice ->
            if (devices.putIfAbsent(foundDevice.avTransportControlUrl, foundDevice) == null) {
                mainHandler.post {
                    if (!dialog.isShowing) return@post
                    deviceList.add(foundDevice)
                    adapter.clear()
                    adapter.addAll(deviceList.map { "📺 ${it.name}" })
                    adapter.notifyDataSetChanged()
                }
            }
        }

        mainHandler.postDelayed({
            if (dialog.isShowing && deviceList.isEmpty()) {
                adapter.clear()
                adapter.add("No se encontraron TVs DLNA. Asegúrate de estar en el mismo Wi-Fi.")
                adapter.notifyDataSetChanged()
            }
        }, 6500)
    }

    private fun discoverDevices(context: Context, onDeviceFound: (DlnaDevice) -> Unit) {
        executor.execute {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("leiflix_dlna_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    soTimeout = 1500
                    reuseAddress = true
                }

                val targets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "ssdp:all"
                )

                val multicastAddr = InetAddress.getByName("239.255.255.250")
                for (st in targets) {
                    val msg = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 3\r\n")
                        append("ST: $st\r\n")
                        append("\r\n")
                    }
                    val data = msg.toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(data, data.size, multicastAddr, 1900)
                    socket.send(packet)
                }

                val seenLocations = mutableSetOf<String>()
                val endTime = System.currentTimeMillis() + 6000
                val buf = ByteArray(4096)

                while (System.currentTimeMillis() < endTime) {
                    try {
                        val recv = DatagramPacket(buf, buf.size)
                        socket.receive(recv)
                        val response = String(recv.data, 0, recv.length, Charsets.UTF_8)
                        val location = extractHeader(response, "LOCATION")
                        if (!location.isNullOrEmpty() && seenLocations.add(location)) {
                            executor.execute {
                                parseDeviceDescription(location)?.let(onDeviceFound)
                            }
                        }
                    } catch (_: Exception) {
                        // Timeout o paquete inválido, seguimos escuchando hasta endTime
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
                try {
                    if (lock?.isHeld == true) lock.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun extractHeader(response: String, headerName: String): String? {
        for (line in response.lines()) {
            if (line.startsWith("$headerName:", ignoreCase = true)) {
                return line.substringAfter(":").trim()
            }
        }
        return null
    }

    private fun parseDeviceDescription(locationUrl: String): DlnaDevice? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(locationUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }
            val xml = conn.inputStream.bufferedReader().use { it.readText() }

            var friendlyName: String? = null
            var avTransportControlUrl: String? = null

            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentTag = ""
            var insideService = false
            var serviceType = ""
            var controlUrl = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""
                        if (currentTag.equals("service", ignoreCase = true)) {
                            insideService = true
                            serviceType = ""
                            controlUrl = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            if (!insideService && currentTag.equals("friendlyName", ignoreCase = true) && friendlyName == null) {
                                friendlyName = text
                            } else if (insideService) {
                                if (currentTag.equals("serviceType", ignoreCase = true)) {
                                    serviceType = text
                                } else if (currentTag.equals("controlURL", ignoreCase = true)) {
                                    controlUrl = text
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name ?: ""
                        if (tag.equals("service", ignoreCase = true)) {
                            if (serviceType.contains("AVTransport", ignoreCase = true) && controlUrl.isNotEmpty()) {
                                avTransportControlUrl = resolveUrl(url, controlUrl)
                            }
                            insideService = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }

            if (avTransportControlUrl != null) {
                return DlnaDevice(
                    name = friendlyName ?: url.host,
                    location = locationUrl,
                    avTransportControlUrl = avTransportControlUrl
                )
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
        return null
    }

    private fun resolveUrl(baseUrl: URL, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val portPart = if (baseUrl.port != -1) ":${baseUrl.port}" else ""
        val baseRoot = "${baseUrl.protocol}://${baseUrl.host}$portPart"
        return if (path.startsWith("/")) {
            "$baseRoot$path"
        } else {
            val basePath = baseUrl.path.substringBeforeLast("/", "")
            "$baseRoot$basePath/$path"
        }
    }

    private fun castToDevice(
        context: Context,
        device: DlnaDevice,
        streamUrl: String,
        headers: Map<String, String>,
        title: String,
        onCastStarted: (() -> Unit)?
    ) {
        Toast.makeText(context, "Conectando con ${device.name}...", Toast.LENGTH_SHORT).show()

        executor.execute {
            try {
                // Si el stream necesita headers (Referer, Cookie, User-Agent) usamos proxy local
                // para que la TV LG pueda reproducirlo sin problemas de autenticación/CORS.
                val finalUrl = if (headers.isNotEmpty()) {
                    startOrUpdateProxy(streamUrl, headers)
                } else {
                    streamUrl
                }

                val mimeType = when {
                    streamUrl.contains(".m3u8", ignoreCase = true) ||
                        streamUrl.contains(".m3u", ignoreCase = true) ||
                        streamUrl.contains("/hls/", ignoreCase = true) -> "application/vnd.apple.mpegurl"
                    streamUrl.contains(".mpd", ignoreCase = true) ||
                        streamUrl.contains("/dash/", ignoreCase = true) -> "application/dash+xml"
                    streamUrl.endsWith(".ts", ignoreCase = true) -> "video/mp2t"
                    else -> "video/mp4"
                }

                // 1. Stop previo por limpieza
                sendSoapAction(
                    device.avTransportControlUrl,
                    "Stop",
                    "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:Stop>"
                )

                // 2. SetAVTransportURI
                val escapedUrl = xmlEscape(finalUrl)
                val escapedTitle = xmlEscape(title)
                val didlMetadata = buildDidlMetadata(escapedUrl, escapedTitle, mimeType)
                val escapedMetadata = xmlEscape(didlMetadata)

                val setUriBody = """
                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <CurrentURI>$escapedUrl</CurrentURI>
                        <CurrentURIMetaData>$escapedMetadata</CurrentURIMetaData>
                    </u:SetAVTransportURI>
                """.trimIndent()

                var ok = sendSoapAction(device.avTransportControlUrl, "SetAVTransportURI", setUriBody)
                if (!ok) {
                    // Fallback sin metadata (algunas TVs prefieren CurrentURIMetaData vacío)
                    val fallbackBody = """
                        <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                            <InstanceID>0</InstanceID>
                            <CurrentURI>$escapedUrl</CurrentURI>
                            <CurrentURIMetaData></CurrentURIMetaData>
                        </u:SetAVTransportURI>
                    """.trimIndent()
                    ok = sendSoapAction(device.avTransportControlUrl, "SetAVTransportURI", fallbackBody)
                }

                // 3. Play
                val playBody = """
                    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <Speed>1</Speed>
                    </u:Play>
                """.trimIndent()
                val playOk = sendSoapAction(device.avTransportControlUrl, "Play", playBody)

                mainHandler.post {
                    if (ok || playOk) {
                        activeDevice = device
                        Toast.makeText(context, "Reproduciendo en ${device.name}", Toast.LENGTH_LONG).show()
                        onCastStarted?.invoke()
                    } else {
                        Toast.makeText(context, "La TV rechazó el formato o la conexión", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(context, "Error DLNA: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun stopCast(context: Context) {
        val device = activeDevice ?: return
        executor.execute {
            sendSoapAction(
                device.avTransportControlUrl,
                "Stop",
                "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:Stop>"
            )
            activeDevice = null
            mainHandler.post {
                Toast.makeText(context, "Transmisión detenida en ${device.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildDidlMetadata(escapedUrl: String, escapedTitle: String, mimeType: String): String {
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="0" parentID="-1" restricted="1"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000">$escapedUrl</res></item></DIDL-Lite>"""
    }

    private fun xmlEscape(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun sendSoapAction(controlUrl: String, action: String, bodyXml: String): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val soapEnvelope = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        $bodyXml
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            val url = URL(controlUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(soapEnvelope)
                writer.flush()
            }

            val code = conn.responseCode
            return code in 200..299
        } catch (_: Exception) {
            return false
        } finally {
            conn?.disconnect()
        }
    }

    // -----------------------------------------------------------------------
    // Proxy HTTP local para reenviar streams que requieren cabeceras/cookies
    // -----------------------------------------------------------------------
    private fun startOrUpdateProxy(targetUrl: String, headers: Map<String, String>): String {
        currentStreamUrl = targetUrl
        currentStreamHeaders = headers

        val localIp = getLocalIpAddress() ?: return targetUrl

        if (proxyServer == null || proxyServer?.isClosed == true) {
            val server = ServerSocket(0)
            proxyServer = server
            proxyPort = server.localPort

            executor.execute {
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        executor.execute { handleProxyClient(client) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }

        val ext = when {
            targetUrl.contains(".m3u8", ignoreCase = true) -> "stream.m3u8"
            targetUrl.contains(".mpd", ignoreCase = true) -> "stream.mpd"
            targetUrl.endsWith(".ts", ignoreCase = true) -> "stream.ts"
            else -> "stream.mp4"
        }
        return "http://$localIp:$proxyPort/$ext"
    }

    private fun handleProxyClient(client: Socket) {
        var conn: HttpURLConnection? = null
        try {
            client.soTimeout = 15000
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val path = if (parts.size >= 2) parts[1] else "/"

            val target = if (path.startsWith("/proxy?u=")) {
                java.net.URLDecoder.decode(path.removePrefix("/proxy?u="), "UTF-8")
            } else {
                currentStreamUrl
            }

            val url = URL(target)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                currentStreamHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val contentType = conn.contentType ?: "application/octet-stream"
            val out = client.getOutputStream()

            if (target.contains(".m3u8", ignoreCase = true) || contentType.contains("mpegurl", ignoreCase = true)) {
                val playlist = conn.inputStream.bufferedReader().use { it.readText() }
                val localIp = getLocalIpAddress() ?: "127.0.0.1"
                val rewritten = playlist.lines().joinToString("\n") { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val absUrl = resolveUrl(url, trimmed)
                        val encoded = java.net.URLEncoder.encode(absUrl, "UTF-8")
                        "http://$localIp:$proxyPort/proxy?u=$encoded"
                    } else {
                        line
                    }
                }
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            } else {
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                val buf = ByteArray(16384)
                val input = conn.inputStream
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
