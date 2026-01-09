import { useState } from 'react';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Plus, X, UserPlus, Sparkles } from 'lucide-react';
import { Contact } from '@/types/broadcast';
import { generateId } from '@/services/storage';
import { apiService } from '@/services/api';
import { useToast } from '@/hooks/use-toast';

interface CreateListDialogProps {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onCreateList: (name: string, contacts: Contact[]) => void;
    existingNames?: string[];
}

export const CreateListDialog = ({
    open,
    onOpenChange,
    onCreateList,
    existingNames = []
}: CreateListDialogProps) => {
    const [listName, setListName] = useState('');
    const [contacts, setContacts] = useState<Contact[]>([]);
    const [newContactName, setNewContactName] = useState('');
    const [newContactPhone, setNewContactPhone] = useState('');
    const [isGeneratingName, setIsGeneratingName] = useState(false);
    const { toast } = useToast();

    const handleAddContact = () => {
        if (!newContactName || !newContactPhone) {
            toast({
                title: "Missing information",
                description: "Please enter both name and phone number",
                variant: "destructive",
            });
            return;
        }

        const contact: Contact = {
            id: generateId('contact'),
            name: newContactName.trim(),
            phone: newContactPhone.trim(),
        };

        setContacts([...contacts, contact]);
        setNewContactName('');
        setNewContactPhone('');
    };

    const handleRemoveContact = (contactId: string) => {
        setContacts(contacts.filter(c => c.id !== contactId));
    };

    const handleSuggestName = async () => {
        if (contacts.length === 0) {
            toast({
                title: "Add contacts first",
                description: "Add some contacts to get AI name suggestions",
                variant: "destructive",
            });
            return;
        }

        setIsGeneratingName(true);
        try {
            const result = await apiService.suggestListName(contacts, existingNames);
            setListName(result.bestPick);
            toast({
                title: "Name suggested!",
                description: result.reasoning,
            });
        } catch (error: any) {
            toast({
                title: "AI unavailable",
                description: error.message || "Could not get AI suggestions",
                variant: "destructive",
            });
        } finally {
            setIsGeneratingName(false);
        }
    };

    const handleCreate = () => {
        if (!listName.trim()) {
            toast({
                title: "Name required",
                description: "Please enter a list name",
                variant: "destructive",
            });
            return;
        }

        if (contacts.length === 0) {
            toast({
                title: "No contacts",
                description: "Please add at least one contact",
                variant: "destructive",
            });
            return;
        }

        onCreateList(listName.trim(), contacts);

        // Reset form
        setListName('');
        setContacts([]);
        setNewContactName('');
        setNewContactPhone('');
        onOpenChange(false);
    };

    const handleKeyPress = (e: React.KeyboardEvent) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            handleAddContact();
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="sm:max-w-md">
                <DialogHeader>
                    <DialogTitle className="flex items-center gap-2">
                        <Plus className="h-5 w-5 text-primary" />
                        Create New List
                    </DialogTitle>
                    <DialogDescription>
                        Create a new broadcast list with contacts
                    </DialogDescription>
                </DialogHeader>

                <div className="space-y-4 py-4">
                    {/* List Name */}
                    <div className="space-y-2">
                        <Label htmlFor="listName">List Name</Label>
                        <div className="flex gap-2">
                            <Input
                                id="listName"
                                value={listName}
                                onChange={(e) => setListName(e.target.value)}
                                placeholder="Enter list name..."
                                className="flex-1"
                            />
                            <Button
                                type="button"
                                variant="outline"
                                size="icon"
                                onClick={handleSuggestName}
                                disabled={isGeneratingName || contacts.length === 0}
                                title="AI suggest name"
                            >
                                <Sparkles className={`h-4 w-4 ${isGeneratingName ? 'animate-pulse' : ''}`} />
                            </Button>
                        </div>
                    </div>

                    {/* Add Contact Form */}
                    <div className="space-y-2">
                        <Label>Add Contacts</Label>
                        <div className="flex gap-2">
                            <Input
                                value={newContactName}
                                onChange={(e) => setNewContactName(e.target.value)}
                                placeholder="Name"
                                className="flex-1"
                                onKeyPress={handleKeyPress}
                            />
                            <Input
                                value={newContactPhone}
                                onChange={(e) => setNewContactPhone(e.target.value)}
                                placeholder="Phone"
                                className="flex-1"
                                onKeyPress={handleKeyPress}
                            />
                            <Button
                                type="button"
                                variant="secondary"
                                size="icon"
                                onClick={handleAddContact}
                            >
                                <UserPlus className="h-4 w-4" />
                            </Button>
                        </div>
                    </div>

                    {/* Contacts List */}
                    {contacts.length > 0 && (
                        <div className="space-y-2">
                            <Label>Contacts ({contacts.length})</Label>
                            <ScrollArea className="h-[150px] rounded-md border p-2">
                                <div className="space-y-2">
                                    {contacts.map((contact) => (
                                        <div
                                            key={contact.id}
                                            className="flex items-center justify-between p-2 rounded bg-muted/50"
                                        >
                                            <div className="flex items-center gap-2">
                                                <div className="h-8 w-8 rounded-full bg-primary/20 flex items-center justify-center text-xs font-medium text-primary">
                                                    {contact.name.split(' ').map(n => n[0]).join('').slice(0, 2)}
                                                </div>
                                                <div>
                                                    <p className="text-sm font-medium">{contact.name}</p>
                                                    <p className="text-xs text-muted-foreground font-mono">{contact.phone}</p>
                                                </div>
                                            </div>
                                            <Button
                                                type="button"
                                                variant="ghost"
                                                size="icon"
                                                className="h-6 w-6"
                                                onClick={() => handleRemoveContact(contact.id)}
                                            >
                                                <X className="h-3 w-3" />
                                            </Button>
                                        </div>
                                    ))}
                                </div>
                            </ScrollArea>
                        </div>
                    )}
                </div>

                <DialogFooter>
                    <Button variant="outline" onClick={() => onOpenChange(false)}>
                        Cancel
                    </Button>
                    <Button onClick={handleCreate} className="gradient-whatsapp">
                        Create List
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};
