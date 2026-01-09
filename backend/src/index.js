import express from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import { analyzeCommonMembers, suggestListName, getInsights, isConfigured } from './services/gemma.js';

dotenv.config();

const app = express();
const PORT = process.env.PORT || 3001;

// Middleware
app.use(cors({
    origin: ['http://localhost:8080', 'http://localhost:5173', 'http://127.0.0.1:8080'],
    methods: ['GET', 'POST'],
    credentials: true
}));
app.use(express.json());

// Health check
app.get('/api/health', (req, res) => {
    res.json({
        status: 'ok',
        timestamp: new Date().toISOString(),
        aiConfigured: isConfigured()
    });
});

// Check AI configuration
app.get('/api/ai/status', (req, res) => {
    res.json({
        configured: isConfigured(),
        model: 'gemma-2-9b-it',
        provider: 'Google AI Studio'
    });
});

// Analyze common members with AI
app.post('/api/analyze', async (req, res) => {
    try {
        if (!isConfigured()) {
            return res.status(503).json({
                error: 'AI not configured',
                message: 'Please add GOOGLE_AI_API_KEY to backend/.env file'
            });
        }

        const { lists, commonMembers } = req.body;

        if (!lists || !commonMembers) {
            return res.status(400).json({
                error: 'Missing required fields: lists and commonMembers'
            });
        }

        const analysis = await analyzeCommonMembers(lists, commonMembers);
        res.json({ success: true, data: analysis });
    } catch (error) {
        console.error('Analyze error:', error);
        res.status(500).json({
            error: 'Analysis failed',
            message: error.message
        });
    }
});

// Suggest list name with AI
app.post('/api/suggest-name', async (req, res) => {
    try {
        if (!isConfigured()) {
            return res.status(503).json({
                error: 'AI not configured',
                message: 'Please add GOOGLE_AI_API_KEY to backend/.env file'
            });
        }

        const { members, existingNames } = req.body;

        if (!members || !Array.isArray(members)) {
            return res.status(400).json({
                error: 'Missing required field: members (array)'
            });
        }

        const suggestions = await suggestListName(members, existingNames || []);
        res.json({ success: true, data: suggestions });
    } catch (error) {
        console.error('Suggest name error:', error);
        res.status(500).json({
            error: 'Name suggestion failed',
            message: error.message
        });
    }
});

// Get insights about lists
app.post('/api/insights', async (req, res) => {
    try {
        if (!isConfigured()) {
            return res.status(503).json({
                error: 'AI not configured',
                message: 'Please add GOOGLE_AI_API_KEY to backend/.env file'
            });
        }

        const { lists } = req.body;

        if (!lists || !Array.isArray(lists)) {
            return res.status(400).json({
                error: 'Missing required field: lists (array)'
            });
        }

        const insights = await getInsights(lists);
        res.json({ success: true, data: insights });
    } catch (error) {
        console.error('Insights error:', error);
        res.status(500).json({
            error: 'Insights failed',
            message: error.message
        });
    }
});

// Start server
app.listen(PORT, () => {
    console.log(`🚀 Group Weaver AI Backend running on http://localhost:${PORT}`);
    console.log(`📊 Health check: http://localhost:${PORT}/api/health`);

    if (!isConfigured()) {
        console.log('\n⚠️  WARNING: AI not configured!');
        console.log('   Copy .env.example to .env and add your Google AI API key');
        console.log('   Get your key at: https://aistudio.google.com/apikey\n');
    } else {
        console.log('✅ Gemma AI configured and ready');
    }
});
