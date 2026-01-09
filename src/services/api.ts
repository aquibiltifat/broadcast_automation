const API_BASE_URL = 'http://localhost:3001/api';

export interface AIAnalysisResult {
    analysis: string;
    suggestedName: string;
    confidence: 'high' | 'medium' | 'low';
}

export interface NameSuggestionResult {
    suggestions: string[];
    bestPick: string;
    reasoning: string;
}

export interface InsightsResult {
    insights: string[];
    recommendation: string;
}

export interface AIStatus {
    configured: boolean;
    model: string;
    provider: string;
}

class ApiService {
    private async fetchApi<T>(endpoint: string, options?: RequestInit): Promise<T> {
        const response = await fetch(`${API_BASE_URL}${endpoint}`, {
            headers: {
                'Content-Type': 'application/json',
            },
            ...options,
        });

        if (!response.ok) {
            const error = await response.json().catch(() => ({ message: 'Request failed' }));
            throw new Error(error.message || `HTTP ${response.status}`);
        }

        return response.json();
    }

    async checkHealth(): Promise<{ status: string; aiConfigured: boolean }> {
        return this.fetchApi('/health');
    }

    async getAIStatus(): Promise<AIStatus> {
        return this.fetchApi('/ai/status');
    }

    async analyzeCommonMembers(
        lists: { name: string; members: { name: string; phone: string }[] }[],
        commonMembers: { name: string; phone: string }[]
    ): Promise<AIAnalysisResult> {
        const response = await this.fetchApi<{ success: boolean; data: AIAnalysisResult }>('/analyze', {
            method: 'POST',
            body: JSON.stringify({ lists, commonMembers }),
        });
        return response.data;
    }

    async suggestListName(
        members: { name: string; phone: string }[],
        existingNames?: string[]
    ): Promise<NameSuggestionResult> {
        const response = await this.fetchApi<{ success: boolean; data: NameSuggestionResult }>('/suggest-name', {
            method: 'POST',
            body: JSON.stringify({ members, existingNames }),
        });
        return response.data;
    }

    async getInsights(
        lists: { name: string; members: { name: string; phone: string }[]; createdAt: Date }[]
    ): Promise<InsightsResult> {
        const response = await this.fetchApi<{ success: boolean; data: InsightsResult }>('/insights', {
            method: 'POST',
            body: JSON.stringify({ lists }),
        });
        return response.data;
    }
}

export const apiService = new ApiService();
