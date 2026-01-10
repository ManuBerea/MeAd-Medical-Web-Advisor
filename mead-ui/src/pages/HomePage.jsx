import React, { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { fetchConditionsList } from "../api/conditionsApi.js";

const PAGE_SIZE = 10;

export default function HomePage() {
    const [items, setItems] = useState([]);
    const [loading, setLoading] = useState(true);
    const [err, setErr] = useState("");
    const [query, setQuery] = useState("");
    const [page, setPage] = useState(1);

    useEffect(() => {
        let alive = true;
        (async () => {
            try {
                setErr("");
                setLoading(true);
                const data = await fetchConditionsList();
                if (alive) setItems(data);
            } catch (e) {
                if (alive) setErr(e.message || String(e));
            } finally {
                if (alive) setLoading(false);
            }
        })();
        return () => { alive = false; };
    }, []);

    const normalizedQuery = query.trim().toLowerCase();
    const filteredItems = normalizedQuery
        ? items.filter((c) => {
            const name = (c.name || "").toLowerCase();
            const id = String(c.id || "").toLowerCase();
            return name.includes(normalizedQuery) || id.includes(normalizedQuery);
        })
        : items;

    const totalPages = Math.ceil(filteredItems.length / PAGE_SIZE);
    const currentPage = totalPages === 0 ? 1 : Math.min(page, totalPages);
    const pageStart = (currentPage - 1) * PAGE_SIZE;
    const pageItems = filteredItems.slice(pageStart, pageStart + PAGE_SIZE);

    useEffect(() => {
        if (totalPages === 0 && page !== 1) {
            setPage(1);
        } else if (totalPages > 0 && page > totalPages) {
            setPage(totalPages);
        }
    }, [page, totalPages]);

    const handleQueryChange = (event) => {
        setQuery(event.target.value);
        setPage(1);
    };

    const handlePrev = () => {
        setPage((prev) => Math.max(1, prev - 1));
    };

    const handleNext = () => {
        setPage((prev) => Math.min(totalPages, prev + 1));
    };

    return (
        <section className="page">
            <div className="hero">
                <div className="hero-copy">
                    <p className="eyebrow">Project focus</p>
                    <h1 className="hero-title">
                        MeAd helps high-school students explore medical conditions through multimedia stories.
                    </h1>
                    <p className="hero-subtitle">
                        Learn about common diseases, allergies, food intolerance, obesity, and disorders, plus how they affect the human body.
                    </p>
                    <p className="hero-subtitle">
                        The platform connects conditions to geography so students can see impacts on populations in a town, country, or continent,
                        including climate, industrial development, population density, and cultural factors.
                    </p>
                </div>
            </div>

            <div className="search-panel">
                <div>
                    <h2>Search conditions</h2>
                    <p className="muted">Filter by name or id.</p>
                </div>
                <div className="search-field">
                    <input
                        id="condition-search"
                        className="search-input"
                        type="search"
                        placeholder="Start typing a condition..."
                        value={query}
                        onChange={handleQueryChange}
                    />
                </div>
            </div>

            {loading && <p className="status">Loading...</p>}
            {err && <p className="status error">Error: {err}</p>}

            {!loading && !err && (
                <>
                    <div className="section-head">
                        <h2>Condition list</h2>
                    </div>

                    {filteredItems.length === 0 ? (
                        <p className="status">No conditions match your search.</p>
                    ) : (
                        <>
                            <ul className="list">
                                {pageItems.map((c) => (
                                    <li key={c.id} className="card condition-card">
                                        <div className="card-body">
                                            <div>
                                                <h3>{c.name}</h3>
                                                <div className="muted">id: {c.id}</div>
                                            </div>
                                            <Link className="button" to={`/condition/${c.id}`}>View details</Link>
                                        </div>
                                    </li>
                                ))}
                            </ul>

                            <div className="pagination">
                                <button
                                    className="page-button"
                                    type="button"
                                    onClick={handlePrev}
                                    disabled={currentPage === 1}
                                >
                                    Previous
                                </button>
                                <span className="page-status">Page {currentPage} of {totalPages}</span>
                                <button
                                    className="page-button"
                                    type="button"
                                    onClick={handleNext}
                                    disabled={currentPage === totalPages}
                                >
                                    Next
                                </button>
                            </div>
                        </>
                    )}
                </>
            )}
        </section>
    );
}
