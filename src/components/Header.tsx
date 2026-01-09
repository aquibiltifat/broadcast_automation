import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { MessageSquare, Smartphone, Wifi, WifiOff, Zap } from 'lucide-react';

interface HeaderProps {
  isConnected: boolean;
  deviceName?: string;
  onDeviceClick?: () => void;
}

export const Header = ({ isConnected, deviceName, onDeviceClick }: HeaderProps) => {
  return (
    <header className="sticky top-0 z-50 header-glassmorphism border-b border-white/5">
      <div className="container flex items-center justify-between h-16 px-4">
        <div className="flex items-center gap-3">
          <div className="h-11 w-11 rounded-2xl bg-gradient-to-br from-emerald-400 to-emerald-600 flex items-center justify-center shadow-lg shadow-emerald-500/20 relative">
            <MessageSquare className="h-5 w-5 text-white" />
            <div className="absolute -top-1 -right-1 h-3 w-3 bg-purple-500 rounded-full animate-pulse" />
          </div>
          <div>
            <h1 className="font-bold text-xl leading-tight bg-gradient-to-r from-white to-gray-300 bg-clip-text text-transparent">
              Group Weaver AI
            </h1>
            <p className="text-xs text-muted-foreground">WhatsApp Broadcast Extractor</p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          {/* Connection Status */}
          <Badge
            variant="outline"
            className={`gap-1.5 px-3 py-1 transition-all duration-300 ${isConnected
                ? 'border-emerald-500/50 text-emerald-400 bg-emerald-500/10'
                : 'border-red-500/50 text-red-400 bg-red-500/10'
              }`}
          >
            {isConnected ? (
              <>
                <span className="relative flex h-2 w-2">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
                </span>
                <span className="hidden sm:inline">Live</span>
              </>
            ) : (
              <>
                <WifiOff className="h-3 w-3" />
                <span className="hidden sm:inline">Offline</span>
              </>
            )}
          </Badge>

          {/* Device Button */}
          <Button
            variant="outline"
            size="sm"
            className="gap-2 border-purple-500/30 hover:border-purple-500/50 hover:bg-purple-500/10 transition-all"
            onClick={onDeviceClick}
          >
            <Smartphone className="h-4 w-4 text-purple-400" />
            <span className="hidden sm:inline text-purple-300">
              {deviceName || 'No Device'}
            </span>
          </Button>

          {/* Auto-Sync Indicator */}
          {isConnected && (
            <div className="hidden md:flex items-center gap-2 text-xs text-muted-foreground">
              <Zap className="h-3 w-3 text-yellow-400" />
              <span>Auto-sync</span>
            </div>
          )}
        </div>
      </div>
    </header>
  );
};
