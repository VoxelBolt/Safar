package com.safar.app.ui.onboarding

import com.safar.app.domain.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingErrorMessagesTest {

    @Test
    fun loginMismatch_namesTheSingleWrongField() {
        val message = OnboardingErrorMessages.messageFor(
            "SAFAR_LOGIN_MISMATCH:name",
            AuthMode.LOGIN,
            UserRole.PASSENGER,
        )

        assertEquals("Неверно указано: имя.", message)
    }

    @Test
    fun loginMismatch_listsEveryWrongField() {
        val message = OnboardingErrorMessages.messageFor(
            "PostgrestRestException: SAFAR_LOGIN_MISMATCH:phone,name,vehicle\nURL: https://example.supabase.co/rest/v1/rpc/login_driver",
            AuthMode.LOGIN,
            UserRole.DRIVER,
        )

        assertEquals("Неверно указаны: номер телефона, имя и номер транспорта.", message)
    }

    @Test
    fun loginNotFound_dependsOnRole() {
        val passenger = OnboardingErrorMessages.messageFor("SAFAR_LOGIN_NOT_FOUND", AuthMode.LOGIN, UserRole.PASSENGER)
        val driver = OnboardingErrorMessages.messageFor("SAFAR_LOGIN_NOT_FOUND", AuthMode.LOGIN, UserRole.DRIVER)

        assertTrue(passenger.contains("номером телефона не найден"))
        assertTrue(driver.startsWith("Водитель"))
    }

    @Test
    fun duplicatePhone_suggestsLogin() {
        val message = OnboardingErrorMessages.messageFor("SAFAR_PHONE_TAKEN", AuthMode.REGISTER, UserRole.PASSENGER)

        assertTrue(message.contains("уже зарегистрирован"))
        assertTrue(message.contains("«Вход»"))
    }

    @Test
    fun legacyUniqueIndexViolation_isStillRecognized() {
        val message = OnboardingErrorMessages.messageFor(
            "duplicate key value violates unique constraint \"users_phone_unique\"",
            AuthMode.REGISTER,
            UserRole.PASSENGER,
        )

        assertTrue(message.contains("уже зарегистрирован"))
    }

    @Test
    fun duplicateVehicle_isReported() {
        val message = OnboardingErrorMessages.messageFor("SAFAR_VEHICLE_TAKEN", AuthMode.REGISTER, UserRole.DRIVER)

        assertEquals("Этот номер транспорта уже зарегистрирован другим водителем.", message)
    }

    @Test
    fun wrongRole_pointsToTheOtherRole() {
        val message = OnboardingErrorMessages.messageFor("SAFAR_LOGIN_ROLE_DRIVER", AuthMode.LOGIN, UserRole.PASSENGER)

        assertTrue(message.contains("«Водитель»"))
    }

    @Test
    fun missingServerFunction_asksForDatabaseUpdate() {
        val message = OnboardingErrorMessages.messageFor(
            "PGRST202 Could not find the function public.login_passenger(p_name, p_phone) in the schema cache",
            AuthMode.LOGIN,
            UserRole.PASSENGER,
        )

        assertTrue(message.contains("не обновлён"))
    }

    @Test
    fun unknownErrors_fallBackToModeSpecificText() {
        assertEquals(
            "Не удалось войти. Проверьте интернет и попробуйте ещё раз.",
            OnboardingErrorMessages.messageFor("boom", AuthMode.LOGIN, UserRole.PASSENGER),
        )
        assertEquals(
            "Не удалось зарегистрироваться. Проверьте интернет и попробуйте ещё раз.",
            OnboardingErrorMessages.messageFor("boom", AuthMode.REGISTER, UserRole.PASSENGER),
        )
    }

    @Test
    fun message_readsTheWholeCauseChain() {
        val error = IllegalStateException("wrapper", RuntimeException("SAFAR_VEHICLE_TAKEN"))

        assertEquals(
            "Этот номер транспорта уже зарегистрирован другим водителем.",
            OnboardingErrorMessages.message(error, AuthMode.REGISTER, UserRole.DRIVER),
        )
    }
}
