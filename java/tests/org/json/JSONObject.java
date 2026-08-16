package org.json;

public final class JSONObject {
    private final String json;

    public JSONObject(String json) {
        this.json = json;
    }

    @Override
    public String toString() {
        return json;
    }
}
