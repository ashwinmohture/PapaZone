import React, { createContext, useContext, useState, useCallback } from 'react';

const AuthContext = createContext(null);

function readSession() {
    const token = localStorage.getItem('token');
    return {
        token,
        customerId: token ? Number(localStorage.getItem('customerId')) : null,
        name: token ? localStorage.getItem('customerName') : null,
        role: token ? localStorage.getItem('role') : null
    };
}

export function AuthProvider({ children }) {
    const [session, setSession] = useState(readSession);

    const login = useCallback(data => {
        localStorage.setItem('token', data.token);
        localStorage.setItem('customerId', data.customerId);
        localStorage.setItem('customerName', data.name);
        localStorage.setItem('role', data.role || 'CUSTOMER');
        setSession(readSession());
    }, []);

    const logout = useCallback(() => {
        ['token', 'customerId', 'customerName', 'role'].forEach(k =>
            localStorage.removeItem(k)
        );
        setSession(readSession());
    }, []);

    const value = {
        ...session,
        isLoggedIn: Boolean(session.token),
        isAdmin: session.role === 'ADMIN',
        login,
        logout
    };

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => {
    const ctx = useContext(AuthContext);
    if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
    return ctx;
};