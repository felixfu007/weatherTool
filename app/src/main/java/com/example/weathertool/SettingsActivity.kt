package com.example.weathertool

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.weathertool.databinding.ActivitySettingsBinding

/**
 * Settings screen allowing the user to configure:
 *  - CWA API key
 *  - Rainfall probability alert threshold (0–100 %)
 *  - Enable / disable hourly monitoring
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

        loadSettings()
        setupListeners()
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

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }
}
