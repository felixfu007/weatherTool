package com.example.weathertool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.weathertool.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main screen that shows monitoring status, last weather check results,
 * and handles the permission flow required for GPS + notifications.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefHelper: PreferenceHelper
    private var manualCheckPending = false

    // --- Permission launchers ---

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            requestBackgroundLocationIfNeeded()
        } else {
            showDialog(
                R.string.permission_denied_title,
                R.string.permission_location_denied_message
            )
        }
    }

    private val backgroundLocationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showDialog(
                R.string.permission_background_denied_title,
                R.string.permission_background_denied_message
            )
        }
        // Proceed regardless; foreground-only location may still work.
        requestIgnoreBatteryOptimizationsIfNeeded()
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Proceed to location permissions regardless of outcome.
        checkAndRequestLocationPermissions()
    }

    // --- Lifecycle ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefHelper = PreferenceHelper(this)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnToggleMonitoring.setOnClickListener {
            toggleMonitoring()
        }

        binding.btnCheckNow.setOnClickListener {
            performImmediateCheck()
        }

        observeManualCheck()
        updateUI()

        // Request permissions on first launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        } else {
            checkAndRequestLocationPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- UI helpers ---

    private fun updateUI() {
        val isEnabled = prefHelper.monitoringEnabled

        binding.btnToggleMonitoring.setText(
            if (isEnabled) R.string.stop_monitoring else R.string.start_monitoring
        )
        binding.tvMonitoringStatus.setText(
            if (isEnabled) R.string.monitoring_active else R.string.monitoring_inactive
        )

        val apiKey = prefHelper.apiKey
        binding.tvApiStatus.setText(
            when {
                apiKey.isEmpty() -> R.string.api_key_not_configured
                !prefHelper.isApiKeyEncrypted -> R.string.api_key_encryption_unavailable
                else -> R.string.api_key_configured
            }
        )

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        binding.tvBatteryStatus.setText(
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                R.string.battery_optimization_exempt
            } else {
                R.string.battery_optimization_not_exempt
            }
        )

        val lastLocation = prefHelper.lastLocation
        binding.tvCurrentLocation.text = if (lastLocation.isNotEmpty()) {
            getString(R.string.current_location, lastLocation)
        } else {
            getString(R.string.location_unknown)
        }
        if (prefHelper.locationIsFallback) {
            binding.tvLocationFallbackWarning.text = getString(
                R.string.location_fallback_warning, PreferenceHelper.DEFAULT_FALLBACK_CITY
            )
            binding.tvLocationFallbackWarning.visibility = View.VISIBLE
        } else {
            binding.tvLocationFallbackWarning.visibility = View.GONE
        }

        val lastPop = prefHelper.lastPop
        binding.tvCurrentPop.text = if (lastPop >= 0) {
            getString(R.string.current_pop, lastPop)
        } else {
            getString(R.string.pop_unknown)
        }

        binding.tvThreshold.text = getString(R.string.current_threshold, prefHelper.rainThreshold)

        val intervalLabels = resources.getStringArray(R.array.interval_options)
        val intervalIndex = PreferenceHelper.INTERVAL_OPTIONS_MINUTES.indexOf(prefHelper.checkIntervalMinutes)
        val intervalLabel = if (intervalIndex >= 0) intervalLabels[intervalIndex] else "${prefHelper.checkIntervalMinutes} 分鐘"
        binding.tvInterval.text = getString(R.string.current_interval, intervalLabel)

        val lastCheckTime = prefHelper.lastCheckTime
        binding.tvLastCheckTime.text = if (lastCheckTime > 0) {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
            getString(R.string.last_check_time, fmt.format(Date(lastCheckTime)))
        } else {
            getString(R.string.no_check_yet)
        }

        if (!manualCheckPending) {
            binding.btnCheckNow.isEnabled = true
            binding.btnCheckNow.setText(R.string.check_now)
        }
    }

    private fun toggleMonitoring() {
        val newState = !prefHelper.monitoringEnabled
        prefHelper.monitoringEnabled = newState

        if (newState) {
            checkAndRequestLocationPermissions()
        } else {
            WeatherWorker.cancel(this)
        }

        updateUI()
    }

    private fun startMonitoringIfEnabled() {
        if (prefHelper.monitoringEnabled) {
            WeatherWorker.schedule(this)
        }
    }

    /**
     * Registers a single, activity-lifetime observer on the manual-check work.
     * [manualCheckPending] gates the reaction so a stale/replayed [WorkInfo] from a
     * previous run (e.g. after rotation) doesn't re-trigger the toast/UI reset.
     */
    private fun observeManualCheck() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(WeatherWorker.MANUAL_WORK_NAME)
            .observe(this) { workInfos ->
                val info = workInfos?.firstOrNull() ?: return@observe
                if (manualCheckPending && info.state.isFinished) {
                    manualCheckPending = false
                    updateUI()
                    val messageRes = if (info.state == WorkInfo.State.SUCCEEDED) {
                        R.string.check_now_success
                    } else {
                        R.string.check_now_failed
                    }
                    Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun performImmediateCheck() {
        if (prefHelper.apiKey.isBlank()) {
            Toast.makeText(this, R.string.api_key_required, Toast.LENGTH_SHORT).show()
            return
        }

        manualCheckPending = true
        binding.btnCheckNow.isEnabled = false
        binding.btnCheckNow.setText(R.string.check_now_running)

        WeatherWorker.enqueueImmediateCheck(this)
    }

    // --- Permission helpers ---

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    checkAndRequestLocationPermissions()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.permission_notification_title)
                        .setMessage(R.string.permission_notification_message)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            checkAndRequestLocationPermissions()
                        }
                        .show()
                }
                else -> notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkAndRequestLocationPermissions() {
        when {
            hasLocationPermission() -> requestBackgroundLocationIfNeeded()
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.permission_location_title)
                    .setMessage(R.string.permission_location_message)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        locationPermissionRequest.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            else -> locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED -> {
                    requestIgnoreBatteryOptimizationsIfNeeded()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.permission_background_location_title)
                        .setMessage(R.string.permission_background_location_message)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            backgroundLocationPermissionRequest.launch(
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            )
                        }
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            requestIgnoreBatteryOptimizationsIfNeeded()
                        }
                        .show()
                }
                else -> backgroundLocationPermissionRequest.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
        } else {
            requestIgnoreBatteryOptimizationsIfNeeded()
        }
    }

    /**
     * Asks the user to exempt the app from Doze/battery optimization so the periodic
     * WorkManager check still fires promptly while the app is closed. Proceeds to start
     * monitoring regardless of the outcome — this is a reliability improvement, not a
     * hard requirement.
     */
    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            startMonitoringIfEnabled()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.permission_battery_title)
            .setMessage(R.string.permission_battery_message)
            .setPositiveButton(R.string.ok) { _, _ ->
                launchIgnoreBatteryOptimizationsSettings()
                startMonitoringIfEnabled()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                startMonitoringIfEnabled()
            }
            .show()
    }

    private fun launchIgnoreBatteryOptimizationsSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: android.content.ActivityNotFoundException) {
            // Not all OEM/Android builds support this intent; nothing more we can do here.
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun showDialog(titleRes: Int, messageRes: Int) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
