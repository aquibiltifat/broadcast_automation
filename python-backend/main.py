"""
Group Weaver AI - Python Backend
FastAPI server for Android app sync, AI analysis, and real-time WebSocket updates
"""

import os
import json
import socket
from datetime import datetime
from typing import List
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from dotenv import load_dotenv

from models.schemas import (
    SyncRequest,
    AnalysisRequest,
    NameSuggestionRequest,
    BroadcastList,
    Contact
)
from services import gemini_service, storage_service

load_dotenv()

app = FastAPI(
    title="Group Weaver AI Backend",
    description="Backend API for WhatsApp Broadcast List extraction and AI analysis",
    version="2.0.0"
)

# CORS configuration - allow Android app and React frontend
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:8080",
        "http://localhost:5173",
        "http://localhost:3000",
        "*"  # Allow Android app from any IP
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ============= WebSocket Connection Manager =============

class ConnectionManager:
    """Manages WebSocket connections for real-time updates"""
    
    def __init__(self):
        self.active_connections: List[WebSocket] = []
        self.connected_devices: dict = {}  # device_id -> last_sync_time
    
    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)
        print(f"📡 WebSocket connected. Total connections: {len(self.active_connections)}")
    
    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)
        print(f"📡 WebSocket disconnected. Total connections: {len(self.active_connections)}")
    
    async def broadcast(self, message: dict):
        """Broadcast message to all connected clients"""
        disconnected = []
        for connection in self.active_connections:
            try:
                await connection.send_json(message)
            except Exception:
                disconnected.append(connection)
        
        # Clean up disconnected clients
        for conn in disconnected:
            self.disconnect(conn)
    
    async def notify_sync(self, device_id: str, lists_count: int, members_count: int):
        """Notify all clients about a sync event"""
        self.connected_devices[device_id] = datetime.now().isoformat()
        
        await self.broadcast({
            "type": "sync",
            "device_id": device_id,
            "lists_count": lists_count,
            "members_count": members_count,
            "timestamp": datetime.now().isoformat()
        })
    
    async def notify_data_change(self, action: str, details: str = ""):
        """Notify all clients about data changes"""
        await self.broadcast({
            "type": "data_change",
            "action": action,
            "details": details,
            "timestamp": datetime.now().isoformat()
        })

manager = ConnectionManager()


# ============= WebSocket Endpoint =============

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    """WebSocket endpoint for real-time updates"""
    await manager.connect(websocket)
    
    # Send initial state
    try:
        lists = storage_service.get_lists()
        await websocket.send_json({
            "type": "init",
            "lists_count": len(lists),
            "members_count": sum(len(l.get("members", [])) for l in lists),
            "connected_devices": list(manager.connected_devices.keys()),
            "timestamp": datetime.now().isoformat()
        })
    except Exception as e:
        print(f"Error sending init: {e}")
    
    try:
        while True:
            # Keep connection alive and handle incoming messages
            data = await websocket.receive_text()
            
            # Handle ping/pong or other messages
            try:
                message = json.loads(data)
                if message.get("type") == "ping":
                    await websocket.send_json({"type": "pong"})
                elif message.get("type") == "refresh":
                    # Send current data
                    lists = storage_service.get_lists()
                    await websocket.send_json({
                        "type": "data",
                        "lists": lists,
                        "timestamp": datetime.now().isoformat()
                    })
            except json.JSONDecodeError:
                pass
                
    except WebSocketDisconnect:
        manager.disconnect(websocket)


# ============= REST Endpoints =============

@app.get("/")
async def root():
    return {
        "name": "Group Weaver AI Backend",
        "version": "2.0.0",
        "status": "running",
        "websocket": "/ws",
        "connected_clients": len(manager.active_connections)
    }


@app.get("/api/health")
async def health_check():
    """Health check endpoint"""
    return {
        "status": "ok",
        "timestamp": datetime.now().isoformat(),
        "ai_configured": gemini_service.is_configured(),
        "websocket_clients": len(manager.active_connections)
    }


@app.get("/api/ai/status")
async def ai_status():
    """Get AI configuration status"""
    return {
        "configured": gemini_service.is_configured(),
        "model": "gemini-2.0-flash",
        "provider": "Google AI Studio"
    }


# ============= Android Sync Endpoints =============

@app.post("/api/sync")
async def sync_from_android(request: SyncRequest):
    """
    Receive broadcast lists from Android AccessibilityService
    Notifies all connected WebSocket clients in real-time
    """
    try:
        result = storage_service.sync_from_android(
            device_id=request.device_id,
            lists=[l.dict() for l in request.lists]
        )
        
        storage_service.add_log(
            action=f"Synced {result['synced']} lists from Android",
            status="success",
            details=f"Device: {request.device_id}"
        )
        
        # Calculate total members
        total_members = sum(len(l.members) for l in request.lists)
        
        # Notify all connected WebSocket clients
        await manager.notify_sync(
            device_id=request.device_id,
            lists_count=result['synced'],
            members_count=total_members
        )
        
        return {"success": True, "data": result}
    except Exception as e:
        storage_service.add_log(
            action="Android sync failed",
            status="error",
            details=str(e)
        )
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/lists")
async def get_lists():
    """Get all stored broadcast lists"""
    lists = storage_service.get_lists()
    return {"success": True, "data": lists}


@app.post("/api/lists")
async def create_list(broadcast_list: BroadcastList):
    """Create a new broadcast list"""
    lists = storage_service.get_lists()
    lists.append(broadcast_list.dict())
    storage_service.save_lists(lists)
    
    storage_service.add_log(
        action=f"Created list '{broadcast_list.name}'",
        status="success",
        details=f"{len(broadcast_list.members)} members"
    )
    
    # Notify WebSocket clients
    await manager.notify_data_change(
        action="list_created",
        details=broadcast_list.name
    )
    
    return {"success": True, "data": broadcast_list}


@app.delete("/api/lists/{list_id}")
async def delete_list(list_id: str):
    """Delete a broadcast list"""
    lists = storage_service.get_lists()
    original_count = len(lists)
    deleted_name = next((l.get("name") for l in lists if l.get("id") == list_id), None)
    lists = [l for l in lists if l.get("id") != list_id]
    
    if len(lists) == original_count:
        raise HTTPException(status_code=404, detail="List not found")
    
    storage_service.save_lists(lists)
    storage_service.add_log(
        action=f"Deleted list {list_id}",
        status="success"
    )
    
    # Notify WebSocket clients
    await manager.notify_data_change(
        action="list_deleted",
        details=deleted_name or list_id
    )
    
    return {"success": True, "message": "List deleted"}


# ============= Common Members Endpoint =============

@app.get("/api/common-members")
async def get_common_members():
    """Find members that appear in 2 or more broadcast lists"""
    lists = storage_service.get_lists()
    
    # Filter out auto-generated lists
    source_lists = [l for l in lists if not l.get("is_auto_generated", False) and not l.get("isAutoGenerated", False)]
    
    if len(source_lists) < 2:
        return {
            "success": True,
            "data": {
                "common_members": [],
                "source_lists_count": len(source_lists),
                "message": "Need at least 2 lists to find common members"
            }
        }
    
    # Count member occurrences by phone or name
    member_counts = {}
    
    for lst in source_lists:
        for member in lst.get("members", []):
            phone = member.get("phone", "")
            name = member.get("name", "")
            
            # Use phone as key if available, otherwise name
            if phone:
                key = phone[-10:]  # Last 10 digits
            else:
                key = name.lower().strip()
            
            if key:
                if key not in member_counts:
                    member_counts[key] = {
                        "member": member,
                        "count": 0,
                        "in_lists": []
                    }
                member_counts[key]["count"] += 1
                member_counts[key]["in_lists"].append(lst.get("name", "Unknown"))
    
    # Find members in 2+ lists
    common_members = [
        {
            **info["member"],
            "appears_in": info["count"],
            "list_names": list(set(info["in_lists"]))
        }
        for key, info in member_counts.items()
        if info["count"] >= 2
    ]
    
    # Sort by number of appearances
    common_members.sort(key=lambda x: x["appears_in"], reverse=True)
    
    return {
        "success": True,
        "data": {
            "common_members": common_members,
            "source_lists_count": len(source_lists),
            "total_common": len(common_members)
        }
    }


# ============= AI Analysis Endpoints =============

@app.post("/api/analyze")
async def analyze_common_members(request: AnalysisRequest):
    """Analyze common members with Gemini AI"""
    if not gemini_service.is_configured():
        raise HTTPException(
            status_code=503,
            detail="AI not configured. Add GOOGLE_AI_API_KEY to .env"
        )
    
    try:
        storage_service.add_log(
            action="Gemini AI analyzing...",
            status="pending"
        )
        
        result = await gemini_service.analyze_common_members(
            request.lists,
            request.common_members
        )
        
        storage_service.add_log(
            action="Gemini AI analysis complete",
            status="success",
            details=result.get("analysis", "")[:100]
        )
        
        return {"success": True, "data": result}
    except Exception as e:
        storage_service.add_log(
            action="AI analysis failed",
            status="error",
            details=str(e)
        )
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/suggest-name")
async def suggest_name(request: NameSuggestionRequest):
    """Get AI-suggested names for a list"""
    if not gemini_service.is_configured():
        raise HTTPException(
            status_code=503,
            detail="AI not configured. Add GOOGLE_AI_API_KEY to .env"
        )
    
    try:
        result = await gemini_service.suggest_list_name(
            request.members,
            request.existing_names
        )
        return {"success": True, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/insights")
async def get_insights(lists: list):
    """Get AI insights about broadcast lists"""
    if not gemini_service.is_configured():
        raise HTTPException(
            status_code=503,
            detail="AI not configured. Add GOOGLE_AI_API_KEY to .env"
        )
    
    try:
        result = await gemini_service.get_insights(lists)
        return {"success": True, "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ============= Logs Endpoints =============

@app.get("/api/logs")
async def get_logs():
    """Get automation logs"""
    logs = storage_service.get_logs()
    return {"success": True, "data": logs}


@app.delete("/api/logs")
async def clear_logs():
    """Clear all logs"""
    storage_service.add_log("Logs cleared", "success")
    return {"success": True, "message": "Logs cleared"}


# ============= Connection Status =============

@app.get("/api/connections")
async def get_connections():
    """Get currently connected WebSocket clients"""
    return {
        "success": True,
        "data": {
            "active_connections": len(manager.active_connections),
            "connected_devices": manager.connected_devices
        }
    }


# ============= Startup =============

@app.on_event("startup")
async def startup_event():
    storage_service.ensure_storage_exists()
    
    # Get local IP address
    port = os.getenv('PORT', 3002)
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        local_ip = "127.0.0.1"
    
    print("")
    print("=" * 60)
    print("🚀 Group Weaver AI Python Backend v2.0 started!")
    print("=" * 60)
    print("")
    print(f"🌐 Local:    http://localhost:{port}")
    print(f"🌐 Network:  http://{local_ip}:{port}")
    print("")
    print("📱 For Android App, use this URL:")
    print(f"   👉  http://{local_ip}:{port}")
    print("")
    print(f"🔌 WebSocket: ws://{local_ip}:{port}/ws")
    print("")
    
    if gemini_service.is_configured():
        print("✅ Gemini AI configured and ready")
    else:
        print("⚠️  WARNING: AI not configured!")
        print("   Add GOOGLE_AI_API_KEY to .env file")
    
    print("")
    print("=" * 60)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=os.getenv("HOST", "0.0.0.0"),
        port=int(os.getenv("PORT", 3002)),
        reload=True
    )
