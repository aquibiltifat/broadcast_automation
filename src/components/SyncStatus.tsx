import { useEffect, useState } from 'react';
import { Bell, RefreshCw, Smartphone, CheckCircle2, XCircle } from 'lucide-react';

interface SyncEvent {
    type: string;
    device_id?: string;
    lists_count?: number;
    members_count?: number;
    action?: string;
    details?: string;
    timestamp: string;
}

interface SyncStatusProps {
    isConnected: boolean;
    lastSync?: SyncEvent | null;
    onDismiss?: () => void;
}

export const SyncStatus = ({ isConnected, lastSync, onDismiss }: SyncStatusProps) => {
    const [visible, setVisible] = useState(false);
    const [animating, setAnimating] = useState(false);

    useEffect(() => {
        if (lastSync && lastSync.type === 'sync') {
            setVisible(true);
            setAnimating(true);

            // Hide after 5 seconds
            const timer = setTimeout(() => {
                setVisible(false);
                onDismiss?.();
            }, 5000);

            // Stop animation after 1 second
            const animTimer = setTimeout(() => setAnimating(false), 1000);

            return () => {
                clearTimeout(timer);
                clearTimeout(animTimer);
            };
        }
    }, [lastSync, onDismiss]);

    if (!visible || !lastSync) return null;

    const formatTime = (timestamp: string) => {
        const date = new Date(timestamp);
        return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    };

    return (
        <div
            className={`fixed bottom-6 right-6 z-50 transition-all duration-500 ${visible ? 'translate-y-0 opacity-100' : 'translate-y-4 opacity-0'
                }`}
        >
            <div className={`
        bg-gradient-to-r from-emerald-500/20 to-purple-500/20 
        backdrop-blur-xl border border-emerald-500/30 
        rounded-2xl p-4 shadow-2xl shadow-emerald-500/10
        max-w-sm
        ${animating ? 'animate-bounce-subtle' : ''}
      `}>
                <div className="flex items-start gap-3">
                    <div className="h-10 w-10 rounded-xl bg-emerald-500/20 flex items-center justify-center flex-shrink-0">
                        <Smartphone className="h-5 w-5 text-emerald-400" />
                    </div>

                    <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                            <CheckCircle2 className="h-4 w-4 text-emerald-400" />
                            <span className="font-semibold text-white text-sm">Sync Received!</span>
                        </div>

                        <p className="text-xs text-gray-400 mt-1">
                            <span className="text-emerald-400 font-medium">{lastSync.device_id}</span> synced{' '}
                            <span className="text-purple-400 font-medium">{lastSync.lists_count} lists</span> with{' '}
                            <span className="text-purple-400 font-medium">{lastSync.members_count} members</span>
                        </p>

                        <p className="text-xs text-gray-500 mt-1">
                            {formatTime(lastSync.timestamp)}
                        </p>
                    </div>

                    <button
                        onClick={() => {
                            setVisible(false);
                            onDismiss?.();
                        }}
                        className="text-gray-400 hover:text-white transition-colors"
                    >
                        <XCircle className="h-4 w-4" />
                    </button>
                </div>
            </div>
        </div>
    );
};

// Hook for WebSocket connection
export const useWebSocket = (url: string) => {
    const [isConnected, setIsConnected] = useState(false);
    const [lastSync, setLastSync] = useState<SyncEvent | null>(null);
    const [socket, setSocket] = useState<WebSocket | null>(null);

    useEffect(() => {
        let ws: WebSocket | null = null;
        let reconnectTimer: NodeJS.Timeout;

        const connect = () => {
            try {
                ws = new WebSocket(url);

                ws.onopen = () => {
                    console.log('WebSocket connected');
                    setIsConnected(true);
                };

                ws.onclose = () => {
                    console.log('WebSocket disconnected');
                    setIsConnected(false);
                    // Reconnect after 3 seconds
                    reconnectTimer = setTimeout(connect, 3000);
                };

                ws.onerror = (error) => {
                    console.error('WebSocket error:', error);
                    setIsConnected(false);
                };

                ws.onmessage = (event) => {
                    try {
                        const data = JSON.parse(event.data);
                        console.log('WebSocket message:', data);

                        if (data.type === 'sync' || data.type === 'data_change') {
                            setLastSync(data);
                        }
                    } catch (e) {
                        console.error('Failed to parse WebSocket message:', e);
                    }
                };

                setSocket(ws);
            } catch (error) {
                console.error('WebSocket connection failed:', error);
                reconnectTimer = setTimeout(connect, 3000);
            }
        };

        connect();

        return () => {
            clearTimeout(reconnectTimer);
            if (ws) {
                ws.close();
            }
        };
    }, [url]);

    const sendMessage = (message: object) => {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify(message));
        }
    };

    return { isConnected, lastSync, sendMessage, clearLastSync: () => setLastSync(null) };
};
