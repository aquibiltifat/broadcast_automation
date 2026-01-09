package com.groupweaver.ai

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.groupweaver.ai.api.ApiClient
import com.groupweaver.ai.databinding.ActivityMainBinding
import com.groupweaver.ai.service.WhatsAppAccessibilityService
import com.groupweaver.ai.service.WhatsAppAccessibilityService.ExtractionState
import com.groupweaver.ai.utils.ContactsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "GroupWeaverPrefs"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val DEFAULT_URL = "http://192.168.1.68:3002"
    }
    
    private lateinit var binding: ActivityMainBinding
    private var pulseAnimator: ObjectAnimator? = null
    
    private val requestContactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Contacts permission granted!", Toast.LENGTH_SHORT).show()
            loadContacts()
        } else {
            Toast.makeText(this, "Contacts permission is needed to match WhatsApp members", Toast.LENGTH_LONG).show()
        }
        updatePermissionStatus()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        loadSavedUrl()
        setupUI()
        setupExtractionListeners()
        observeService()
        checkContactsPermission()
    }
    
    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updatePermissionStatus()
        checkBackendConnection()
        updateExtractionUI(WhatsAppAccessibilityService.extractionState, WhatsAppAccessibilityService.currentProgress)
    }
    
    private fun loadSavedUrl() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_BACKEND_URL, DEFAULT_URL) ?: DEFAULT_URL
        ApiClient.setBaseUrl(savedUrl)
        Log.d(TAG, "Loaded saved URL: $savedUrl")
    }
    
    private fun saveUrl(url: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BACKEND_URL, url).apply()
        Log.d(TAG, "Saved URL: $url")
    }
    
    private fun setupUI() {
        // Backend URL
        binding.etBackendUrl.setText(ApiClient.getBaseUrl())
        
        binding.btnSaveUrl.setOnClickListener {
            val url = binding.etBackendUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "http://$url"
                } else {
                    url
                }
                
                ApiClient.setBaseUrl(finalUrl)
                saveUrl(finalUrl)
                binding.etBackendUrl.setText(finalUrl)
                Toast.makeText(this, "URL saved, checking connection...", Toast.LENGTH_SHORT).show()
                checkBackendConnection()
            }
        }
        
        // Accessibility Settings
        binding.btnOpenSettings.setOnClickListener {
            openAccessibilitySettings()
        }
        
        // Auto Extraction Button
        binding.btnStartExtraction.setOnClickListener {
            startAutoExtraction()
        }
        
        // Sync button
        binding.btnSync.setOnClickListener {
            syncToBackend()
        }
        
        // Clear data button
        binding.btnClear.setOnClickListener {
            WhatsAppAccessibilityService.instance?.clearExtractedData()
            updateStats()
            Toast.makeText(this, "Extracted data cleared", Toast.LENGTH_SHORT).show()
        }
        
        // Grant contacts permission button
        binding.btnGrantContacts.setOnClickListener {
            requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
        
        checkBackendConnection()
    }
    
    private fun setupExtractionListeners() {
        // Listen for state changes
        WhatsAppAccessibilityService.stateListeners.add { state, progress ->
            runOnUiThread {
                updateExtractionUI(state, progress)
            }
        }
    }
    
    private fun startAutoExtraction() {
        val service = WhatsAppAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Please enable the Accessibility Service first", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        
        // Check if WhatsApp is installed
        if (!service.isWhatsAppInstalled()) {
            AlertDialog.Builder(this)
                .setTitle("WhatsApp Not Found")
                .setMessage("WhatsApp is not installed on this device. Please install WhatsApp or WhatsApp Business to use this feature.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        // Check if backend is connected
        if (binding.tvBackendStatus.text.toString() != "Connected") {
            AlertDialog.Builder(this)
                .setTitle("Backend Not Connected")
                .setMessage("The backend server is not connected. Extraction will work but sync will fail. Continue anyway?")
                .setPositiveButton("Continue") { _, _ ->
                    service.startAutonomousExtraction()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        
        service.startAutonomousExtraction()
    }
    
    private fun updateExtractionUI(state: ExtractionState, progress: String) {
        // Update progress text
        binding.tvExtractionProgress.text = progress
        binding.tvExtractionProgress.visibility = if (progress.isNotEmpty()) View.VISIBLE else View.GONE
        
        // Update button state
        when (state) {
            ExtractionState.IDLE -> {
                binding.btnStartExtraction.isEnabled = true
                binding.btnStartExtraction.text = "🚀 Start Auto-Extraction"
                stopPulseAnimation()
            }
            ExtractionState.COMPLETE -> {
                binding.btnStartExtraction.isEnabled = true
                binding.btnStartExtraction.text = "✓ Extraction Complete"
                stopPulseAnimation()
                
                // Show success toast
                Toast.makeText(this, "Extraction complete! Data synced to backend.", Toast.LENGTH_LONG).show()
            }
            ExtractionState.ERROR -> {
                binding.btnStartExtraction.isEnabled = true
                binding.btnStartExtraction.text = "⚠️ Retry Extraction"
                stopPulseAnimation()
            }
            else -> {
                binding.btnStartExtraction.isEnabled = false
                binding.btnStartExtraction.text = "Extracting..."
                startPulseAnimation()
            }
        }
        
        // Update step indicators
        updateStepIndicators(WhatsAppAccessibilityService.extractionStep)
        
        // Update stats
        updateStats()
    }
    
    private fun updateStepIndicators(currentStep: Int) {
        val steps = listOf(
            binding.tvStep1,
            binding.tvStep2,
            binding.tvStep3,
            binding.tvStep4,
            binding.tvStep5
        )
        
        for ((index, stepView) in steps.withIndex()) {
            val stepNum = index + 1
            when {
                stepNum < currentStep -> {
                    stepView.text = "✓"
                    stepView.setTextColor(getColor(R.color.whatsapp_green))
                }
                stepNum == currentStep -> {
                    stepView.text = "◉"
                    stepView.setTextColor(getColor(R.color.purple_accent))
                }
                else -> {
                    stepView.text = "○"
                    stepView.setTextColor(getColor(android.R.color.darker_gray))
                }
            }
        }
    }
    
    private fun startPulseAnimation() {
        if (pulseAnimator?.isRunning == true) return
        
        pulseAnimator = ObjectAnimator.ofFloat(binding.btnStartExtraction, "alpha", 1f, 0.6f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
    
    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        binding.btnStartExtraction.alpha = 1f
    }
    
    private fun checkContactsPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED -> {
                loadContacts()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) -> {
                AlertDialog.Builder(this)
                    .setTitle("Contacts Permission Needed")
                    .setMessage("This app needs access to your contacts to match WhatsApp broadcast members with their saved names.")
                    .setPositiveButton("Grant Permission") { _, _ ->
                        requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> {
                requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }
    
    private fun loadContacts() {
        lifecycleScope.launch {
            try {
                val contacts = ContactsHelper.loadContacts(this@MainActivity)
                runOnUiThread {
                    binding.tvContactsLoaded.text = "${contacts.size} contacts loaded"
                    binding.tvContactsLoaded.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading contacts", e)
                runOnUiThread {
                    binding.tvContactsLoaded.text = "Failed to load contacts"
                    binding.tvContactsLoaded.visibility = View.VISIBLE
                }
            }
        }
    }
    
    private fun updatePermissionStatus() {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        
        binding.btnGrantContacts.visibility = if (hasContactsPermission) View.GONE else View.VISIBLE
        binding.tvContactsStatus.text = if (hasContactsPermission) "✓ Contacts" else "✗ Contacts"
        binding.tvContactsStatus.setTextColor(
            getColor(if (hasContactsPermission) android.R.color.holo_green_light else android.R.color.holo_red_light)
        )
    }
    
    private fun observeService() {
        WhatsAppAccessibilityService.extractionListeners.add { lists ->
            runOnUiThread {
                binding.tvListsFound.text = "${lists.size}"
                val totalMembers = lists.sumOf { it.members.size }
                binding.tvMembersFound.text = "$totalMembers"
            }
        }
    }
    
    private fun updateServiceStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        val isRunning = WhatsAppAccessibilityService.isRunning
        
        binding.tvServiceStatus.text = when {
            isRunning -> "Running"
            isEnabled -> "Enabled (Restart app)"
            else -> "Disabled"
        }
        
        binding.tvServiceStatus.setTextColor(
            getColor(if (isRunning) android.R.color.holo_green_light else android.R.color.holo_red_light)
        )
        
        binding.cardServiceStatus.visibility = View.VISIBLE
        
        updateStats()
    }
    
    private fun updateStats() {
        val lists = WhatsAppAccessibilityService.extractedLists
        binding.tvListsFound.text = "${lists.size}"
        binding.tvMembersFound.text = "${lists.sumOf { it.members.size }}"
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${WhatsAppAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        
        return !TextUtils.isEmpty(enabledServices) && enabledServices.contains(serviceName)
    }
    
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(
            this,
            "Find 'Group Weaver AI' and enable it",
            Toast.LENGTH_LONG
        ).show()
    }
    
    private fun checkBackendConnection() {
        val url = ApiClient.getBaseUrl()
        Log.d(TAG, "Checking connection to: $url")
        
        binding.tvBackendStatus.text = "Checking..."
        binding.tvBackendStatus.setTextColor(getColor(android.R.color.darker_gray))
        
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.getApiService().healthCheck()
                }
                
                Log.d(TAG, "Health check response: ${response.code()}")
                
                runOnUiThread {
                    if (response.isSuccessful) {
                        binding.tvBackendStatus.text = "Connected"
                        binding.tvBackendStatus.setTextColor(getColor(android.R.color.holo_green_light))
                    } else {
                        binding.tvBackendStatus.text = "Error: ${response.code()}"
                        binding.tvBackendStatus.setTextColor(getColor(android.R.color.holo_red_light))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed to $url", e)
                runOnUiThread {
                    binding.tvBackendStatus.text = "Offline: ${e.message?.take(30) ?: "Unknown error"}"
                    binding.tvBackendStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
    }
    
    private fun syncToBackend() {
        val service = WhatsAppAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Service not running", Toast.LENGTH_SHORT).show()
            return
        }
        
        val lists = WhatsAppAccessibilityService.extractedLists
        if (lists.isEmpty()) {
            Toast.makeText(this, "No data to sync. Start extraction first.", Toast.LENGTH_LONG).show()
            return
        }
        
        binding.btnSync.isEnabled = false
        binding.btnSync.text = "Syncing..."
        
        service.syncToBackend()
        
        binding.btnSync.postDelayed({
            binding.btnSync.isEnabled = true
            binding.btnSync.text = getString(R.string.btn_sync_now)
            Toast.makeText(this, "Sync complete!", Toast.LENGTH_SHORT).show()
            binding.tvLastSync.text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        }, 2000)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
    }
}
