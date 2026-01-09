import { Contact } from '@/types/broadcast';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { UserCheck, Plus, Phone, Copy, CheckCircle } from 'lucide-react';
import { useState } from 'react';
import { useToast } from '@/hooks/use-toast';

interface ExtendedContact extends Contact {
  appears_in?: number;
  list_names?: string[];
}

interface CommonMembersPanelProps {
  members: ExtendedContact[];
  onCreateList: () => void;
}

export const CommonMembersPanel = ({ members, onCreateList }: CommonMembersPanelProps) => {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const { toast } = useToast();

  const copyPhoneNumber = (phone: string, memberId: string) => {
    if (!phone) return;
    navigator.clipboard.writeText(phone);
    setCopiedId(memberId);
    setTimeout(() => setCopiedId(null), 2000);
    toast({
      title: "Copied!",
      description: `${phone} copied to clipboard`,
    });
  };

  const copyAllMembers = () => {
    const text = members
      .map(m => `${m.name}: ${m.phone || 'No phone'}`)
      .join('\n');
    navigator.clipboard.writeText(text);
    toast({
      title: "All members copied!",
      description: `${members.length} members copied to clipboard`,
    });
  };

  if (members.length === 0) {
    return (
      <Card className="glass-card border-dashed border-purple-500/30">
        <CardContent className="flex flex-col items-center justify-center py-12 text-center">
          <div className="h-16 w-16 rounded-2xl bg-purple-500/20 flex items-center justify-center mb-4">
            <UserCheck className="h-8 w-8 text-purple-400" />
          </div>
          <p className="text-muted-foreground">
            No common members found across lists
          </p>
          <p className="text-sm text-muted-foreground mt-2">
            Extract at least 2 broadcast lists to find common members
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="glass-card border-purple-500/30">
      <CardHeader>
        <div className="flex items-center justify-between flex-wrap gap-3">
          <CardTitle className="flex items-center gap-2">
            <div className="h-8 w-8 rounded-lg bg-purple-500/20 flex items-center justify-center">
              <UserCheck className="h-4 w-4 text-purple-400" />
            </div>
            <span className="text-white">Common Members</span>
            <Badge variant="secondary" className="ml-2 bg-purple-500/20 text-purple-300">
              {members.length} found
            </Badge>
          </CardTitle>
          <div className="flex gap-2">
            <Button
              variant="outline"
              onClick={copyAllMembers}
              className="border-purple-500/30 hover:bg-purple-500/10"
            >
              <Copy className="h-4 w-4 mr-2" />
              Copy All
            </Button>
            <Button
              onClick={onCreateList}
              className="bg-gradient-to-r from-emerald-500 to-emerald-600 hover:from-emerald-600 hover:to-emerald-700"
            >
              <Plus className="h-4 w-4 mr-2" />
              Create List
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent>
        <div className="space-y-3 max-h-[500px] overflow-y-auto pr-2">
          {members.map((member, index) => (
            <div
              key={member.id || index}
              className="flex items-center gap-3 p-4 rounded-xl bg-white/5 border border-white/5 transition-all hover:border-purple-500/30 hover:bg-purple-500/5"
            >
              {/* Avatar */}
              <div className="h-12 w-12 rounded-xl bg-gradient-to-br from-purple-500 to-emerald-500 flex items-center justify-center text-sm font-bold text-white flex-shrink-0">
                {member.name.split(' ').map(n => n[0]).join('').slice(0, 2).toUpperCase()}
              </div>

              {/* Member Info */}
              <div className="flex-1 min-w-0">
                <p className="font-semibold text-white truncate">{member.name}</p>
                {member.phone ? (
                  <button
                    onClick={() => copyPhoneNumber(member.phone, member.id)}
                    className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-purple-300 transition-colors mt-1"
                  >
                    {copiedId === member.id ? (
                      <CheckCircle className="h-3 w-3 text-emerald-400" />
                    ) : (
                      <Phone className="h-3 w-3" />
                    )}
                    <span className="font-mono">{member.phone}</span>
                  </button>
                ) : (
                  <p className="text-sm text-muted-foreground mt-1">No phone number</p>
                )}

                {/* Lists the member appears in */}
                {member.list_names && member.list_names.length > 0 && (
                  <div className="flex flex-wrap gap-1 mt-2">
                    {member.list_names.map((listName, i) => (
                      <Badge
                        key={i}
                        variant="outline"
                        className="text-xs bg-white/5 border-white/10 text-gray-400"
                      >
                        {listName}
                      </Badge>
                    ))}
                  </div>
                )}
              </div>

              {/* Appears in count */}
              <div className="text-center flex-shrink-0">
                <div className="text-lg font-bold text-purple-400">
                  {member.appears_in || 2}
                </div>
                <div className="text-xs text-muted-foreground">lists</div>
              </div>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
};
