package com.x8bit.bitwarden.ui.auth.feature.createaccount

import android.net.Uri
import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.x8bit.bitwarden.R
import com.x8bit.bitwarden.data.auth.repository.model.RegisterResult
import com.x8bit.bitwarden.data.auth.repository.util.CaptchaCallbackTokenResult
import com.x8bit.bitwarden.data.auth.repository.util.generateUriForCaptcha
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.ui.auth.feature.createaccount.CreateAccountAction.AcceptPoliciesToggle
import com.x8bit.bitwarden.ui.auth.feature.createaccount.CreateAccountAction.CheckDataBreachesToggle
import com.x8bit.bitwarden.ui.auth.feature.createaccount.CreateAccountAction.ConfirmPasswordInputChange
import com.x8bit.bitwarden.ui.auth.feature.createaccount.CreateAccountAction.EmailInputChange
import com.x8bit.bitwarden.ui.auth.feature.createaccount.CreateAccountAction.PasswordHintChange
import com.x8bit.bitwarden.ui.auth.feature.createaccount.CreateAccountAction.PasswordInputChange
import com.x8bit.bitwarden.ui.auth.feature.createaccount.CreateAccountAction.PrivacyPolicyClick
import com.x8bit.bitwarden.ui.auth.feature.createaccount.CreateAccountAction.SubmitClick
import com.x8bit.bitwarden.ui.auth.feature.createaccount.CreateAccountAction.TermsClick
import com.x8bit.bitwarden.ui.platform.base.BaseViewModel
import com.x8bit.bitwarden.ui.platform.base.util.asText
import com.x8bit.bitwarden.ui.platform.components.BasicDialogState
import com.x8bit.bitwarden.ui.platform.components.LoadingDialogState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

private const val KEY_STATE = "state"
private const val MIN_PASSWORD_LENGTH = 12

/**
 * Models logic for the create account screen.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class CreateAccountViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
) : BaseViewModel<CreateAccountState, CreateAccountEvent, CreateAccountAction>(
    initialState = savedStateHandle[KEY_STATE]
        ?: CreateAccountState(
            emailInput = "",
            passwordInput = "",
            confirmPasswordInput = "",
            passwordHintInput = "",
            isAcceptPoliciesToggled = false,
            isCheckDataBreachesToggled = false,
            errorDialogState = BasicDialogState.Hidden,
            loadingDialogState = LoadingDialogState.Hidden,
        ),
) {

    init {
        // As state updates, write to saved state handle:
        stateFlow
            .onEach { savedStateHandle[KEY_STATE] = it }
            .launchIn(viewModelScope)
        authRepository
            .captchaTokenResultFlow
            .onEach {
                sendAction(
                    CreateAccountAction.Internal.ReceiveCaptchaToken(
                        tokenResult = it,
                    ),
                )
            }
            .launchIn(viewModelScope)
    }

    override fun handleAction(action: CreateAccountAction) {
        when (action) {
            is SubmitClick -> handleSubmitClick()
            is ConfirmPasswordInputChange -> handleConfirmPasswordInputChanged(action)
            is EmailInputChange -> handleEmailInputChanged(action)
            is PasswordHintChange -> handlePasswordHintChanged(action)
            is PasswordInputChange -> handlePasswordInputChanged(action)
            is CreateAccountAction.CloseClick -> handleCloseClick()
            is CreateAccountAction.ErrorDialogDismiss -> handleDialogDismiss()
            is AcceptPoliciesToggle -> handleAcceptPoliciesToggle(action)
            is CheckDataBreachesToggle -> handleCheckDataBreachesToggle(action)
            is PrivacyPolicyClick -> handlePrivacyPolicyClick()
            is TermsClick -> handleTermsClick()
            is CreateAccountAction.Internal.ReceiveRegisterResult -> {
                handleReceiveRegisterAccountResult(action)
            }

            is CreateAccountAction.Internal.ReceiveCaptchaToken -> {
                handleReceiveCaptchaToken(action)
            }
        }
    }

    private fun handleReceiveCaptchaToken(
        action: CreateAccountAction.Internal.ReceiveCaptchaToken,
    ) {
        when (val result = action.tokenResult) {
            is CaptchaCallbackTokenResult.MissingToken -> {
                mutableStateFlow.update {
                    it.copy(
                        errorDialogState = BasicDialogState.Shown(
                            title = R.string.an_error_has_occurred.asText(),
                            message = R.string.captcha_failed.asText(),
                        ),
                    )
                }
            }

            is CaptchaCallbackTokenResult.Success -> {
                submitRegisterAccountRequest(captchaToken = result.token)
            }
        }
    }

    private fun handleReceiveRegisterAccountResult(
        action: CreateAccountAction.Internal.ReceiveRegisterResult,
    ) {
        when (val registerAccountResult = action.registerResult) {
            is RegisterResult.CaptchaRequired -> {
                mutableStateFlow.update { it.copy(loadingDialogState = LoadingDialogState.Hidden) }
                sendEvent(
                    CreateAccountEvent.NavigateToCaptcha(
                        uri = generateUriForCaptcha(captchaId = registerAccountResult.captchaId),
                    ),
                )
            }

            is RegisterResult.Error -> {
                // TODO show more robust error messages BIT-763
                mutableStateFlow.update {
                    it.copy(
                        loadingDialogState = LoadingDialogState.Hidden,
                        errorDialogState = BasicDialogState.Shown(
                            title = R.string.an_error_has_occurred.asText(),
                            message = registerAccountResult.errorMessage?.asText()
                                ?: R.string.generic_error_message.asText(),
                        ),
                    )
                }
            }

            is RegisterResult.Success -> {
                mutableStateFlow.update { it.copy(loadingDialogState = LoadingDialogState.Hidden) }
                sendEvent(
                    CreateAccountEvent.NavigateToLogin(
                        email = mutableStateFlow.value.emailInput,
                        captchaToken = registerAccountResult.captchaToken,
                    ),
                )
            }
        }
    }

    private fun handlePrivacyPolicyClick() = sendEvent(CreateAccountEvent.NavigateToPrivacyPolicy)

    private fun handleTermsClick() = sendEvent(CreateAccountEvent.NavigateToTerms)

    private fun handleAcceptPoliciesToggle(action: AcceptPoliciesToggle) {
        mutableStateFlow.update {
            it.copy(isAcceptPoliciesToggled = action.newState)
        }
    }

    private fun handleCheckDataBreachesToggle(action: CheckDataBreachesToggle) {
        mutableStateFlow.update {
            it.copy(isCheckDataBreachesToggled = action.newState)
        }
    }

    private fun handleDialogDismiss() {
        mutableStateFlow.update {
            it.copy(errorDialogState = BasicDialogState.Hidden)
        }
    }

    private fun handleCloseClick() {
        sendEvent(CreateAccountEvent.NavigateBack)
    }

    private fun handleEmailInputChanged(action: EmailInputChange) {
        mutableStateFlow.update { it.copy(emailInput = action.input) }
    }

    private fun handlePasswordHintChanged(action: PasswordHintChange) {
        mutableStateFlow.update { it.copy(passwordHintInput = action.input) }
    }

    private fun handlePasswordInputChanged(action: PasswordInputChange) {
        mutableStateFlow.update { it.copy(passwordInput = action.input) }
    }

    private fun handleConfirmPasswordInputChanged(action: ConfirmPasswordInputChange) {
        mutableStateFlow.update { it.copy(confirmPasswordInput = action.input) }
    }

    private fun handleSubmitClick() = when {
        mutableStateFlow.value.passwordInput.length < MIN_PASSWORD_LENGTH -> {
            val dialog = BasicDialogState.Shown(
                title = R.string.an_error_has_occurred.asText(),
                message = R.string.master_password_length_val_message_x.asText(MIN_PASSWORD_LENGTH),
            )
            mutableStateFlow.update { it.copy(errorDialogState = dialog) }
        }

        else -> {
            submitRegisterAccountRequest(captchaToken = null)
        }
    }

    private fun submitRegisterAccountRequest(captchaToken: String?) {
        mutableStateFlow.update {
            it.copy(
                loadingDialogState = LoadingDialogState.Shown(
                    text = R.string.creating_account.asText(),
                ),
            )
        }
        viewModelScope.launch {
            val result = authRepository.register(
                email = mutableStateFlow.value.emailInput,
                masterPassword = mutableStateFlow.value.passwordInput,
                masterPasswordHint = mutableStateFlow.value.passwordHintInput.ifBlank { null },
                captchaToken = captchaToken,
            )
            sendAction(
                CreateAccountAction.Internal.ReceiveRegisterResult(
                    registerResult = result,
                ),
            )
        }
    }
}

/**
 * UI state for the create account screen.
 */
@Parcelize
data class CreateAccountState(
    val emailInput: String,
    val passwordInput: String,
    val confirmPasswordInput: String,
    val passwordHintInput: String,
    val isCheckDataBreachesToggled: Boolean,
    val isAcceptPoliciesToggled: Boolean,
    val errorDialogState: BasicDialogState,
    val loadingDialogState: LoadingDialogState,
) : Parcelable

/**
 * Models events for the create account screen.
 */
sealed class CreateAccountEvent {

    /**
     * Navigate back to previous screen.
     */
    data object NavigateBack : CreateAccountEvent()

    /**
     * Placeholder event for showing a toast. Can be removed once there are real events.
     */
    data class ShowToast(val text: String) : CreateAccountEvent()

    /**
     * Navigates to the captcha verification screen.
     */
    data class NavigateToCaptcha(val uri: Uri) : CreateAccountEvent()

    /**
     * Navigates to the captcha verification screen.
     */
    data class NavigateToLogin(
        val email: String,
        val captchaToken: String,
    ) : CreateAccountEvent()

    /**
     * Navigate to terms and conditions.
     */
    data object NavigateToTerms : CreateAccountEvent()

    /**
     * Navigate to privacy policy.
     */
    data object NavigateToPrivacyPolicy : CreateAccountEvent()
}

/**
 * Models actions for the create account screen.
 */
sealed class CreateAccountAction {
    /**
     * User clicked submit.
     */
    data object SubmitClick : CreateAccountAction()

    /**
     * User clicked close.
     */
    data object CloseClick : CreateAccountAction()

    /**
     * Email input changed.
     */
    data class EmailInputChange(val input: String) : CreateAccountAction()

    /**
     * Password input changed.
     */
    data class PasswordInputChange(val input: String) : CreateAccountAction()

    /**
     * Confirm password input changed.
     */
    data class ConfirmPasswordInputChange(val input: String) : CreateAccountAction()

    /**
     * Password hint input changed.
     */
    data class PasswordHintChange(val input: String) : CreateAccountAction()

    /**
     * User dismissed the error dialog.
     */
    data object ErrorDialogDismiss : CreateAccountAction()

    /**
     * User tapped check data breaches toggle.
     */
    data class CheckDataBreachesToggle(val newState: Boolean) : CreateAccountAction()

    /**
     * User tapped accept policies toggle.
     */
    data class AcceptPoliciesToggle(val newState: Boolean) : CreateAccountAction()

    /**
     * User tapped privacy policy link.
     */
    data object PrivacyPolicyClick : CreateAccountAction()

    /**
     * User tapped terms link.
     */
    data object TermsClick : CreateAccountAction()

    /**
     * Models actions that the [CreateAccountViewModel] itself might send.
     */
    sealed class Internal : CreateAccountAction() {
        /**
         * Indicates a captcha callback token has been received.
         */
        data class ReceiveCaptchaToken(
            val tokenResult: CaptchaCallbackTokenResult,
        ) : Internal()

        /**
         * Indicates a [RegisterResult] has been received.
         */
        data class ReceiveRegisterResult(
            val registerResult: RegisterResult,
        ) : Internal()
    }
}
