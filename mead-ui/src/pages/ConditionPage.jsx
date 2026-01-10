import React, { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { fetchConditionDetail } from "../api/conditionsApi.js";

export default function ConditionPage() {
    const { id } = useParams();
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [err, setErr] = useState("");
    const [activeIndex, setActiveIndex] = useState(0);
    const [touchStartX, setTouchStartX] = useState(null);

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

    const images = useMemo(() => {
        if (!data) return [];
        const combined = data.images?.length ? data.images : data.image ? [data.image] : [];
        const deduped = new Map();
        combined.forEach((url) => {
            if (!url) return;
            const key = url.toLowerCase();
            if (!deduped.has(key)) deduped.set(key, url);
        });
        return Array.from(deduped.values());
    }, [data]);

    useEffect(() => {
        setActiveIndex(0);
    }, [images.length]);

    const totalImages = images.length;
    const currentImage = totalImages ? images[activeIndex] : null;

    const showPrev = () => {
        if (!totalImages) return;
        setActiveIndex((prev) => (prev - 1 + totalImages) % totalImages);
    };

    const showNext = () => {
        if (!totalImages) return;
        setActiveIndex((prev) => (prev + 1) % totalImages);
    };

    const handleTouchStart = (event) => {
        setTouchStartX(event.touches[0].clientX);
    };

    const handleTouchEnd = (event) => {
        if (touchStartX == null) return;
        const delta = event.changedTouches[0].clientX - touchStartX;
        if (Math.abs(delta) > 40) {
            if (delta > 0) {
                showPrev();
            } else {
                showNext();
            }
        }
        setTouchStartX(null);
    };

    if (loading) return <p className="status">Loading...</p>;
    if (err) return <p className="status error">Error: {err}</p>;
    if (!data) return <p className="status">No data.</p>;

    return (
        <section className="page">
            <Link to="/" className="back-link">{"<-"} Back to conditions</Link>

            <article
                vocab={data.context || "https://schema.org/"}
                typeof={data.type || "MedicalCondition"}
                resource={data.id}
                className="card detail-card"
            >
                <header className="detail-header">
                    <div>
                        <p className="eyebrow">Condition</p>
                        <h1 property="name">{data.name}</h1>
                        <p className="muted">id: {data.id}</p>
                    </div>
                    {currentImage && (
                        <div className="carousel" onTouchStart={handleTouchStart} onTouchEnd={handleTouchEnd}>
                            <div className="carousel-frame image-frame">
                                <img
                                    src={currentImage}
                                    alt={data.name}
                                    property="image"
                                    className="carousel-image"
                                />
                                {totalImages > 1 && (
                                    <>
                                        <button
                                            type="button"
                                            className="carousel-arrow left"
                                            onClick={showPrev}
                                            aria-label="Previous image"
                                        >
                                            {"<"}
                                        </button>
                                        <button
                                            type="button"
                                            className="carousel-arrow right"
                                            onClick={showNext}
                                            aria-label="Next image"
                                        >
                                            {">"}
                                        </button>
                                    </>
                                )}
                            </div>
                            {totalImages > 1 && (
                                <div className="carousel-dots">
                                    {images.map((_, index) => (
                                        <button
                                            key={`${data.id}-${index}`}
                                            type="button"
                                            className={`carousel-dot${index === activeIndex ? " active" : ""}`}
                                            onClick={() => setActiveIndex(index)}
                                            aria-label={`Show image ${index + 1}`}
                                        />
                                    ))}
                                </div>
                            )}
                        </div>
                    )}
                </header>

                <div className="detail-section">
                    <h2>What is it?</h2>
                    <p property="description">
                        {data.description || "No description available."}
                    </p>
                </div>

                <div className="detail-grid">
                    <div className="detail-section">
                        <h2>Symptoms</h2>
                        {data.symptoms?.length ? (
                            <ul className="info-list">
                                {data.symptoms.map((s) => (
                                    <li key={s} property="signOrSymptom">{s}</li>
                                ))}
                            </ul>
                        ) : (
                            <p className="muted">No symptoms found.</p>
                        )}
                    </div>
                    <div className="detail-section">
                        <h2>Risk factors</h2>
                        {data.riskFactors?.length ? (
                            <ul className="info-list">
                                {data.riskFactors.map((r) => (
                                    <li key={r} property="riskFactor">{r}</li>
                                ))}
                            </ul>
                        ) : (
                            <p className="muted">No risk factors found.</p>
                        )}
                    </div>
                </div>

                <div className="detail-section">
                    <h2>Simple explanation</h2>
                    <pre className="snippet">{data.wikidocSnippet || "No local snippet."}</pre>
                </div>

                <div className="detail-section">
                    <h2>Geography insights</h2>
                    <p className="muted">
                        Coming soon. This section will connect the condition to climate, industrial development,
                        population density, and cultural factors for a selected region.
                    </p>
                </div>

                <div className="detail-section">
                    <h2>Sources</h2>
                    <ul className="source-list">
                        {data.sameAs?.map((u) => (
                            <li key={u}>
                                <a href={u} target="_blank" rel="noreferrer">{u}</a>
                            </li>
                        ))}
                    </ul>
                </div>
            </article>
        </section>
    );
}
