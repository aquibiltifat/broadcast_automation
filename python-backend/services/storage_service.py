import json
import os
from datetime import datetime
from pathlib import Path

# Storage file path
STORAGE_FILE = Path(__file__).parent.parent / "data" / "storage.json"


def ensure_storage_exists():
    """Ensure storage directory and file exist"""
    STORAGE_FILE.parent.mkdir(parents=True, exist_ok=True)
    if not STORAGE_FILE.exists():
        with open(STORAGE_FILE, "w") as f:
            json.dump({"lists": [], "logs": [], "last_sync": None}, f)


def load_data() -> dict:
    """Load data from storage file"""
    ensure_storage_exists()
    with open(STORAGE_FILE, "r") as f:
        return json.load(f)


def save_data(data: dict):
    """Save data to storage file"""
    ensure_storage_exists()
    with open(STORAGE_FILE, "w") as f:
        json.dump(data, f, indent=2, default=str)


def get_lists() -> list:
    """Get all broadcast lists"""
    data = load_data()
    return data.get("lists", [])


def save_lists(lists: list):
    """Save broadcast lists"""
    data = load_data()
    data["lists"] = lists
    data["last_sync"] = datetime.now().isoformat()
    save_data(data)


def sync_from_android(device_id: str, lists: list) -> dict:
    """Sync broadcast lists from Android device"""
    data = load_data()
    
    # Merge or replace lists from this device
    existing_lists = {l["id"]: l for l in data.get("lists", [])}
    
    for new_list in lists:
        new_list["synced_from"] = device_id
        new_list["synced_at"] = datetime.now().isoformat()
        existing_lists[new_list["id"]] = new_list
    
    data["lists"] = list(existing_lists.values())
    data["last_sync"] = datetime.now().isoformat()
    save_data(data)
    
    return {
        "synced": len(lists),
        "total": len(data["lists"]),
        "timestamp": data["last_sync"]
    }


def add_log(action: str, status: str, details: str = None) -> dict:
    """Add automation log entry"""
    data = load_data()
    
    log = {
        "id": f"log-{datetime.now().timestamp()}",
        "timestamp": datetime.now().isoformat(),
        "action": action,
        "status": status,
        "details": details
    }
    
    logs = data.get("logs", [])
    logs.insert(0, log)
    logs = logs[:100]  # Keep only last 100 logs
    
    data["logs"] = logs
    save_data(data)
    
    return log


def get_logs() -> list:
    """Get automation logs"""
    data = load_data()
    return data.get("logs", [])


def clear_data():
    """Clear all stored data"""
    save_data({"lists": [], "logs": [], "last_sync": None})
