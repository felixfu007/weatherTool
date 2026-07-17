package com.example.weathertool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        // Start monitoring regardless; foreground-only location may still work.
        startMonitoringIfEnabled()
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
            if (apiKey.isNotEmpty()) R.string.api_key_configured else R.string.api_key_not_configured
        )

        val lastLocation = prefHelper.lastLocation
        binding.tvCurrentLocation.text = if (lastLocation.isNotEmpty()) {
            getString(R.string.current_location, lastLocation)
        } else {
            getString(R.string.location_unknown)
        }

        val lastPop = prefHelper.lastPop
        binding.tvCurrentPop.text = if (lastPop >= 0) {
            getString(R.string.current_pop, lastPop)
        } else {
            getString(R.string.pop_unknown)
        }

        binding.tvThreshold.text = getString(R.string.current_threshold, prefHelper.rainThreshold)

        val lastCheckTime = prefHelper.lastCheckTime
        binding.tvLastCheckTime.text = if (lastCheckTime > 0) {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
            getString(R.string.last_check_time, fmt.format(Date(lastCheckTime)))
        } else {
            getString(R.string.no_check_yet)
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
                    startMonitoringIfEnabled()
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
                            startMonitoringIfEnabled()
                        }
                        .show()
                }
                else -> backgroundLocationPermissionRequest.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
        } else {
            startMonitoringIfEnabled()
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
