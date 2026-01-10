import React, { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getAllConditions } from "../api/conditionsApi.js";

const PAGE_SIZE = 10;

/**
 * Filter function to check if a condition matches the search term.
 */
const isConditionMatch = (condition, searchTerm) => {
    const name = (condition.name || "").toLowerCase();
    const id = String(condition.id || "").toLowerCase();
    return name.includes(searchTerm) || id.includes(searchTerm);
};

export default function HomePage() {
    const [conditions, setConditions] = useState([]);
    const [isLoading, setIsLoading] = useState(true);
    const [errorMessage, setErrorMessage] = useState("");
    const [searchQuery, setSearchQuery] = useState("");
    const [pageNumber, setPageNumber] = useState(1);

    useEffect(() => {
        let isMounted = true;
        
        async function loadConditions() {
            try {
                setErrorMessage("");
                setIsLoading(true);
                const data = await getAllConditions();
                if (isMounted) setConditions(data);
            } catch (err) {
                if (isMounted) setErrorMessage(err.message || String(err));
            } finally {
                if (isMounted) setIsLoading(false);
            }
        }

        loadConditions();
        
        return () => { isMounted = false; };
    }, []);

    const searchTerm = searchQuery.trim().toLowerCase();
    const filteredConditions = searchTerm
        ? conditions.filter((condition) => isConditionMatch(condition, searchTerm))
        : conditions;

    const totalPagesCount = Math.ceil(filteredConditions.length / PAGE_SIZE);
    
    // Validate current page number against total pages
    const activePage = totalPagesCount === 0 ? 1 : Math.min(pageNumber, totalPagesCount);
    
    const startIndex = (activePage - 1) * PAGE_SIZE;
    const paginatedConditions = filteredConditions.slice(startIndex, startIndex + PAGE_SIZE);

    useEffect(() => {
        // Adjust page number if it goes out of bounds (e.g., after filtering)
        if (totalPagesCount === 0 && pageNumber !== 1) {
            setPageNumber(1);
        } else if (totalPagesCount > 0 && pageNumber > totalPagesCount) {
            setPageNumber(totalPagesCount);
        }
    }, [pageNumber, totalPagesCount]);

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
            <div className="hero">
                <div className="hero-copy">
                    <p className="eyebrow">Project focus</p>
                    <h1 className="hero-title">
                        MeAd helps high-school students explore medical conditions
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
                        value={searchQuery}
                        onChange={handleSearchChange}
                    />
                </div>
            </div>

            {isLoading && <p className="status">Loading...</p>}
            {errorMessage && <p className="status error">Error: {errorMessage}</p>}

            {!isLoading && !errorMessage && (
                <>
                    <div className="section-head">
                        <h2>Condition list</h2>
                    </div>

                    {filteredConditions.length === 0 ? (
                        <p className="status">No conditions match your search.</p>
                    ) : (
                        <>
                            <ul className="list">
                                {paginatedConditions.map((condition) => (
                                    <li key={condition.id} className="card condition-card">
                                        <div className="card-body">
                                            <div>
                                                <h3>{condition.name}</h3>
                                                <div className="muted">id: {condition.id}</div>
                                            </div>
                                            <Link className="button" to={`/condition/${condition.id}`}>View details</Link>
                                        </div>
                                    </li>
                                ))}
                            </ul>

                            <div className="pagination">
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
                        </>
                    )}
                </>
            )}
        </section>
    );
}
