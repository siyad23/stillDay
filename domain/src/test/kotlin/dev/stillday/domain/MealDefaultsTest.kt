package dev.stillday.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MealDefaultsTest {
    private val day = LocalDate.of(2026, 11, 10)
    private val template = MealTemplate("meal", 2, "Breakfast", "08:00", "Egg and bread")
    private val data = Snapshot(meals = listOf(template))
    private fun breakfast(s: Snapshot = data, p: Preferences = Preferences()) = LifeResolver.meals(day, s, p).first()

    @Test fun everydaySkipAppliesAcrossTheWeekAndReleasesAllBreakfastTime() {
        val p = Preferences(skippedMeals = setOf("Breakfast"))
        repeat(7) { offset ->
            val date = day.plusDays(offset.toLong())
            assertTrue(LifeResolver.meals(date, data, p).first().skipped)
            assertTrue(DayPlanner.build(date, data, p).blocks.none { it.kind == DayBlockKind.MEAL && it.title == "Breakfast" })
        }
        assertFalse(LifeResolver.meals(day, data, p)[1].skipped)
    }
    @Test fun weekdayEatingOverridesEverydaySkipOnlyOnThatWeekday() {
        val s = data.copy(meals = listOf(template.copy(defaultPlan = MealDefault.EAT)))
        val p = Preferences(skippedMeals = setOf("Breakfast"))
        assertFalse(breakfast(s, p).skipped)
        assertTrue(LifeResolver.meals(day.plusDays(1), s, p).first().skipped)
        assertFalse(LifeResolver.meals(day.plusWeeks(1), s, p).first().skipped)
    }
    @Test fun weekdaySkippingRepeatsWithoutSkippingOtherDays() {
        val s = data.copy(meals = listOf(template.copy(defaultPlan = MealDefault.SKIP)))
        assertTrue(breakfast(s).skipped)
        assertFalse(LifeResolver.meals(day.plusDays(1), s).first().skipped)
        assertTrue(LifeResolver.meals(day.plusWeeks(1), s).first().skipped)
    }
    @Test fun datedEatWinsOverGlobalAndWeeklySkippingAndReservesItsManualTime() {
        val s = data.copy(meals = listOf(template.copy(defaultPlan = MealDefault.SKIP)), mealOverrides = listOf(
            MealOverride(date = day.toString(), slot = "Breakfast", action = MealAction.EAT, time = "09:15")))
        val p = Preferences(skippedMeals = setOf("Breakfast"), mealMinutes = 25)
        val meal = breakfast(s, p)
        assertFalse(meal.skipped); assertEquals("Egg and bread", meal.description); assertEquals("09:15", meal.time)
        val block = DayPlanner.build(day, s, p).blocks.single { it.kind == DayBlockKind.MEAL && it.title == "Breakfast" }
        assertEquals(25, block.minutes); assertEquals("09:15", block.start.toLocalTime().toString())
        assertTrue(LifeResolver.meals(day.plusWeeks(1), s, p).first().skipped)
    }
    @Test fun datedSkipWinsOverWeeklyEatingAndRemovingItRestoresTheDefault() {
        val s = data.copy(meals = listOf(template.copy(defaultPlan = MealDefault.EAT)),
            mealOverrides = listOf(MealOverride(date = day.toString(), slot = "Breakfast", action = MealAction.SKIP)))
        assertTrue(breakfast(s).skipped)
        assertFalse(breakfast(s.copy(mealOverrides = emptyList())).skipped)
    }
    @Test fun OutsideAndDifferentFoodRestoreMealsThatAreUsuallySkipped() {
        val p = Preferences(skippedMeals = setOf("Breakfast"))
        listOf(MealAction.OUTSIDE, MealAction.DIFFERENT).forEach { action ->
            val s = data.copy(mealOverrides = listOf(MealOverride(date = day.toString(), slot = "Breakfast", action = action, food = "Fruit")))
            assertFalse(breakfast(s, p).skipped)
        }
    }
    @Test fun defaultsApplyAtHomeAndOldBackupsKeepEatingUntilConfigured() {
        val home = data.copy(travel = listOf(TravelLeg(destination = Place.HOME, departure = "2026-11-09T12:00", arrival = "2026-11-09T15:00")))
        assertTrue(breakfast(home, Preferences(skippedMeals = setOf("Breakfast"))).skipped)
        assertEquals("Home meal · flexible", breakfast(home).description)
        assertTrue(Json.decodeFromString<Preferences>("{}").skippedMeals.isEmpty())
        val old = Json.decodeFromString<MealTemplate>("""{"id":"m","weekday":2,"slot":"Breakfast","time":"08:00","food":"Egg"}""")
        assertEquals(MealDefault.FOLLOW_DEFAULT, old.defaultPlan)
    }
    @Test fun serializationPreservesGlobalAndWeeklyRulesAndRejectsUnknownDefaults() {
        val prefs = Preferences(skippedMeals = setOf("Breakfast", "Dinner"))
        assertEquals(prefs, Json.decodeFromString<Preferences>(Json.encodeToString(prefs)))
        val meal = template.copy(defaultPlan = MealDefault.SKIP)
        assertEquals(meal, Json.decodeFromString<MealTemplate>(Json.encodeToString(meal)))
        assertThrows(IllegalArgumentException::class.java) { Validation.preferences(Preferences(skippedMeals = setOf("Unknown meal"))) }
    }
}
