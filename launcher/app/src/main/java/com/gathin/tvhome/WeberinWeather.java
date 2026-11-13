package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Open-Meteo is contacted only for a city selected by the user. */
final class WeberinWeather {
    static final class Reading {
        final String city;
        final int temperature, humidity, low, high, code;
        Reading(String city, int temperature, int humidity, int low, int high, int code) {
            this.city = city; this.temperature = temperature; this.humidity = humidity;
            this.low = low; this.high = high; this.code = code;
        }
    }

    private WeberinWeather() { }

    static Reading fetch(String city, String language) throws IOException, JSONException {
        String query = URLEncoder.encode(city, "UTF-8");
        String lang = "ru".equals(language) ? "ru" : "en";
        JSONObject locations = get("https://geocoding-api.open-meteo.com/v1/search?name=" + query
                + "&count=1&language=" + lang + "&format=json");
        JSONArray results = locations.optJSONArray("results");
        if (results == null || results.length() == 0) throw new IOException("City not found");
        JSONObject location = results.getJSONObject(0);
        double latitude = location.getDouble("latitude"), longitude = location.getDouble("longitude");
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) throw new IOException("Invalid coordinates");
        String endpoint = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                + "&current=temperature_2m,relative_humidity_2m,weather_code"
                + "&daily=temperature_2m_min,temperature_2m_max&forecast_days=1&timezone=auto", latitude, longitude);
        JSONObject forecast = get(endpoint);
        JSONObject current = forecast.getJSONObject("current"), daily = forecast.getJSONObject("daily");
        return new Reading(location.getString("name"), (int) Math.round(current.getDouble("temperature_2m")),
                current.getInt("relative_humidity_2m"), (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)), current.getInt("weather_code"));
    }

    private static JSONObject get(String url) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Weather service unavailable");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 131072) throw new IOException("Weather response too large");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    static int condition(int code) {
        if (code == 0) return 0; // clear
        if (code <= 3) return 1; // cloudy
        if (code == 45 || code == 48) return 2; // fog
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return 3; // rain
        if (code >= 71 && code <= 77 || code >= 85 && code <= 86) return 4; // snow
        if (code >= 95 && code <= 99) return 5; // thunderstorm
        return 1;
    }
}
