import { BroadcastList, Contact, AutomationLog } from '@/types/broadcast';

const generateContacts = (count: number, prefix: string): Contact[] => {
  return Array.from({ length: count }, (_, i) => ({
    id: `${prefix}-${i + 1}`,
    name: `Contact ${prefix.toUpperCase()}${i + 1}`,
    phone: `+91 ${Math.floor(Math.random() * 9000000000) + 1000000000}`,
  }));
};

// Create contacts with some overlap for common members demonstration
const contactsA = generateContacts(8, 'a');
const contactsB = generateContacts(6, 'b');
const contactsC = generateContacts(7, 'c');

// Add some common contacts to each list
const commonContacts: Contact[] = [
  { id: 'common-1', name: 'Rahul Sharma', phone: '+91 9876543210' },
  { id: 'common-2', name: 'Priya Patel', phone: '+91 9123456789' },
  { id: 'common-3', name: 'Amit Kumar', phone: '+91 9988776655' },
];

export const broadcastLists: BroadcastList[] = [
  {
    id: 'list-1',
    name: 'Work Team',
    members: [...contactsA, ...commonContacts],
    createdAt: new Date('2024-12-15'),
  },
  {
    id: 'list-2',
    name: 'Family Group',
    members: [...contactsB, ...commonContacts],
    createdAt: new Date('2024-12-20'),
  },
  {
    id: 'list-3',
    name: 'Friends Circle',
    members: [...contactsC, ...commonContacts],
    createdAt: new Date('2025-01-02'),
  },
];

export const automationLogs: AutomationLog[] = [
  {
    id: 'log-1',
    timestamp: new Date('2025-01-07T10:30:00'),
    action: 'Scanned broadcast lists',
    status: 'success',
    details: 'Found 3 broadcast lists with 24 total members',
  },
  {
    id: 'log-2',
    timestamp: new Date('2025-01-07T10:31:00'),
    action: 'Extracted contact data',
    status: 'success',
    details: 'Successfully extracted data from WhatsApp',
  },
  {
    id: 'log-3',
    timestamp: new Date('2025-01-07T10:32:00'),
    action: 'LLM processing common members',
    status: 'success',
    details: 'Identified 3 common members across all lists',
  },
  {
    id: 'log-4',
    timestamp: new Date('2025-01-07T10:33:00'),
    action: 'Creating new broadcast list',
    status: 'pending',
    details: 'Awaiting user confirmation...',
  },
];

export const findCommonMembers = (lists: BroadcastList[]): Contact[] => {
  if (lists.length === 0) return [];
  
  const firstListPhones = new Set(lists[0].members.map(m => m.phone));
  
  return lists[0].members.filter(member =>
    lists.every(list => list.members.some(m => m.phone === member.phone))
  );
};
