import React, { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { getAllConditions, getConditionDetails } from "../api/conditionsApi.js";

const DEFAULT_PAGE_SIZE = 8;
const SWIPE_DISTANCE_THRESHOLD = 40;

const isConditionMatch = (condition, searchTerm) => {
    const name = (condition.name || "").toLowerCase();
    const id = String(condition.id || "").toLowerCase();
    return name.includes(searchTerm) || id.includes(searchTerm);
};

export default function ConditionsExplorerPage() {
    const [conditions, setConditions] = useState([]);
    const [isListLoading, setIsListLoading] = useState(true);
    const [listError, setListError] = useState("");
    const [searchQuery, setSearchQuery] = useState("");
    const [pageNumber, setPageNumber] = useState(1);
    const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

    const [activeConditionId, setActiveConditionId] = useState(null);
    const [conditionDetail, setConditionDetail] = useState(null);
    const [isDetailLoading, setIsDetailLoading] = useState(false);
    const [detailError, setDetailError] = useState("");

    const [currentImageIndex, setCurrentImageIndex] = useState(0);
    const [swipeStartX, setSwipeStartX] = useState(null);
    const [brokenImageUrls, setBrokenImageUrls] = useState(() => new Set());

    const listRef = useRef(null);
    const listPanelRef = useRef(null);
    const paginationRef = useRef(null);
    const detailCardRef = useRef(null);

    useEffect(() => {
        let isMounted = true;

        async function loadConditions() {
            try {
                setListError("");
                setIsListLoading(true);
                const data = await getAllConditions();
                if (isMounted) setConditions(data);
            } catch (err) {
                if (isMounted) setListError(err.message || String(err));
            } finally {
                if (isMounted) setIsListLoading(false);
            }
        }

        loadConditions();

        return () => { isMounted = false; };
    }, []);

    const searchTerm = searchQuery.trim().toLowerCase();
    const filteredConditions = searchTerm
        ? conditions.filter((condition) => isConditionMatch(condition, searchTerm))
        : conditions;

    const totalPagesCount = Math.ceil(filteredConditions.length / pageSize);
    const activePage = totalPagesCount === 0 ? 1 : Math.min(pageNumber, totalPagesCount);
    const startIndex = (activePage - 1) * pageSize;
    const paginatedConditions = filteredConditions.slice(startIndex, startIndex + pageSize);

    useEffect(() => {
        if (totalPagesCount === 0 && pageNumber !== 1) {
            setPageNumber(1);
        } else if (totalPagesCount > 0 && pageNumber > totalPagesCount) {
            setPageNumber(totalPagesCount);
        }
    }, [pageNumber, totalPagesCount]);

    useEffect(() => {
        const listEl = listRef.current;
        const detailCard = detailCardRef.current;
        const listPanel = listPanelRef.current;
        if (!listEl || !detailCard || !listPanel || filteredConditions.length === 0) return;

        const computePageSize = () => {
            const listStyles = getComputedStyle(listEl);
            const gapValue = listStyles.rowGap || listStyles.gap || "0";
            const rowGap = Number.parseFloat(gapValue) || 0;
            const itemHeightValue = listStyles.getPropertyValue("--list-item-height");
            const itemHeight = Number.parseFloat(itemHeightValue) || 160;
            const panelStyles = getComputedStyle(listPanel);
            const panelGap = Number.parseFloat(panelStyles.rowGap || panelStyles.gap || "0") || 0;
            const paginationHeight = paginationRef.current
                ? paginationRef.current.getBoundingClientRect().height
                : 0;
            const detailHeight = detailCard.getBoundingClientRect().height;
            const availableHeight = detailHeight - paginationHeight - panelGap;
            if (itemHeight <= 0 || availableHeight <= 0) return;
            const fitCount = Math.max(1, Math.floor((availableHeight + rowGap) / (itemHeight + rowGap)));
            setPageSize((prev) => (prev === fitCount ? prev : fitCount));
        };

        computePageSize();

        if (typeof ResizeObserver === "undefined") return;
        const observer = new ResizeObserver(() => computePageSize());
        observer.observe(detailCard);
        observer.observe(listPanel);
        if (paginationRef.current) observer.observe(paginationRef.current);
        return () => observer.disconnect();
    }, [filteredConditions.length, conditionDetail]);

    useEffect(() => {
        if (!activeConditionId && filteredConditions.length > 0) {
            setActiveConditionId(filteredConditions[0].id);
            return;
        }
        if (activeConditionId && !filteredConditions.some((c) => c.id === activeConditionId)) {
            setActiveConditionId(filteredConditions[0]?.id || null);
        }
    }, [activeConditionId, filteredConditions]);

    useEffect(() => {
        let isMounted = true;
        if (!activeConditionId) {
            setConditionDetail(null);
            return () => { isMounted = false; };
        }

        async function loadConditionDetail() {
            try {
                setDetailError("");
                setIsDetailLoading(true);
                const data = await getConditionDetails(activeConditionId);
                if (isMounted) setConditionDetail(data);
            } catch (err) {
                if (isMounted) setDetailError(err.message || String(err));
            } finally {
                if (isMounted) setIsDetailLoading(false);
            }
        }

        loadConditionDetail();

        return () => { isMounted = false; };
    }, [activeConditionId]);

    useEffect(() => {
        setCurrentImageIndex(0);
        setBrokenImageUrls(new Set());
    }, [activeConditionId]);

    const allImages = useMemo(() => conditionDetail?.images || [], [conditionDetail]);

    const validImages = useMemo(() => {
        return allImages.filter((url) => !brokenImageUrls.has(url));
    }, [allImages, brokenImageUrls]);

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

    const handleSearchChange = (event) => {
        setSearchQuery(event.target.value);
        setPageNumber(1);
    };

    const handlePreviousPage = () => {
        setPageNumber((prev) => Math.max(1, prev - 1));
    };

    const handleNextPage = () => {
        setPageNumber((prev) => Math.min(totalPagesCount, prev + 1));
    };

    return (
        <section className="page">
            <Link to="/" className="back-link">{"<-"} Back to home</Link>

            <div className="hero">
                <div className="hero-copy">
                    <p className="eyebrow">Medical explorer</p>
                    <h1 className="hero-title">Medical conditions directory</h1>
                    <p className="hero-subtitle">
                        Review symptoms, risk factors, and clinical summaries from linked open data sources.
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
                        value={searchQuery}
                        onChange={handleSearchChange}
                    />
                </div>
            </div>

            {isListLoading && <p className="status">Loading conditions...</p>}
            {listError && <p className="status error">Error: {listError}</p>}

            {!isListLoading && !listError && (
                <div className="split-layout">
                    <div className="list-column">
                        <div className="section-head">
                            <h2>Condition list</h2>
                        </div>

                        {filteredConditions.length === 0 ? (
                            <p className="status">No conditions match your search.</p>
                        ) : (
                            <>
                                <div className="list-panel" ref={listPanelRef}>
                                    <div className="list-scroll">
                                        <ul className="list" ref={listRef}>
                                            {paginatedConditions.map((condition) => (
                                                <li
                                                    key={condition.id}
                                                    className={`card condition-card${condition.id === activeConditionId ? " active" : ""}`}
                                                >
                                                    <div className="card-body list-row">
                                                        <div>
                                                            <h3>{condition.name}</h3>
                                                            <div className="muted">id: {condition.id}</div>
                                                        </div>
                                                        <button
                                                            type="button"
                                                            className="button"
                                                            onClick={() => setActiveConditionId(condition.id)}
                                                            aria-pressed={condition.id === activeConditionId}
                                                        >
                                                            View details
                                                        </button>
                                                    </div>
                                                </li>
                                            ))}
                                        </ul>
                                    </div>

                                    <div className="pagination" ref={paginationRef}>
                                        <button
                                            className="page-button"
                                            type="button"
                                            onClick={handlePreviousPage}
                                            disabled={activePage === 1}
                                        >
                                            Previous
                                        </button>
                                        <span className="page-status">Page {activePage} of {totalPagesCount}</span>
                                        <button
                                            className="page-button"
                                            type="button"
                                            onClick={handleNextPage}
                                            disabled={activePage === totalPagesCount}
                                        >
                                            Next
                                        </button>
                                    </div>
                                </div>
                            </>
                        )}
                    </div>

                    <div className="detail-column">
                        <div className="section-head">
                            <h2>Condition details</h2>
                        </div>
                        {isDetailLoading && <p className="status">Loading condition details...</p>}
                        {detailError && <p className="status error">Error: {detailError}</p>}
                        {!isDetailLoading && !detailError && !conditionDetail && filteredConditions.length > 0 && (
                            <p className="status">Select a condition to see details.</p>
                        )}

                        {!isDetailLoading && !detailError && conditionDetail && (
                            <article
                                vocab={conditionDetail.context || "https://schema.org/"}
                                typeof={conditionDetail.type || "MedicalCondition"}
                                resource={conditionDetail.id}
                                className="card detail-card"
                                ref={detailCardRef}
                            >
                                <header className={`detail-header${currentImageUrl ? "" : " single"}`}>
                                    <div className="title-group">
                                        <p className="eyebrow">Medical condition</p>
                                        <h1 property="name">{conditionDetail.name}</h1>
                                        <p className="muted">ID: {conditionDetail.identifier}</p>
                                    </div>

                                    {currentImageUrl && (
                                        <div className="carousel" onTouchStart={handleTouchStart} onTouchEnd={handleTouchEnd}>
                                            <div className="carousel-frame image-frame">
                                                <img
                                                    src={currentImageUrl}
                                                    alt={conditionDetail.name}
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
                                                            {"<"}
                                                        </button>
                                                        <button
                                                            type="button"
                                                            className="carousel-arrow right"
                                                            onClick={navigateToNextImage}
                                                            aria-label="Next image"
                                                        >
                                                            {">"}
                                                        </button>
                                                    </>
                                                )}
                                            </div>

                                            {imageCount > 1 && (
                                                <div className="carousel-dots">
                                                    {validImages.map((_, index) => (
                                                        <button
                                                            key={`${conditionDetail.id}-img-${index}`}
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
                                        {conditionDetail.description || "No description available."}
                                    </p>
                                </div>

                                <div className="detail-grid">
                                    <section className="detail-section">
                                        <h2>Symptoms</h2>
                                        {conditionDetail.symptoms?.length ? (
                                            <ul className="info-list">
                                                {conditionDetail.symptoms.map((symptom) => (
                                                    <li key={symptom} property="signOrSymptom">{symptom}</li>
                                                ))}
                                            </ul>
                                        ) : (
                                            <p className="muted">No symptoms documented.</p>
                                        )}
                                    </section>

                                    <section className="detail-section">
                                        <h2>Risk factors</h2>
                                        {conditionDetail.riskFactors?.length ? (
                                            <ul className="info-list">
                                                {conditionDetail.riskFactors.map((factor) => (
                                                    <li key={factor} property="riskFactor">{factor}</li>
                                                ))}
                                            </ul>
                                        ) : (
                                            <p className="muted">No risk factors documented.</p>
                                        )}
                                    </section>
                                </div>

                                <section className="detail-section">
                                    <h2>Clinical summary</h2>
                                    <div className="snippet-container">
                                        <pre className="snippet">{conditionDetail.wikidocSnippet || "No clinical snippet available."}</pre>
                                    </div>
                                </section>

                                <footer className="detail-section">
                                    <h2>References & Sources</h2>
                                    <ul className="source-list">
                                        {conditionDetail.sameAs?.map((url) => (
                                            <li key={url}>
                                                <a href={url} target="_blank" rel="noreferrer">{url}</a>
                                            </li>
                                        ))}
                                    </ul>
                                </footer>
                            </article>
                        )}
                    </div>
                </div>
            )}
        </section>
    );
}
