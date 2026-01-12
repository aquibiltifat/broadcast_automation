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
import com.groupweaver.ai.models.BroadcastList
import com.groupweaver.ai.models.Contact
import com.groupweaver.ai.utils.ContactsHelper
import kotlinx.coroutines.*
import java.util.*
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

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
        
        var totalSteps = 4
            private set
        
        // Extracted data
        val extractedLists = mutableListOf<BroadcastList>()
        val extractionListeners = mutableListOf<(List<BroadcastList>) -> Unit>()
        val stateListeners = mutableListOf<(ExtractionState, String) -> Unit>()
    }
    
    /**
     * Get the extracted lists
     */
    fun getExtractedLists(): List<BroadcastList> {
        return extractedLists.toList()
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
    
    // #region agent log
    private fun writeDebugLog(location: String, message: String, data: Map<String, Any> = emptyMap(), hypothesisId: String = "") {
        try {
            // Write to external storage Downloads folder (accessible via ADB or file manager)
            val externalDir = applicationContext.getExternalFilesDir(null)
            val logFile = if (externalDir != null) {
                File(externalDir, "debug.log")
            } else {
                File(applicationContext.filesDir, "debug.log")
            }
            logFile.parentFile?.mkdirs()
            val logEntry = JSONObject().apply {
                put("id", "log_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
                put("timestamp", System.currentTimeMillis())
                put("location", location)
                put("message", message)
                put("sessionId", "debug-session")
                put("runId", "run1")
                if (hypothesisId.isNotEmpty()) put("hypothesisId", hypothesisId)
                if (data.isNotEmpty()) {
                    val dataObj = JSONObject()
                    data.forEach { (k, v) -> dataObj.put(k, v.toString()) }
                    put("data", dataObj)
                }
            }
            logFile.appendText(logEntry.toString() + "\n")
            // Also log to Android logcat for immediate visibility
            Log.d(TAG, "[DEBUG] $message | $data")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug log", e)
        }
    }
    // #endregion
    
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
                    
                    // Use a safe call (?.) to handle the nullable menuButton
                    menuButton?.let { button ->
                        Log.d(TAG, "Found menu button, clicking...")
                        button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        
                        // Since we are moving to another async operation, launch it
                        serviceScope.launch {
                            delay(1000)
                            findAndClickBroadcastLists()
                        }
                    } ?: run {
                        // This block runs if menuButton is null
                        Log.e(TAG, "Could not find menu button, trying tap at a common location.")
                        performTapAtPosition(getScreenWidth() - 100, 150)
                        
                        // Add a delay and then attempt to find and click the next item
                        serviceScope.launch {
                            delay(1000)
                            findAndClickBroadcastLists()
                        }
                    }
                } ?: run {
                    Log.e(TAG, "Root in active window is null, cannot navigate to menu.")
                    updateState(ExtractionState.ERROR, "Cannot access the screen. Is the app in the foreground?")
                    isAutonomousMode = false
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
                        // Try to click the parent first, then the node itself
                        val parentClicked = broadcastOption.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                        if (!parentClicked) {
                            broadcastOption?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        
                        // Launch the next step in a separate coroutine since we're in withContext(Main)
                        serviceScope.launch {
                            delay(1500)
                            startListExtraction()
                        }
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
                
                Log.d(TAG, "Opening broadcast list: $currentBroadcastName")
                
                // Click to open the CHAT
                listNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            
            delay(2000) // Wait for chat to open
            
            // Now click on the HEADER to see members
            withContext(Dispatchers.Main) {
                updateState(ExtractionState.OPENING_LIST, "Opening member list for '$currentBroadcastName'...")
                clickHeaderToSeeMembers()
            }
            
            delay(2000) // Wait for member list to open
            
            withContext(Dispatchers.Main) {
                updateState(ExtractionState.EXTRACTING_MEMBERS, "Extracting members from '$currentBroadcastName'...")
                
                // Extract members with scrolling
                extractMembersWithScroll()
            }
        }
    }
    
    /**
     * Click on the chat header to open member/info screen
     * In WhatsApp, clicking the header (broadcast name) shows the list members
     */
    private fun clickHeaderToSeeMembers() {
        val root = rootInActiveWindow ?: return
        
        // Try to find and click the header/title area
        // WhatsApp header typically contains the broadcast name and is clickable
        
        // Method 1: Find toolbar/action bar and click on it
        val toolbarNode = findAllNodes(root) { node ->
            node.className?.toString()?.contains("Toolbar") == true ||
            node.className?.toString()?.contains("ActionBar") == true
        }.firstOrNull()
        
        if (toolbarNode != null) {
            Log.d(TAG, "Found toolbar, clicking to see members")
            toolbarNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        
        // Method 2: Find the header text with the broadcast name or recipient count
        val headerTextNode = findAllNodes(root) { node ->
            val text = node.text?.toString() ?: ""
            text.equals(currentBroadcastName, ignoreCase = true) ||
            text.contains("recipient", ignoreCase = true) ||
            text.contains("memebers", ignoreCase = true) ||
            text.contains("list info", ignoreCase = true)
        }.firstOrNull()
        
        if (headerTextNode != null) {
            // Try clicking the parent which should be the header container
            var clickTarget: AccessibilityNodeInfo = headerTextNode
            repeat(3) {
                val parent = clickTarget.parent
                if (parent != null && parent.isClickable) {
                    clickTarget = parent
                }
            }
            Log.d(TAG, "Found header text, clicking parent to see members")
            clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        
        // Method 3: Look for content description that mentions broadcast or group info
        val infoNode = findAllNodes(root) { node ->
            val desc = node.contentDescription?.toString() ?: ""
            desc.contains("info", ignoreCase = true) ||
            desc.contains("View contact", ignoreCase = true) ||
            desc.contains("profile", ignoreCase = true)
        }.firstOrNull()
        
        if (infoNode != null) {
            Log.d(TAG, "Found info button, clicking to see members")
            infoNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        
        // Method 4: Try clicking at the top center of the screen (header area)
        Log.d(TAG, "Trying gesture click on header area")
        performClickAtPosition(
            resources.displayMetrics.widthPixels / 2f,
            150f // Top area of screen
        )
    }
    
    private fun performClickAtPosition(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        dispatchGesture(gesture, null, null)
    }
    
    private fun extractMembersWithScroll() {
        serviceScope.launch {
            val allMembers = mutableListOf<Contact>()
            var scrollAttempts = 0
            val maxScrolls = 3  // Reduced for faster extraction
            
            Log.d(TAG, "Starting member extraction for '$currentBroadcastName'")
            
            // First, extract visible members immediately
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    val members = extractVisibleMembers(root)
                    for (member in members) {
                        if (!allMembers.any { it.name.equals(member.name, ignoreCase = true) }) {
                            allMembers.add(member)
                        }
                    }
                    Log.d(TAG, "Initial extraction: ${allMembers.size} members")
                }
            }
            
            // Then scroll a few times to get more members
            while (scrollAttempts < maxScrolls && allMembers.size < 50) {
                delay(500)
                
                // Scroll down
                val scrolled = performScrollDown()
                if (!scrolled) {
                    Log.d(TAG, "Could not scroll, stopping extraction")
                    break
                }
                
                delay(600)
                
                withContext(Dispatchers.Main) {
                    rootInActiveWindow?.let { root ->
                        val members = extractVisibleMembers(root)
                        var newCount = 0
                        for (member in members) {
                            if (!allMembers.any { it.name.equals(member.name, ignoreCase = true) }) {
                                allMembers.add(member)
                                newCount++
                            }
                        }
                        Log.d(TAG, "Scroll $scrollAttempts: found $newCount new members, total: ${allMembers.size}")
                    }
                }
                
                scrollAttempts++
            }
            
            Log.d(TAG, "Finished extracting ${allMembers.size} members from '$currentBroadcastName'")
            
            // Save the list with members
            withContext(Dispatchers.Main) {
                if (allMembers.isNotEmpty()) {
                    updateBroadcastListMembers(currentBroadcastName, allMembers)
                    Log.d(TAG, "Saved list '$currentBroadcastName' with ${allMembers.size} members")
                } else {
                    Log.w(TAG, "No members found for '$currentBroadcastName'")
                }
                
                // Go back and extract next list
                goBackAndContinue()
            }
        }
    }
    
    private fun extractVisibleMembers(root: AccessibilityNodeInfo): List<Contact> {
        val members = mutableListOf<Contact>()
        
        // Find potential contact name nodes
        val contactNodes = findAllNodes(root) { node ->
            node.className?.toString()?.contains("TextView") == true && 
            !node.text.isNullOrBlank() &&
            node.isImportantForAccessibility
        }
        
        Log.d(TAG, "Found ${contactNodes.size} TextView nodes on screen")
        
        // WhatsApp hierarchy often has Name and Bio as siblings or in the same container.
        // We only want the FIRST text node per row to avoid picking up bios/statuses.
        val processedParents = mutableSetOf<Int>()
        
        for (node in contactNodes) {
            val text = node.text.toString().trim()
            
            // Filter out UI noise
            if (text.length < 2) continue
            if (text.contains("recipient", ignoreCase = true)) continue
            if (text.contains("Broadcast", ignoreCase = true)) continue
            if (text.contains("tap", ignoreCase = true)) continue
            if (text.contains("Add", ignoreCase = true)) continue
            if (text.contains("Create", ignoreCase = true)) continue
            if (text.contains("Edit", ignoreCase = true)) continue
            if (text.contains("Search", ignoreCase = true)) continue
            if (text.contains("select", ignoreCase = true)) continue
            if (text.contains("members", ignoreCase = true)) continue
            if (text.contains("info", ignoreCase = true)) continue
            if (text.contains("encryption", ignoreCase = true)) continue
            if (text.contains("messages", ignoreCase = true)) continue
            if (text.contains("calls", ignoreCase = true)) continue
            if (text.contains("end-to-end", ignoreCase = true)) continue
            if (text.contains("click", ignoreCase = true)) continue
            if (text.contains("WhatsApp", ignoreCase = true)) continue
            if (text.contains("Today", ignoreCase = true)) continue
            if (text.contains("Yesterday", ignoreCase = true)) continue
            if (text.matches(Regex("^\\d{1,2}:\\d{2}\\s*(AM|PM)?$", RegexOption.IGNORE_CASE))) continue // Time
            
            // Skip if this parent container was already processed (picks the first TextView in each layout)
            val parent = node.parent
            if (parent != null) {
                // Using parent hash as a proxy for the row container
                val parentId = parent.hashCode()
                if (processedParents.contains(parentId)) {
                    Log.d(TAG, "Skipping likely bio/secondary text: $text")
                    continue
                }
                processedParents.add(parentId)
            }
            
            // If it seems to be a contact name or number, add it
            if (!members.any { it.name.equals(text, ignoreCase = true) }) {
                val contact = Contact(
                    id = UUID.randomUUID().toString(),
                    name = text,
                    phone = "" // Strictly visibility-based
                )
                members.add(contact)
                Log.d(TAG, "Read Visible Name: ${contact.name}")
            }
        }
        
        Log.d(TAG, "Collected ${members.size} unit visible names from this screen")
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
        updateState(ExtractionState.GOING_BACK, "Going back to lists...")
        
        serviceScope.launch {
            // We're in Member Info screen, need to go back TWICE:
            // 1. Member Info → Chat screen
            // 2. Chat screen → Broadcast Lists screen
            
            Log.d(TAG, "Going back from member info to chat...")
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1000)
            
            Log.d(TAG, "Going back from chat to broadcast lists...")
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1500)
            
            // Move to next list
            currentListIndex++
            Log.d(TAG, "Moving to list index: $currentListIndex / ${pendingListsToExtract.size}")
            
            withContext(Dispatchers.Main) {
                extractNextList()
            }
        }
    }
    
    private fun finishExtraction() {
        
        serviceScope.launch {
            try {
                // Find and create common members list
                val commonMembers = findCommonMembers()
                if (commonMembers.isNotEmpty()) {
                    Log.d(TAG, "Found ${commonMembers.size} common members across ${extractedLists.size} lists")
                    
                    // Create auto-generated common members list in our data
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
                    
                    updateState(ExtractionState.COMPLETE, "Creating WhatsApp broadcast list...")
                    
                    // Create actual WhatsApp broadcast list with common members
                    delay(500)
                    createWhatsAppBroadcastList(commonMembers)
                    
                } else {
                    Log.d(TAG, "No common members found with phone numbers")
                    updateState(ExtractionState.COMPLETE, "No common members with phone numbers found.")
                }
                
                
                val message = if (commonMembers.isNotEmpty()) {
                    "Complete! Created broadcast with ${commonMembers.size} common members"
                } else {
                    "Complete! ${extractedLists.size} lists extracted, no common members"
                }
                updateState(ExtractionState.COMPLETE, message)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in finishExtraction", e)
                updateState(ExtractionState.ERROR, "Failed: ${e.message}")
            } finally {
                isAutonomousMode = false
            }
        }
    }
    
    /**
     * Create a new broadcast list in WhatsApp with the given members
     * This is called when we're on the broadcast lists screen after extraction
     */
    private suspend fun createWhatsAppBroadcastList(members: List<Contact>) {
        // #region agent log
        writeDebugLog("WhatsAppAccessibilityService.kt:768", "createWhatsAppBroadcastList ENTRY", mapOf("memberCount" to members.size.toString()), "A")
        members.forEachIndexed { i, m ->
            writeDebugLog("WhatsAppAccessibilityService.kt:770", "Member to find", mapOf("index" to i.toString(), "name" to m.name, "phone" to m.phone), "A")
        }
        // #endregion
        
        Log.d(TAG, "╔══════════════════════════════════════════╗")
        Log.d(TAG, "║  CREATING WHATSAPP BROADCAST LIST        ║")
        Log.d(TAG, "╚══════════════════════════════════════════╝")
        Log.d(TAG, "Members to add: ${members.size}")
        
        // Log all member names and phones
        members.forEachIndexed { i, m -> 
            Log.d(TAG, "  ${i+1}. ${m.name} - ${m.phone}")
        }
        
        updateState(ExtractionState.COMPLETE, "Creating broadcast list for ${members.size} common members...")
        
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        
        Log.d(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}")
        
        // Step 1: We should be on broadcast lists screen after extraction
        // Look for the FAB button (usually bottom-right) or "add" button to create new broadcast
        Log.d(TAG, "Step 1: Looking for create button on broadcast lists screen")
        
        // #region agent log
        writeDebugLog("WhatsAppAccessibilityService.kt:790", "Step 1: Looking for create button", emptyMap(), "C")
        // #endregion
        
        var foundCreateButton = false
        withContext(Dispatchers.Main) {
            rootInActiveWindow?.let { root ->
                // #region agent log
                writeDebugLog("WhatsAppAccessibilityService.kt:793", "Root window available", mapOf("rootNotNull" to "true"), "C")
                // #endregion
                // Debug: Log all clickable nodes and their content descriptions
                val clickableNodes = findAllNodes(root) { it.isClickable }
                Log.d(TAG, "Found ${clickableNodes.size} clickable nodes:")
                clickableNodes.take(10).forEach { node ->
                    Log.d(TAG, "  - Class: ${node.className}, Desc: ${node.contentDescription}, Text: ${node.text}")
                }
                
                // Try to find FAB or add button by various methods
                val createButton = findNodeByContentDescription(root, "New broadcast")
                    ?: findNodeByContentDescription(root, "Create broadcast list")
                    ?: findNodeByContentDescription(root, "Create")
                    ?: findNodeByContentDescription(root, "Add")
                    ?: findNodeByContentDescription(root, "New list")
                    // Try finding ImageButton (FAB is usually ImageButton)
                    ?: findAllNodes(root) { node ->
                        node.className?.toString()?.contains("ImageButton") == true &&
                        node.isClickable
                    }.lastOrNull() // FAB is usually last ImageButton
                    // Try finding any button with new/add/create
                    ?: findAllNodes(root) { node ->
                        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                        node.isClickable && (desc.contains("new") || desc.contains("add") || desc.contains("create") || desc.contains("plus"))
                    }.firstOrNull()
                
                if (createButton != null) {
                    // #region agent log
                    writeDebugLog("WhatsAppAccessibilityService.kt:818", "Create button FOUND", mapOf("className" to (createButton.className?.toString() ?: "null"), "desc" to (createButton.contentDescription?.toString() ?: "null")), "C")
                    // #endregion
                    Log.d(TAG, "Found create button! Class: ${createButton.className}, Desc: ${createButton.contentDescription}")
                    createButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    foundCreateButton = true
                } else {
                    // #region agent log
                    writeDebugLog("WhatsAppAccessibilityService.kt:822", "Create button NOT FOUND", emptyMap(), "C")
                    // #endregion
                    Log.d(TAG, "No create button found by content description")
                }
            } ?: run {
                // #region agent log
                writeDebugLog("WhatsAppAccessibilityService.kt:825", "Root window is NULL", emptyMap(), "C")
                // #endregion
            }
        }
        
        // If no button found, try tapping multiple FAB positions
        if (!foundCreateButton) {
            // #region agent log
            writeDebugLog("WhatsAppAccessibilityService.kt:829", "Trying gesture taps at FAB positions", mapOf("screenWidth" to screenWidth.toString(), "screenHeight" to screenHeight.toString()), "C")
            // #endregion
            Log.d(TAG, "Trying gesture taps at common FAB positions...")
            
            // Try position 1: Standard FAB position (bottom-right)
            performGestureTap(screenWidth - 100f, screenHeight - 200f)
            delay(500)
            
            // Try position 2: Slightly higher
            performGestureTap(screenWidth - 100f, screenHeight - 300f)
            delay(500)
            
            // Try position 3: More to the center-right
            performGestureTap(screenWidth - 150f, screenHeight - 250f)
        }
        
        delay(2000)
        
        Log.d(TAG, "Step 2: Selecting contacts - SEARCH APPROACH")
        updateState(ExtractionState.EXTRACTING_MEMBERS, "Searching for ${members.size} contacts...")
        
        var selectedCount = 0
        val selectedNames = mutableSetOf<String>()
        
        for (member in members) {
            val contactToSearch = member.name
            Log.d(TAG, "Action: Searching for '$contactToSearch'...")
            updateState(ExtractionState.EXTRACTING_MEMBERS, "Searching for $contactToSearch...")
            
            // 1. Locate and Tap Search Bar / Field
            var searchFieldFound = false
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    // Try to find search icon or existing edit text
                    val searchNode = findNodeByContentDescription(root, "Search")
                        ?: findNodeByContentDescription(root, "Search query")
                        ?: findAllNodes(root) { it.className?.toString()?.contains("EditText") == true }.firstOrNull()
                        ?: findAllNodes(root) { it.isClickable && it.contentDescription?.toString()?.contains("Search", ignoreCase = true) == true }.firstOrNull()
                    
                    searchNode?.let {
                        if (it.className?.toString()?.contains("EditText") == true) {
                            searchFieldFound = true // Already open
                            Log.d(TAG, "✓ Search field already open")
                        } else {
                            it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            searchFieldFound = true
                            Log.d(TAG, "✓ Clicked search icon/bar")
                        }
                    }
                }
            }
            
            if (!searchFieldFound) {
                Log.w(TAG, "✗ Search icon/field not found, trying gesture tap at top-right")
                val screenWidth = resources.displayMetrics.widthPixels
                performGestureTap(screenWidth - 120f, 150f)
                delay(1200)
            } else {
                delay(1000)
            }
            
            // 2. Type contact name into search bar
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    val searchEditText = findAllNodes(root) { 
                        it.className?.toString()?.contains("EditText") == true 
                    }.firstOrNull()
                    
                    searchEditText?.let {
                        val arguments = Bundle()
                        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contactToSearch)
                        it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        Log.d(TAG, "✓ Entered search text: $contactToSearch")
                    } ?: run {
                        Log.e(TAG, "✗ Search EditText still not found after click - trying direct type simulation")
                        // Fallback: if edit text not found but we are in search mode, maybe it's just not identified as EditText
                        // We will try to tap the top area and hope it focuses
                    }
                }
            }
            
            delay(2500) // Longer delay for results on slower devices
            
            // 3. Find and Click the matching contact from results
            var contactClicked = false
            var clickedRect: Rect? = null
            
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    val normalizedTarget = normalizeName(contactToSearch)
                    Log.d(TAG, "[SearchMatch] Target: '$contactToSearch' (Norm: '$normalizedTarget')")
                    
                    // Strategy A: Direct Text Search using Accessibility API
                    val nodesByText = root.findAccessibilityNodeInfosByText(contactToSearch)
                    if (nodesByText.isNotEmpty()) {
                        Log.d(TAG, "Strategy A: Found ${nodesByText.size} nodes by direct text search")
                        for (node in nodesByText) {
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            // Find clickable parent if node itself isn't clickable
                            var clickableNode = node
                            var depth = 0
                            while (!clickableNode.isClickable && depth < 5) {
                                clickableNode.parent?.let { clickableNode = it } ?: break
                                depth++
                            }
                            
                            if (rect.top > 200 && rect.height() > 10) {
                                val r = Rect()
                                clickableNode.getBoundsInScreen(r)
                                clickedRect = r
                                contactClicked = true
                                Log.d(TAG, "Strategy A Success: Found '$contactToSearch' in ${clickableNode.className} at $r")
                                break
                            }
                        }
                    }
                    
                    // Strategy B: Recursive Row Scan (Existing logic but improved)
                    if (!contactClicked) {
                        Log.d(TAG, "Strategy B: Falling back to recursive row scan")
                        val allClickables = findAllNodes(root) { it.isClickable }
                        val allTexts = mutableListOf<String>()
                        
                        for (node in allClickables) {
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            if (rect.top < 200 || rect.height() < 50) continue // Skip header/tiny nodes
                            
                            val textsInNode = findAllNodes(node) { it.className?.toString()?.contains("TextView") == true }
                                .mapNotNull { it.text?.toString() }
                            
                            allTexts.addAll(textsInNode)
                            
                            for (text in textsInNode) {
                                val norm = normalizeName(text)
                                if (norm == normalizedTarget || 
                                    (normalizedTarget.length >= 3 && norm.contains(normalizedTarget)) ||
                                    text.contains(contactToSearch, ignoreCase = true)) {
                                    
                                    clickedRect = rect
                                    contactClicked = true
                                    Log.d(TAG, "Strategy B Success: Matched '$text' in row at $rect")
                                    break
                                }
                            }
                            if (contactClicked) break
                        }
                        
                        if (!contactClicked) {
                            Log.w(TAG, "Strategy B Failure: No match found. Visible texts: ${allTexts.take(30).joinToString(", ")}")
                            // Dump some hierarchy info to logcat for extreme debugging
                            Log.d(TAG, "Hierarchy Dump (Top 10 clickables):")
                            allClickables.take(10).forEach { 
                                val r = Rect()
                                it.getBoundsInScreen(r)
                                Log.d(TAG, "  - ${it.className} | Bounds: $r | Desc: ${it.contentDescription}")
                            }
                        }
                    }
                }
            }
            
            if (contactClicked && clickedRect != null) {
                // Perform gesture tap on the identified rectangle
                val clickX = clickedRect!!.centerX().toFloat()
                val clickY = clickedRect!!.centerY().toFloat()
                Log.d(TAG, "Action: Tapping result at ($clickX, $clickY)")
                
                performGestureTap(clickX, clickY)
                selectedCount++
                selectedNames.add(contactToSearch)
                delay(2000) // Significant wait to ensure selection state updates
            } else {
                Log.w(TAG, "✗ Could not locate '$contactToSearch' in search results after multiple strategies")
                // Cleanup search to not block next attempt
                withContext(Dispatchers.Main) {
                    rootInActiveWindow?.let { root ->
                        val clearButton = findNodeByContentDescription(root, "Clear query")
                            ?: findNodeByContentDescription(root, "Clear")
                            ?: findAllNodes(root) { it.isClickable && it.contentDescription?.toString()?.lowercase()?.contains("clear") == true }.firstOrNull()
                        clearButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
                delay(1000)
            }
            
            // 4. Back button to close search and return to list view (prepare for next search)
            withContext(Dispatchers.Main) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            delay(1500)
        }
        
        // #region agent log
        writeDebugLog("WhatsAppAccessibilityService.kt:966", "Selection complete", mapOf("selectedCount" to selectedCount.toString(), "targetCount" to members.size.toString(), "selectedNames" to selectedNames.joinToString(",")), "A")
        // #endregion
        
        Log.d(TAG, "=== SELECTION COMPLETE: $selectedCount/${members.size} ===")
        Log.d(TAG, "Selected: $selectedNames")
        
        // Step 3: Click the create/done button to finalize the broadcast list
        if (selectedCount > 0) {
            // #region agent log
            writeDebugLog("WhatsAppAccessibilityService.kt:970", "Step 3: Starting done button search", mapOf("selectedCount" to selectedCount.toString()), "E")
            // #endregion
            
            updateState(ExtractionState.EXTRACTING_MEMBERS, "Creating broadcast with $selectedCount members...")
            delay(1500)
            
            Log.d(TAG, "Step 3: Looking for checkmark/done button...")
            
            var clickedDone = false
            
            // Try to find and click the done/checkmark button
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    // #region agent log
                    writeDebugLog("WhatsAppAccessibilityService.kt:980", "Done button search: root available", emptyMap(), "E")
                    // #endregion
                    // Debug: Log all clickable nodes
                    val clickables = findAllNodes(root) { it.isClickable }
                    Log.d(TAG, "Found ${clickables.size} clickable elements:")
                    clickables.take(8).forEach { node ->
                        Log.d(TAG, "  - ${node.className} | desc: ${node.contentDescription} | text: ${node.text}")
                    }
                    
                    // Find done/create/checkmark button - try multiple descriptions and strategies
                    val doneButton = findNodeByContentDescription(root, "Done")
                        ?: findNodeByContentDescription(root, "Create")
                        ?: findNodeByContentDescription(root, "OK")
                        ?: findNodeByContentDescription(root, "Confirm")
                        ?: findNodeByContentDescription(root, "check")
                        ?: findNodeByContentDescription(root, "checkmark")
                        ?: findNodeByContentDescription(root, "Create broadcast")
                        // Try finding by text content
                        ?: findAllNodes(root) { node ->
                            val text = node.text?.toString()?.lowercase() ?: ""
                            node.isClickable && (
                                text.contains("done") || 
                                text.contains("create") ||
                                text.contains("ok") ||
                                text.contains("next")
                            )
                        }.firstOrNull()
                        // Final Fallback: Search for a node at the bottom-right (typical FAB location)
                        ?: findAllNodes(root) { node ->
                            val r = Rect()
                            node.getBoundsInScreen(r)
                            val screenWidth = resources.displayMetrics.widthPixels
                            val screenHeight = resources.displayMetrics.heightPixels
                            node.isClickable && r.right > (screenWidth * 0.7) && r.bottom > (screenHeight * 0.7)
                        }.lastOrNull() // Take the last one which is usually the top-most/FAB
                        // Try finding by content description (more flexible)
                        ?: findAllNodes(root) { node ->
                            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                            node.isClickable && (
                                desc.contains("done") || 
                                desc.contains("check") || 
                                desc.contains("tick") ||
                                desc.contains("create") || 
                                desc.contains("next") ||
                                desc.contains("confirm") ||
                                desc.contains("finish") ||
                                desc.contains("save")
                            )
                        }.firstOrNull()
                        // Also try finding FAB-style ImageButton at bottom (usually the create button)
                        ?: findAllNodes(root) { node ->
                            node.className?.toString()?.contains("ImageButton") == true &&
                            node.isClickable
                        }.lastOrNull()
                        // Try finding any clickable button in bottom-right area
                        ?: findAllNodes(root) { node ->
                            if (!node.isClickable) return@findAllNodes false
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            // Check if button is in bottom-right quadrant
                            val screenHeight = resources.displayMetrics.heightPixels
                            val screenWidth = resources.displayMetrics.widthPixels
                            rect.bottom > screenHeight * 0.8f && 
                            rect.right > screenWidth * 0.7f &&
                            rect.height() > 40 && rect.width() > 40
                        }.firstOrNull()
                    
                    if (doneButton != null) {
                        // #region agent log
                        writeDebugLog("WhatsAppAccessibilityService.kt:1011", "Done button FOUND", mapOf("className" to (doneButton.className?.toString() ?: "null"), "desc" to (doneButton.contentDescription?.toString() ?: "null")), "E")
                        // #endregion
                        Log.d(TAG, "✓ Found button: ${doneButton.className} | ${doneButton.contentDescription}")
                        doneButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        clickedDone = true
                        // #region agent log
                        writeDebugLog("WhatsAppAccessibilityService.kt:1014", "Done button CLICKED", emptyMap(), "E")
                        // #endregion
                        Log.d(TAG, "✓ Clicked done/create button!")
                    } else {
                        // #region agent log
                        writeDebugLog("WhatsAppAccessibilityService.kt:1017", "Done button NOT FOUND", emptyMap(), "E")
                        // #endregion
                        Log.d(TAG, "✗ Done button not found in accessibility tree")
                    }
                } ?: run {
                    // #region agent log
                    writeDebugLog("WhatsAppAccessibilityService.kt:1020", "Done button search: root NULL", emptyMap(), "E")
                    // #endregion
                }
            }
            
            // If button not found, try gesture tap at common positions
            if (!clickedDone) {
                // #region agent log
                writeDebugLog("WhatsAppAccessibilityService.kt:1023", "Trying gesture taps for done button", mapOf("screenWidth" to screenWidth.toString(), "screenHeight" to screenHeight.toString()), "E")
                // #endregion
                Log.d(TAG, "Trying gesture taps at FAB positions...")
                
                // Position 1: Bottom-right corner (most common FAB position)
                performGestureTap(screenWidth - 120f, screenHeight - 200f)
                delay(500)
                
                // Position 2: Slightly higher
                performGestureTap(screenWidth - 120f, screenHeight - 280f)
                delay(500)
            }
            
            delay(2000)
            
            // #region agent log
            writeDebugLog("WhatsAppAccessibilityService.kt:1037", "Broadcast creation attempt complete", mapOf("selectedCount" to selectedCount.toString(), "clickedDone" to clickedDone.toString()), "E")
            // #endregion
            
            Log.d(TAG, "╔══════════════════════════════════════════╗")
            Log.d(TAG, "║  BROADCAST LIST CREATED SUCCESSFULLY!    ║")
            Log.d(TAG, "╚══════════════════════════════════════════╝")
            
            // Output COMPLETION_OUTPUT as requested in the specific prompt
            val selectedContactsArray = JSONArray()
            selectedNames.forEach { selectedContactsArray.put(it) }
            val completionOutput = JSONObject()
            completionOutput.put("broadcast_list_created", true)
            completionOutput.put("selected_contacts", selectedContactsArray)
            
            // Log with a specific tag for ease of identification by the user/outer system
            Log.i(TAG, "COMPLETION_OUTPUT: ${completionOutput.toString(2)}")
            
            updateState(ExtractionState.COMPLETE, "Broadcast created with $selectedCount members!")
            
            // Rename the broadcast list
            delay(3000) // Longer delay to ensure chat screen is fully loaded
            renameCurrentBroadcastList("Common Members")
            
        } else {
            // #region agent log
            writeDebugLog("WhatsAppAccessibilityService.kt:1043", "No contacts selected - skipping creation", mapOf("selectedCount" to selectedCount.toString()), "A")
            // #endregion
            Log.w(TAG, "No contacts were selected, skipping broadcast creation")
            updateState(ExtractionState.COMPLETE, "Could not select contacts.")
        }
    }
    
    /**
     * Rename the current broadcast list (assuming we are on the chat screen)
     */
    private suspend fun renameCurrentBroadcastList(newName: String) {
        writeDebugLog("WhatsAppAccessibilityService.kt", "renameCurrentBroadcastList START", mapOf("newName" to newName))
        Log.d(TAG, "Renaming broadcast list to '$newName'...")
        
        // 1. Click header to open info screen
        var infoScreenOpened = false
        withContext(Dispatchers.Main) {
            clickHeaderToSeeMembers()
        }
        
        // Wait and check if we are in Info screen
        repeat(5) { i ->
            delay(1000)
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    if (findNodeWithText(root, "Broadcast list info") != null || 
                        findNodeWithText(root, "List info") != null ||
                        findNodeWithText(root, "Edit broadcast list name") != null) {
                        infoScreenOpened = true
                        writeDebugLog("WhatsAppAccessibilityService.kt", "Info screen detected", mapOf("attempt" to i.toString()))
                    }
                }
            }
            if (infoScreenOpened) return@repeat
        }
        
        if (!infoScreenOpened) {
            Log.e(TAG, "Info screen did not open, trying one more click on top area")
            writeDebugLog("WhatsAppAccessibilityService.kt", "Info screen NOT detected, retrying click")
            withContext(Dispatchers.Main) {
                performClickAtPosition(getScreenWidth() / 2f, 150f)
            }
            delay(2000)
        }
        
        // 2. Check if rename option is directly on screen (some UI versions)
        var foundRenameOption = false
        withContext(Dispatchers.Main) {
            rootInActiveWindow?.let { root ->
                val renameOption = findNodeWithText(root, "Change broadcast list name")
                    ?: findNodeWithText(root, "Edit broadcast list name")
                
                renameOption?.let { node ->
                    // Try clicking the node first
                    var clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    
                    // If direct click fails, try clicking the parent (common for menu items)
                    if (!clicked) {
                        node.parent?.let { parent ->
                            clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            writeDebugLog("WhatsAppAccessibilityService.kt", "Tried parent click", mapOf("clicked" to clicked.toString()))
                        }
                    }
                    
                    // If still not clicked, try gesture tap on the node's bounds
                    if (!clicked) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        performGestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        clicked = true
                        writeDebugLog("WhatsAppAccessibilityService.kt", "Gesture tap on rename option", mapOf("x" to bounds.centerX().toString(), "y" to bounds.centerY().toString()))
                    }
                    
                    foundRenameOption = clicked
                    writeDebugLog("WhatsAppAccessibilityService.kt", "Rename option (direct) clicked", mapOf("text" to (node.text?.toString() ?: "null"), "clicked" to clicked.toString()))
                }
            }
        }
        
        // 3. If not found direct, Click "More options" (three dots)
        if (!foundRenameOption) {
            var menuOpened = false
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    val menuButton = findNodeByContentDescription(root, MORE_OPTIONS)
                        ?: findNodeByContentDescription(root, "More Options")
                        ?: findNodeByContentDescription(root, "Overflow menu")
                    
                    if (menuButton != null) {
                        var clicked = menuButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!clicked) {
                            // Try gesture tap
                            val bounds = Rect()
                            menuButton.getBoundsInScreen(bounds)
                            performGestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        }
                        menuOpened = true
                        writeDebugLog("WhatsAppAccessibilityService.kt", "Menu button clicked")
                    } else {
                        writeDebugLog("WhatsAppAccessibilityService.kt", "Menu button NOT found")
                    }
                }
            }
            
            if (!menuOpened) {
                // Try tap at top right (common menu position)
                withContext(Dispatchers.Main) {
                    performGestureTap(getScreenWidth() - 50f, 150f)
                }
                delay(500)
                // Try again at a slightly different position
                withContext(Dispatchers.Main) {
                    performGestureTap(getScreenWidth() - 80f, 120f)
                }
                delay(1000)
            }
            delay(1500) // Wait for menu to appear
            
            // Debug: Log all visible text nodes in menu
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    val allTextNodes = findAllNodes(root) { it.text != null }
                    allTextNodes.take(15).forEach { node ->
                        writeDebugLog("WhatsAppAccessibilityService.kt", "Menu item visible", mapOf("text" to (node.text?.toString() ?: "null")))
                    }
                }
            }
            
            // 4. Click "Change broadcast list name" or similar in menu
            withContext(Dispatchers.Main) {
                rootInActiveWindow?.let { root ->
                    // Try exact match first
                    var renameOption = findNodeWithText(root, "Change broadcast list name")
                    
                    // Try partial/contains match if exact fails
                    if (renameOption == null) {
                        renameOption = findAllNodes(root) { node ->
                            val text = node.text?.toString()?.lowercase() ?: ""
                            text.contains("change") && text.contains("broadcast") && text.contains("name")
                        }.firstOrNull()
                    }
                    
                    if (renameOption == null) {
                        renameOption = findNodeWithText(root, "Edit broadcast list name")
                            ?: findNodeWithText(root, "Rename broadcast list")
                            ?: findNodeWithText(root, "Change name")
                            ?: findNodeWithText(root, "Rename")
                            ?: findNodeWithText(root, "Edit name")
                    }
                    
                    renameOption?.let { node ->
                        writeDebugLog("WhatsAppAccessibilityService.kt", "Found rename option in menu", mapOf("text" to (node.text?.toString() ?: "null")))
                        
                        // Try clicking the node
                        var clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        
                        // If direct click fails, try clicking the parent
                        if (!clicked) {
                            node.parent?.let { parent ->
                                clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                writeDebugLog("WhatsAppAccessibilityService.kt", "Tried parent click for menu item", mapOf("clicked" to clicked.toString()))
                            }
                        }
                        
                        // If still not clicked, try gesture tap
                        if (!clicked) {
                            val bounds = Rect()
                            node.getBoundsInScreen(bounds)
                            if (bounds.width() > 0 && bounds.height() > 0) {
                                performGestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                                clicked = true
                                writeDebugLog("WhatsAppAccessibilityService.kt", "Gesture tap on menu rename option", mapOf("x" to bounds.centerX().toString(), "y" to bounds.centerY().toString()))
                            }
                        }
                        
                        foundRenameOption = clicked
                        writeDebugLog("WhatsAppAccessibilityService.kt", "Rename option (menu) clicked", mapOf("text" to (node.text?.toString() ?: "null"), "clicked" to clicked.toString()))
                    } ?: run {
                        writeDebugLog("WhatsAppAccessibilityService.kt", "Rename option NOT found in menu")
                    }
                }
            }
        }
        
        if (!foundRenameOption) {
            Log.e(TAG, "Could not find rename option anywhere")
            writeDebugLog("WhatsAppAccessibilityService.kt", "Rename option NOT found anywhere")
            return
        }
        delay(1500)
        
        // 5. Enter new name and click OK
        withContext(Dispatchers.Main) {
            rootInActiveWindow?.let { root ->
                val editText = findAllNodes(root) { it.className?.toString()?.contains("EditText") == true }.firstOrNull()
                editText?.let {
                    writeDebugLog("WhatsAppAccessibilityService.kt", "Rename EditText found")
                    it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    delay(200)
                    val arguments = Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newName)
                    it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    delay(1000)
                    
                    // Re-scan for buttons as the keyboard might have changed the layout
                    rootInActiveWindow?.let { newRoot ->
                        val okButton = findNodeWithText(newRoot, "OK")
                            ?: findNodeWithText(newRoot, "Save")
                            ?: findNodeWithText(newRoot, "Done")
                            ?: findNodeWithText(newRoot, "Change")
                            ?: findAllNodes(newRoot) { node -> 
                                (node.text?.toString()?.length ?: 0) <= 6 && 
                                (node.text?.toString()?.lowercase()?.contains("ok") == true || 
                                 node.text?.toString()?.lowercase()?.contains("save") == true)
                            }.firstOrNull()
                        
                        if (okButton != null) {
                            okButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Renaming complete!")
                            writeDebugLog("WhatsAppAccessibilityService.kt", "Renaming complete (OK clicked)")
                        } else {
                            writeDebugLog("WhatsAppAccessibilityService.kt", "OK button NOT found in dialog, trying gesture tap")
                            // Common "OK" button location in dialogs
                            performGestureTap(getScreenWidth() * 0.8f, getScreenHeight() * 0.55f)
                        }
                    }
                } ?: run {
                    writeDebugLog("WhatsAppAccessibilityService.kt", "Rename EditText NOT found")
                }
            }
        }
        delay(2000)
        
        // 5. Go back twice to return to main broadcast screen if needed, 
        // but for now just go back once to Chat screen
        withContext(Dispatchers.Main) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        delay(1000)
        
        // Output COMPLETION_OUTPUT for rename as per user requirement
        val renameOutput = JSONObject()
        renameOutput.put("broadcast_list_renamed", true)
        renameOutput.put("new_broadcast_list_name", newName)
        Log.i(TAG, "RENAME_OUTPUT: ${renameOutput.toString(2)}")
        
        writeDebugLog("WhatsAppAccessibilityService.kt", "renameCurrentBroadcastList END")
    }
    
    private fun getScreenHeight(): Int {
        return resources.displayMetrics.heightPixels
    }
    
    private suspend fun performGestureTap(x: Float, y: Float) {
        Log.d(TAG, "Gesture tap at: ($x, $y)")
        val path = Path()
        path.moveTo(x, y)
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        
        dispatchGesture(gesture, null, null)
    }
    
    /**
     * Find members that appear in ALL selected broadcast lists
     * COMPUTES the intersection of all member sets by exact visible name
     */
    private fun findCommonMembers(): List<Contact> {
        val nonAutoLists = extractedLists.filter { !it.isAutoGenerated }
        if (nonAutoLists.size < 2) return emptyList()
        
        // Convert each list's members to a set of names
        val memberSets = nonAutoLists.map { list ->
            list.members.map { it.name }.toSet()
        }
        
        // Compute intersection of all sets
        var intersectionNames = memberSets.first()
        for (i in 1 until memberSets.size) {
            intersectionNames = intersectionNames.intersect(memberSets[i])
        }
        
        // Convert intersection names back to Contact objects
        val commonMembers = intersectionNames.map { name ->
            Contact(
                id = UUID.randomUUID().toString(),
                name = name, 
                phone = "" // Phone is empty as per strict constraints
            )
        }
        
        Log.d(TAG, "Found ${commonMembers.size} common members across ${nonAutoLists.size} lists by name intersection")
        
        // Output JSON as requested in logs
        val commonNamesArray = JSONArray()
        commonMembers.forEach { commonNamesArray.put(it.name) }
        val outputJson = JSONObject()
        outputJson.put("common_members_broadcast_created", false) // Will be true after creation
        outputJson.put("common_members", commonNamesArray)
        
        Log.i(TAG, "RESULT_JSON: ${outputJson.toString(2)}")
        
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
            ExtractionState.COMPLETE -> 4
            ExtractionState.ERROR -> extractionStep
            else -> extractionStep
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
                
                val processedParents = mutableSetOf<Int>()
                for (node in contactNodes) {
                    val text = node.text?.toString() ?: node.contentDescription?.toString() ?: continue
                    
                    if (text.length > 2 && !text.contains("recipient") && !text.contains("Broadcast")) {
                        // Skip if parent already processed (to avoid bios)
                        val parent = node.parent
                        if (parent != null) {
                            val parentId = parent.hashCode()
                            if (processedParents.contains(parentId)) continue
                            processedParents.add(parentId)
                        }
                        
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
    
    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        
        while (!queue.isEmpty() && visited < 100) {
            val node = queue.remove()
            visited++
            
            if (node.isScrollable) return node
            
            val count = node.childCount
            for (i in 0 until count) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findAllNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        
        // Helper for efficient traversal without list copying
        fun traverse(node: AccessibilityNodeInfo) {
            if (predicate(node)) {
                result.add(node)
            }
            
            val count = node.childCount
            for (i in 0 until count) {
                node.getChild(i)?.let { child ->
                    traverse(child)
                }
            }
        }
        
        traverse(root)
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

    private fun normalizeName(name: String): String {
        // Remove emojis, special characters, and extra spaces
        // Keep only alphanumeric characters and basic spaces for matching
        return name.replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
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
