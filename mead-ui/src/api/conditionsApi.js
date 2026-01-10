const CONDITIONS_API_BASE_URL = import.meta.env.VITE_CONDITIONS_API_BASE_URL;

/**
 * Utility to perform API requests to the conditions service.
 */
async function fetchFromApi(endpoint) {
    const response = await fetch(`${CONDITIONS_API_BASE_URL}${endpoint}`, {
        headers: { Accept: "application/json" },
    });

    if (!response.ok) {
        const errorDetail = await response.text().catch(() => "");
        throw new Error(`API Error (${response.status}): ${errorDetail || response.statusText}`);
    }
    return response.json();
}

/**
 * Retrieves the full list of medical conditions.
 */
export function getAllConditions() {
    return fetchFromApi("/api/v1/conditions");
}

/**
 * Retrieves detailed information for a specific condition.
 * @param {string} conditionId - The unique identifier of the condition.
 */
export function getConditionDetails(conditionId) {
    return fetchFromApi(`/api/v1/conditions/${encodeURIComponent(conditionId)}`);
}
