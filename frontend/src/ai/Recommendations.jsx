import React, { useEffect, useState } from 'react';
import { Plus, Sparkles } from 'lucide-react';

import api from '../api';
import {
    cartCustomerId,
    getRecommendations,
    isLoggedIn,
    money
} from './aiApi';
import './ai.css';

const FALLBACK_IMG =
    'https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600';

const BADGE = {
    REORDER: 'Order again',
    SIMILAR: 'You might like',
    POPULAR: 'Popular',
    MENU: 'From our menu'
};

/**
 * Feature 2 - personalised recommendations.
 * Logged-in customers get picks based on past orders; visitors get popular items.
 * No LLM: results are deterministic and always come from the real menu.
 */
export default function Recommendations({ onAdd }) {
    const [items, setItems] = useState([]);
    const [note, setNote] = useState('');
    const loggedIn = isLoggedIn();

    useEffect(() => {
        getRecommendations(6)
            .then(setItems)
            .catch(() => setItems([])); // optional section: fail silently
    }, [loggedIn]);

    async function add(item) {
        try {
            await api.post(`/cart/${cartCustomerId()}/items`, {
                menuItemId: item.id,
                quantity: 1
            });
            setNote(`${item.name} added to cart`);
            onAdd?.();
        } catch (e) {
            setNote('Could not add item. Please try again.');
        }
    }

    if (!items.length) return null;

    return (
        <section className="menu-section ai-reco">
            <div className="section-head">
                <div>
                    <span className="eyebrow">
                        <Sparkles size={12} /> {loggedIn ? 'PICKED FOR YOU' : 'TRENDING'}
                    </span>
                    <h2>{loggedIn ? 'Recommended for you' : 'Popular right now'}</h2>
                </div>
            </div>

            {note && <p className="notice">{note}</p>}

            <div className="menu-grid">
                {items.map(item => (
                    <article className="food-card" key={item.id}>
                        <div className="food-img">
                            <img
                                src={item.imageUrl || FALLBACK_IMG}
                                alt={item.name}
                                onError={e => {
                                    e.currentTarget.src = FALLBACK_IMG;
                                }}
                            />
                            <span>{BADGE[item.kind] || item.category}</span>
                        </div>

                        <div className="food-info">
                            <h3>{item.name}</h3>
                            <p className="ai-reason">{item.reason}</p>

                            <div className="food-bottom">
                                <strong>{money(item.price)}</strong>

                                <button className="add" onClick={() => add(item)}>
                                    <Plus size={17} /> Add
                                </button>
                            </div>
                        </div>
                    </article>
                ))}
            </div>
        </section>
    );
}
