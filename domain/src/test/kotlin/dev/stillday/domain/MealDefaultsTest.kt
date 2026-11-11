package dev.stillday.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MealDefaultsTest {
    private val day = LocalDate.of(2026,11,11)
    private val meal = MealTemplate("m", 3, "Lunch", "13:00", "Rice", MealDefault.SKIP)
    private val data = Snapshot(meals = listOf(meal))
    private fun lunch(date: LocalDate = day, s: Snapshot = data) = LifeResolver.meals(date,s)[1]
    @Test fun wednesdayLunchRepeatsWithoutSkippingOtherMealsOrWeekdays() {
        repeat(4) { week ->
            val date = day.plusWeeks(week.toLong())
            assertTrue(lunch(date).skipped)
            assertFalse(lunch(date.minusDays(1)).skipped)
            assertFalse(lunch(date.plusDays(1)).skipped)
            assertFalse(LifeResolver.meals(date,data).first().skipped)
            assertFalse(LifeResolver.meals(date,data).last().skipped)
        }
    }
    @Test fun skippedWeekdayReleasesOnlyThatMealsTime() {
        assertTrue(DayPlanner.build(day,data,Preferences()).blocks.none { it.kind==DayBlockKind.MEAL && it.title=="Lunch" })
        assertTrue(DayPlanner.build(day.plusDays(1),data,Preferences()).blocks.any { it.kind==DayBlockKind.MEAL && it.title=="Lunch" })
    }
    @Test fun datedEatingRestoresOnlyOneSkippedWednesday() {
        val s=data.copy(mealOverrides=listOf(MealOverride(date=day.toString(),slot="Lunch",action=MealAction.EAT,time="14:15")))
        assertFalse(lunch(s=s).skipped); assertEquals("14:15",lunch(s=s).time)
        assertTrue(lunch(day.plusWeeks(1),s).skipped)
    }
    @Test fun datedOutsideAndDifferentFoodOverrideTheWeekdaySkip() {
        listOf(MealAction.OUTSIDE,MealAction.DIFFERENT).forEach { action ->
            assertFalse(lunch(s=data.copy(mealOverrides=listOf(MealOverride(date=day.toString(),slot="Lunch",action=action,food="Fruit")))).skipped)
        }
    }
    @Test fun datedSkipDoesNotCreateARecurringRule() {
        val s=Snapshot(mealOverrides=listOf(MealOverride(date=day.toString(),slot="Lunch",action=MealAction.SKIP)))
        assertTrue(lunch(s=s).skipped); assertFalse(lunch(day.plusWeeks(1),s).skipped)
    }
    @Test fun removingWeeklySkipKeepsItsMenuAndRestoresEating() {
        val edited=meal.copy(defaultPlan=MealDefault.EAT)
        assertFalse(lunch(s=data.copy(meals=listOf(edited))).skipped)
        assertEquals("Rice",lunch(s=data.copy(meals=listOf(edited))).description)
    }
    @Test fun oldEverydayPreferenceIsIgnoredAndOldTemplatesMeanEat() {
        val json=Json { ignoreUnknownKeys=true }
        val p=json.decodeFromString<Preferences>("""{"skippedMeals":["Breakfast","Lunch","Dinner"]}""")
        assertTrue(LifeResolver.meals(day,Snapshot(),p).none { it.skipped })
        val old=json.decodeFromString<MealTemplate>("""{"id":"m","weekday":3,"slot":"Lunch","time":"13:00","food":"Rice"}""")
        assertFalse(lunch(s=Snapshot(meals=listOf(old))).skipped)
        assertFalse(Json.encodeToString(p).contains("skippedMeals"))
    }
    @Test fun weeklyAndDatedRulesRoundTripWithoutEverydaySettings() {
        val s=data.copy(mealOverrides=listOf(MealOverride(date=day.toString(),slot="Lunch",action=MealAction.EAT)))
        assertEquals(s,Json.decodeFromString<Snapshot>(Json.encodeToString(s)))
    }
}
