import os
import json
from dotenv import load_dotenv
from google import genai

load_dotenv()

# Configure Gemini client
client = None

def get_client():
    global client
    if client is None and is_configured():
        client = genai.Client(api_key=os.getenv("GOOGLE_AI_API_KEY"))
    return client


def is_configured() -> bool:
    """Check if API key is configured"""
    key = os.getenv("GOOGLE_AI_API_KEY")
    return key is not None and key != "your_api_key_here"


async def analyze_common_members(lists: list, common_members: list) -> dict:
    """Analyze broadcast lists and find common member insights"""
    
    prompt = f"""You are an AI assistant helping analyze WhatsApp broadcast lists.

Given these broadcast lists:
{chr(10).join([f'- "{l.get("name", "Unknown")}" with {len(l.get("members", []))} members' for l in lists])}

And these common members found across all selected lists:
{chr(10).join([f'- {m.get("name", "Unknown")} ({m.get("phone", "Unknown")})' for m in common_members])}

Provide a brief analysis (2-3 sentences) about:
1. What type of group these common members might represent
2. A suggested name for a new broadcast list containing these common members

Respond in JSON format:
{{
  "analysis": "your analysis here",
  "suggestedName": "suggested list name",
  "confidence": "high/medium/low"
}}"""

    try:
        c = get_client()
        if c is None:
            raise Exception("AI client not configured")
        
        response = c.models.generate_content(
            model="gemini-2.0-flash",
            contents=prompt
        )
        text = response.text
        
        # Extract JSON from response
        json_match = text[text.find("{"):text.rfind("}")+1]
        if json_match:
            return json.loads(json_match)
        
        return {
            "analysis": text,
            "suggestedName": "Common Members",
            "confidence": "medium"
        }
    except Exception as e:
        raise Exception(f"Failed to analyze with Gemini AI: {str(e)}")


async def suggest_list_name(members: list, existing_names: list = None) -> dict:
    """Generate a smart name for a broadcast list based on its members"""
    
    if existing_names is None:
        existing_names = []
    
    members_display = members[:10]
    prompt = f"""You are helping name a WhatsApp broadcast list.

The list contains these members:
{chr(10).join([f'- {m.get("name", "Unknown")}' for m in members_display])}
{f'... and {len(members) - 10} more' if len(members) > 10 else ''}

Existing list names to avoid: {', '.join(existing_names) if existing_names else 'none'}

Suggest 3 creative but professional names for this broadcast list.

Respond in JSON format:
{{
  "suggestions": ["Name 1", "Name 2", "Name 3"],
  "bestPick": "Name 1",
  "reasoning": "brief explanation"
}}"""

    try:
        c = get_client()
        if c is None:
            raise Exception("AI client not configured")
        
        response = c.models.generate_content(
            model="gemini-2.0-flash",
            contents=prompt
        )
        text = response.text
        
        json_match = text[text.find("{"):text.rfind("}")+1]
        if json_match:
            return json.loads(json_match)
        
        return {
            "suggestions": ["New List", "My Contacts", "Broadcast Group"],
            "bestPick": "New List",
            "reasoning": "Default suggestions"
        }
    except Exception as e:
        raise Exception(f"Failed to generate name with Gemini AI: {str(e)}")


async def get_insights(lists: list) -> dict:
    """Get insights about broadcast list patterns"""
    
    prompt = f"""Analyze these WhatsApp broadcast lists and provide insights:

{chr(10).join([f'- "{l.get("name", "Unknown")}": {len(l.get("members", []))} members' for l in lists])}

Provide brief insights (2-3 points) about:
1. Any patterns you notice
2. Suggestions for better organization
3. Potential improvements

Respond in JSON format:
{{
  "insights": ["insight 1", "insight 2", "insight 3"],
  "recommendation": "main recommendation"
}}"""

    try:
        c = get_client()
        if c is None:
            raise Exception("AI client not configured")
        
        response = c.models.generate_content(
            model="gemini-2.0-flash",
            contents=prompt
        )
        text = response.text
        
        json_match = text[text.find("{"):text.rfind("}")+1]
        if json_match:
            return json.loads(json_match)
        
        return {
            "insights": ["Lists are well organized"],
            "recommendation": "Continue current approach"
        }
    except Exception as e:
        raise Exception(f"Failed to get insights from Gemini AI: {str(e)}")
