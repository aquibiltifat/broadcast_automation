import { useState } from 'react';
import { AutomationConfig, AutomationLog } from '@/types/broadcast';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  Bot,
  Play,
  Settings,
  CheckCircle2,
  XCircle,
  Clock,
  Cpu,
  Zap
} from 'lucide-react';
import { format } from 'date-fns';

interface AutomationPanelProps {
  logs: AutomationLog[];
  onRunAutomation: () => void;
}

export const AutomationPanel = ({ logs, onRunAutomation }: AutomationPanelProps) => {
  const [config, setConfig] = useState<AutomationConfig>({
    enabled: true,
    llmModel: 'Gemini AI',
    autoCreateList: false,
    minimumCommonMembers: 2,
  });

  const getStatusIcon = (status: AutomationLog['status']) => {
    switch (status) {
      case 'success':
        return <CheckCircle2 className="h-4 w-4 text-primary" />;
      case 'error':
        return <XCircle className="h-4 w-4 text-destructive" />;
      case 'pending':
        return <Clock className="h-4 w-4 text-automation animate-pulse" />;
    }
  };

  const getStatusBadge = (status: AutomationLog['status']) => {
    switch (status) {
      case 'success':
        return <Badge className="bg-primary/20 text-primary border-primary/30">Success</Badge>;
      case 'error':
        return <Badge variant="destructive">Error</Badge>;
      case 'pending':
        return <Badge className="bg-automation/20 text-automation border-automation/30">Pending</Badge>;
    }
  };

  return (
    <div className="space-y-4">
      {/* Automation Controls */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Bot className="h-5 w-5 text-llm" />
            LLM Automation
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <Label className="text-base">Enable Automation</Label>
              <p className="text-sm text-muted-foreground">
                Allow LLM to analyze and create lists
              </p>
            </div>
            <Switch
              checked={config.enabled}
              onCheckedChange={(enabled) => setConfig({ ...config, enabled })}
            />
          </div>

          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <Label className="text-base">Auto-Create Lists</Label>
              <p className="text-sm text-muted-foreground">
                Automatically create lists without confirmation
              </p>
            </div>
            <Switch
              checked={config.autoCreateList}
              onCheckedChange={(autoCreateList) => setConfig({ ...config, autoCreateList })}
            />
          </div>

          <div className="p-4 rounded-lg bg-muted/50 border">
            <div className="flex items-center gap-2 mb-2">
              <Cpu className="h-4 w-4 text-llm" />
              <span className="font-medium text-sm">Connected Model</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="font-mono text-sm">{config.llmModel}</span>
              <Badge variant="outline" className="text-xs">
                <Zap className="h-3 w-3 mr-1" />
                Ready
              </Badge>
            </div>
          </div>

          <Button
            onClick={onRunAutomation}
            className="w-full gradient-automation text-white"
            disabled={!config.enabled}
          >
            <Play className="h-4 w-4 mr-2" />
            Run Automation
          </Button>
        </CardContent>
      </Card>

      {/* Activity Log */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Settings className="h-4 w-4" />
            Activity Log
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <ScrollArea className="h-[280px]">
            <div className="p-4 space-y-3">
              {logs.map((log) => (
                <div
                  key={log.id}
                  className="p-3 rounded-lg bg-muted/30 border space-y-2 animate-fade-in"
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex items-center gap-2">
                      {getStatusIcon(log.status)}
                      <span className="font-medium text-sm">{log.action}</span>
                    </div>
                    {getStatusBadge(log.status)}
                  </div>
                  {log.details && (
                    <p className="text-sm text-muted-foreground pl-6">
                      {log.details}
                    </p>
                  )}
                  <p className="text-xs text-muted-foreground pl-6 font-mono">
                    {format(log.timestamp, 'HH:mm:ss')}
                  </p>
                </div>
              ))}
            </div>
          </ScrollArea>
        </CardContent>
      </Card>
    </div>
  );
};
