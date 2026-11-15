package com.mochyy.mochymusic.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class NetworkProbeReport(
    val overallOk: Boolean,
    val transportSummary: String,
    val dnsSummary: String,
    val google204Summary: String,
    val ytMusicSummary: String,
    val summaryVi: String,
)

/**
 * Kiểm tra nhanh trạng thái mạng hệ thống và dò chặng kết nối tới máy chủ phát nhạc
 * để chẩn đoán nguyên nhân lỗi luồng (mất mạng, DNS nghẽn, VPN/Proxy hoặc chặn vùng).
 */
object ConnectivityDiagnostics {

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    fun quickFailureHint(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return "Mất kết nối mạng"
        val caps = cm.getNetworkCapabilities(network) ?: return "Không có dữ liệu mạng"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "Mạng không có Internet"
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return "Mạng chưa thông (cần đăng nhập Wi-Fi)"
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "Đang qua VPN"
        }
        return null
    }

    suspend fun runMultiHopProbe(context: Context): NetworkProbeReport = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = if (network != null) cm.getNetworkCapabilities(network) else null

        val transportTags = buildList {
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("4G/5G")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            }
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val transportSummary = when {
            network == null || caps == null -> "Chưa kết nối mạng"
            !hasInternet -> "${transportTags.joinToString(" + ").ifBlank { "Mạng" }} (không có Internet)"
            !validated -> "${transportTags.joinToString(" + ").ifBlank { "Mạng" }} (chưa thông / captive)"
            else -> "${transportTags.joinToString(" + ").ifBlank { "Đã kết nối" }} · Sẵn sàng"
        }

        // Chặng 1: Phân giải DNS
        val dnsResult = probeTiming {
            val addrs = InetAddress.getAllByName("music.youtube.com")
            addrs.isNotEmpty()
        }
        val dnsSummary = if (dnsResult.first) {
            "OK (${dnsResult.second} ms)"
        } else {
            "Thất bại (${dnsResult.third ?: "DNS timeout"})"
        }

        // Chặng 2: Google Connectivity 204
        val g204 = probeHttp("https://www.google.com/generate_204")
        val google204Summary = if (g204.first in 200..399) {
            "HTTP ${g204.first} (${g204.second} ms)"
        } else {
            "Lỗi ${g204.third ?: g204.first.toString()}"
        }

        // Chặng 3: Máy chủ YouTube Music
        val ytHead = probeHttp("https://music.youtube.com/")
        val ytOk = ytHead.first in 200..399
        val ytMusicSummary = if (ytOk) {
            "HTTP ${ytHead.first} (${ytHead.second} ms)"
        } else {
            "Không phản hồi (${ytHead.third ?: ytHead.first.toString()})"
        }

        val overallOk = hasInternet && dnsResult.first && ytOk
        val summaryVi = when {
            network == null || !hasInternet -> "Thiết bị đang ngoại tuyến. Hãy bật Wi-Fi hoặc dữ liệu di động."
            !validated -> "Mạng có tín hiệu nhưng chưa truy cập được Internet (hãy kiểm tra trang đăng nhập Wi-Fi)."
            !dnsResult.first -> "Không phân giải được tên miền music.youtube.com. Thử đổi DNS hoặc bật/tắt Wi-Fi."
            g204.first !in 200..399 -> "Internet bị chặn ở tầng mạng/VPN. Thử tắt VPN hoặc kiểm tra cài đặt Proxy."
            !ytOk -> "Internet ổn nhưng máy chủ YouTube Music đang từ chối hoặc nghẽn. Thử bật Proxy hoặc đợi vài phút."
            else -> "Tất cả 3 chặng đều thông suốt (${ytHead.second} ms tới YouTube Music)."
        }

        NetworkProbeReport(
            overallOk = overallOk,
            transportSummary = transportSummary,
            dnsSummary = dnsSummary,
            google204Summary = google204Summary,
            ytMusicSummary = ytMusicSummary,
            summaryVi = summaryVi,
        )
    }

    private fun probeTiming(block: () -> Boolean): Triple<Boolean, Long, String?> {
        val start = System.currentTimeMillis()
        return try {
            val ok = block()
            val dt = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            Triple(ok, dt, null)
        } catch (e: Exception) {
            Triple(false, System.currentTimeMillis() - start, e.javaClass.simpleName)
        }
    }

    private fun probeHttp(url: String): Triple<Int, Long, String?> {
        val start = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                val dt = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                Triple(resp.code, dt, null)
            }
        } catch (e: Exception) {
            Triple(-1, System.currentTimeMillis() - start, e.javaClass.simpleName)
        }
    }
}
