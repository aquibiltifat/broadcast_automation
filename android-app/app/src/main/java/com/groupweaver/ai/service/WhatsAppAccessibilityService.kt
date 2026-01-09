package com.groupweaver.ai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.groupweaver.ai.api.ApiClient
import com.groupweaver.ai.models.BroadcastList
import com.groupweaver.ai.models.Contact
import com.groupweaver.ai.models.SyncRequest
import com.groupweaver.ai.utils.ContactsHelper
import kotlinx.coroutines.*
import java.util.*

/**
 * WhatsApp Accessibility Service
 * 
 * This service monitors WhatsApp UI to extract broadcast list information.
 * Supports both manual and autonomous extraction modes.
 */
class WhatsAppAccessibilityService : AccessibilityService() {
    
    // Extraction state machine
    enum class ExtractionState {
        IDLE,
        OPENING_WHATSAPP,
        NAVIGATING_TO_MENU,
        NAVIGATING_TO_BROADCASTS,
        EXTRACTING_LISTS,
        OPENING_LIST,
        EXTRACTING_MEMBERS,
        GOING_BACK,
        SYNCING,
        COMPLETE,
        ERROR
    }
    
    companion object {
        private const val TAG = "WhatsAppService"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        
        // WhatsApp UI element identifiers
        private const val BROADCAST_LIST_TITLE = "Broadcast lists"
        private const val NEW_BROADCAST = "New broadcast"
        private const val MORE_OPTIONS = "More options"
        
        // Service state
        var isRunning = false
            private set
        
        var instance: WhatsAppAccessibilityService? = null
            private set
        
        // Autonomous extraction state
        var extractionState = ExtractionState.IDLE
            private set
        
        var currentProgress = ""
            private set
        
        var extractionStep = 0
            private set
        
        var totalSteps = 5
            private set
        
        // Extracted data
        val extractedLists = mutableListOf<BroadcastList>()
        val extractionListeners = mutableListOf<(List<BroadcastList>) -> Unit>()
        val stateListeners = mutableListOf<(ExtractionState, String) -> Unit>()
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentScreen = ""
    private var isExtracting = false
    private var currentBroadcastName = ""
    
    // Autonomous extraction
    private var isAutonomousMode = false
    private var pendingListsToExtract = mutableListOf<AccessibilityNodeInfo>()
    private var currentListIndex = 0
    private var extractionJob: Job? = null
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        
        Log.d(TAG, "Accessibility Service connected")
        isRunning = true
        instance = this
        
        // Configure service with gesture capability
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                   AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            notificationTimeout = 100
        }
        
        Log.d(TAG, "Service configured for WhatsApp monitoring")
    }
    
    // ============= AUTONOMOUS EXTRACTION =============
    
    /**
     * Check if WhatsApp is installed on the device
     */
    fun isWhatsAppInstalled(): Boolean {
        val pm = applicationContext.packageManager
        return try {
            pm.getPackageInfo(WHATSAPP_PACKAGE, 0)
            true
        } catch (e: Exception) {
            try {
                pm.getPackageInfo("com.whatsapp.w4b", 0)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
    
    /**
     * Get the installed WhatsApp package name
     */
    private fun getWhatsAppPackage(): String? {
        val pm = applicationContext.packageManager
        return try {
            pm.getPackageInfo(WHATSAPP_PACKAGE, 0)
            WHATSAPP_PACKAGE
        } catch (e: Exception) {
            try {
                pm.getPackageInfo("com.whatsapp.w4b", 0)
                "com.whatsapp.w4b"
            } catch (e2: Exception) {
                null
            }
        }
    }
    
    /**
     * Start autonomous extraction - opens WhatsApp and extracts all broadcast lists
     */
    fun startAutonomousExtraction() {
        if (extractionState != ExtractionState.IDLE && extractionState != ExtractionState.COMPLETE && extractionState != ExtractionState.ERROR) {
            Log.w(TAG, "Extraction already in progress")
            return
        }
        
        // Check if WhatsApp is installed first
        val whatsappPackage = getWhatsAppPackage()
        if (whatsappPackage == null) {
            Log.e(TAG, "WhatsApp is not installed on this device")
            updateState(ExtractionState.ERROR, "WhatsApp is not installed. Please install WhatsApp first.")
            return
        }
        
        Log.d(TAG, "Found WhatsApp: $whatsappPackage")
        
        isAutonomousMode = true
        currentListIndex = 0
        pendingListsToExtract.clear()
        extractedLists.clear()
        
        updateState(ExtractionState.OPENING_WHATSAPP, "Opening WhatsApp...")
        
        // Launch WhatsApp
        extractionJob = serviceScope.launch {
            try {
                val whatsAppLaunched = openWhatsApp()
                
                if (!whatsAppLaunched) {
                    Log.e(TAG, "Could not launch WhatsApp, stopping extraction")
                    return@launch
                }
                
                delay(2500) // Wait for WhatsApp to open
                
                // Verify WhatsApp is in foreground
                withContext(Dispatchers.Main) {
                    val root = rootInActiveWindow
                    if (root == null) {
                        Log.e(TAG, "Cannot access WhatsApp window")
                        updateState(ExtractionState.ERROR, "Cannot access WhatsApp. Please enable accessibility permissions.")
                        isAutonomousMode = false
                        return@withContext
                    }
                    
                    updateState(ExtractionState.NAVIGATING_TO_MENU, "Opening menu...")
                    navigateToMenu()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting extraction", e)
                updateState(ExtractionState.ERROR, "Failed: ${e.message}")
                isAutonomousMode = false
            }
        }
    }
    
    /**
     * Stop autonomous extraction
     */
    fun stopAutonomousExtraction() {
        extractionJob?.cancel()
        isAutonomousMode = false
        updateState(ExtractionState.IDLE, "Extraction stopped")
    }
    
    private fun openWhatsApp(): Boolean {
        try {
            // Try regular WhatsApp first, then WhatsApp Business
            val pm = applicationContext.packageManager
            var intent = pm.getLaunchIntentForPackage(WHATSAPP_PACKAGE)
            var packageUsed = WHATSAPP_PACKAGE
            
            if (intent == null) {
                // Try WhatsApp Business
                intent = pm.getLaunchIntentForPackage("com.whatsapp.w4b")
                packageUsed = "com.whatsapp.w4b"
            }
            
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                applicationContext.startActivity(intent)
                Log.d(TAG, "Launched WhatsApp ($packageUsed) successfully")
                return true
            } else {
                Log.e(TAG, "WhatsApp not installed")
                updateState(ExtractionState.ERROR, "WhatsApp not installed on this device")
                isAutonomousMode = false
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching WhatsApp: ${e.message}", e)
            updateState(ExtractionState.ERROR, "Could not open WhatsApp: ${e.message}")
            isAutonomousMode = false
            return false
        }
    }
    
    private fun navigateToMenu() {
        serviceScope.launch {
            delay(1500)
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    // Find and click "More options" (three dots menu)
                    val menuButton = findNodeByContentDescription(root, MORE_OPTIONS)
                        ?: findNodeByContentDescription(root, "More Options")
                        ?: findNodeByContentDescription(root, "Overflow menu")
                    
                    if (menuButton != null) {
                        Log.d(TAG, "Found menu button, clicking...")
                        menuButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        
                        delay(1000)
                        findAndClickBroadcastLists()
                    } else {
                        Log.e(TAG, "Could not find menu button")
                        // Try tapping at common menu location
                        performTapAtPosition(getScreenWidth() - 100, 150)
                        delay(1000)
                        findAndClickBroadcastLists()
                    }
                }
            }
        }
    }
    
    private fun findAndClickBroadcastLists() {
        serviceScope.launch {
            delay(500)
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    updateState(ExtractionState.NAVIGATING_TO_BROADCASTS, "Finding broadcast lists...")
                    
                    // Find "New broadcast" option in menu
                    val broadcastOption = findNodeWithText(root, NEW_BROADCAST)
                        ?: findNodeWithText(root, "Broadcast lists")
                        ?: findNodeWithText(root, "broadcast")
                    
                    if (broadcastOption != null) {
                        Log.d(TAG, "Found broadcast option, clicking...")
                        broadcastOption.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            ?: broadcastOption.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        
                        delay(1500)
                        startListExtraction()
                    } else {
                        Log.e(TAG, "Could not find broadcast lists option")
                        updateState(ExtractionState.ERROR, "Could not find broadcast lists menu")
                    }
                }
            }
        }
    }
    
    private fun startListExtraction() {
        updateState(ExtractionState.EXTRACTING_LISTS, "Scanning broadcast lists...")
        
        serviceScope.launch {
            delay(1000)
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    // Find all broadcast list items
                    val listItems = findBroadcastListItems(root)
                    
                    if (listItems.isNotEmpty()) {
                        pendingListsToExtract.clear()
                        pendingListsToExtract.addAll(listItems)
                        currentListIndex = 0
                        
                        Log.d(TAG, "Found ${listItems.size} broadcast lists to extract")
                        updateState(ExtractionState.OPENING_LIST, "Opening list 1/${listItems.size}...")
                        
                        extractNextList()
                    } else {
                        Log.w(TAG, "No broadcast lists found")
                        updateState(ExtractionState.SYNCING, "No lists found, syncing...")
                        finishExtraction()
                    }
                }
            }
        }
    }
    
    private fun findBroadcastListItems(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val items = mutableListOf<AccessibilityNodeInfo>()
        
        // Find RecyclerView or ListView containing broadcast lists
        val listContainer = findAllNodes(root) { node ->
            node.className?.toString()?.contains("RecyclerView") == true ||
            node.className?.toString()?.contains("ListView") == true
        }.firstOrNull()
        
        if (listContainer != null) {
            // Get clickable children
            for (i in 0 until listContainer.childCount) {
                listContainer.getChild(i)?.let { child ->
                    if (child.isClickable) {
                        items.add(child)
                    }
                }
            }
        }
        
        // Fallback: find items with "recipients" text
        if (items.isEmpty()) {
            items.addAll(findAllNodes(root) { node ->
                val text = node.text?.toString() ?: ""
                text.contains("recipient", ignoreCase = true) && node.parent?.isClickable == true
            }.mapNotNull { it.parent }.distinct())
        }
        
        return items
    }
    
    private fun extractNextList() {
        if (currentListIndex >= pendingListsToExtract.size) {
            updateState(ExtractionState.SYNCING, "All lists extracted, syncing...")
            finishExtraction()
            return
        }
        
        val listNode = pendingListsToExtract[currentListIndex]
        updateState(ExtractionState.OPENING_LIST, "Opening list ${currentListIndex + 1}/${pendingListsToExtract.size}...")
        
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                // Extract list name before clicking
                val textNodes = findAllTextNodes(listNode)
                if (textNodes.isNotEmpty()) {
                    currentBroadcastName = textNodes[0].text?.toString() ?: "Broadcast List ${currentListIndex + 1}"
                }
                
                // Click to open list
                listNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            
            delay(2000) // Wait for list to open
            
            withContext(Dispatchers.Main) {
                updateState(ExtractionState.EXTRACTING_MEMBERS, "Extracting members from '$currentBroadcastName'...")
                
                // Extract members with scrolling
                extractMembersWithScroll()
            }
        }
    }
    
    private fun extractMembersWithScroll() {
        serviceScope.launch {
            val allMembers = mutableListOf<Contact>()
            var scrollAttempts = 0
            val maxScrolls = 10
            
            while (scrollAttempts < maxScrolls) {
                withContext(Dispatchers.Main) {
                    rootInActiveWindow?.let { root ->
                        val members = extractVisibleMembers(root)
                        for (member in members) {
                            if (!allMembers.any { it.name == member.name }) {
                                allMembers.add(member)
                            }
                        }
                    }
                }
                
                // Scroll down
                val scrolled = performScrollDown()
                if (!scrolled) break
                
                delay(800)
                scrollAttempts++
            }
            
            Log.d(TAG, "Extracted ${allMembers.size} members from '$currentBroadcastName'")
            
            // Save the list with members
            withContext(Dispatchers.Main) {
                if (allMembers.isNotEmpty()) {
                    updateBroadcastListMembers(currentBroadcastName, allMembers)
                }
                
                // Go back and extract next list
                goBackAndContinue()
            }
        }
    }
    
    private fun extractVisibleMembers(root: AccessibilityNodeInfo): List<Contact> {
        val members = mutableListOf<Contact>()
        
        // Build lookup maps from device contacts
        val phoneToNameMap = try {
            ContactsHelper.buildPhoneToNameMap(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build phone map: ${e.message}")
            emptyMap()
        }
        
        // Also build name-to-phone map for when WhatsApp shows names instead of numbers
        val nameToPhoneMap = try {
            buildNameToPhoneMap(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build name map: ${e.message}")
            emptyMap()
        }
        
        Log.d(TAG, "Loaded ${phoneToNameMap.size} phone entries, ${nameToPhoneMap.size} name entries")
        
        // Find contact items
        val contactNodes = findAllNodes(root) { node ->
            node.contentDescription?.toString()?.contains("Contact") == true ||
            (node.className?.toString() == "android.widget.TextView" && 
             node.text?.toString()?.length ?: 0 > 2)
        }
        
        Log.d(TAG, "Found ${contactNodes.size} potential contact nodes")
        
        for (node in contactNodes) {
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: continue
            
            // Skip UI elements
            if (text.length <= 2) continue
            if (text.contains("recipient", ignoreCase = true)) continue
            if (text.contains("Broadcast", ignoreCase = true)) continue
            if (text.contains("tap", ignoreCase = true)) continue
            if (text.contains("Add", ignoreCase = true)) continue
            if (text.contains("Create", ignoreCase = true)) continue
            if (text.contains("Edit", ignoreCase = true)) continue
            
            // Try to extract phone number from text (e.g., "+91 98765 43210")
            val extractedPhone = extractPhoneNumber(text)
            
            var contactName = text
            var phoneNumber = extractedPhone ?: ""
            
            // If we got a phone number, try to find the contact name
            if (extractedPhone != null && phoneToNameMap.isNotEmpty()) {
                val normalized = ContactsHelper.normalizePhoneNumber(extractedPhone)
                val key = normalized.takeLast(10)
                val foundName = phoneToNameMap[key]
                if (foundName != null) {
                    contactName = foundName
                    phoneNumber = extractedPhone
                }
            } 
            // If no phone number in text, try to match by name and get phone
            else if (nameToPhoneMap.isNotEmpty()) {
                val cleanName = text.trim()
                val foundPhone = nameToPhoneMap[cleanName.lowercase()]
                if (foundPhone != null) {
                    contactName = cleanName
                    phoneNumber = foundPhone
                    Log.d(TAG, "Matched by name: $cleanName -> $foundPhone")
                } else {
                    // Keep the name from WhatsApp, no phone found
                    contactName = cleanName
                }
            }
            
            // Only add if we have a meaningful name
            if (contactName.isNotBlank() && !members.any { it.name.equals(contactName, ignoreCase = true) }) {
                val contact = Contact(
                    id = UUID.randomUUID().toString(),
                    name = contactName,
                    phone = phoneNumber
                )
                members.add(contact)
                Log.d(TAG, "Extracted: ${contact.name} - ${contact.phone}")
            }
        }
        
        Log.d(TAG, "Extracted ${members.size} unique members")
        return members
    }
    
    /**
     * Build a map of contact name -> phone number
     */
    private fun buildNameToPhoneMap(context: android.content.Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val contacts = ContactsHelper.loadContacts(context)
        
        for (contact in contacts) {
            if (contact.phoneNumbers.isNotEmpty()) {
                val phone = contact.phoneNumbers.first()
                map[contact.name.lowercase()] = ContactsHelper.normalizePhoneNumber(phone)
            }
        }
        
        Log.d(TAG, "Built name-to-phone map with ${map.size} entries")
        return map
    }
    
    private fun goBackAndContinue() {
        updateState(ExtractionState.GOING_BACK, "Going back...")
        
        serviceScope.launch {
            // Perform back action
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1500)
            
            currentListIndex++
            
            withContext(Dispatchers.Main) {
                extractNextList()
            }
        }
    }
    
    private fun finishExtraction() {
        updateState(ExtractionState.SYNCING, "Finding common members...")
        
        serviceScope.launch {
            try {
                // Find and create common members list
                val commonMembers = findCommonMembers()
                if (commonMembers.isNotEmpty()) {
                    Log.d(TAG, "Found ${commonMembers.size} common members across ${extractedLists.size} lists")
                    
                    // Create auto-generated common members list
                    val commonList = BroadcastList(
                        id = UUID.randomUUID().toString(),
                        name = "⭐ Common Members (${commonMembers.size})",
                        members = commonMembers,
                        isAutoGenerated = true
                    )
                    
                    synchronized(extractedLists) {
                        // Remove any existing auto-generated common members list
                        extractedLists.removeAll { it.isAutoGenerated && it.name.contains("Common Members") }
                        extractedLists.add(commonList)
                    }
                    
                    updateState(ExtractionState.SYNCING, "Found ${commonMembers.size} common members! Syncing...")
                } else {
                    Log.d(TAG, "No common members found across lists")
                    updateState(ExtractionState.SYNCING, "No common members found. Syncing...")
                }
                
                // Sync to backend
                syncToBackend()
                delay(1000)
                
                val message = if (commonMembers.isNotEmpty()) {
                    "Complete! ${extractedLists.size} lists, ${commonMembers.size} common members"
                } else {
                    "Complete! ${extractedLists.size} lists extracted"
                }
                updateState(ExtractionState.COMPLETE, message)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in finishExtraction", e)
                updateState(ExtractionState.ERROR, "Sync failed: ${e.message}")
            } finally {
                isAutonomousMode = false
            }
        }
    }
    
    /**
     * Find members that appear in 2 or more broadcast lists
     */
    private fun findCommonMembers(): List<Contact> {
        if (extractedLists.size < 2) return emptyList()
        
        // Count occurrences of each member (by name or phone)
        val memberCounts = mutableMapOf<String, MutableList<Contact>>()
        
        for (list in extractedLists) {
            if (list.isAutoGenerated) continue // Skip auto-generated lists
            
            for (member in list.members) {
                // Use phone number as key if available, otherwise use name
                val key = if (member.phone.isNotBlank()) {
                    member.phone.takeLast(10) // Normalize to last 10 digits
                } else {
                    member.name.lowercase().trim()
                }
                
                memberCounts.getOrPut(key) { mutableListOf() }.add(member)
            }
        }
        
        // Find members that appear in 2+ lists
        val commonMembers = memberCounts.values
            .filter { it.size >= 2 }
            .map { occurrences ->
                // Return the contact with the most info (prefer one with phone number)
                occurrences.maxByOrNull { 
                    (if (it.phone.isNotBlank()) 10 else 0) + it.name.length 
                } ?: occurrences.first()
            }
            .distinctBy { it.name }
        
        Log.d(TAG, "Found ${commonMembers.size} common members from ${extractedLists.size} lists")
        return commonMembers
    }
    
    private fun updateState(state: ExtractionState, progress: String) {
        extractionState = state
        currentProgress = progress
        
        extractionStep = when (state) {
            ExtractionState.IDLE -> 0
            ExtractionState.OPENING_WHATSAPP -> 1
            ExtractionState.NAVIGATING_TO_MENU, ExtractionState.NAVIGATING_TO_BROADCASTS -> 2
            ExtractionState.EXTRACTING_LISTS, ExtractionState.OPENING_LIST, ExtractionState.EXTRACTING_MEMBERS, ExtractionState.GOING_BACK -> 3
            ExtractionState.SYNCING -> 4
            ExtractionState.COMPLETE -> 5
            ExtractionState.ERROR -> extractionStep
        }
        
        Log.d(TAG, "State: $state - $progress")
        stateListeners.forEach { it(state, progress) }
    }
    
    // ============= GESTURE HELPERS =============
    
    private fun performTapAtPosition(x: Int, y: Int): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        return dispatchGesture(gesture, null, null)
    }
    
    private fun performScrollDown(): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.7f
        val endY = screenHeight * 0.3f
        
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(startX, endY)
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        
        return dispatchGesture(gesture, null, null)
    }
    
    private fun getScreenWidth(): Int {
        return resources.displayMetrics.widthPixels
    }
    
    private fun findNodeByContentDescription(root: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString()?.contains(description, ignoreCase = true) == true) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findNodeByContentDescription(child, description)?.let { return it }
            }
        }
        
        return null
    }
    
    // ============= MANUAL EXTRACTION (existing code) =============
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val packageName = event.packageName?.toString() ?: return
        
        // Only process WhatsApp events
        if (packageName != WHATSAPP_PACKAGE) return
        
        // In autonomous mode, let the extraction flow handle events
        if (isAutonomousMode) {
            handleAutonomousEvent(event)
            return
        }
        
        // Manual mode - existing behavior
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChange(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChange(event)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleScroll(event)
            }
        }
    }
    
    private fun handleAutonomousEvent(event: AccessibilityEvent) {
        // Handle events during autonomous extraction
        when (extractionState) {
            ExtractionState.NAVIGATING_TO_MENU -> {
                // Check if menu opened
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    rootInActiveWindow?.let { root ->
                        if (findNodeWithText(root, NEW_BROADCAST) != null) {
                            findAndClickBroadcastLists()
                        }
                    }
                }
            }
            ExtractionState.EXTRACTING_MEMBERS -> {
                // Continue extracting on content change
            }
            else -> {}
        }
    }
    
    private fun handleWindowStateChange(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""
        Log.d(TAG, "Window state changed: $className")
        
        rootInActiveWindow?.let { root ->
            if (findBroadcastListsScreen(root)) {
                currentScreen = "broadcast_lists"
                Log.d(TAG, "Detected: Broadcast Lists screen")
                extractBroadcastLists(root)
            } else if (findBroadcastDetailScreen(root)) {
                currentScreen = "broadcast_detail"
                Log.d(TAG, "Detected: Broadcast Detail screen")
                extractBroadcastMembers(root)
            }
        }
    }
    
    private fun handleContentChange(event: AccessibilityEvent) {
        if (currentScreen.isNotEmpty() && !isExtracting) {
            rootInActiveWindow?.let { root ->
                when (currentScreen) {
                    "broadcast_lists" -> extractBroadcastLists(root)
                    "broadcast_detail" -> extractBroadcastMembers(root)
                }
            }
        }
    }
    
    private fun handleScroll(event: AccessibilityEvent) {
        if (currentScreen.isNotEmpty()) {
            serviceScope.launch {
                delay(500)
                withContext(Dispatchers.Main) {
                    rootInActiveWindow?.let { root ->
                        when (currentScreen) {
                            "broadcast_lists" -> extractBroadcastLists(root)
                            "broadcast_detail" -> extractBroadcastMembers(root)
                        }
                    }
                }
            }
        }
    }
    
    private fun findBroadcastListsScreen(root: AccessibilityNodeInfo): Boolean {
        return findNodeWithText(root, BROADCAST_LIST_TITLE) != null ||
               findNodeWithText(root, NEW_BROADCAST) != null
    }
    
    private fun findBroadcastDetailScreen(root: AccessibilityNodeInfo): Boolean {
        val hasRecipients = findNodeWithText(root, "recipients") != null ||
               findNodeWithText(root, "recipient") != null
        
        if (hasRecipients) {
            val headerNodes = findAllNodes(root) { node ->
                node.className?.toString() == "android.widget.TextView"
            }
            
            for (node in headerNodes) {
                val text = node.text?.toString() ?: continue
                if (text.isNotBlank() && 
                    !text.contains("recipient", ignoreCase = true) &&
                    !text.contains("tap", ignoreCase = true) &&
                    text.length > 1 && text.length < 100) {
                    currentBroadcastName = text
                    Log.d(TAG, "Detected broadcast list name: $currentBroadcastName")
                    break
                }
            }
        }
        
        return hasRecipients
    }
    
    private fun extractBroadcastLists(root: AccessibilityNodeInfo) {
        if (isExtracting) return
        isExtracting = true
        
        serviceScope.launch {
            try {
                val lists = mutableListOf<BroadcastList>()
                
                val listItems = findAllNodes(root) { node ->
                    node.className?.toString() == "android.widget.RelativeLayout" ||
                    node.className?.toString() == "android.widget.LinearLayout"
                }
                
                for (item in listItems) {
                    val textNodes = findAllTextNodes(item)
                    if (textNodes.size >= 2) {
                        val name = textNodes[0].text?.toString() ?: continue
                        val memberCount = textNodes[1].text?.toString() ?: "0"
                        
                        if (name.contains("Broadcast") || 
                            memberCount.contains("recipient")) {
                            
                            val list = BroadcastList(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                members = emptyList(),
                                isAutoGenerated = false
                            )
                            
                            if (!lists.any { it.name == name }) {
                                lists.add(list)
                            }
                        }
                    }
                }
                
                if (lists.isNotEmpty()) {
                    Log.d(TAG, "Extracted ${lists.size} broadcast lists")
                    updateExtractedLists(lists)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting broadcast lists", e)
            } finally {
                isExtracting = false
            }
        }
    }
    
    private fun extractBroadcastMembers(root: AccessibilityNodeInfo) {
        if (isExtracting) return
        isExtracting = true
        
        serviceScope.launch {
            try {
                val members = mutableListOf<Contact>()
                
                val phoneToNameMap = try {
                    ContactsHelper.buildPhoneToNameMap(applicationContext)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load contacts: ${e.message}")
                    emptyMap()
                }
                
                val contactNodes = findAllNodes(root) { node ->
                    node.contentDescription?.toString()?.contains("Contact") == true ||
                    node.className?.toString() == "android.widget.TextView"
                }
                
                for (node in contactNodes) {
                    val text = node.text?.toString() ?: node.contentDescription?.toString() ?: continue
                    
                    if (text.length > 2 && !text.contains("recipient") && !text.contains("Broadcast")) {
                        val extractedPhone = extractPhoneNumber(text)
                        
                        val contactName = if (extractedPhone != null && phoneToNameMap.isNotEmpty()) {
                            val normalized = ContactsHelper.normalizePhoneNumber(extractedPhone)
                            val key = normalized.takeLast(10)
                            phoneToNameMap[key] ?: text
                        } else {
                            text
                        }
                        
                        val contact = Contact(
                            id = UUID.randomUUID().toString(),
                            name = contactName,
                            phone = extractedPhone ?: ""
                        )
                        
                        if (!members.any { it.name == contactName }) {
                            members.add(contact)
                            Log.d(TAG, "Extracted contact: $contactName (phone: ${extractedPhone ?: "unknown"})")
                        }
                    }
                }
                
                if (members.isNotEmpty()) {
                    Log.d(TAG, "Extracted ${members.size} members for '$currentBroadcastName'")
                    updateBroadcastListMembers(currentBroadcastName, members)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting members", e)
            } finally {
                isExtracting = false
            }
        }
    }
    
    private fun findNodeWithText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return root
        }
        
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findNodeWithText(child, text)?.let { return it }
            }
        }
        
        return null
    }
    
    private fun findAllNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        
        if (predicate(root)) {
            result.add(root)
        }
        
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                result.addAll(findAllNodes(child, predicate))
            }
        }
        
        return result
    }
    
    private fun findAllTextNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        return findAllNodes(root) { node ->
            node.className?.toString() == "android.widget.TextView" &&
            !node.text.isNullOrBlank()
        }
    }
    
    private fun extractPhoneNumber(text: String): String? {
        val phoneRegex = Regex("""\+?\d[\d\s\-()]{8,}""")
        return phoneRegex.find(text)?.value
    }
    
    private fun updateExtractedLists(newLists: List<BroadcastList>) {
        synchronized(extractedLists) {
            for (list in newLists) {
                val existing = extractedLists.find { it.name == list.name }
                if (existing == null) {
                    extractedLists.add(list)
                }
            }
        }
        
        extractionListeners.forEach { it(extractedLists.toList()) }
    }
    
    private fun updateBroadcastListMembers(listName: String, members: List<Contact>) {
        if (listName.isBlank()) {
            Log.w(TAG, "Cannot update members - no list name specified")
            val unnamedList = BroadcastList(
                id = UUID.randomUUID().toString(),
                name = "Broadcast List (${members.size} members)",
                members = members,
                isAutoGenerated = false
            )
            synchronized(extractedLists) {
                extractedLists.add(unnamedList)
            }
            extractionListeners.forEach { it(extractedLists.toList()) }
            return
        }
        
        synchronized(extractedLists) {
            val existingIndex = extractedLists.indexOfFirst { 
                it.name.equals(listName, ignoreCase = true) 
            }
            
            if (existingIndex >= 0) {
                val existingList = extractedLists[existingIndex]
                val mergedMembers = (existingList.members + members).distinctBy { it.name }
                extractedLists[existingIndex] = existingList.copy(members = mergedMembers)
                Log.d(TAG, "Updated list '$listName' with ${mergedMembers.size} total members")
            } else {
                val newList = BroadcastList(
                    id = UUID.randomUUID().toString(),
                    name = listName,
                    members = members,
                    isAutoGenerated = false
                )
                extractedLists.add(newList)
                Log.d(TAG, "Created new list '$listName' with ${members.size} members")
            }
        }
        
        extractionListeners.forEach { it(extractedLists.toList()) }
    }
    
    fun syncToBackend() {
        serviceScope.launch {
            try {
                val request = SyncRequest(
                    deviceId = android.os.Build.MODEL,
                    lists = extractedLists.toList()
                )
                
                val response = ApiClient.getApiService().syncBroadcasts(request)
                if (response.isSuccessful) {
                    Log.d(TAG, "Synced ${extractedLists.size} lists to backend")
                } else {
                    Log.e(TAG, "Sync failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync error", e)
            }
        }
    }
    
    fun clearExtractedData() {
        extractedLists.clear()
        extractionListeners.forEach { it(emptyList()) }
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        extractionJob?.cancel()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }
}
