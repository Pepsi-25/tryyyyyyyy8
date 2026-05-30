package com.networkmonitor.app

import android.app.AppOpsManager
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
                    addLog("🔄 تغيّر الاتصال إلى: $type", LogType.WARNING)
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

        // Apps RecyclerView
        appAdapter = AppUsageAdapter(appUsageList)
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = appAdapter
        binding.rvApps.isNestedScrollingEnabled = false

        // Log RecyclerView
        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = LogAdapter(logEntries)
        binding.rvLog.isNestedScrollingEnabled = false

        // Permission button
        binding.btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        // Period selector
        binding.btnToday.setOnClickListener { loadAppUsage(1); selectBtn(0) }
        binding.btnWeek.setOnClickListener { loadAppUsage(7); selectBtn(1) }
        binding.btnMonth.setOnClickListener { loadAppUsage(30); selectBtn(2) }

        // Refresh button
        binding.btnRefresh.setOnClickListener {
            addLog("🔄 تحديث يدوي", LogType.INFO)
            updateAll()
        }

        registerNetworkCallback()
        addLog("🚀 تم تشغيل مراقب الشبكة", LogType.INFO)
        updateAll()
        startAutoRefresh()
    }

    private fun selectBtn(idx: Int) {
        val btns = listOf(binding.btnToday, binding.btnWeek, binding.btnMonth)
        btns.forEachIndexed { i, btn ->
            btn.alpha = if (i == idx) 1f else 0.5f
        }
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
                val info = wifiManager.connectionInfo
                val ssid = info.ssid?.replace("\"", "") ?: "WiFi"
                "واي فاي ($ssid)"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                when (tm.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_NR -> "داتا 5G 🚀"
                    TelephonyManager.NETWORK_TYPE_LTE -> "داتا 4G LTE"
                    TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_HSPA -> "داتا 3G"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS -> "داتا 2G"
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
        val rssi = wifiManager.connectionInfo.rssi
        return when {
            rssi >= -50 -> "ممتاز 💪"
            rssi >= -65 -> "قوي 👍"
            rssi >= -75 -> "متوسط 📶"
            rssi >= -85 -> "ضعيف ⚠️"
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

    private fun updateAll() {
        updateStatusCard()
        checkPermissionAndLoad()
    }

    private fun checkPermissionAndLoad() {
        if (hasUsagePermission()) {
            binding.layoutPermission.visibility = View.GONE
            binding.layoutAppSection.visibility = View.VISIBLE
            loadAppUsage(1)
            selectBtn(0)
        } else {
            binding.layoutPermission.visibility = View.VISIBLE
            binding.layoutAppSection.visibility = View.GONE
        }
    }

    private fun hasUsagePermission(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun loadAppUsage(days: Int) {
        binding.tvAppsTitle.text = "التطبيقات — استهلاك النت (آخر $days ${if (days == 1) "يوم" else "أيام"})"
        binding.progressApps.visibility = View.VISIBLE

        Thread {
            try {
                val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
                val pm = packageManager
                val end = System.currentTimeMillis()
                val start = end - (days * 24L * 60 * 60 * 1000)

                val uidMap = mutableMapOf<Int, Long>()

                // WiFi
                try {
                    val s = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, start, end)
                    val b = android.app.usage.NetworkStats.Bucket()
                    while (s.hasNextBucket()) {
                        s.getNextBucket(b)
                        if (b.rxBytes + b.txBytes > 0)
                            uidMap[b.uid] = (uidMap[b.uid] ?: 0L) + b.rxBytes + b.txBytes
                    }
                    s.close()
                } catch (_: Exception) {}

                // Mobile
                try {
                    val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    val sub = tm.subscriberId
                    val s = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, sub, start, end)
                    val b = android.app.usage.NetworkStats.Bucket()
                    while (s.hasNextBucket()) {
                        s.getNextBucket(b)
                        if (b.rxBytes + b.txBytes > 0)
                            uidMap[b.uid] = (uidMap[b.uid] ?: 0L) + b.rxBytes + b.txBytes
                    }
                    s.close()
                } catch (_: Exception) {}

                val result = mutableListOf<AppUsageInfo>()
                val allApps = pm.getInstalledApplications(0)

                for (app in allApps) {
                    val bytes = uidMap[app.uid] ?: 0L
                    if (bytes > 0) {
                        val name = pm.getApplicationLabel(app).toString()
                        val icon = try { pm.getApplicationIcon(app.packageName) } catch (_: Exception) { null }
                        val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        result.add(AppUsageInfo(name, app.packageName, bytes, icon, isSystem))
                    }
                }

                result.sortByDescending { it.totalBytes }

                runOnUiThread {
                    binding.progressApps.visibility = View.GONE
                    appUsageList.clear()
                    appUsageList.addAll(result)
                    appAdapter.notifyDataSetChanged()
                    binding.tvAppsCount.text = "عدد التطبيقات: ${result.size}"
                    if (result.isEmpty()) addLog("⚠️ مفيش بيانات للفترة دي", LogType.WARNING)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressApps.visibility = View.GONE
                    addLog("❌ خطأ: ${e.message}", LogType.DANGER)
                }
            }
        }.start()
    }

    private fun addLog(msg: String, type: LogType) {
        logEntries.add(0, LogEntry(time(), msg, type))
        if (logEntries.size > 100) logEntries.removeAt(logEntries.size - 1)
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
                refreshHandler.postDelayed(this, 10000)
            }
        }
        refreshHandler.postDelayed(refreshRunnable!!, 10000)
    }

    private fun time() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    override fun onResume() {
        super.onResume()
        updateAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        uptimeRunnable?.let { uptimeHandler.removeCallbacks(it) }
    }
}
