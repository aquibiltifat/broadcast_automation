package com.groupweaver.ai.utils

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

/**
 * Helper class for reading device contacts and matching with WhatsApp data
 */
object ContactsHelper {
    
    private const val TAG = "ContactsHelper"
    
    data class DeviceContact(
        val id: String,
        val name: String,
        val phoneNumbers: List<String>
    )
    
    private var cachedContacts: List<DeviceContact>? = null
    
    /**
     * Load all contacts from device
     */
    fun loadContacts(context: Context): List<DeviceContact> {
        if (cachedContacts != null) {
            return cachedContacts!!
        }
        
        val contacts = mutableListOf<DeviceContact>()
        val contentResolver: ContentResolver = context.contentResolver
        
        try {
            val cursor: Cursor? = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                null,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            
            cursor?.use {
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneIndex = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                
                while (it.moveToNext()) {
                    val id = it.getString(idIndex) ?: continue
                    val name = it.getString(nameIndex) ?: continue
                    val hasPhone = it.getInt(hasPhoneIndex)
                    
                    if (hasPhone > 0) {
                        val phoneNumbers = getPhoneNumbers(contentResolver, id)
                        if (phoneNumbers.isNotEmpty()) {
                            contacts.add(DeviceContact(id, name, phoneNumbers))
                        }
                    }
                }
            }
            
            cachedContacts = contacts
            Log.d(TAG, "Loaded ${contacts.size} contacts from device")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts", e)
        }
        
        return contacts
    }
    
    /**
     * Get phone numbers for a contact
     */
    private fun getPhoneNumbers(contentResolver: ContentResolver, contactId: String): List<String> {
        val phoneNumbers = mutableListOf<String>()
        
        val phoneCursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )
        
        phoneCursor?.use {
            val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val phone = it.getString(phoneIndex)
                if (!phone.isNullOrBlank()) {
                    phoneNumbers.add(normalizePhoneNumber(phone))
                }
            }
        }
        
        return phoneNumbers
    }
    
    /**
     * Normalize phone number for comparison
     */
    fun normalizePhoneNumber(phone: String): String {
        return phone.replace(Regex("[^0-9+]"), "")
    }
    
    /**
     * Find contact name by phone number
     */
    fun findContactByPhone(context: Context, phone: String): String? {
        val normalizedPhone = normalizePhoneNumber(phone)
        val contacts = loadContacts(context)
        
        for (contact in contacts) {
            for (contactPhone in contact.phoneNumbers) {
                // Match last 10 digits
                val normalizedContactPhone = normalizePhoneNumber(contactPhone)
                if (matchPhoneNumbers(normalizedPhone, normalizedContactPhone)) {
                    return contact.name
                }
            }
        }
        
        return null
    }
    
    /**
     * Check if two phone numbers match (comparing last 10 digits)
     */
    private fun matchPhoneNumbers(phone1: String, phone2: String): Boolean {
        val digits1 = phone1.takeLast(10)
        val digits2 = phone2.takeLast(10)
        return digits1.length >= 10 && digits1 == digits2
    }
    
    /**
     * Build a map of phone -> contact name for quick lookup
     */
    fun buildPhoneToNameMap(context: Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val contacts = loadContacts(context)
        
        for (contact in contacts) {
            for (phone in contact.phoneNumbers) {
                val normalized = normalizePhoneNumber(phone)
                if (normalized.length >= 10) {
                    // Use last 10 digits as key
                    val key = normalized.takeLast(10)
                    if (!map.containsKey(key)) {
                        map[key] = contact.name
                    }
                }
            }
        }
        
        Log.d(TAG, "Built phone lookup map with ${map.size} entries")
        return map
    }
    
    /**
     * Clear cached contacts (call when contacts might have changed)
     */
    fun clearCache() {
        cachedContacts = null
    }
    
    /**
     * Check if a phone number exists in WhatsApp contacts
     * This matches extracted WhatsApp data with device contacts
     */
    fun enrichWithContactNames(
        context: Context,
        extractedMembers: List<Pair<String, String>>  // (name from WhatsApp, phone)
    ): List<Triple<String, String, String>> {  // (WhatsApp name, phone, device contact name)
        val phoneMap = buildPhoneToNameMap(context)
        
        return extractedMembers.map { (whatsappName, phone) ->
            val normalized = normalizePhoneNumber(phone)
            val key = normalized.takeLast(10)
            val deviceName = phoneMap[key]
            Triple(whatsappName, phone, deviceName ?: whatsappName)
        }
    }
}
