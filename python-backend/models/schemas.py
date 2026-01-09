from pydantic import BaseModel
from typing import Optional
from datetime import datetime


class Contact(BaseModel):
    id: str
    name: str
    phone: str
    avatar: Optional[str] = None


class BroadcastList(BaseModel):
    id: str
    name: str
    members: list[Contact]
    created_at: datetime = datetime.now()
    is_auto_generated: bool = False


class SyncRequest(BaseModel):
    """Request from Android app to sync broadcast lists"""
    device_id: str
    lists: list[BroadcastList]
    timestamp: datetime = datetime.now()


class AnalysisRequest(BaseModel):
    """Request for AI analysis of broadcast lists"""
    lists: list[dict]
    common_members: list[dict]


class AnalysisResponse(BaseModel):
    """AI analysis response"""
    analysis: str
    suggested_name: str
    confidence: str


class NameSuggestionRequest(BaseModel):
    """Request for AI name suggestions"""
    members: list[dict]
    existing_names: list[str] = []


class NameSuggestionResponse(BaseModel):
    """AI name suggestion response"""
    suggestions: list[str]
    best_pick: str
    reasoning: str
