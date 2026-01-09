# Group Weaver AI - Android App

Native Android app with AccessibilityService for WhatsApp broadcast list extraction.

## Setup

1. Open the `android-app` folder in Android Studio
2. Sync Gradle files
3. Connect your Android device (10+)
4. Build and install the app

## Usage

1. **Enable Accessibility Service**
   - Open Android Settings → Accessibility
   - Find "Group Weaver AI" and enable it
   - Accept the permissions

2. **Configure Backend**
   - Enter your Python backend URL (e.g., `http://192.168.1.100:3002`)
   - Click "Save URL"

3. **Extract Data**
   - Open WhatsApp
   - Go to Menu (⋮) → New broadcast
   - The app will detect and extract broadcast list data

4. **Sync to Backend**
   - Return to Group Weaver AI app
   - Click "Sync Now" to send data to server

## Architecture

- `WhatsAppAccessibilityService` - Core service that monitors WhatsApp UI
- `MainActivity` - Control panel with status and sync buttons
- `ApiClient` - Retrofit client for backend communication

## Important Notes

- AccessibilityService requires explicit user permission
- App only monitors WhatsApp (package: com.whatsapp)
- Data is stored locally until synced to backend
