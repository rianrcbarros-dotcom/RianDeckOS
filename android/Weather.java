package br.com.rian.deck;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Weather {
    static final class City {
        final String name;
        final double latitude, longitude;
        City(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    final int degrees, code;
    final boolean day;

    private Weather(int degrees, int code, boolean day) {
        this.degrees = degrees;
        this.code = code;
        this.day = day;
    }

    static Weather current(City city) throws Exception {
        JSONObject now = read("https://api.open-meteo.com/v1/forecast?latitude=" + city.latitude
            + "&longitude=" + city.longitude + "&current=temperature_2m,weather_code,is_day")
            .getJSONObject("current");
        return new Weather((int) Math.round(now.getDouble("temperature_2m")),
            now.getInt("weather_code"), now.getInt("is_day") == 1);
    }

    static List<City> search(String name) throws Exception {
        JSONArray results = read("https://geocoding-api.open-meteo.com/v1/search?name="
            + Uri.encode(name) + "&count=6&language=pt").optJSONArray("results");
        List<City> cities = new ArrayList<>();
        if (results == null) return cities;
        for (int index = 0; index < results.length(); index++) {
            JSONObject item = results.getJSONObject(index);
            String place = item.getString("name");
            String region = item.optString("admin1");
            if (!region.isEmpty() && !region.equals(place)) place += " · " + region;
            String country = item.optString("country_code");
            if (!country.isEmpty()) place += " · " + country;
            cities.add(new City(place, item.getDouble("latitude"), item.getDouble("longitude")));
        }
        return cities;
    }

    private static JSONObject read(String resource) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(resource).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > 65536) throw new IllegalStateException("Resposta grande demais");
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    boolean wet() { return code >= 51; }
    boolean snow() { return (code >= 71 && code <= 77) || code == 85 || code == 86; }
    boolean storm() { return code >= 95; }

    String description() {
        if (code == 0) return day ? "Céu limpo" : "Noite limpa";
        if (code == 1 || code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code <= 48) return "Névoa";
        if (storm()) return "Trovoadas";
        if (snow()) return "Neve";
        if (code <= 57) return "Garoa";
        return "Chuva";
    }
}
