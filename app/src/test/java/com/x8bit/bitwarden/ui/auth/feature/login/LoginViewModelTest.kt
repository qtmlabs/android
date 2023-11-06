package com.x8bit.bitwarden.ui.auth.feature.login

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.x8bit.bitwarden.R
import com.x8bit.bitwarden.data.auth.datasource.disk.model.EnvironmentUrlDataJson
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.LoginResult
import com.x8bit.bitwarden.data.auth.repository.util.CaptchaCallbackTokenResult
import com.x8bit.bitwarden.data.auth.repository.util.generateUriForCaptcha
import com.x8bit.bitwarden.data.platform.repository.EnvironmentRepository
import com.x8bit.bitwarden.data.platform.repository.model.Environment
import com.x8bit.bitwarden.ui.platform.base.BaseViewModelTest
import com.x8bit.bitwarden.ui.platform.base.util.asText
import com.x8bit.bitwarden.ui.platform.components.BasicDialogState
import com.x8bit.bitwarden.ui.platform.components.LoadingDialogState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoginViewModelTest : BaseViewModelTest() {

    private val savedStateHandle = SavedStateHandle().also {
        it["email_address"] = "test@gmail.com"
    }

    @BeforeEach
    fun setUp() {
        mockkStatic(LOGIN_RESULT_PATH)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(LOGIN_RESULT_PATH)
    }

    @Test
    fun `initial state should be correct for non-custom Environments`() = runTest {
        val viewModel = LoginViewModel(
            authRepository = mockk {
                every { captchaTokenResultFlow } returns flowOf()
            },
            environmentRepository = mockk {
                every { environment } returns Environment.Us
            },
            savedStateHandle = savedStateHandle,
        )
        viewModel.stateFlow.test {
            assertEquals(DEFAULT_STATE, awaitItem())
        }
    }

    @Test
    fun `initial state should be correct for custom Environments with empty base URLs`() = runTest {
        val viewModel = LoginViewModel(
            authRepository = mockk {
                every { captchaTokenResultFlow } returns flowOf()
            },
            environmentRepository = mockk {
                every { environment } returns Environment.SelfHosted(
                    environmentUrlData = EnvironmentUrlDataJson(
                        base = "",
                    ),
                )
            },
            savedStateHandle = savedStateHandle,
        )
        viewModel.stateFlow.test {
            assertEquals(
                DEFAULT_STATE.copy(
                    environmentLabel = "".asText(),
                ),
                awaitItem(),
            )
        }
    }

    @Test
    fun `initial state should be correct for custom Environments with non-empty base URLs`() =
        runTest {
            val viewModel = LoginViewModel(
                authRepository = mockk {
                    every { captchaTokenResultFlow } returns flowOf()
                },
                environmentRepository = mockk {
                    every { environment } returns Environment.SelfHosted(
                        environmentUrlData = EnvironmentUrlDataJson(
                            base = "https://abc.com/path-1/path-2",
                        ),
                    )
                },
                savedStateHandle = savedStateHandle,
            )
            viewModel.stateFlow.test {
                assertEquals(
                    DEFAULT_STATE.copy(
                        environmentLabel = "abc.com".asText(),
                    ),
                    awaitItem(),
                )
            }
        }

    @Test
    fun `initial state should pull from handle when present`() = runTest {
        val expectedState = DEFAULT_STATE.copy(
            passwordInput = "input",
            isLoginButtonEnabled = true,
        )
        val handle = SavedStateHandle(
            mapOf(
                "email_address" to "test@gmail.com",
                "state" to expectedState,
            ),
        )
        val viewModel = LoginViewModel(
            authRepository = mockk {
                every { captchaTokenResultFlow } returns flowOf()
            },
            environmentRepository = mockk {
                every { environment } returns Environment.Us
            },
            savedStateHandle = handle,
        )
        viewModel.stateFlow.test {
            assertEquals(expectedState, awaitItem())
        }
    }

    @Test
    fun `CloseButtonClick should emit NavigateBack`() = runTest {
        val viewModel = LoginViewModel(
            authRepository = mockk {
                every { captchaTokenResultFlow } returns flowOf()
            },
            environmentRepository = mockk {
                every { environment } returns Environment.Us
            },
            savedStateHandle = savedStateHandle,
        )
        viewModel.eventFlow.test {
            viewModel.actionChannel.trySend(LoginAction.CloseButtonClick)
            assertEquals(
                LoginEvent.NavigateBack,
                awaitItem(),
            )
        }
    }

    @Test
    fun `LoginButtonClick login returns error should update errorDialogState`() = runTest {
        val authRepository = mockk<AuthRepository> {
            coEvery {
                login(
                    email = "test@gmail.com",
                    password = "",
                    captchaToken = null,
                )
            } returns LoginResult.Error(errorMessage = "mock_error")
            every { captchaTokenResultFlow } returns flowOf()
        }
        val environmentRepository = mockk<EnvironmentRepository> {
            every { environment } returns Environment.Us
        }
        val viewModel = LoginViewModel(
            authRepository = authRepository,
            environmentRepository = environmentRepository,
            savedStateHandle = savedStateHandle,
        )
        viewModel.stateFlow.test {
            assertEquals(DEFAULT_STATE, awaitItem())
            viewModel.trySendAction(LoginAction.LoginButtonClick)
            assertEquals(
                DEFAULT_STATE.copy(
                    loadingDialogState = LoadingDialogState.Shown(
                        text = R.string.logging_in.asText(),
                    ),
                ),
                awaitItem(),
            )
            assertEquals(
                DEFAULT_STATE.copy(
                    errorDialogState = BasicDialogState.Shown(
                        title = R.string.an_error_has_occurred.asText(),
                        message = "mock_error".asText(),
                    ),
                    loadingDialogState = LoadingDialogState.Hidden,
                ),
                awaitItem(),
            )
        }
        coVerify {
            authRepository.login(email = "test@gmail.com", password = "", captchaToken = null)
        }
    }

    @Test
    fun `LoginButtonClick login returns success should update loadingDialogState`() = runTest {
        val authRepository = mockk<AuthRepository> {
            coEvery {
                login("test@gmail.com", "", captchaToken = null)
            } returns LoginResult.Success
            every { captchaTokenResultFlow } returns flowOf()
        }
        val viewModel = LoginViewModel(
            authRepository = authRepository,
            environmentRepository = mockk {
                every { environment } returns Environment.Us
            },
            savedStateHandle = savedStateHandle,
        )
        viewModel.stateFlow.test {
            assertEquals(DEFAULT_STATE, awaitItem())
            viewModel.trySendAction(LoginAction.LoginButtonClick)
            assertEquals(
                DEFAULT_STATE.copy(
                    loadingDialogState = LoadingDialogState.Shown(
                        text = R.string.logging_in.asText(),
                    ),
                ),
                awaitItem(),
            )
            assertEquals(
                DEFAULT_STATE.copy(loadingDialogState = LoadingDialogState.Hidden),
                awaitItem(),
            )
        }
        coVerify {
            authRepository.login(email = "test@gmail.com", password = "", captchaToken = null)
        }
    }

    @Test
    fun `LoginButtonClick login returns CaptchaRequired should emit NavigateToCaptcha`() =
        runTest {
            val mockkUri = mockk<Uri>()
            every {
                generateUriForCaptcha(captchaId = "mock_captcha_id")
            } returns mockkUri
            val authRepository = mockk<AuthRepository> {
                coEvery { login("test@gmail.com", "", captchaToken = null) } returns
                    LoginResult.CaptchaRequired(captchaId = "mock_captcha_id")
                every { captchaTokenResultFlow } returns flowOf()
            }
            val viewModel = LoginViewModel(
                authRepository = authRepository,
                environmentRepository = mockk {
                    every { environment } returns Environment.Us
                },
                savedStateHandle = savedStateHandle,
            )
            viewModel.eventFlow.test {
                viewModel.actionChannel.trySend(LoginAction.LoginButtonClick)
                assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
                assertEquals(LoginEvent.NavigateToCaptcha(uri = mockkUri), awaitItem())
            }
            coVerify {
                authRepository.login(email = "test@gmail.com", password = "", captchaToken = null)
            }
        }

    @Test
    fun `MasterPasswordHintClick should emit ShowToast`() = runTest {
        val viewModel = LoginViewModel(
            authRepository = mockk {
                every { captchaTokenResultFlow } returns flowOf()
            },
            environmentRepository = mockk {
                every { environment } returns Environment.Us
            },
            savedStateHandle = savedStateHandle,
        )
        viewModel.eventFlow.test {
            viewModel.actionChannel.trySend(LoginAction.MasterPasswordHintClick)
            assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
            assertEquals(
                LoginEvent.ShowToast("Not yet implemented."),
                awaitItem(),
            )
        }
    }

    @Test
    fun `SingleSignOnClick should emit ShowToast`() = runTest {
        val viewModel = LoginViewModel(
            authRepository = mockk {
                every { captchaTokenResultFlow } returns flowOf()
            },
            environmentRepository = mockk {
                every { environment } returns Environment.Us
            },
            savedStateHandle = savedStateHandle,
        )
        viewModel.eventFlow.test {
            viewModel.actionChannel.trySend(LoginAction.SingleSignOnClick)
            assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
            assertEquals(
                LoginEvent.ShowToast("Not yet implemented."),
                awaitItem(),
            )
        }
    }

    @Test
    fun `NotYouButtonClick should emit NavigateBack`() = runTest {
        val viewModel = LoginViewModel(
            authRepository = mockk {
                every { captchaTokenResultFlow } returns flowOf()
            },
            environmentRepository = mockk {
                every { environment } returns Environment.Us
            },
            savedStateHandle = savedStateHandle,
        )
        viewModel.eventFlow.test {
            viewModel.actionChannel.trySend(LoginAction.NotYouButtonClick)
            assertEquals(
                LoginEvent.NavigateBack,
                awaitItem(),
            )
        }
    }

    @Test
    fun `PasswordInputChanged should update password input`() = runTest {
        val input = "input"
        val viewModel = LoginViewModel(
            authRepository = mockk {
                every { captchaTokenResultFlow } returns flowOf()
            },
            environmentRepository = mockk {
                every { environment } returns Environment.Us
            },
            savedStateHandle = savedStateHandle,
        )
        viewModel.eventFlow.test {
            viewModel.actionChannel.trySend(LoginAction.PasswordInputChanged(input))
            assertEquals(
                DEFAULT_STATE.copy(passwordInput = input),
                viewModel.stateFlow.value,
            )
        }
    }

    @Test
    fun `captchaTokenFlow success update should trigger a login`() = runTest {
        val authRepository = mockk<AuthRepository> {
            every { captchaTokenResultFlow } returns flowOf(
                CaptchaCallbackTokenResult.Success("token"),
            )
            coEvery {
                login(
                    "test@gmail.com",
                    "",
                    captchaToken = "token",
                )
            } returns LoginResult.Success
        }
        val environmentRepository = mockk<EnvironmentRepository> {
            every { environment } returns Environment.Us
        }
        LoginViewModel(
            authRepository = authRepository,
            environmentRepository = environmentRepository,
            savedStateHandle = savedStateHandle,
        )
        coVerify {
            authRepository.login(email = "test@gmail.com", password = "", captchaToken = "token")
        }
    }

    companion object {
        private val DEFAULT_STATE = LoginState(
            emailAddress = "test@gmail.com",
            passwordInput = "",
            isLoginButtonEnabled = true,
            environmentLabel = Environment.Us.type.label,
            loadingDialogState = LoadingDialogState.Hidden,
            errorDialogState = BasicDialogState.Hidden,
            captchaToken = null,
        )

        private const val LOGIN_RESULT_PATH =
            "com.x8bit.bitwarden.data.auth.repository.util.CaptchaUtilsKt"
    }
}
