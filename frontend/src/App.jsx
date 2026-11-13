import React, { useEffect, useState } from 'react';
import {
    Routes,
    Route,
    Link,
    useNavigate,
    useParams
} from 'react-router-dom';

import {
    ShoppingCart,
    User,
    MapPin,
    Plus,
    Minus,
    Trash2,
    Pizza,
    ClipboardList,
    ChefHat,
    Search,
    ArrowRight
} from 'lucide-react';

import api from './api';
import AiChatWidget from './ai/AiChatWidget';
import Recommendations from './ai/Recommendations';
import AdminAssistant from './ai/AdminAssistant';

const money = n => '₹' + Number(n || 0).toFixed(2);
function parseOrderItems(itemsJson) {
    if (!itemsJson) return [];

    // Handle valid JSON format
    try {
        const parsed = JSON.parse(itemsJson);
        if (Array.isArray(parsed)) return parsed;
    } catch (error) {
        // Continue with Java List.toString() format
    }

    // Handle Java format:
    // [{id=3, customerId=1, menuItemId=6, name=Coke, unitPrice=59.0, quantity=1}]
    const matches = [...itemsJson.matchAll(/\{([^{}]+)\}/g)];

    return matches.map(match => {
        const item = match[1];

        const name = item.match(/name=(.*?),\s*unitPrice=/);
        const price = item.match(/unitPrice=([\d.]+)/);
        const quantity = item.match(/quantity=(\d+)/);

        return {
            name: name?.[1]?.trim() || 'Unknown item',
            unitPrice: Number(price?.[1] || 0),
            quantity: Number(quantity?.[1] || 0)
        };
    });
}

const demoId = () => Number(localStorage.getItem('customerId') || 1);


// ================= HEADER =================

function Header({ count }) {
    return (
        <header>
            <Link className="brand" to="/">
                <span className="brandmark">P</span>

                <span>
                    Papa<span className="red">Zone</span>
                    <small>HOT • FRESH • DELIVERED</small>
                </span>
            </Link>

            <nav>
                <Link to="/">Menu</Link>
                <Link to="/orders">My Orders</Link>
                <Link to="/admin">Admin</Link>
            </nav>

            <div className="navright">
                <Link className="cartlink" to="/cart">
                    <ShoppingCart size={19} />
                    Cart <b>{count}</b>
                </Link>

                <Link className="userlink" to="/login">
                    <User size={18} />
                </Link>
            </div>
        </header>
    );
}


// ================= MAIN APP =================

function App() {
    const [count, setCount] = useState(0);

    async function refresh() {
        try {
            const response = await api.get(`/cart/${demoId()}`);
            setCount(response.data.itemCount || 0);
        } catch (error) {
            console.error('Cart refresh failed:', error);
        }
    }

    useEffect(() => {
        refresh();
    }, []);

    return (
        <>
            <Header count={count} />

            <Routes>
                <Route path="/" element={<Menu onAdd={refresh} />} />

                <Route path="/cart" element={<Cart onChange={refresh} />} />

                <Route path="/login" element={<Login />} />

                <Route path="/checkout" element={<Checkout onDone={refresh} />} />

                <Route path="/orders" element={<Orders />} />

                <Route path="/orders/:id" element={<Track />} />

                <Route path="/admin" element={<Admin />} />
            </Routes>

            <AiChatWidget onCartChange={refresh} />

            <footer>
                © 2026 Papa Zone · Freshly baked, delivered with care.
            </footer>
        </>
    );
}


// ================= MENU =================

function Menu({ onAdd }) {
    const [items, setItems] = useState([]);
    const [q, setQ] = useState('');
    const [cat, setCat] = useState('All');
    const [msg, setMsg] = useState('');

    useEffect(() => {
        api.get('/menu')
            .then(response => setItems(response.data))
            .catch(() => {
                setMsg('Cannot connect to menu service. Start the backend services.');
            });
    }, []);

    const cats = [
        'All',
        ...new Set(items.map(x => x.category).filter(Boolean))
    ];

    const filtered = items.filter(x =>
        (cat === 'All' || x.category === cat) &&
        x.name.toLowerCase().includes(q.toLowerCase())
    );

    async function add(item) {
        try {
            await api.post(`/cart/${demoId()}/items`, {
                menuItemId: item.id,
                quantity: 1
            });

            setMsg(`${item.name} added to cart`);

            onAdd();
        } catch (error) {
            setMsg('Could not add item. Please check backend services.');
        }
    }

    return (
        <main>
            <section className="hero">
                <div>
                    <span className="eyebrow">GOOD FOOD. GOOD MOOD.</span>

                    <h1>
                        Hot pizza.<br />
                        <em>Happy moments.</em>
                    </h1>

                    <p>
                        Freshly prepared favourites, delivered right to your door.
                    </p>

                    <a href="#menu" className="primary">
                        Explore menu <ArrowRight size={17} />
                    </a>
                </div>

                <div className="hero-art">
                    <img
                        src="https://images.unsplash.com/photo-1579751626657-72bc17010498?w=900"
                        alt="Fresh pizza"
                    />
                </div>

                <div className="hero-stamp">
                    FRESH<br />& TASTY
                </div>
            </section>

            <Recommendations onAdd={onAdd} />

            <section id="menu" className="menu-section">
                <div className="section-head">
                    <div>
                        <span className="eyebrow">MADE FOR YOU</span>
                        <h2>Our menu</h2>
                    </div>

                    <label className="search">
                        <Search size={18} />

                        <input
                            placeholder="Search your favourite..."
                            value={q}
                            onChange={e => setQ(e.target.value)}
                        />
                    </label>
                </div>

                <div className="chips">
                    {cats.map(c => (
                        <button
                            key={c}
                            className={cat === c ? 'chip active' : 'chip'}
                            onClick={() => setCat(c)}
                        >
                            {c}
                        </button>
                    ))}
                </div>

                {msg && <p className="notice">{msg}</p>}

                <div className="menu-grid">
                    {filtered.map(item => (
                        <article className="food-card" key={item.id}>
                            <div className="food-img">
                                <img
                                    src={
                                        item.imageUrl ||
                                        'https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600'
                                    }
                                    alt={item.name}
                                    onError={e => {
                                        e.currentTarget.src =
                                            'https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600';
                                    }}
                                />

                                <span>{item.category}</span>
                            </div>

                            <div className="food-info">
                                <h3>{item.name}</h3>
                                <p>{item.description}</p>

                                <div className="food-bottom">
                                    <strong>{money(item.price)}</strong>

                                    <button
                                        onClick={() => add(item)}
                                        className="add"
                                    >
                                        <Plus size={17} /> Add
                                    </button>
                                </div>
                            </div>
                        </article>
                    ))}
                </div>

                {!filtered.length && (
                    <p className="empty">No menu items found.</p>
                )}
            </section>
        </main>
    );
}


// ================= CART =================

function Cart({ onChange }) {
    const [cart, setCart] = useState({
        items: [],
        total: 0
    });

    const [error, setError] = useState('');

    async function load() {
        try {
            const response = await api.get(`/cart/${demoId()}`);
            setCart(response.data);
            setError('');
        } catch (error) {
            setError('Unable to load cart. Check backend services.');
        }
    }

    useEffect(() => {
        load();
    }, []);

    async function remove(id) {
        await api.delete(`/cart/${demoId()}/items/${id}`);
        await load();
        onChange();
    }

    async function qty(item, n) {
        if (n < 1) {
            return remove(item.id);
        }

        await api.patch(`/cart/items/${item.id}`, {
            quantity: n
        });

        await load();
        onChange();
    }

    return (
        <main className="page">
            <div className="page-title">
                <span className="eyebrow">YOUR SELECTION</span>
                <h1>Shopping cart</h1>
            </div>

            {error && <p className="notice">{error}</p>}

            {cart.items?.length ? (
                <div className="cart-layout">
                    <div className="cart-list">
                        {cart.items.map(item => (
                            <div className="cart-row" key={item.id}>
                                <div className="cart-thumb">
                                    <Pizza />
                                </div>

                                <div className="cart-desc">
                                    <b>{item.name}</b>
                                    <span>{money(item.unitPrice)} each</span>
                                </div>

                                <div className="qty">
                                    <button onClick={() => qty(item, item.quantity - 1)}>
                                        <Minus size={14} />
                                    </button>

                                    <b>{item.quantity}</b>

                                    <button onClick={() => qty(item, item.quantity + 1)}>
                                        <Plus size={14} />
                                    </button>
                                </div>

                                <strong>
                                    {money(item.unitPrice * item.quantity)}
                                </strong>

                                <button
                                    className="iconbtn"
                                    onClick={() => remove(item.id)}
                                >
                                    <Trash2 size={17} />
                                </button>
                            </div>
                        ))}
                    </div>

                    <aside className="summary">
                        <h3>Order summary</h3>

                        <p>
                            <span>Subtotal</span>
                            <b>{money(cart.total)}</b>
                        </p>

                        <p>
                            <span>Delivery</span>
                            <b>{cart.total >= 499 ? 'FREE' : money(40)}</b>
                        </p>

                        <p>
                            <span>Estimated tax</span>
                            <b>{money(cart.total * 0.05)}</b>
                        </p>

                        <div className="total">
                            <span>Total</span>

                            <strong>
                                {money(
                                    cart.total +
                                    (cart.total >= 499 ? 0 : 40) +
                                    cart.total * 0.05
                                )}
                            </strong>
                        </div>

                        <Link className="primary full" to="/checkout">
                            Proceed to checkout <ArrowRight size={17} />
                        </Link>
                    </aside>
                </div>
            ) : (
                <div className="emptybox">
                    <ShoppingCart size={42} />
                    <h2>Your cart is empty</h2>
                    <p>Add something delicious from our menu.</p>
                    <Link to="/" className="primary">Browse menu</Link>
                </div>
            )}
        </main>
    );
}


// ================= LOGIN =================

function Login() {
    const [mode, setMode] = useState('login');

    const [form, setForm] = useState({
        name: '',
        email: '',
        phone: '',
        password: ''
    });

    const [msg, setMsg] = useState('');

    const nav = useNavigate();

    async function submit(e) {
        e.preventDefault();

        try {
            if (mode === 'register') {
                await api.post('/customers/register', form);

                setMode('login');
                setMsg('Account created. Please log in.');
            } else {
                const response = await api.post('/customers/login', {
                    email: form.email,
                    password: form.password
                });

                localStorage.setItem('token', response.data.token);
                localStorage.setItem('customerId', response.data.customerId);
                localStorage.setItem('customerName', response.data.name);
                localStorage.setItem('role', response.data.role || 'CUSTOMER');

                nav('/');
            }
        } catch (error) {
            setMsg(
                error.response?.data?.message ||
                'Unable to complete request'
            );
        }
    }

    return (
        <main className="page narrow">
            <div className="auth-card">
                <span className="eyebrow">WELCOME TO PAPAZONE</span>

                <h1>
                    {mode === 'login' ? 'Welcome back' : 'Create account'}
                </h1>

                <p>Sign in to order your favourites.</p>

                {msg && <p className="notice">{msg}</p>}

                <form onSubmit={submit}>
                    {mode === 'register' && (
                        <input
                            required
                            placeholder="Full name"
                            value={form.name}
                            onChange={e => setForm({
                                ...form,
                                name: e.target.value
                            })}
                        />
                    )}

                    <input
                        required
                        type="email"
                        placeholder="Email address"
                        value={form.email}
                        onChange={e => setForm({
                            ...form,
                            email: e.target.value
                        })}
                    />

                    {mode === 'register' && (
                        <input
                            placeholder="Phone number"
                            value={form.phone}
                            onChange={e => setForm({
                                ...form,
                                phone: e.target.value
                            })}
                        />
                    )}

                    <input
                        required
                        type="password"
                        placeholder="Password"
                        value={form.password}
                        onChange={e => setForm({
                            ...form,
                            password: e.target.value
                        })}
                    />

                    <button className="primary full">
                        {mode === 'login' ? 'Sign in' : 'Create account'}
                    </button>
                </form>

                <button
                    className="textbtn"
                    onClick={() => {
                        setMode(mode === 'login' ? 'register' : 'login');
                        setMsg('');
                    }}
                >
                    {mode === 'login'
                        ? 'New here? Create an account'
                        : 'Already registered? Sign in'}
                </button>
            </div>
        </main>
    );
}


// ================= CHECKOUT =================

function Checkout({ onDone }) {
    const [cart, setCart] = useState({
        items: [],
        total: 0
    });

    const [address, setAddress] = useState('');
    const [method, setMethod] = useState('COD');
    const [msg, setMsg] = useState('');

    const nav = useNavigate();

    useEffect(() => {
        api.get(`/cart/${demoId()}`)
            .then(response => setCart(response.data))
            .catch(() => {});
    }, []);

    async function place(e) {
        e.preventDefault();

        try {
            const response = await api.post('/orders/checkout', {
                customerId: demoId(),
                address,
                paymentMethod: method
            });

            onDone();
            nav(`/orders/${response.data.id}`);
        } catch (error) {
            setMsg(
                error.response?.data?.message ||
                'Checkout failed.'
            );
        }
    }

    return (
        <main className="page narrow">
            <div className="page-title">
                <span className="eyebrow">ALMOST THERE</span>
                <h1>Checkout</h1>
            </div>

            <div className="checkout-card">
                <h3>Delivery details</h3>

                {msg && <p className="notice">{msg}</p>}

                <form onSubmit={place}>
                    <label>Delivery address</label>

                    <textarea
                        required
                        rows="3"
                        placeholder="House / flat, street, area, city, PIN code"
                        value={address}
                        onChange={e => setAddress(e.target.value)}
                    />

                    <label>Payment method</label>

                    <select
                        value={method}
                        onChange={e => setMethod(e.target.value)}
                    >
                        <option value="COD">Cash on delivery</option>
                        <option value="CARD">Card (simulated)</option>
                        <option value="UPI">UPI (simulated)</option>
                    </select>

                    <div className="total">
                        <span>Order total</span>

                        <strong>
                            {money(
                                cart.total +
                                (cart.total >= 499 ? 0 : 40) +
                                cart.total * 0.05
                            )}
                        </strong>
                    </div>

                    <button
                        className="primary full"
                        disabled={!cart.items?.length}
                    >
                        Place order
                    </button>
                </form>

                <small>
                    Card and UPI payments are simulated in this demo.
                    No real money is charged.
                </small>
            </div>
        </main>
    );
}


// ================= MY ORDERS =================

function Orders() {
    const [orders, setOrders] = useState([]);
    const [err, setErr] = useState('');

    useEffect(() => {
        api.get(`/orders/customer/${demoId()}`)
            .then(response => setOrders(response.data))
            .catch(() => setErr('Unable to load orders.'));
    }, []);

    return (
        <main className="page">
            <div className="page-title">
                <span className="eyebrow">YOUR FOOD JOURNEY</span>
                <h1>My orders</h1>
            </div>

            {err && <p className="notice">{err}</p>}

            {orders.length ? (
                orders.map(order => (
                    <Link
                        className="order-card"
                        to={`/orders/${order.id}`}
                        key={order.id}
                    >
                        <div>
                            <b>Order #{order.id}</b>
                            <span>
                                {new Date(order.createdAt).toLocaleString()}
                            </span>
                        </div>

                        <span className="status">{order.status}</span>

                        <strong>{money(order.total)}</strong>

                        <ArrowRight />
                    </Link>
                ))
            ) : (
                <div className="emptybox">
                    <ClipboardList size={40} />
                    <h2>No orders yet</h2>
                    <Link className="primary" to="/">Start ordering</Link>
                </div>
            )}
        </main>
    );
}


// ================= ORDER TRACKING =================

function Track() {
    const { id } = useParams();

    const [o, setO] = useState(null);
    const [d, setD] = useState(null);
    const [error, setError] = useState('');

    const steps = [
        'PLACED',
        'PREPARING',
        'READY_FOR_PICKUP',
        'PICKED_UP',
        'DELIVERED'
    ];

    useEffect(() => {
        let active = true;

        async function loadOrder() {
            try {
                const response = await api.get(`/orders/${id}`);

                if (active) {
                    setO(response.data);
                    setError('');
                }
            } catch (error) {
                if (active) {
                    setError('Unable to load order details.');
                }
            }
        }

        async function loadDelivery() {
            try {
                const response = await api.get(`/deliveries/order/${id}`);

                if (active) {
                    setD(response.data);
                }
            } catch (error) {
                if (active) {
                    setD(null);
                }
            }
        }

        loadOrder();
        loadDelivery();

        // Automatically refresh delivery status every 5 seconds.
        const interval = setInterval(() => {
            loadDelivery();
        }, 5000);

        return () => {
            active = false;
            clearInterval(interval);
        };
    }, [id]);

    if (error) {
        return (
            <main className="page">
                <p className="notice">{error}</p>
            </main>
        );
    }

    if (!o) {
        return <main className="page">Loading order...</main>;
    }

    // Delivery Service is the source of truth for delivery progress.
    const currentStatus = (
        d?.status || o.status || 'PLACED'
    ).toUpperCase();

    const normalizedStatus =
        currentStatus === 'CREATED' ? 'PLACED' : currentStatus;

    const currentIndex = steps.indexOf(normalizedStatus);

    return (
        <main className="page narrow">
            <div className="page-title">
                <span className="eyebrow">ORDER TRACKING</span>

                <h1>Order #{o.id}</h1>

                <span className="status">
                    {normalizedStatus.replaceAll('_', ' ')}
                </span>
            </div>

            <div className="checkout-card">
                <h3>Delivery progress</h3>

                <div className="timeline">
                    {steps.map((step, index) => (
                        <div
                            className={
                                currentIndex >= 0 && index <= currentIndex
                                    ? 'step done'
                                    : 'step'
                            }
                            key={step}
                        >
                            <i />
                            {step.replaceAll('_', ' ')}
                        </div>
                    ))}
                </div>

                <hr />

                <p>
                    <MapPin size={17} /> {o.address}
                </p>

                <p>
                    Order total <b>{money(o.total)}</b>
                </p>

                {d && (
                    <p>
                        Delivery partner: <b>{d.provider}</b>
                        {' · '}
                        Tracking: {d.trackingCode}
                    </p>
                )}

                <small>
                    {d
                        ? 'Delivery status updates automatically every 5 seconds.'
                        : 'Delivery information is not available yet.'}
                </small>
            </div>
        </main>
    );
}


// ================= ADMIN DASHBOARD =================


function Admin() {
    const [items, setItems] = useState([]);
    const [orders, setOrders] = useState([]);
    const [deliveries, setDeliveries] = useState({});
    const [msg, setMsg] = useState('');
    const [loading, setLoading] = useState(false);
    const [filter, setFilter] = useState('ALL');
    const [searchTerm, setSearchTerm] = useState('');
    const [expandedOrderId, setExpandedOrderId] = useState(null);

    const [form, setForm] = useState({
        name: '',
        description: '',
        category: 'Pizza',
        price: 0,
        imageUrl: '',
        available: true
    });

    const DELIVERY_STATUSES = [
        'PLACED',
        'PREPARING',
        'READY_FOR_PICKUP',
        'PICKED_UP',
        'DELIVERED'
    ];

    // Load menu items, orders and deliveries
    async function load() {
        try {
            setLoading(true);
            setMsg('');

            const [menuResponse, orderResponse] = await Promise.all([
                api.get('/menu'),
                api.get('/orders')
            ]);

            setItems(menuResponse.data);
            setOrders(orderResponse.data);

            const deliveryResults = await Promise.all(
                orderResponse.data.map(async order => {
                    try {
                        const response = await api.get(
                            `/deliveries/order/${order.id}`
                        );

                        return [order.id, response.data];
                    } catch (error) {
                        return [order.id, null];
                    }
                })
            );

            const deliveryMap = {};

            deliveryResults.forEach(([orderId, delivery]) => {
                deliveryMap[orderId] = delivery;
            });

            setDeliveries(deliveryMap);

        } catch (error) {
            console.error('Admin loading error:', error);
            setMsg('Unable to load admin dashboard.');
        } finally {
            setLoading(false);
        }
    }

    useEffect(() => {
        load();
    }, []);

    // Dashboard statistics
    const totalOrders = orders.length;

    const getDeliveryStatus = order => {
        const status = deliveries[order.id]?.status || order.status || 'PLACED';
        return status === 'CREATED' ? 'PLACED' : status;
    };

    const deliveredOrders = orders.filter(
        order => getDeliveryStatus(order) === 'DELIVERED'
    ).length;

    const pendingOrders = orders.filter(
        order => ['PLACED', 'PREPARING'].includes(getDeliveryStatus(order))
    ).length;

    const activeDeliveries = orders.filter(
        order => ['READY_FOR_PICKUP', 'PICKED_UP'].includes(
            getDeliveryStatus(order)
        )
    ).length;

    // Filter and search orders
    const filteredOrders = orders.filter(order => {
        const status = getDeliveryStatus(order);

        const matchesFilter =
            filter === 'ALL' || status === filter;

        const delivery = deliveries[order.id];
        const search = searchTerm.trim().toLowerCase();

        const matchesSearch =
            !search ||
            String(order.id).includes(search) ||
            String(order.customerId).includes(search) ||
            String(order.address || '').toLowerCase().includes(search) ||
            String(delivery?.trackingCode || '').toLowerCase().includes(search);

        return matchesFilter && matchesSearch;
    });

    // Add menu item
    async function add(e) {
        e.preventDefault();

        try {
            await api.post('/menu', form);

            setForm({
                name: '',
                description: '',
                category: 'Pizza',
                price: 0,
                imageUrl: '',
                available: true
            });

            await load();
            setMsg('Menu item added successfully');

        } catch (error) {
            setMsg('Unable to add menu item');
        }
    }

    // Delete menu item
    async function del(id) {
        try {
            await api.delete(`/menu/${id}`);
            await load();
            setMsg('Menu item deleted successfully');

        } catch (error) {
            setMsg('Unable to delete menu item');
        }
    }

    // Advance delivery status sequentially
    async function advanceDelivery(delivery) {
        if (!delivery) {
            setMsg('Delivery record not found');
            return;
        }

        const currentStatus =
            delivery.status === 'CREATED'
                ? 'PLACED'
                : delivery.status;

        const currentIndex = DELIVERY_STATUSES.indexOf(currentStatus);

        if (currentIndex === -1) {
            setMsg('Invalid delivery status');
            return;
        }

        if (currentIndex === DELIVERY_STATUSES.length - 1) {
            setMsg('Order already delivered');
            return;
        }

        const nextStatus = DELIVERY_STATUSES[currentIndex + 1];

        try {
            await api.patch(
                `/deliveries/${delivery.id}/status`,
                { status: nextStatus }
            );

            await load();

            setMsg(
                `Order #${delivery.orderId} updated to ${nextStatus.replaceAll('_', ' ')}`
            );

        } catch (error) {
            setMsg(
                error.response?.data?.message ||
                'Unable to update delivery status'
            );

            await load();
        }
    }

    return (
        <main className="page admin-page">

            <div className="page-title">
                <span className="eyebrow">CONTROL CENTRE</span>
                <h1>Admin Dashboard</h1>

                <p>
                    Manage all customer orders, menu inventory
                    and delivery operations.
                </p>
            </div>

            {msg && <p className="notice">{msg}</p>}

            <AdminAssistant />

            {/* Dashboard Statistics */}
            <div className="admin-grid">

                <section className="checkout-card">
                    <h3>Total Orders</h3>
                    <h1>{totalOrders}</h1>
                </section>

                <section className="checkout-card">
                    <h3>Pending Orders</h3>
                    <h1>{pendingOrders}</h1>
                </section>

                <section className="checkout-card">
                    <h3>Active Deliveries</h3>
                    <h1>{activeDeliveries}</h1>
                </section>

                <section className="checkout-card">
                    <h3>Delivered Orders</h3>
                    <h1>{deliveredOrders}</h1>
                </section>

            </div>

            {/* Menu Management */}
            <div className="admin-grid">

                <section className="checkout-card">
                    <h3>
                        <ChefHat size={20} /> Add menu item
                    </h3>

                    <form onSubmit={add}>
                        <input
                            required
                            placeholder="Item name"
                            value={form.name}
                            onChange={e => setForm({
                                ...form,
                                name: e.target.value
                            })}
                        />

                        <input
                            placeholder="Description"
                            value={form.description}
                            onChange={e => setForm({
                                ...form,
                                description: e.target.value
                            })}
                        />

                        <input
                            placeholder="Category"
                            value={form.category}
                            onChange={e => setForm({
                                ...form,
                                category: e.target.value
                            })}
                        />

                        <input
                            type="number"
                            min="0"
                            placeholder="Price"
                            value={form.price}
                            onChange={e => setForm({
                                ...form,
                                price: Number(e.target.value)
                            })}
                        />

                        <input
                            placeholder="Image URL"
                            value={form.imageUrl}
                            onChange={e => setForm({
                                ...form,
                                imageUrl: e.target.value
                            })}
                        />

                        <button className="primary full">
                            Add item
                        </button>
                    </form>
                </section>

                <section className="checkout-card">
                    <h3>Menu Inventory ({items.length})</h3>

                    {items.map(item => (
                        <div className="admin-row" key={item.id}>
                            <span>
                                {item.name}
                                <small>{money(item.price)}</small>
                            </span>

                            <button
                                className="iconbtn"
                                onClick={() => del(item.id)}
                            >
                                <Trash2 size={16} />
                            </button>
                        </div>
                    ))}
                </section>

            </div>

            {/* All Customer Orders */}
            <section className="checkout-card orders-admin">

                <h3>All Customer Orders ({orders.length})</h3>

                {/* Search Orders */}
                <div className="admin-search">
                    <input
                        type="text"
                        placeholder="Search by Order ID, Customer ID, address or tracking code..."
                        value={searchTerm}
                        onChange={e => setSearchTerm(e.target.value)}
                    />
                </div>

                {/* Filter Orders */}
                <div className="filter-section">
                    <label>Filter by delivery status</label>

                    <select
                        value={filter}
                        onChange={e => setFilter(e.target.value)}
                    >
                        <option value="ALL">All Orders</option>

                        {DELIVERY_STATUSES.map(status => (
                            <option key={status} value={status}>
                                {status.replaceAll('_', ' ')}
                            </option>
                        ))}
                    </select>
                </div>

                {loading && <p>Loading orders...</p>}

                {!loading && filteredOrders.length === 0 && (
                    <p>No orders found for this filter.</p>
                )}

                {/* Order List */}
                {!loading && filteredOrders.map(order => {
                    const delivery = deliveries[order.id];
                    const status = getDeliveryStatus(order);

                    const currentIndex =
                        DELIVERY_STATUSES.indexOf(status);

                    const nextStatus =
                        currentIndex >= 0 &&
                        currentIndex < DELIVERY_STATUSES.length - 1
                            ? DELIVERY_STATUSES[currentIndex + 1]
                            : null;

                    const isExpanded = expandedOrderId === order.id;

                    return (
                        <div className="admin-order-card" key={order.id}>

                            <div className="admin-row">

                                <div>
                                    <b>Order #{order.id}</b>

                                    <small>Customer ID: {order.customerId}</small>
                                    <small>Address: {order.address}</small>
                                    <small>Amount: {money(order.total)}</small>
                                    <small>Order Status: {order.status}</small>
                                    <small>Delivery Status: {status}</small>

                                    {delivery && (
                                        <small>
                                            Tracking: {delivery.trackingCode}
                                        </small>
                                    )}

                                    <button
                                        type="button"
                                        className="textbtn order-details-toggle"
                                        onClick={() =>
                                            setExpandedOrderId(
                                                isExpanded ? null : order.id
                                            )
                                        }
                                    >
                                        {isExpanded
                                            ? 'Hide Details ▲'
                                            : 'View Details ▼'}
                                    </button>
                                </div>

                                <div className="admin-order-actions">
                                    {delivery && nextStatus ? (
                                        <button
                                            className="primary"
                                            onClick={() => advanceDelivery(delivery)}
                                        >
                                            Mark as {nextStatus.replaceAll('_', ' ')}
                                        </button>
                                    ) : delivery && status === 'DELIVERED' ? (
                                        <span className="status">DELIVERED</span>
                                    ) : (
                                        <span className="status">NO DELIVERY</span>
                                    )}
                                </div>

                            </div>

                            {/* Expandable Order Details */}
                            {isExpanded && (
                                <div className="order-details-panel">

                                    <h4>Order Details</h4>

                                    <div className="order-details-grid">

                                        <div>
                                            <span>Order ID</span>
                                            <strong>#{order.id}</strong>
                                        </div>

                                        <div>
                                            <span>Customer ID</span>
                                            <strong>{order.customerId}</strong>
                                        </div>

                                        <div>
                                            <span>Subtotal</span>
                                            <strong>{money(order.subtotal)}</strong>
                                        </div>

                                        <div>
                                            <span>Delivery Fee</span>
                                            <strong>{money(order.deliveryFee)}</strong>
                                        </div>

                                        <div>
                                            <span>Tax</span>
                                            <strong>{money(order.tax)}</strong>
                                        </div>

                                        <div>
                                            <span>Total Amount</span>
                                            <strong>{money(order.total)}</strong>
                                        </div>

                                        <div>
                                            <span>Payment Status</span>
                                            <strong>{order.paymentStatus || 'PENDING'}</strong>
                                        </div>

                                        <div>
                                            <span>Delivery Provider</span>
                                            <strong>{delivery?.provider || 'Not assigned'}</strong>
                                        </div>

                                        <div>
                                            <span>Delivery Status</span>
                                            <strong>{status}</strong>
                                        </div>

                                        <div>
                                            <span>Tracking Code</span>
                                            <strong>{delivery?.trackingCode || 'Not available'}</strong>
                                        </div>

                                    </div>

                                    <h4>Ordered Items</h4>

                                    {(() => {
                                        const orderItems = parseOrderItems(order.itemsJson);

                                        return orderItems.length > 0 ? (
                                            <div className="order-items-table-wrapper">
                                                <table className="order-items-table">
                                                    <thead>
                                                        <tr>
                                                            <th>Product</th>
                                                            <th>Quantity</th>
                                                            <th>Unit Price</th>
                                                            <th>Total</th>
                                                        </tr>
                                                    </thead>

                                                    <tbody>
                                                        {orderItems.map((item, index) => (
                                                            <tr key={index}>
                                                                <td>{item.name}</td>
                                                                <td>{item.quantity}</td>
                                                                <td>{money(item.unitPrice)}</td>
                                                                <td>
                                                                    <strong>
                                                                        {money(item.unitPrice * item.quantity)}
                                                                    </strong>
                                                                </td>
                                                            </tr>
                                                        ))}
                                                    </tbody>
                                                </table>
                                            </div>
                                        ) : (
                                            <p>No item details available.</p>
                                        );
                                    })()}

                                    <h4>Delivery Address</h4>
                                    <p>{order.address}</p>

                                    <small className="order-created">
                                        Created: {order.createdAt
                                            ? new Date(order.createdAt).toLocaleString()
                                            : 'Not available'}
                                    </small>

                                </div>
                            )}

                        </div>
                    );
                })}

            </section>

        </main>
    );
}




export default App;