const GEOGRAPHY_API_BASE_URL = import.meta.env.VITE_GEOGRAPHY_API_BASE_URL;

/**
 * Utility to perform API requests to the geography service.
 */
async function fetchFromApi(endpoint) {
    if (!GEOGRAPHY_API_BASE_URL) {
        throw new Error("Missing VITE_GEOGRAPHY_API_BASE_URL.");
    }
    const response = await fetch(`${GEOGRAPHY_API_BASE_URL}${endpoint}`, {
        headers: { Accept: "application/json" },
    });

    if (!response.ok) {
        const errorDetail = await response.text().catch(() => "");
        throw new Error(`API Error (${response.status}): ${errorDetail || response.statusText}`);
    }
    return response.json();
}

/**
 * Retrieves the full list of regions.
 */
export function getAllRegions() {
    return fetchFromApi("/api/v1/regions");
}

/**
 * Retrieves detailed information for a specific region.
 * @param {string} regionId - The unique identifier of the region.
 */
export function getRegionDetails(regionId) {
    return fetchFromApi(`/api/v1/regions/${encodeURIComponent(regionId)}`);
}
