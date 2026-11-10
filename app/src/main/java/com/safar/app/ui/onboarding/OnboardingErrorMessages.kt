package com.safar.app.ui.onboarding

import com.safar.app.domain.model.UserRole

/**
 * Maps registration and login failures to Russian UI text.
 *
 * The backend reports business errors with stable SAFAR_* codes (see the
 * 20261110120000_login_and_registration_fixes migration). The mapping has no
 * Android dependencies so it is covered by plain unit tests.
 */
internal object OnboardingErrorMessages {
    private val MISMATCH_PATTERN = Regex("safar_login_mismatch:([a-z,]+)")

    fun message(error: Throwable, mode: AuthMode, role: UserRole?): String =
        messageFor(chainText(error), mode, role)

    fun messageFor(rawChain: String, mode: AuthMode, role: UserRole?): String {
        val chain = rawChain.lowercase()
        MISMATCH_PATTERN.find(chain)?.let { match ->
            return mismatchMessage(match.groupValues[1].split(',').filter { it.isNotBlank() })
        }
        return when {
            "safar_login_not_found" in chain -> if (role == UserRole.DRIVER) {
                "Водитель с такими данными не найден. Проверьте номер телефона и номер транспорта или зарегистрируйтесь."
            } else {
                "Аккаунт с таким номером телефона не найден. Проверьте номер или зарегистрируйтесь."
            }
            "safar_login_role_driver" in chain ->
                "Этот номер телефона зарегистрирован как водитель. Вернитесь назад и выберите роль «Водитель»."
            "safar_login_role_passenger" in chain ->
                "Этот номер телефона зарегистрирован как пассажир. Вернитесь назад и выберите роль «Пассажир»."
            "safar_phone_taken" in chain || "users_phone_unique" in chain ||
                ("duplicate key value violates unique constraint" in chain && "phone" in chain) ->
                "Этот номер телефона уже зарегистрирован. Если это ваш аккаунт — нажмите «Вход»."
            "safar_vehicle_taken" in chain || "vehicle is already assigned" in chain ->
                "Этот номер транспорта уже зарегистрирован другим водителем."
            "safar_invalid_phone" in chain ->
                "Введите номер телефона полностью, например +992 93 123 45 67."
            "safar_name_required" in chain -> "Введите имя."
            "safar_vehicle_required" in chain -> "Введите номер транспорта."
            "safar_session_has_profile" in chain ->
                "Не удалось войти: на этом устройстве уже открыт другой аккаунт. Попробуйте ещё раз."
            "user role is already passenger" in chain ->
                "Эта сессия уже зарегистрирована как пассажир. Выйдите из аккаунта, чтобы зарегистрироваться водителем."
            "user role is already driver" in chain ->
                "Эта сессия уже зарегистрирована как водитель. Выйдите из аккаунта, чтобы зарегистрироваться пассажиром."
            "driver profile already exists with different vehicle or route" in chain ||
                "driver application already exists with different vehicle or route" in chain ->
                "Этот аккаунт уже зарегистрирован с другим номером транспорта или маршрутом."
            "route must be selected" in chain || "route not found" in chain ->
                "Выберите номер маршрута."
            "vehicle could not be registered" in chain ->
                "Не удалось зарегистрировать номер транспорта."
            "pgrst202" in chain || "could not find the function" in chain ->
                "Сервер Safar ещё не обновлён. Нужно применить обновление базы данных."
            "permission denied" in chain || "row-level security" in chain ->
                "Сервер отклонил запрос. Проверьте настройки доступа Safar."
            "authentication is required" in chain ||
                "authenticated user does not exist" in chain ||
                ("session" in chain && ("expired" in chain || "invalid" in chain)) ->
                "Не удалось подтвердить сессию. Попробуйте ещё раз."
            "timeout" in chain || "timed out" in chain ->
                "Сервер отвечает слишком долго. Проверьте интернет и попробуйте ещё раз."
            "unknownhost" in chain || "unable to resolve host" in chain ||
                "no address associated with hostname" in chain ->
                "Нет соединения с сервером. Проверьте интернет."
            mode == AuthMode.LOGIN ->
                "Не удалось войти. Проверьте интернет и попробуйте ещё раз."
            else ->
                "Не удалось зарегистрироваться. Проверьте интернет и попробуйте ещё раз."
        }
    }

    private fun mismatchMessage(fields: List<String>): String {
        val labels = fields.mapNotNull { field ->
            when (field) {
                "name" -> "имя"
                "phone" -> "номер телефона"
                "vehicle" -> "номер транспорта"
                else -> null
            }
        }.distinct()
        if (labels.isEmpty()) return "Данные для входа указаны неверно."
        if (labels.size == 1) return "Неверно указано: ${labels.single()}."
        val joined = labels.dropLast(1).joinToString(", ") + " и " + labels.last()
        return "Неверно указаны: $joined."
    }

    private fun chainText(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(6)
            .mapNotNull { it.message }
            .joinToString(" ")
}
