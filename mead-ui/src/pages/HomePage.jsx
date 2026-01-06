import React, { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { fetchConditionsList } from "../api/conditionsApi.js";

export default function HomePage() {
    const [items, setItems] = useState([]);
    const [loading, setLoading] = useState(true);
    const [err, setErr] = useState("");

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

    return (
        <section>
            <h1>Conditions</h1>

            {loading && <p>Loading…</p>}
            {err && <p className="error">Error: {err}</p>}

            {!loading && !err && (
                <ul className="list">
                    {items.map((c) => (
                        <li key={c.id} className="card">
                            <div className="row">
                                <div>
                                    <strong>{c.name}</strong>
                                    <div className="muted">id: {c.id}</div>
                                </div>
                                <Link className="button" to={`/condition/${c.id}`}>Open</Link>
                            </div>
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}
