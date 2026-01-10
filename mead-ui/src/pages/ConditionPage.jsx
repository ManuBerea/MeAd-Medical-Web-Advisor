import React, { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getConditionDetails } from "../api/conditionsApi.js";

const SWIPE_DISTANCE_THRESHOLD = 40;

export default function ConditionPage() {
    const { id: conditionId } = useParams();
    const [condition, setCondition] = useState(null);
    const [isLoading, setIsLoading] = useState(true);
    const [errorMessage, setErrorMessage] = useState("");
    
    const [currentImageIndex, setCurrentImageIndex] = useState(0);
    const [swipeStartX, setSwipeStartX] = useState(null);
    const [brokenImageUrls, setBrokenImageUrls] = useState(() => new Set());

    useEffect(() => {
        let isMounted = true;
        
        async function fetchDetails() {
            try {
                setErrorMessage("");
                setIsLoading(true);
                const data = await getConditionDetails(conditionId);
                if (isMounted) setCondition(data);
            } catch (err) {
                if (isMounted) setErrorMessage(err.message || String(err));
            } finally {
                if (isMounted) setIsLoading(false);
            }
        }

        fetchDetails();
        
        return () => { isMounted = false; };
    }, [conditionId]);

    // Reset carousel state when switching conditions
    useEffect(() => {
        setCurrentImageIndex(0);
        setBrokenImageUrls(new Set());
    }, [conditionId]);

    const allImages = useMemo(() => condition?.images || [], [condition]);

    const validImages = useMemo(() => {
        return allImages.filter((url) => !brokenImageUrls.has(url));
    }, [allImages, brokenImageUrls]);

    // Ensure index is within bounds if images are removed from the list due to errors
    useEffect(() => {
        if (currentImageIndex >= validImages.length && validImages.length > 0) {
            setCurrentImageIndex(0);
        }
    }, [currentImageIndex, validImages.length]);

    const imageCount = validImages.length;
    const currentImageUrl = imageCount ? validImages[currentImageIndex] : null;

    const navigateToPreviousImage = () => {
        if (imageCount === 0) return;
        setCurrentImageIndex((prev) => (prev - 1 + imageCount) % imageCount);
    };

    const navigateToNextImage = () => {
        if (imageCount === 0) return;
        setCurrentImageIndex((prev) => (prev + 1) % imageCount);
    };

    const handleTouchStart = (event) => {
        setSwipeStartX(event.touches[0].clientX);
    };

    const handleTouchEnd = (event) => {
        if (swipeStartX === null) return;
        
        const swipeDistance = event.changedTouches[0].clientX - swipeStartX;
        
        if (Math.abs(swipeDistance) > SWIPE_DISTANCE_THRESHOLD) {
            if (swipeDistance > 0) {
                navigateToPreviousImage();
            } else {
                navigateToNextImage();
            }
        }
        setSwipeStartX(null);
    };

    const handleImageError = () => {
        if (!currentImageUrl) return;
        setBrokenImageUrls((prev) => {
            if (prev.has(currentImageUrl)) return prev;
            const updatedSet = new Set(prev);
            updatedSet.add(currentImageUrl);
            return updatedSet;
        });
    };

    if (isLoading) return <p className="status">Loading condition details...</p>;
    if (errorMessage) return <p className="status error">Error: {errorMessage}</p>;
    if (!condition) return <p className="status">Condition not found.</p>;

    return (
        <section className="page">
            <Link to="/" className="back-link">{"←"} Back to conditions</Link>

            <article
                vocab={condition.context || "https://schema.org/"}
                typeof={condition.type || "MedicalCondition"}
                resource={condition.id}
                className="card detail-card"
            >
                <header className="detail-header">
                    <div className="title-group">
                        <p className="eyebrow">Medical Condition</p>
                        <h1 property="name">{condition.name}</h1>
                        <p className="muted">ID: {condition.id}</p>
                    </div>
                    
                    {currentImageUrl && (
                        <div className="carousel" onTouchStart={handleTouchStart} onTouchEnd={handleTouchEnd}>
                            <div className="carousel-frame image-frame">
                                <img
                                    src={currentImageUrl}
                                    alt={condition.name}
                                    property="image"
                                    className="carousel-image"
                                    onError={handleImageError}
                                />
                                {imageCount > 1 && (
                                    <>
                                        <button
                                            type="button"
                                            className="carousel-arrow left"
                                            onClick={navigateToPreviousImage}
                                            aria-label="Previous image"
                                        >
                                            {"‹"}
                                        </button>
                                        <button
                                            type="button"
                                            className="carousel-arrow right"
                                            onClick={navigateToNextImage}
                                            aria-label="Next image"
                                        >
                                            {"›"}
                                        </button>
                                    </>
                                )}
                            </div>
                            
                            {imageCount > 1 && (
                                <div className="carousel-dots">
                                    {validImages.map((_, index) => (
                                        <button
                                            key={`${condition.id}-img-${index}`}
                                            type="button"
                                            className={`carousel-dot${index === currentImageIndex ? " active" : ""}`}
                                            onClick={() => setCurrentImageIndex(index)}
                                            aria-label={`Go to image ${index + 1}`}
                                        />
                                    ))}
                                </div>
                            )}
                        </div>
                    )}
                </header>

                <div className="detail-section">
                    <h2>Overview</h2>
                    <p property="description">
                        {condition.description || "No description available."}
                    </p>
                </div>

                <div className="detail-grid">
                    <section className="detail-section">
                        <h2>Symptoms</h2>
                        {condition.symptoms?.length ? (
                            <ul className="info-list">
                                {condition.symptoms.map((symptom) => (
                                    <li key={symptom} property="signOrSymptom">{symptom}</li>
                                ))}
                            </ul>
                        ) : (
                            <p className="muted">No symptoms documented.</p>
                        )}
                    </section>
                    
                    <section className="detail-section">
                        <h2>Risk Factors</h2>
                        {condition.riskFactors?.length ? (
                            <ul className="info-list">
                                {condition.riskFactors.map((factor) => (
                                    <li key={factor} property="riskFactor">{factor}</li>
                                ))}
                            </ul>
                        ) : (
                            <p className="muted">No risk factors documented.</p>
                        )}
                    </section>
                </div>

                <section className="detail-section">
                    <h2>Clinical Summary</h2>
                    <div className="snippet-container">
                        <pre className="snippet">{condition.wikidocSnippet || "No clinical snippet available."}</pre>
                    </div>
                </section>

                <section className="detail-section">
                    <h2>Geography Insights</h2>
                    <div className="placeholder-box">
                        <p className="muted">
                            Coming soon. This section will connect the condition to climate, industrial development,
                            population density, and cultural factors for a selected region.
                        </p>
                    </div>
                </section>

                <footer className="detail-section">
                    <h2>References & Sources</h2>
                    <ul className="source-list">
                        {condition.sameAs?.map((url) => (
                            <li key={url}>
                                <a href={url} target="_blank" rel="noreferrer">{url}</a>
                            </li>
                        ))}
                    </ul>
                </footer>
            </article>
        </section>
    );
}
