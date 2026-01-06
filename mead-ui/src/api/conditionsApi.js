const BASE = import.meta.env.VITE_CONDITIONS_API_BASE_URL;

async function httpGetJson(path) {
    const res = await fetch(`${BASE}${path}`, {
        headers: { Accept: "application/json" },
    });

    if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new Error(`HTTP ${res.status}: ${text || res.statusText}`);
    }
    return res.json();
}

export function fetchConditionsList() {
    return httpGetJson("/api/v1/conditions");
}

export function fetchConditionDetail(id) {
    return httpGetJson(`/api/v1/conditions/${encodeURIComponent(id)}`);
}
