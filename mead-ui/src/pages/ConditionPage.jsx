import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { fetchConditionDetail } from "../api/conditionsApi.js";

export default function ConditionPage() {
    const { id } = useParams();
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [err, setErr] = useState("");

    useEffect(() => {
        let alive = true;
        (async () => {
            try {
                setErr("");
                setLoading(true);
                const d = await fetchConditionDetail(id);
                if (alive) setData(d);
            } catch (e) {
                if (alive) setErr(e.message || String(e));
            } finally {
                if (alive) setLoading(false);
            }
        })();
        return () => { alive = false; };
    }, [id]);

    if (loading) return <p>Loading…</p>;
    if (err) return <p className="error">Error: {err}</p>;
    if (!data) return <p>No data.</p>;

    return (
        <section>
            <p><Link to="/">← Back</Link></p>

            {/* RDFa root node */}
            <article
                vocab={data.context || "https://schema.org/"}
                typeof={data.type || "MedicalCondition"}
                resource={data.id}
                className="card"
            >
                <h1 property="name">{data.name}</h1>

                {data.image && (
                    <img
                        src={data.image}
                        alt={data.name}
                        property="image"
                        style={{ maxWidth: "100%", borderRadius: 8 }}
                    />
                )}

                <h2>What is it?</h2>
                <p property="description">
                    {data.description || "No description available."}
                </p>

                <h2>Symptoms</h2>
                {data.symptoms?.length ? (
                    <ul>
                        {data.symptoms.map((s) => (
                            <li key={s} property="signOrSymptom">{s}</li>
                        ))}
                    </ul>
                ) : (
                    <p className="muted">No symptoms found.</p>
                )}

                <h2>Risk factors</h2>
                {data.riskFactors?.length ? (
                    <ul>
                        {data.riskFactors.map((r) => (
                            <li key={r} property="riskFactor">{r}</li>
                        ))}
                    </ul>
                ) : (
                    <p className="muted">No risk factors found.</p>
                )}

                <h2>Simple explanation</h2>
                <pre className="snippet">{data.wikidocSnippet || "No local snippet."}</pre>

                <h2>Sources</h2>
                <ul>
                    {data.sameAs?.map((u) => (
                        <li key={u}>
                            <a href={u} target="_blank" rel="noreferrer">{u}</a>
                        </li>
                    ))}
                </ul>
            </article>
        </section>
    );
}
