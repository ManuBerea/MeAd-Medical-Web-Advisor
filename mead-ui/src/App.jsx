import React from "react";
import { Routes, Route, Link } from "react-router-dom";
import HomePage from "./pages/HomePage.jsx";
import ConditionPage from "./pages/ConditionPage.jsx";
import GeographyPage from "./pages/GeographyPage.jsx";
import ConditionsExplorerPage from "./pages/ConditionsExplorerPage.jsx";

export default function App() {
    return (
        <div className="app-shell">
            <header className="app-header">
                <div className="brand-block">
                    <Link to="/" className="brand">MeAd</Link>
                    <span className="brand-subtitle">Medical Web Advisor</span>
                </div>
                <p className="header-meta">
                    A multimedia experience for high-school students exploring medical conditions and geographic population factors.
                </p>
            </header>

            <main className="app-main">
                <Routes>
                    <Route path="/" element={<HomePage />} />
                    <Route path="/conditions" element={<ConditionsExplorerPage />} />
                    <Route path="/condition/:id" element={<ConditionPage />} />
                    <Route path="/geography" element={<GeographyPage />} />
                </Routes>
            </main>
        </div>
    );
}
