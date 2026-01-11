import React from "react";
import { Link } from "react-router-dom";

export default function HomePage() {
    return (
        <section className="page">
            <div className="hero">
                <div className="hero-copy">
                    <p className="eyebrow">Project focus</p>
                    <h1 className="hero-title">
                        MeAd helps high-school students explore medical conditions and geography
                    </h1>
                    <p className="hero-subtitle">
                        Learn about common diseases, allergies, food intolerance, obesity, and disorders, plus how they affect the human body.
                    </p>
                    <p className="hero-subtitle">
                        Explore geographic population context with climate, industrial development, population density, and cultural factors
                        for towns, countries, or continents.
                    </p>
                </div>
            </div>

            <div className="feature-grid">
                <div className="card feature-card">
                    <p className="eyebrow">Medical</p>
                    <h2>Medical conditions</h2>
                    <p className="muted">
                        Browse conditions, symptoms, risk factors, and sources to understand health topics.
                    </p>
                    <Link className="button" to="/conditions">Browse conditions</Link>
                </div>
                <div className="card feature-card">
                    <p className="eyebrow">Geography</p>
                    <h2>Geography explorer</h2>
                    <p className="muted">
                        Inspect population context by region, including climate, industry, and cultural factors.
                    </p>
                    <Link className="button" to="/geography">Open geography explorer</Link>
                </div>
            </div>
        </section>
    );
}
