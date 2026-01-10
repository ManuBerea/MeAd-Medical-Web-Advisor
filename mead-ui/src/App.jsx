import React from "react";
import { Routes, Route, Link } from "react-router-dom";
import HomePage from "./pages/HomePage.jsx";
import ConditionPage from "./pages/ConditionPage.jsx";

export default function App() {
    return (
        <div className="app-shell">
            <header className="app-header">
                <div className="brand-block">
                    <Link to="/" className="brand">MeAd</Link>
                    <span className="brand-subtitle">Medical Web Advisor</span>
                </div>
                <p className="header-meta">
                    A multimedia experience for high-school students exploring medical conditions and their impact across regions.
                </p>
            </header>

            <main className="app-main">
                <Routes>
                    <Route path="/" element={<HomePage />} />
                    <Route path="/condition/:id" element={<ConditionPage />} />
                </Routes>
            </main>
        </div>
    );
}
