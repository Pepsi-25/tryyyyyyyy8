package com.networkmonitor.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.networkmonitor.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private lateinit var appAdapter: AppUsageAdapter
    private val logEntries = mutableListOf<LogEntry>()
    private val appUsageList = mutableListOf<AppUsageInfo>()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var uptimeStart: Long = 0
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private var uptimeRunnable: Runnable? = null
    private var lastConnType = ""
    private var currentPeriod = 1
    private var sessionStart: Long = System.currentTimeMillis()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                val type = getConnectionType()
                addLog("✅ اتصل بالنت — $type", LogType.SUCCESS)
                startUptime()
                updateStatusCard()
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                addLog("❌ انقطع النت عن الموبايل!", LogType.DANGER)
                stopUptime()
                updateStatusCard()
            }
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            runOnUiThread {
                val type = getConnectionType()
                if (type != lastConnType) {
                    lastConnType = type
                    addLog("🔄 تغيّر الاتصال: $type", LogType.WARNING)
                }
                updateStatusCard()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        appAdapter = AppUsageAdapter(appUsageList)
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = appAdapter
        binding.rvApps.isNestedScrollingEnabled = false

        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = LogAdapter(logEntries)
        binding.rvLog.isNestedScrollingEnabled = false

        binding.btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.btnSession.setOnClickListener { currentPeriod = 0; selectBtn(0); loadAppUsage(0) }
        binding.btnToday.setOnClickListener   { currentPeriod = 1; selectBtn(1); loadAppUsage(1) }
        binding.btnWeek.setOnClickListener    { currentPeriod = 7; selectBtn(2); loadAppUsage(7) }
        binding.btnMonth.setOnClickListener   { currentPeriod = 30; selectBtn(3); loadAppUsage(30) }

        binding.btnRefresh.setOnClickListener {
            addLog("🔄 تحديث يدوي", LogType.INFO)
            loadAppUsage(currentPeriod)
            updateStatusCard()
        }

        registerNetworkCallback()
        addLog("🚀 بدأ تسجيل الجلسة: ${time()}", LogType.INFO)

        if (isOnline()) {
            lastConnType = getConnectionType()
            addLog("✅ متصل بـ $lastConnType", LogType.SUCCESS)
            startUptime()
        } else {
            addLog("❌ لا يوجد اتصال بالنت", LogType.DANGER)
        }

        updateStatusCard()
        checkPermissionAndLoad()
        startAutoRefresh()
    }

    private fun selectBtn(idx: Int) {
        val btns = listOf(binding.btnSession, binding.btnToday, binding.btnWeek, binding.btnMonth)
        btns.forEachIndexed { i, btn -> btn.alpha = if (i == idx) 1f else 0.45f }
    }

    private fun registerNetworkCallback() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(req, networkCallback)
    }

    private fun getConnectionType(): String {
        val network = connectivityManager.activeNetwork ?: return "مفيش نت"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "مفيش نت"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = wifiManager.connectionInfo.ssid?.replace("\"", "") ?: "WiFi"
                "واي فاي ($ssid)"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                when (tm.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_NR    -> "داتا 5G 🚀"
                    TelephonyManager.NETWORK_TYPE_LTE   -> "داتا 4G LTE"
                    TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_HSPA  -> "داتا 3G"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS  -> "داتا 2G"
                    else -> "داتا موبايل"
                }
            }
            else -> "اتصال آخر"
        }
    }

    private fun isOnline(): Boolean {
        val net = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getWifiSignal(): String {
        val net = connectivityManager.activeNetwork ?: return "--"
        val caps = connectivityManager.getNetworkCapabilities(net) ?: return "--"
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "--"
        return when {
            wifiManager.connectionInfo.rssi >= -50 -> "ممتاز 💪"
            wifiManager.connectionInfo.rssi >= -65 -> "قوي 👍"
            wifiManager.connectionInfo.rssi >= -75 -> "متوسط 📶"
            wifiManager.connectionInfo.rssi >= -85 -> "ضعيف ⚠️"
            else -> "ضعيف جداً ❌"
        }
    }

    private fun updateStatusCard() {
        val online = isOnline()
        if (online) {
            binding.tvStatus.text = "متصل بالإنترنت"
            binding.tvStatus.setTextColor(getColor(R.color.success))
            binding.ivDot.setBackgroundResource(R.drawable.dot_green)
            binding.tvConnType.text = getConnectionType()
            binding.tvSignal.text = getWifiSignal()
        } else {
            binding.tvStatus.text = "لا يوجد اتصال بالنت"
            binding.tvStatus.setTextColor(getColor(R.color.danger))
            binding.ivDot.setBackgroundResource(R.drawable.dot_red)
            binding.tvConnType.text = "مفيش اتصال"
            binding.tvSignal.text = "--"
        }
        binding.tvLastUpdate.text = "آخر تحديث: ${time()}"
    }

    private fun checkPermissionAndLoad() {
        if (hasUsagePermission()) {
            binding.layoutPermission.visibility = View.GONE
            binding.layoutAppSection.visibility = View.VISIBLE
            loadAppUsage(1)
            selectBtn(1)
        } else {
            binding.layoutPermission.visibility = View.VISIBLE
            binding.layoutAppSection.visibility = View.GONE
        }
    }

    private fun hasUsagePermission(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun loadAppUsage(days: Int) {
        currentPeriod = days
        val label = when (days) {
            0    -> "منذ فتح التطبيق"
            1    -> "اليوم"
            7    -> "آخر 7 أيام"
            30   -> "آخر 30 يوم"
            else -> "آخر $days أيام"
        }
        binding.tvAppsTitle.text = "كل التطبيقات — $label"
        binding.progressApps.visibility = View.VISIBLE
        binding.tvAppsCount.text = "جاري التحميل..."

        Thread {
            try {
                val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
                val pm = packageManager
                val end = System.currentTimeMillis()
                val start = if (days == 0) sessionStart else end - (days * 24L * 60 * 60 * 1000)

                // uid -> Pair(rx, tx)
                val uidMap = mutableMapOf<Int, Pair<Long, Long>>()

                fun addStats(type: Int, subId: String?) {
                    try {
                        val s = nsm.querySummary(type, subId, start, end)
                        val b = NetworkStats.Bucket()
                        while (s.hasNextBucket()) {
                            s.getNextBucket(b)
                            val prev = uidMap[b.uid] ?: Pair(0L, 0L)
                            uidMap[b.uid] = Pair(prev.first + b.rxBytes, prev.second + b.txBytes)
                        }
                        s.close()
                    } catch (_: Exception) {}
                }

                addStats(ConnectivityManager.TYPE_WIFI, null)
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                addStats(ConnectivityManager.TYPE_MOBILE, tm.subscriberId)

                val result = mutableListOf<AppUsageInfo>()
                val allApps = pm.getInstalledApplications(0)

                for (app in allApps) {
                    val (rx, tx) = uidMap[app.uid] ?: continue
                    if (rx + tx == 0L) continue
                    val name = pm.getApplicationLabel(app).toString()
                    val icon = try { pm.getApplicationIcon(app.packageName) } catch (_: Exception) { null }
                    val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    result.add(AppUsageInfo(name, app.packageName, rx + tx, rx, tx, icon, isSystem))
                }

                // ترتيب تنازلي حسب الاستهلاك الكلي
                result.sortByDescending { it.totalBytes }

                runOnUiThread {
                    binding.progressApps.visibility = View.GONE
                    appUsageList.clear()
                    appUsageList.addAll(result)
                    appAdapter.notifyDataSetChanged()
                    val total = result.sumOf { it.totalBytes }
                    binding.tvAppsCount.text = "${result.size} تطبيق — إجمالي: ${formatBytes(total)}"
                    if (result.isEmpty()) {
                        addLog("⚠️ مفيش بيانات للفترة دي", LogType.WARNING)
                        binding.tvAppsCount.text = "مفيش بيانات"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressApps.visibility = View.GONE
                    binding.tvAppsCount.text = "خطأ في التحميل"
                    addLog("❌ خطأ: ${e.message}", LogType.DANGER)
                }
            }
        }.start()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1048576 -> "%.1f KB".format(bytes / 1024f)
        bytes < 1073741824 -> "%.1f MB".format(bytes / 1048576f)
        else -> "%.2f GB".format(bytes / 1073741824f)
    }

    fun addLog(msg: String, type: LogType) {
        logEntries.add(0, LogEntry(time(), msg, type))
        if (logEntries.size > 200) logEntries.removeAt(logEntries.size - 1)
        binding.rvLog.adapter?.notifyDataSetChanged()
    }

    private fun startUptime() {
        uptimeStart = System.currentTimeMillis()
        uptimeRunnable?.let { uptimeHandler.removeCallbacks(it) }
        uptimeRunnable = object : Runnable {
            override fun run() {
                val e = System.currentTimeMillis() - uptimeStart
                binding.tvUptime.text = String.format("%02d:%02d:%02d", e/3600000, (e%3600000)/60000, (e%60000)/1000)
                uptimeHandler.postDelayed(this, 1000)
            }
        }
        uptimeHandler.post(uptimeRunnable!!)
    }

    private fun stopUptime() {
        uptimeRunnable?.let { uptimeHandler.removeCallbacks(it) }
        binding.tvUptime.text = "00:00:00"
    }

    private fun startAutoRefresh() {
        refreshRunnable = object : Runnable {
            override fun run() {
                updateStatusCard()
                if (hasUsagePermission()) loadAppUsage(currentPeriod)
                refreshHandler.postDelayed(this, 30000)
            }
        }
        refreshHandler.postDelayed(refreshRunnable!!, 30000)
    }

    fun time() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    override fun onResume() {
        super.onResume()
        updateStatusCard()
        checkPermissionAndLoad()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        uptimeRunnable?.let { uptimeHandler.removeCallbacks(it) }
    }
}
