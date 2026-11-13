import api from '../api';

// The JWT (added by api.js) tells the server who is asking. The browser never
// sends a customerId to the AI endpoints.

// Feature 1: chat. messages = [{ role: 'user' | 'assistant', content }]
export const askAssistant = messages =>
    api.post('/ai/chat', { messages }).then(r => r.data);

// Feature 2: personalised recommendations (visitors get popular items)
export const getRecommendations = (limit = 6) =>
    api.get('/ai/recommendations', { params: { limit } }).then(r => r.data);

// Feature 6: admin analytics (server requires role ADMIN)
export const askAdminAssistant = question =>
    api.post('/ai/admin/ask', { question }).then(r => r.data);

export const errorMessage = (error, fallback = 'Something went wrong. Please try again.') =>
    error?.response?.data?.message || fallback;

// Same behaviour as the rest of the app: anonymous visitors use customer 1 for the cart.
export const cartCustomerId = () => Number(localStorage.getItem('customerId') || 1);

export const isLoggedIn = () => Boolean(localStorage.getItem('token'));

// UI hint only - the server enforces the ADMIN role from the JWT.
export const isAdmin = () => localStorage.getItem('role') === 'ADMIN';

export const money = n => '₹' + Number(n || 0).toFixed(2);
