import React, { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { getAllRegions, getRegionDetails } from "../api/geographyApi.js";

const DEFAULT_PAGE_SIZE = 8;

const isRegionMatch = (region, searchTerm) => {
    const name = (region.name || "").toLowerCase();
    const id = String(region.id || "").toLowerCase();
    return name.includes(searchTerm) || id.includes(searchTerm);
};

const formatNumber = (value, maxFractionDigits) => {
    if (value === null || value === undefined) return null;
    const raw = String(value).trim();
    if (!raw) return null;
    const normalized = raw.replace(/,/g, "");
    const numeric = Number(normalized);
    if (!Number.isFinite(numeric)) return raw;
    return new Intl.NumberFormat("en-US", { maximumFractionDigits: maxFractionDigits }).format(numeric);
};

export default function GeographyPage() {
    const [regions, setRegions] = useState([]);
    const [isListLoading, setIsListLoading] = useState(true);
    const [listError, setListError] = useState("");
    const [searchQuery, setSearchQuery] = useState("");
    const [pageNumber, setPageNumber] = useState(1);
    const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

    const [activeRegionId, setActiveRegionId] = useState(null);
    const [regionDetail, setRegionDetail] = useState(null);
    const [isDetailLoading, setIsDetailLoading] = useState(false);
    const [detailError, setDetailError] = useState("");
    const [brokenImageUrls, setBrokenImageUrls] = useState(() => new Set());

    const listRef = useRef(null);
    const listPanelRef = useRef(null);
    const paginationRef = useRef(null);
    const detailCardRef = useRef(null);

    useEffect(() => {
        let isMounted = true;

        async function loadRegions() {
            try {
                setListError("");
                setIsListLoading(true);
                const data = await getAllRegions();
                if (isMounted) setRegions(data);
            } catch (err) {
                if (isMounted) setListError(err.message || String(err));
            } finally {
                if (isMounted) setIsListLoading(false);
            }
        }

        loadRegions();

        return () => { isMounted = false; };
    }, []);

    const searchTerm = searchQuery.trim().toLowerCase();
    const filteredRegions = searchTerm
        ? regions.filter((region) => isRegionMatch(region, searchTerm))
        : regions;

    const totalPagesCount = Math.ceil(filteredRegions.length / pageSize);
    const activePage = totalPagesCount === 0 ? 1 : Math.min(pageNumber, totalPagesCount);
    const startIndex = (activePage - 1) * pageSize;
    const paginatedRegions = filteredRegions.slice(startIndex, startIndex + pageSize);

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
        if (!listEl || !detailCard || !listPanel || filteredRegions.length === 0) return;

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
    }, [filteredRegions.length, regionDetail]);

    useEffect(() => {
        if (!activeRegionId && filteredRegions.length > 0) {
            setActiveRegionId(filteredRegions[0].id);
            return;
        }
        if (activeRegionId && !filteredRegions.some((r) => r.id === activeRegionId)) {
            setActiveRegionId(filteredRegions[0]?.id || null);
        }
    }, [activeRegionId, filteredRegions]);

    useEffect(() => {
        let isMounted = true;
        if (!activeRegionId) {
            setRegionDetail(null);
            return () => { isMounted = false; };
        }

        async function loadRegionDetail() {
            try {
                setDetailError("");
                setIsDetailLoading(true);
                const data = await getRegionDetails(activeRegionId);
                if (isMounted) setRegionDetail(data);
            } catch (err) {
                if (isMounted) setDetailError(err.message || String(err));
            } finally {
                if (isMounted) setIsDetailLoading(false);
            }
        }

        loadRegionDetail();

        return () => { isMounted = false; };
    }, [activeRegionId]);

    useEffect(() => {
        setBrokenImageUrls(new Set());
    }, [activeRegionId]);

    const validImages = useMemo(() => {
        return (regionDetail?.images || []).filter((url) => !brokenImageUrls.has(url));
    }, [regionDetail, brokenImageUrls]);

    const limitedImages = validImages.slice(0, 6);
    const populationDisplay = formatNumber(regionDetail?.populationTotal, 0);
    const densityDisplay = formatNumber(regionDetail?.populationDensity, 2);

    const handleImageError = (url) => {
        setBrokenImageUrls((prev) => {
            if (prev.has(url)) return prev;
            const updated = new Set(prev);
            updated.add(url);
            return updated;
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
            <Link to="/" className="back-link">Back to home</Link>

            <div className="hero">
                <div className="hero-copy">
                    <p className="eyebrow">Geography explorer</p>
                    <h1 className="hero-title">Population insights by region</h1>
                    <p className="hero-subtitle">
                        Explore climate, industrial development, population density, and cultural factors using linked open data.
                    </p>
                </div>
            </div>

            <div className="search-panel">
                <div>
                    <h2>Search regions</h2>
                    <p className="muted">Filter by name or id.</p>
                </div>
                <div className="search-field">
                    <input
                        id="region-search"
                        className="search-input"
                        type="search"
                        placeholder="Start typing a region..."
                        value={searchQuery}
                        onChange={handleSearchChange}
                    />
                </div>
            </div>

            {isListLoading && <p className="status">Loading regions...</p>}
            {listError && <p className="status error">Error: {listError}</p>}

            {!isListLoading && !listError && (
                <div className="split-layout">
                    <div className="list-column">
                        <div className="section-head">
                            <h2>Region list</h2>
                        </div>

                        {filteredRegions.length === 0 ? (
                            <p className="status">No regions match your search.</p>
                        ) : (
                            <>
                                <div className="list-panel" ref={listPanelRef}>
                                    <div className="list-scroll">
                                        <ul className="list" ref={listRef}>
                                            {paginatedRegions.map((region) => (
                                                <li
                                                    key={region.id}
                                                    className={`card condition-card${region.id === activeRegionId ? " active" : ""}`}
                                                >
                                                    <div className="card-body list-row">
                                                        <div>
                                                            <h3>{region.name}</h3>
                                                            <div className="muted">id: {region.id}</div>
                                                        </div>
                                                        <button
                                                            type="button"
                                                            className="button"
                                                            onClick={() => setActiveRegionId(region.id)}
                                                            aria-pressed={region.id === activeRegionId}
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
                            <h2>Region details</h2>
                        </div>
                        {isDetailLoading && <p className="status">Loading region details...</p>}
                        {detailError && <p className="status error">Error: {detailError}</p>}
                        {!isDetailLoading && !detailError && !regionDetail && filteredRegions.length > 0 && (
                            <p className="status">Select a region to see details.</p>
                        )}

                        {!isDetailLoading && !detailError && regionDetail && (
                            <article
                                vocab={regionDetail.context || "https://schema.org/"}
                                typeof={regionDetail.type || "Place"}
                                resource={regionDetail.id}
                                className="card detail-card"
                                ref={detailCardRef}
                            >
                                <header className="detail-header single">
                                    <div className="title-group">
                                        <p className="eyebrow">Geographic region</p>
                                        <h1 property="name">{regionDetail.name}</h1>
                                        <p className="muted">ID: {regionDetail.identifier}</p>
                                    </div>
                                </header>

                                <div className="detail-section">
                                    <h2>Overview</h2>
                                    <p property="description">
                                        {regionDetail.description || "No description available."}
                                    </p>
                                </div>

                                <div className="detail-section">
                                    <h2>Population</h2>
                                    <div className="detail-stats">
                                        <div className="stat-card">
                                            <div className="muted">Population total</div>
                                            <div className="stat-value">{populationDisplay || "Unknown"}</div>
                                        </div>
                                        <div className="stat-card">
                                            <div className="muted">Population density</div>
                                            <div className="stat-value">{densityDisplay || "Unknown"}</div>
                                        </div>
                                    </div>
                                </div>

                                <div className="detail-grid">
                                    <section className="detail-section">
                                        <h2>Climate</h2>
                                        {regionDetail.climates?.length ? (
                                            <ul className="info-list">
                                                {regionDetail.climates.map((climate) => (
                                                    <li key={climate}>{climate}</li>
                                                ))}
                                            </ul>
                                        ) : (
                                            <p className="muted">No climate data available.</p>
                                        )}
                                    </section>

                                    <section className="detail-section">
                                        <h2>Industrial development</h2>
                                        {regionDetail.industrialDevelopment?.length ? (
                                            <ul className="info-list">
                                                {regionDetail.industrialDevelopment.map((industry) => (
                                                    <li key={industry}>{industry}</li>
                                                ))}
                                            </ul>
                                        ) : (
                                            <p className="muted">No industry data available.</p>
                                        )}
                                    </section>

                                    <section className="detail-section">
                                        <h2>Cultural factors</h2>
                                        {regionDetail.culturalFactors?.length ? (
                                            <ul className="info-list">
                                                {regionDetail.culturalFactors.map((factor) => (
                                                    <li key={factor}>{factor}</li>
                                                ))}
                                            </ul>
                                        ) : (
                                            <p className="muted">No cultural data available.</p>
                                        )}
                                    </section>
                                </div>

                                <section className="detail-section">
                                    <h2>Images</h2>
                                    {limitedImages.length ? (
                                        <div className="image-grid">
                                            {limitedImages.map((url) => (
                                                <img
                                                    key={url}
                                                    src={url}
                                                    alt={regionDetail.name}
                                                    onError={() => handleImageError(url)}
                                                />
                                            ))}
                                        </div>
                                    ) : (
                                        <p className="muted">No images available.</p>
                                    )}
                                </section>

                                <section className="detail-section">
                                    <h2>Summary</h2>
                                    <div className="snippet-container">
                                        <pre className="snippet">
                                            {regionDetail.wikidocSnippet || "No local summary available."}
                                        </pre>
                                    </div>
                                </section>

                                <footer className="detail-section">
                                    <h2>References & Sources</h2>
                                    <ul className="source-list">
                                        {regionDetail.sameAs?.map((url) => (
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
