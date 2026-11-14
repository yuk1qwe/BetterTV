package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WeberinWeatherTest {
    @Test public void mapsOpenMeteoConditionsToVisibleCategories() {
        assertEquals(0, WeberinWeather.condition(0));
        assertEquals(1, WeberinWeather.condition(3));
        assertEquals(2, WeberinWeather.condition(48));
        assertEquals(3, WeberinWeather.condition(61));
        assertEquals(3, WeberinWeather.condition(82));
        assertEquals(4, WeberinWeather.condition(75));
        assertEquals(4, WeberinWeather.condition(86));
        assertEquals(5, WeberinWeather.condition(95));
    }
}
