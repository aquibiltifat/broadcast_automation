import { GoogleGenerativeAI } from '@google/generative-ai';
import dotenv from 'dotenv';

dotenv.config();

const genAI = new GoogleGenerativeAI(process.env.GOOGLE_AI_API_KEY);

// Use Gemini model (Gemma is not available via Google AI Studio API)
const model = genAI.getGenerativeModel({ model: 'gemini-2.0-flash' });

/**
 * Analyze broadcast lists and find common member insights
 */
export async function analyzeCommonMembers(lists, commonMembers) {
    const prompt = `You are an AI assistant helping analyze WhatsApp broadcast lists.

Given these broadcast lists:
${lists.map(l => `- "${l.name}" with ${l.members.length} members`).join('\n')}

And these common members found across all selected lists:
${commonMembers.map(m => `- ${m.name} (${m.phone})`).join('\n')}

Provide a brief analysis (2-3 sentences) about:
1. What type of group these common members might represent
2. A suggested name for a new broadcast list containing these common members

Respond in JSON format:
{
  "analysis": "your analysis here",
  "suggestedName": "suggested list name",
  "confidence": "high/medium/low"
}`;

    try {
        const result = await model.generateContent(prompt);
        const response = await result.response;
        const text = response.text();

        // Extract JSON from response
        const jsonMatch = text.match(/\{[\s\S]*\}/);
        if (jsonMatch) {
            return JSON.parse(jsonMatch[0]);
        }

        return {
            analysis: text,
            suggestedName: 'Common Members',
            confidence: 'medium'
        };
    } catch (error) {
        console.error('Gemma AI Error:', error);
        throw new Error('Failed to analyze with Gemma AI: ' + error.message);
    }
}

/**
 * Generate a smart name for a broadcast list based on its members
 */
export async function suggestListName(members, existingNames = []) {
    const prompt = `You are helping name a WhatsApp broadcast list.

The list contains these members:
${members.slice(0, 10).map(m => `- ${m.name}`).join('\n')}
${members.length > 10 ? `... and ${members.length - 10} more` : ''}

Existing list names to avoid: ${existingNames.join(', ') || 'none'}

Suggest 3 creative but professional names for this broadcast list.

Respond in JSON format:
{
  "suggestions": ["Name 1", "Name 2", "Name 3"],
  "bestPick": "Name 1",
  "reasoning": "brief explanation"
}`;

    try {
        const result = await model.generateContent(prompt);
        const response = await result.response;
        const text = response.text();

        const jsonMatch = text.match(/\{[\s\S]*\}/);
        if (jsonMatch) {
            return JSON.parse(jsonMatch[0]);
        }

        return {
            suggestions: ['New List', 'My Contacts', 'Broadcast Group'],
            bestPick: 'New List',
            reasoning: 'Default suggestions'
        };
    } catch (error) {
        console.error('Gemma AI Error:', error);
        throw new Error('Failed to generate name with Gemma AI: ' + error.message);
    }
}

/**
 * Get insights about broadcast list patterns
 */
export async function getInsights(lists) {
    const prompt = `Analyze these WhatsApp broadcast lists and provide insights:

${lists.map(l => `- "${l.name}": ${l.members.length} members, created ${new Date(l.createdAt).toLocaleDateString()}`).join('\n')}

Provide brief insights (2-3 points) about:
1. Any patterns you notice
2. Suggestions for better organization
3. Potential improvements

Respond in JSON format:
{
  "insights": ["insight 1", "insight 2", "insight 3"],
  "recommendation": "main recommendation"
}`;

    try {
        const result = await model.generateContent(prompt);
        const response = await result.response;
        const text = response.text();

        const jsonMatch = text.match(/\{[\s\S]*\}/);
        if (jsonMatch) {
            return JSON.parse(jsonMatch[0]);
        }

        return {
            insights: ['Lists are well organized'],
            recommendation: 'Continue current approach'
        };
    } catch (error) {
        console.error('Gemma AI Error:', error);
        throw new Error('Failed to get insights from Gemma AI: ' + error.message);
    }
}

/**
 * Check if API key is configured
 */
export function isConfigured() {
    return !!process.env.GOOGLE_AI_API_KEY &&
        process.env.GOOGLE_AI_API_KEY !== 'your_api_key_here';
}
