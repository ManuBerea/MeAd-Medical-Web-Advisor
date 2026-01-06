import React from "react";
import { Routes, Route, Link } from "react-router-dom";
import HomePage from "./pages/HomePage.jsx";
import ConditionPage from "./pages/ConditionPage.jsx";

export default function App() {
    return (
        <div className="container">
            <header className="header">
                <Link to="/" className="brand">MeAd — Medical Web Advisor</Link>
            </header>

            <Routes>
                <Route path="/" element={<HomePage />} />
                <Route path="/condition/:id" element={<ConditionPage />} />
            </Routes>
        </div>
    );
}
