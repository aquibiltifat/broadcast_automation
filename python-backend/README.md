# Group Weaver AI - Python Backend

FastAPI backend for WhatsApp broadcast list management with Gemini AI.

## Setup

```bash
cd python-backend

# Create virtual environment
python -m venv venv

# Activate (Windows)
venv\Scripts\activate

# Activate (Linux/Mac)
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Copy env file
copy .env.example .env  # Windows
cp .env.example .env    # Linux/Mac

# Add your Google AI API key to .env
# GOOGLE_AI_API_KEY=your_key_here

# Run server
python main.py
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /api/health | Health check |
| GET | /api/ai/status | AI configuration status |
| POST | /api/sync | Sync from Android app |
| GET | /api/lists | Get all broadcast lists |
| POST | /api/lists | Create new list |
| DELETE | /api/lists/{id} | Delete list |
| POST | /api/analyze | AI analysis |
| POST | /api/suggest-name | AI name suggestions |
| GET | /api/logs | Get automation logs |

## Default Port
- Server runs on `http://localhost:3002`
- Change in `.env` file with `PORT=xxxx`
