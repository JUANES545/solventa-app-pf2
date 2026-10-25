package co.solventa.mobile.espresso

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import co.solventa.mobile.feature.auth.validateRegistration
import co.solventa.mobile.feature.auth.AuthProblem
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.MainActivity
import co.solventa.mobile.R
import co.solventa.mobile.feature.auth.EncryptedAuthStore
import dagger.hilt.android.testing.HiltAndroidRule
import org.junit.After
import org.junit.Before
import org.junit.Rule

abstract class AuthJourney {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    protected val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected val store get() = EncryptedAuthStore(context)
    protected var scenario: ActivityScenario<MainActivity>? = null

    @Before fun resetSession() {
        localBackendUrl()
        store.clear()
        hilt.inject()
    }

    @After fun closeSession() {
        scenario?.close()
        store.clear()
    }

    protected fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Espresso.onView(isRoot()).check(matches(isDisplayed()))
        compose.waitUntil(20_000) {
            listOf(R.string.sign_in, R.string.sign_out).any {
                compose.onAllNodesWithText(text(it)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    protected fun text(id: Int) = context.getString(id)

    protected fun awaitText(id: Int) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(id)).performScrollTo().assertIsDisplayed()
    }

    protected fun click(id: Int) {
        compose.onNodeWithText(text(id)).performScrollTo().performClick()
    }

    protected fun input(id: Int, value: String) {
        compose.onNodeWithContentDescription(text(id)).performScrollTo().performTextReplacement(value)
    }

    protected fun assertInput(id: Int, value: String) {
        compose.onNodeWithContentDescription(text(id)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)),
        )
    }

    protected fun login(email: String = EMAIL, password: String = PASSWORD) {
        input(R.string.email, email)
        input(R.string.password, password)
        Espresso.closeSoftKeyboard()
        click(R.string.sign_in)
    }

    protected fun register(email: String = EMAIL, confirmation: String = PASSWORD) {
        click(R.string.create_account)
        input(R.string.name, "Cliente Espresso")
        input(R.string.email, email)
        input(R.string.password, PASSWORD)
        input(R.string.confirm_password, confirmation)
        Espresso.closeSoftKeyboard()
        click(R.string.registration_continue)
        val valid = try {
            validateRegistration("Cliente Espresso", email, PASSWORD, confirmation)
            true
        } catch (_: AuthProblem) {
            false
        }
        if (valid) {
            compose.onNodeWithText(text(R.string.registration_continue)).performScrollTo().assertIsNotEnabled()
            click(R.string.registration_terms)
            click(R.string.registration_continue)
        }
    }

    companion object {
        const val EMAIL = "espresso@example.com"
        const val PASSWORD = "Solventa123!"
    }
}
