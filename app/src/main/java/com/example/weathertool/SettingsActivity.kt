package com.example.weathertool

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.weathertool.databinding.ActivitySettingsBinding

/**
 * Settings screen allowing the user to configure:
 *  - CWA API key
 *  - Rainfall probability alert threshold (0–100 %)
 *  - Background check frequency
 *  - Enable / disable monitoring
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefHelper: PreferenceHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        prefHelper = PreferenceHelper(this)

        setupIntervalSpinner()
        loadSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // The battery optimization exemption is granted via a system settings screen,
        // so re-check it whenever the user comes back to this Activity.
        updateBatteryOptimizationStatus()
    }

    private fun updateBatteryOptimizationStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isExempt = powerManager.isIgnoringBatteryOptimizations(packageName)
        binding.btnBatteryOptimization.isEnabled = !isExempt
        binding.tvBatteryOptimizationDescription.text = if (isExempt) {
            getString(R.string.battery_optimization_already_exempt)
        } else {
            getString(R.string.battery_optimization_description)
        }
    }

    private fun setupIntervalSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.interval_options, android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerInterval.adapter = adapter
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadSettings() {
        binding.etApiKey.setText(prefHelper.apiKey)

        val threshold = prefHelper.rainThreshold
        binding.seekBarThreshold.progress = threshold
        binding.tvThresholdValue.text = getString(R.string.threshold_value, threshold)

        val intervalIndex = PreferenceHelper.INTERVAL_OPTIONS_MINUTES
            .indexOf(prefHelper.checkIntervalMinutes)
            .let { if (it >= 0) it else PreferenceHelper.INTERVAL_OPTIONS_MINUTES.indexOf(PreferenceHelper.DEFAULT_CHECK_INTERVAL_MINUTES) }
        binding.spinnerInterval.setSelection(intervalIndex)

        binding.switchMonitoring.isChecked = prefHelper.monitoringEnabled
    }

    private fun setupListeners() {
        binding.seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvThresholdValue.text = getString(R.string.threshold_value, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            prefHelper.monitoringEnabled = isChecked
            if (isChecked) {
                WeatherWorker.schedule(this)
            } else {
                WeatherWorker.cancel(this)
            }
        }

        binding.btnBatteryOptimization.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: ActivityNotFoundException) {
                // Not all OEM/Android builds support this intent; nothing more we can do here.
            }
        }

        binding.btnSave.setOnClickListener { saveSettings() }
    }

    private fun saveSettings() {
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, R.string.api_key_required, Toast.LENGTH_SHORT).show()
            return
        }

        prefHelper.apiKey = apiKey
        prefHelper.rainThreshold = binding.seekBarThreshold.progress
        prefHelper.checkIntervalMinutes =
            PreferenceHelper.INTERVAL_OPTIONS_MINUTES[binding.spinnerInterval.selectedItemPosition]

        // Re-apply the schedule so a changed interval takes effect immediately
        // instead of waiting for the current periodic run to fire.
        if (prefHelper.monitoringEnabled) {
            WeatherWorker.schedule(this)
        }

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }
}
