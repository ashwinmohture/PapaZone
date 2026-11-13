import React, { useState } from 'react';
import { BarChart3, Send } from 'lucide-react';

import { askAdminAssistant, errorMessage, isAdmin, money } from './aiApi';
import './ai.css';

const EXAMPLES = [
    'How many orders did we receive today?',
    'Which pizza is selling the most?',
    "Show this month's revenue.",
    'Compare revenue today vs yesterday'
];

const MONEY_FIELDS = new Set(['revenue', 'averageOrderValue']);
const label = key => key.replace(/([A-Z])/g, ' $1').replace(/^./, c => c.toUpperCase());

function ResultCard({ result }) {
    const { metric, period, fromDate, toDate, items, byStatus, error, currency, ...rest } = result;
    const scalars = Object.entries(rest).filter(([, v]) => typeof v !== 'object');

    return (
        <div className="ai-result">
            <h4>
                {label(metric)} · {label(period)}
                <small>
                    {fromDate === toDate ? fromDate : `${fromDate} → ${toDate}`}
                </small>
            </h4>

            {scalars.map(([k, v]) => (
                <div className="ai-kv" key={k}>
                    <span>{label(k)}</span>
                    <b>{MONEY_FIELDS.has(k) ? money(v) : String(v)}</b>
                </div>
            ))}

            {byStatus &&
                Object.entries(byStatus).map(([k, v]) => (
                    <div className="ai-kv" key={k}>
                        <span>{k}</span>
                        <b>{v}</b>
                    </div>
                ))}

            {items &&
                (items.length ? (
                    <table>
                        <thead>
                            <tr><th>Item</th><th>Units</th><th>Revenue</th></tr>
                        </thead>
                        <tbody>
                            {items.map(i => (
                                <tr key={i.name}>
                                    <td>{i.name}</td>
                                    <td>{i.unitsSold}</td>
                                    <td>{money(i.revenue)}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                ) : (
                    <p>No sales in this period.</p>
                ))}
        </div>
    );
}

/**
 * Feature 6 - AI admin assistant.
 * The AI can only run predefined, read-only analytics. The exact figures are
 * shown under the answer so the admin can verify the wording.
 */
export default function AdminAssistant() {
    const [question, setQuestion] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const [answer, setAnswer] = useState(null);

    async function ask(text) {
        const q = text.trim();
        if (!q || busy) return;

        setQuestion(q);
        setBusy(true);
        setError('');
        setAnswer(null);

        try {
            setAnswer(await askAdminAssistant(q));
        } catch (e) {
            setError(errorMessage(e));
        } finally {
            setBusy(false);
        }
    }

    return (
        <div className="checkout-card ai-admin">
            <h3>
                <BarChart3 size={18} /> Ask the AI assistant
            </h3>

            {!isAdmin() && (
                <p className="notice">
                    Log in with an admin account to use the AI assistant.
                </p>
            )}

            <div className="ai-quick">
                {EXAMPLES.map(x => (
                    <button key={x} onClick={() => ask(x)} disabled={busy}>
                        {x}
                    </button>
                ))}
            </div>

            <div className="ai-askrow">
                <input
                    maxLength={500}
                    placeholder="Ask about orders, revenue, best sellers…"
                    value={question}
                    onChange={e => setQuestion(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && ask(question)}
                />
                <button className="primary" onClick={() => ask(question)} disabled={busy || !question.trim()}>
                    <Send size={15} /> {busy ? 'Thinking…' : 'Ask'}
                </button>
            </div>

            {error && <p className="ai-error">{error}</p>}

            {answer && (
                <>
                    <p className="ai-answer">{answer.answer}</p>
                    {answer.results?.map((r, i) => (
                        <ResultCard key={i} result={r} />
                    ))}
                </>
            )}
        </div>
    );
}
