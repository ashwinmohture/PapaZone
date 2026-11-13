import React, { useEffect, useRef, useState } from 'react';
import { Bot, Send, X, Plus, Check, Sparkles } from 'lucide-react';

import api from '../api';
import {
    askAssistant,
    cartCustomerId,
    errorMessage,
    money
} from './aiApi';
import './ai.css';

const GREETING = {
    role: 'assistant',
    content: 'Hi! I\'m Papa 🍕 Tell me what you feel like and your budget, and I\'ll suggest something.'
};

const QUICK_PROMPTS = [
    'Suggest something spicy under ₹300',
    'Vegetarian options',
    'Something sweet',
    'Best for sharing'
];

/**
 * Feature 1 - AI food ordering assistant.
 * The AI only suggests. Nothing reaches the cart until the customer presses "Add".
 */
export default function AiChatWidget({ onCartChange }) {
    const [open, setOpen] = useState(false);
    const [messages, setMessages] = useState([GREETING]);
    const [input, setInput] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const [added, setAdded] = useState({});

    const bottomRef = useRef(null);

    useEffect(() => {
        bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages, busy, open]);

    async function send(text) {
        const content = text.trim();
        if (!content || busy) return;

        const next = [...messages, { role: 'user', content }];
        setMessages(next);
        setInput('');
        setError('');
        setBusy(true);

        try {
            // Only role + content go to the server (suggestion cards stay in the UI).
            const payload = next
                .filter(m => m !== GREETING)
                .map(({ role, content }) => ({ role, content }));

            const res = await askAssistant(payload);

            setMessages([
                ...next,
                { role: 'assistant', content: res.reply, items: res.items || [] }
            ]);
        } catch (e) {
            setError(errorMessage(e));
        } finally {
            setBusy(false);
        }
    }

    async function addToCart(item) {
        try {
            await api.post(`/cart/${cartCustomerId()}/items`, {
                menuItemId: item.id,
                quantity: 1
            });

            setAdded(a => ({ ...a, [item.id]: true }));
            onCartChange?.();
        } catch (e) {
            setError('Could not add that item to your cart. Please try again.');
        }
    }

    function onKeyDown(e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            send(input);
        }
    }

    if (!open) {
        return (
            <button
                className="ai-fab"
                onClick={() => setOpen(true)}
                aria-label="Open Papa, the AI food assistant"
            >
                <Sparkles size={20} />
                Ask Papa
            </button>
        );
    }

    return (
        <section className="ai-panel" role="dialog" aria-label="Papa, AI food assistant">
            <div className="ai-head">
                <span>
                    <Bot size={18} /> Papa · AI assistant
                </span>
                <button onClick={() => setOpen(false)} aria-label="Close assistant">
                    <X size={18} />
                </button>
            </div>

            <div className="ai-body" aria-live="polite">
                {messages.map((m, i) => (
                    <div key={i} className={`ai-msg ${m.role}`}>
                        <p>{m.content}</p>

                        {m.items?.length > 0 && (
                            <div className="ai-items">
                                {m.items.map(item => (
                                    <div className="ai-item" key={item.id}>
                                        {item.imageUrl && (
                                            <img src={item.imageUrl} alt="" loading="lazy" />
                                        )}

                                        <div>
                                            <strong>{item.name}</strong>
                                            <small>
                                                {item.category} · {money(item.price)}
                                            </small>
                                        </div>

                                        <button
                                            className={added[item.id] ? 'ai-add done' : 'ai-add'}
                                            onClick={() => addToCart(item)}
                                            disabled={added[item.id]}
                                        >
                                            {added[item.id] ? (
                                                <><Check size={14} /> Added</>
                                            ) : (
                                                <><Plus size={14} /> Add</>
                                            )}
                                        </button>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                ))}

                {messages.length === 1 && (
                    <div className="ai-quick">
                        {QUICK_PROMPTS.map(p => (
                            <button key={p} onClick={() => send(p)}>
                                {p}
                            </button>
                        ))}
                    </div>
                )}

                {busy && <div className="ai-msg assistant ai-typing">Papa is thinking…</div>}
                {error && <p className="ai-error">{error}</p>}

                <div ref={bottomRef} />
            </div>

            <div className="ai-input">
                <textarea
                    rows={1}
                    maxLength={500}
                    placeholder="e.g. something cheesy under ₹400"
                    value={input}
                    onChange={e => setInput(e.target.value)}
                    onKeyDown={onKeyDown}
                    aria-label="Message to Papa"
                />

                <button onClick={() => send(input)} disabled={busy || !input.trim()} aria-label="Send">
                    <Send size={17} />
                </button>
            </div>
        </section>
    );
}
