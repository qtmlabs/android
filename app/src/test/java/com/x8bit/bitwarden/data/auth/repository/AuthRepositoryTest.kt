package com.x8bit.bitwarden.data.auth.repository

import app.cash.turbine.test
import com.bitwarden.core.RegisterKeyResponse
import com.bitwarden.crypto.HashPurpose
import com.bitwarden.crypto.Kdf
import com.bitwarden.crypto.RsaKeyPair
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.EnvironmentUrlDataJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.network.model.GetTokenResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.KdfTypeJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.PreLoginResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.RefreshTokenResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.RegisterRequestJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.RegisterResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.service.AccountsService
import com.x8bit.bitwarden.data.auth.datasource.network.service.DevicesService
import com.x8bit.bitwarden.data.auth.datasource.network.service.HaveIBeenPwnedService
import com.x8bit.bitwarden.data.auth.datasource.network.service.IdentityService
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength.LEVEL_0
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength.LEVEL_1
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength.LEVEL_2
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength.LEVEL_3
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength.LEVEL_4
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManager
import com.x8bit.bitwarden.data.auth.repository.model.AuthState
import com.x8bit.bitwarden.data.auth.repository.model.BreachCountResult
import com.x8bit.bitwarden.data.auth.repository.model.DeleteAccountResult
import com.x8bit.bitwarden.data.auth.repository.model.KnownDeviceResult
import com.x8bit.bitwarden.data.auth.repository.model.LoginResult
import com.x8bit.bitwarden.data.auth.repository.model.PasswordStrengthResult
import com.x8bit.bitwarden.data.auth.repository.model.RegisterResult
import com.x8bit.bitwarden.data.auth.repository.model.SwitchAccountResult
import com.x8bit.bitwarden.data.auth.repository.model.UserOrganizations
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.auth.repository.model.VaultUnlockType
import com.x8bit.bitwarden.data.auth.repository.util.CaptchaCallbackTokenResult
import com.x8bit.bitwarden.data.auth.repository.util.toOrganizations
import com.x8bit.bitwarden.data.auth.repository.util.toSdkParams
import com.x8bit.bitwarden.data.auth.repository.util.toUserState
import com.x8bit.bitwarden.data.auth.repository.util.toUserStateJson
import com.x8bit.bitwarden.data.auth.util.toSdkParams
import com.x8bit.bitwarden.data.platform.base.FakeDispatcherManager
import com.x8bit.bitwarden.data.platform.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.platform.repository.model.Environment
import com.x8bit.bitwarden.data.platform.repository.util.FakeEnvironmentRepository
import com.x8bit.bitwarden.data.platform.util.asFailure
import com.x8bit.bitwarden.data.platform.util.asSuccess
import com.x8bit.bitwarden.data.vault.datasource.network.model.createMockOrganization
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultState
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
class AuthRepositoryTest {

    private val dispatcherManager: DispatcherManager = FakeDispatcherManager()
    private val accountsService: AccountsService = mockk()
    private val devicesService: DevicesService = mockk()
    private val identityService: IdentityService = mockk()
    private val haveIBeenPwnedService: HaveIBeenPwnedService = mockk()
    private val mutableVaultStateFlow = MutableStateFlow(VAULT_STATE)
    private val vaultRepository: VaultRepository = mockk {
        every { vaultStateFlow } returns mutableVaultStateFlow
        every { deleteVaultData(any()) } just runs
        every { clearUnlockedData() } just runs
    }
    private val fakeAuthDiskSource = FakeAuthDiskSource()
    private val fakeEnvironmentRepository =
        FakeEnvironmentRepository()
            .apply {
                environment = Environment.Us
            }
    private val settingsRepository: SettingsRepository = mockk {
        every { setDefaultsIfNecessary(any()) } just runs
    }
    private val authSdkSource = mockk<AuthSdkSource> {
        coEvery {
            hashPassword(
                email = EMAIL,
                password = PASSWORD,
                kdf = PRE_LOGIN_SUCCESS.kdfParams.toSdkParams(),
                purpose = HashPurpose.SERVER_AUTHORIZATION,
            )
        } returns Result.success(PASSWORD_HASH)
        coEvery {
            makeRegisterKeys(
                email = EMAIL,
                password = PASSWORD,
                kdf = Kdf.Pbkdf2(DEFAULT_KDF_ITERATIONS.toUInt()),
            )
        } returns Result.success(
            RegisterKeyResponse(
                masterPasswordHash = PASSWORD_HASH,
                encryptedUserKey = ENCRYPTED_USER_KEY,
                keys = RsaKeyPair(
                    public = PUBLIC_KEY,
                    private = PRIVATE_KEY,
                ),
            ),
        )
    }
    private val userLogoutManager: UserLogoutManager = mockk {
        every { logout(any()) } just runs
    }

    private var elapsedRealtimeMillis = 123456789L

    private val repository = AuthRepositoryImpl(
        accountsService = accountsService,
        devicesService = devicesService,
        identityService = identityService,
        haveIBeenPwnedService = haveIBeenPwnedService,
        authSdkSource = authSdkSource,
        authDiskSource = fakeAuthDiskSource,
        environmentRepository = fakeEnvironmentRepository,
        settingsRepository = settingsRepository,
        vaultRepository = vaultRepository,
        userLogoutManager = userLogoutManager,
        dispatcherManager = dispatcherManager,
        elapsedRealtimeMillisProvider = { elapsedRealtimeMillis },
    )

    @BeforeEach
    fun beforeEach() {
        clearMocks(identityService, accountsService, haveIBeenPwnedService)
        mockkStatic(
            GET_TOKEN_RESPONSE_EXTENSIONS_PATH,
            REFRESH_TOKEN_RESPONSE_EXTENSIONS_PATH,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(
            GET_TOKEN_RESPONSE_EXTENSIONS_PATH,
            REFRESH_TOKEN_RESPONSE_EXTENSIONS_PATH,
        )
    }

    @Test
    fun `authStateFlow should react to user state changes`() {
        assertEquals(
            AuthState.Unauthenticated,
            repository.authStateFlow.value,
        )

        // Update the active user updates the state
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        assertEquals(
            AuthState.Authenticated(ACCESS_TOKEN),
            repository.authStateFlow.value,
        )

        // Updating the non-active user does not update the state
        fakeAuthDiskSource.userState = MULTI_USER_STATE
        assertEquals(
            AuthState.Authenticated(ACCESS_TOKEN),
            repository.authStateFlow.value,
        )

        // Clearing the tokens of the active state results in the Unauthenticated state
        val updatedAccount = ACCOUNT_1.copy(
            tokens = AccountJson.Tokens(
                accessToken = null,
                refreshToken = null,
            ),
        )
        val updatedState = MULTI_USER_STATE.copy(
            accounts = MULTI_USER_STATE
                .accounts
                .toMutableMap()
                .apply {
                    set(USER_ID_1, updatedAccount)
                },
        )
        fakeAuthDiskSource.userState = updatedState
        assertEquals(
            AuthState.Unauthenticated,
            repository.authStateFlow.value,
        )
    }

    @Test
    fun `userStateFlow should update according to changes in its underlying data sources`() {
        fakeAuthDiskSource.userState = null
        assertEquals(
            null,
            repository.userStateFlow.value,
        )

        mutableVaultStateFlow.value = VAULT_STATE
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        assertEquals(
            SINGLE_USER_STATE_1.toUserState(
                vaultState = VAULT_STATE,
                userOrganizationsList = emptyList(),
                specialCircumstance = null,
                vaultUnlockTypeProvider = { VaultUnlockType.MASTER_PASSWORD },
            ),
            repository.userStateFlow.value,
        )

        fakeAuthDiskSource.apply {
            storePinProtectedUserKey(
                userId = USER_ID_1,
                pinProtectedUserKey = "pinProtectedUseKey",
            )
            storePinProtectedUserKey(
                userId = USER_ID_2,
                pinProtectedUserKey = "pinProtectedUseKey",
            )
            userState = MULTI_USER_STATE
        }
        assertEquals(
            MULTI_USER_STATE.toUserState(
                vaultState = VAULT_STATE,
                userOrganizationsList = emptyList(),
                specialCircumstance = null,
                vaultUnlockTypeProvider = { VaultUnlockType.PIN },
            ),
            repository.userStateFlow.value,
        )

        val emptyVaultState = VaultState(
            unlockedVaultUserIds = emptySet(),
            unlockingVaultUserIds = emptySet(),
        )
        mutableVaultStateFlow.value = emptyVaultState
        assertEquals(
            MULTI_USER_STATE.toUserState(
                vaultState = emptyVaultState,
                userOrganizationsList = emptyList(),
                specialCircumstance = null,
                vaultUnlockTypeProvider = { VaultUnlockType.PIN },
            ),
            repository.userStateFlow.value,
        )

        fakeAuthDiskSource.apply {
            storePinProtectedUserKey(
                userId = USER_ID_1,
                pinProtectedUserKey = null,
            )
            storePinProtectedUserKey(
                userId = USER_ID_2,
                pinProtectedUserKey = null,
            )
            storeOrganizations(
                userId = USER_ID_1,
                organizations = ORGANIZATIONS,
            )
        }
        assertEquals(
            MULTI_USER_STATE.toUserState(
                vaultState = emptyVaultState,
                userOrganizationsList = USER_ORGANIZATIONS,
                specialCircumstance = null,
                vaultUnlockTypeProvider = { VaultUnlockType.MASTER_PASSWORD },
            ),
            repository.userStateFlow.value,
        )
    }

    @Test
    fun `rememberedEmailAddress should pull from and update AuthDiskSource`() {
        // AuthDiskSource and the repository start with the same value.
        assertNull(repository.rememberedEmailAddress)
        assertNull(fakeAuthDiskSource.rememberedEmailAddress)

        // Updating the repository updates AuthDiskSource
        repository.rememberedEmailAddress = "remembered@gmail.com"
        assertEquals("remembered@gmail.com", fakeAuthDiskSource.rememberedEmailAddress)

        // Updating AuthDiskSource updates the repository
        fakeAuthDiskSource.rememberedEmailAddress = null
        assertNull(repository.rememberedEmailAddress)
    }

    @Test
    fun `specialCircumstance update should trigger a change in UserState`() {
        // Populate the initial UserState
        assertNull(repository.specialCircumstance)
        val initialUserState = SINGLE_USER_STATE_1.toUserState(
            vaultState = VAULT_STATE,
            userOrganizationsList = emptyList(),
            specialCircumstance = null,
            vaultUnlockTypeProvider = { VaultUnlockType.MASTER_PASSWORD },
        )
        mutableVaultStateFlow.value = VAULT_STATE
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        assertEquals(
            initialUserState,
            repository.userStateFlow.value,
        )

        repository.specialCircumstance = UserState.SpecialCircumstance.PendingAccountAddition

        assertEquals(
            initialUserState.copy(
                specialCircumstance = UserState.SpecialCircumstance.PendingAccountAddition,
            ),
            repository.userStateFlow.value,
        )
    }

    @Test
    fun `delete account fails if not logged in`() = runTest {
        val masterPassword = "hello world"
        val result = repository.deleteAccount(password = masterPassword)
        assertEquals(DeleteAccountResult.Error, result)
    }

    @Test
    fun `delete account fails if hashPassword fails`() = runTest {
        val masterPassword = "hello world"
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        val kdf = SINGLE_USER_STATE_1.activeAccount.profile.toSdkParams()
        coEvery {
            authSdkSource.hashPassword(EMAIL, masterPassword, kdf, HashPurpose.SERVER_AUTHORIZATION)
        } returns Throwable("Fail").asFailure()

        val result = repository.deleteAccount(password = masterPassword)

        assertEquals(DeleteAccountResult.Error, result)
        coVerify {
            authSdkSource.hashPassword(EMAIL, masterPassword, kdf, HashPurpose.SERVER_AUTHORIZATION)
        }
    }

    @Test
    fun `delete account fails if deleteAccount fails`() = runTest {
        val masterPassword = "hello world"
        val hashedMasterPassword = "dlrow olleh"
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        val kdf = SINGLE_USER_STATE_1.activeAccount.profile.toSdkParams()
        coEvery {
            authSdkSource.hashPassword(EMAIL, masterPassword, kdf, HashPurpose.SERVER_AUTHORIZATION)
        } returns hashedMasterPassword.asSuccess()
        coEvery {
            accountsService.deleteAccount(hashedMasterPassword)
        } returns Throwable("Fail").asFailure()

        val result = repository.deleteAccount(password = masterPassword)

        assertEquals(DeleteAccountResult.Error, result)
        coVerify {
            authSdkSource.hashPassword(EMAIL, masterPassword, kdf, HashPurpose.SERVER_AUTHORIZATION)
            accountsService.deleteAccount(hashedMasterPassword)
        }
    }

    @Test
    fun `delete account succeeds`() = runTest {
        val masterPassword = "hello world"
        val hashedMasterPassword = "dlrow olleh"
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        val kdf = SINGLE_USER_STATE_1.activeAccount.profile.toSdkParams()
        coEvery {
            authSdkSource.hashPassword(EMAIL, masterPassword, kdf, HashPurpose.SERVER_AUTHORIZATION)
        } returns hashedMasterPassword.asSuccess()
        coEvery {
            accountsService.deleteAccount(hashedMasterPassword)
        } returns Unit.asSuccess()

        val result = repository.deleteAccount(password = masterPassword)

        assertEquals(DeleteAccountResult.Success, result)
        coVerify {
            authSdkSource.hashPassword(EMAIL, masterPassword, kdf, HashPurpose.SERVER_AUTHORIZATION)
            accountsService.deleteAccount(hashedMasterPassword)
        }
    }

    @Test
    fun `refreshTokenSynchronously returns failure if not logged in`() = runTest {
        fakeAuthDiskSource.userState = null

        val result = repository.refreshAccessTokenSynchronously(USER_ID_1)

        assertTrue(result.isFailure)
    }

    @Test
    fun `refreshTokenSynchronously returns failure and logs out on failure`() = runTest {
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        coEvery {
            identityService.refreshTokenSynchronously(REFRESH_TOKEN)
        } returns Throwable("Fail").asFailure()

        assertTrue(repository.refreshAccessTokenSynchronously(USER_ID_1).isFailure)

        coVerify(exactly = 1) {
            identityService.refreshTokenSynchronously(REFRESH_TOKEN)
        }
    }

    @Test
    fun `refreshTokenSynchronously returns success and update user state on success`() = runTest {
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        coEvery {
            identityService.refreshTokenSynchronously(REFRESH_TOKEN)
        } returns REFRESH_TOKEN_RESPONSE_JSON.asSuccess()
        every {
            REFRESH_TOKEN_RESPONSE_JSON.toUserStateJson(
                userId = USER_ID_1,
                previousUserState = SINGLE_USER_STATE_1,
            )
        } returns SINGLE_USER_STATE_1

        val result = repository.refreshAccessTokenSynchronously(USER_ID_1)

        assertEquals(REFRESH_TOKEN_RESPONSE_JSON.asSuccess(), result)
        coVerify(exactly = 1) {
            identityService.refreshTokenSynchronously(REFRESH_TOKEN)
            REFRESH_TOKEN_RESPONSE_JSON.toUserStateJson(
                userId = USER_ID_1,
                previousUserState = SINGLE_USER_STATE_1,
            )
        }
    }

    @Test
    fun `login when pre login fails should return Error with no message`() = runTest {
        coEvery {
            accountsService.preLogin(email = EMAIL)
        } returns (Result.failure(RuntimeException()))
        val result = repository.login(email = EMAIL, password = PASSWORD, captchaToken = null)
        assertEquals(LoginResult.Error(errorMessage = null), result)
        assertEquals(AuthState.Unauthenticated, repository.authStateFlow.value)
        coVerify { accountsService.preLogin(email = EMAIL) }
    }

    @Test
    fun `login get token fails should return Error with no message`() = runTest {
        coEvery {
            accountsService.preLogin(email = EMAIL)
        } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            identityService.getToken(
                email = EMAIL,
                passwordHash = PASSWORD_HASH,
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
            .returns(Result.failure(RuntimeException()))
        val result = repository.login(email = EMAIL, password = PASSWORD, captchaToken = null)
        assertEquals(LoginResult.Error(errorMessage = null), result)
        assertEquals(AuthState.Unauthenticated, repository.authStateFlow.value)
        coVerify { accountsService.preLogin(email = EMAIL) }
        coVerify {
            identityService.getToken(
                email = EMAIL,
                passwordHash = PASSWORD_HASH,
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
    }

    @Test
    fun `login get token returns Invalid should return Error with correct message`() = runTest {
        coEvery {
            accountsService.preLogin(email = EMAIL)
        } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            identityService.getToken(
                email = EMAIL,
                passwordHash = PASSWORD_HASH,
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        } returns Result.success(
            GetTokenResponseJson.Invalid(
                errorModel = GetTokenResponseJson.Invalid.ErrorModel(
                    errorMessage = "mock_error_message",
                ),
            ),
        )

        val result = repository.login(email = EMAIL, password = PASSWORD, captchaToken = null)
        assertEquals(LoginResult.Error(errorMessage = "mock_error_message"), result)
        assertEquals(AuthState.Unauthenticated, repository.authStateFlow.value)
        coVerify { accountsService.preLogin(email = EMAIL) }
        coVerify {
            identityService.getToken(
                email = EMAIL,
                passwordHash = PASSWORD_HASH,
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
    }

    @Test
    @Suppress("MaxLineLength")
    fun `login get token succeeds should return Success, unlockVault, update AuthState, update stored keys, and sync`() =
        runTest {
            val successResponse = GET_TOKEN_RESPONSE_SUCCESS
            coEvery {
                accountsService.preLogin(email = EMAIL)
            } returns Result.success(PRE_LOGIN_SUCCESS)
            coEvery {
                identityService.getToken(
                    email = EMAIL,
                    passwordHash = PASSWORD_HASH,
                    captchaToken = null,
                    uniqueAppId = UNIQUE_APP_ID,
                )
            }
                .returns(Result.success(successResponse))
            coEvery {
                vaultRepository.unlockVault(
                    userId = USER_ID_1,
                    email = EMAIL,
                    kdf = ACCOUNT_1.profile.toSdkParams(),
                    userKey = successResponse.key,
                    privateKey = successResponse.privateKey,
                    organizationKeys = null,
                    masterPassword = PASSWORD,
                )
            } returns VaultUnlockResult.Success
            coEvery { vaultRepository.sync() } just runs
            every {
                GET_TOKEN_RESPONSE_SUCCESS.toUserState(
                    previousUserState = null,
                    environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US,
                )
            } returns SINGLE_USER_STATE_1
            val result = repository.login(email = EMAIL, password = PASSWORD, captchaToken = null)
            assertEquals(LoginResult.Success, result)
            assertEquals(AuthState.Authenticated(ACCESS_TOKEN), repository.authStateFlow.value)
            coVerify { accountsService.preLogin(email = EMAIL) }
            fakeAuthDiskSource.assertPrivateKey(
                userId = USER_ID_1,
                privateKey = "privateKey",
            )
            fakeAuthDiskSource.assertUserKey(
                userId = USER_ID_1,
                userKey = "key",
            )
            coVerify {
                identityService.getToken(
                    email = EMAIL,
                    passwordHash = PASSWORD_HASH,
                    captchaToken = null,
                    uniqueAppId = UNIQUE_APP_ID,
                )
                vaultRepository.unlockVault(
                    userId = USER_ID_1,
                    email = EMAIL,
                    kdf = ACCOUNT_1.profile.toSdkParams(),
                    userKey = successResponse.key,
                    privateKey = successResponse.privateKey,
                    organizationKeys = null,
                    masterPassword = PASSWORD,
                )
                vaultRepository.sync()
            }
            assertEquals(
                SINGLE_USER_STATE_1,
                fakeAuthDiskSource.userState,
            )
            assertNull(repository.specialCircumstance)
            verify { settingsRepository.setDefaultsIfNecessary(userId = USER_ID_1) }
            verify { vaultRepository.clearUnlockedData() }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `login get token succeeds when there is an existing user should switch to the new logged in user and lock the old user's vault`() =
        runTest {
            // Ensure the initial state for User 2 with a account addition
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_2
            repository.specialCircumstance = UserState.SpecialCircumstance.PendingAccountAddition

            // Set up login for User 1
            val successResponse = GET_TOKEN_RESPONSE_SUCCESS
            coEvery {
                accountsService.preLogin(email = EMAIL)
            } returns Result.success(PRE_LOGIN_SUCCESS)
            coEvery {
                identityService.getToken(
                    email = EMAIL,
                    passwordHash = PASSWORD_HASH,
                    captchaToken = null,
                    uniqueAppId = UNIQUE_APP_ID,
                )
            }
                .returns(Result.success(successResponse))
            coEvery {
                vaultRepository.unlockVault(
                    userId = USER_ID_1,
                    email = EMAIL,
                    kdf = ACCOUNT_1.profile.toSdkParams(),
                    userKey = successResponse.key,
                    privateKey = successResponse.privateKey,
                    organizationKeys = null,
                    masterPassword = PASSWORD,
                )
            } returns VaultUnlockResult.Success
            coEvery { vaultRepository.sync() } just runs
            every {
                GET_TOKEN_RESPONSE_SUCCESS.toUserState(
                    previousUserState = SINGLE_USER_STATE_2,
                    environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US,
                )
            } returns MULTI_USER_STATE

            val result = repository.login(email = EMAIL, password = PASSWORD, captchaToken = null)

            assertEquals(LoginResult.Success, result)
            assertEquals(AuthState.Authenticated(ACCESS_TOKEN), repository.authStateFlow.value)
            coVerify { accountsService.preLogin(email = EMAIL) }
            fakeAuthDiskSource.assertPrivateKey(
                userId = USER_ID_1,
                privateKey = "privateKey",
            )
            fakeAuthDiskSource.assertUserKey(
                userId = USER_ID_1,
                userKey = "key",
            )
            coVerify {
                identityService.getToken(
                    email = EMAIL,
                    passwordHash = PASSWORD_HASH,
                    captchaToken = null,
                    uniqueAppId = UNIQUE_APP_ID,
                )
                vaultRepository.unlockVault(
                    userId = USER_ID_1,
                    email = EMAIL,
                    kdf = ACCOUNT_1.profile.toSdkParams(),
                    userKey = successResponse.key,
                    privateKey = successResponse.privateKey,
                    organizationKeys = null,
                    masterPassword = PASSWORD,
                )
                vaultRepository.sync()
            }
            assertEquals(
                MULTI_USER_STATE,
                fakeAuthDiskSource.userState,
            )
            assertNull(repository.specialCircumstance)
            verify { settingsRepository.setDefaultsIfNecessary(userId = USER_ID_1) }
            verify { vaultRepository.clearUnlockedData() }
        }

    @Test
    fun `login get token returns captcha request should return CaptchaRequired`() = runTest {
        coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            identityService.getToken(
                email = EMAIL,
                passwordHash = PASSWORD_HASH,
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
            .returns(Result.success(GetTokenResponseJson.CaptchaRequired(CAPTCHA_KEY)))
        val result = repository.login(email = EMAIL, password = PASSWORD, captchaToken = null)
        assertEquals(LoginResult.CaptchaRequired(CAPTCHA_KEY), result)
        assertEquals(AuthState.Unauthenticated, repository.authStateFlow.value)
        coVerify { accountsService.preLogin(email = EMAIL) }
        coVerify {
            identityService.getToken(
                email = EMAIL,
                passwordHash = PASSWORD_HASH,
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
    }

    @Test
    fun `register check data breaches error should still return register success`() = runTest {
        coEvery {
            haveIBeenPwnedService.hasPasswordBeenBreached(PASSWORD)
        } returns Result.failure(Throwable())
        coEvery {
            accountsService.register(
                body = RegisterRequestJson(
                    email = EMAIL,
                    masterPasswordHash = PASSWORD_HASH,
                    masterPasswordHint = null,
                    captchaResponse = null,
                    key = ENCRYPTED_USER_KEY,
                    keys = RegisterRequestJson.Keys(
                        publicKey = PUBLIC_KEY,
                        encryptedPrivateKey = PRIVATE_KEY,
                    ),
                    kdfType = KdfTypeJson.PBKDF2_SHA256,
                    kdfIterations = DEFAULT_KDF_ITERATIONS.toUInt(),
                ),
            )
        } returns Result.success(RegisterResponseJson.Success(captchaBypassToken = CAPTCHA_KEY))

        val result = repository.register(
            email = EMAIL,
            masterPassword = PASSWORD,
            masterPasswordHint = null,
            captchaToken = null,
            shouldCheckDataBreaches = true,
        )
        assertEquals(RegisterResult.Success(CAPTCHA_KEY), result)
    }

    @Test
    fun `register check data breaches found should return DataBreachFound`() = runTest {
        coEvery {
            haveIBeenPwnedService.hasPasswordBeenBreached(PASSWORD)
        } returns true.asSuccess()

        val result = repository.register(
            email = EMAIL,
            masterPassword = PASSWORD,
            masterPasswordHint = null,
            captchaToken = null,
            shouldCheckDataBreaches = true,
        )
        assertEquals(RegisterResult.DataBreachFound, result)
    }

    @Test
    fun `register check data breaches Success should return Success`() = runTest {
        coEvery {
            haveIBeenPwnedService.hasPasswordBeenBreached(PASSWORD)
        } returns false.asSuccess()
        coEvery {
            accountsService.register(
                body = RegisterRequestJson(
                    email = EMAIL,
                    masterPasswordHash = PASSWORD_HASH,
                    masterPasswordHint = null,
                    captchaResponse = null,
                    key = ENCRYPTED_USER_KEY,
                    keys = RegisterRequestJson.Keys(
                        publicKey = PUBLIC_KEY,
                        encryptedPrivateKey = PRIVATE_KEY,
                    ),
                    kdfType = KdfTypeJson.PBKDF2_SHA256,
                    kdfIterations = DEFAULT_KDF_ITERATIONS.toUInt(),
                ),
            )
        } returns Result.success(RegisterResponseJson.Success(captchaBypassToken = CAPTCHA_KEY))

        val result = repository.register(
            email = EMAIL,
            masterPassword = PASSWORD,
            masterPasswordHint = null,
            captchaToken = null,
            shouldCheckDataBreaches = true,
        )
        assertEquals(RegisterResult.Success(CAPTCHA_KEY), result)
        coVerify { haveIBeenPwnedService.hasPasswordBeenBreached(PASSWORD) }
    }

    @Test
    fun `register Success should return Success`() = runTest {
        coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            accountsService.register(
                body = RegisterRequestJson(
                    email = EMAIL,
                    masterPasswordHash = PASSWORD_HASH,
                    masterPasswordHint = null,
                    captchaResponse = null,
                    key = ENCRYPTED_USER_KEY,
                    keys = RegisterRequestJson.Keys(
                        publicKey = PUBLIC_KEY,
                        encryptedPrivateKey = PRIVATE_KEY,
                    ),
                    kdfType = KdfTypeJson.PBKDF2_SHA256,
                    kdfIterations = DEFAULT_KDF_ITERATIONS.toUInt(),
                ),
            )
        } returns Result.success(RegisterResponseJson.Success(captchaBypassToken = CAPTCHA_KEY))

        val result = repository.register(
            email = EMAIL,
            masterPassword = PASSWORD,
            masterPasswordHint = null,
            captchaToken = null,
            shouldCheckDataBreaches = false,
        )
        assertEquals(RegisterResult.Success(CAPTCHA_KEY), result)
    }

    @Test
    fun `register returns CaptchaRequired captchaKeys empty should return Error no message`() =
        runTest {
            coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
            coEvery {
                accountsService.register(
                    body = RegisterRequestJson(
                        email = EMAIL,
                        masterPasswordHash = PASSWORD_HASH,
                        masterPasswordHint = null,
                        captchaResponse = null,
                        key = ENCRYPTED_USER_KEY,
                        keys = RegisterRequestJson.Keys(
                            publicKey = PUBLIC_KEY,
                            encryptedPrivateKey = PRIVATE_KEY,
                        ),
                        kdfType = KdfTypeJson.PBKDF2_SHA256,
                        kdfIterations = DEFAULT_KDF_ITERATIONS.toUInt(),
                    ),
                )
            } returns Result.success(
                RegisterResponseJson.CaptchaRequired(
                    validationErrors = RegisterResponseJson
                        .CaptchaRequired
                        .ValidationErrors(
                            captchaKeys = emptyList(),
                        ),
                ),
            )

            val result = repository.register(
                email = EMAIL,
                masterPassword = PASSWORD,
                masterPasswordHint = null,
                captchaToken = null,
                shouldCheckDataBreaches = false,
            )
            assertEquals(RegisterResult.Error(errorMessage = null), result)
        }

    @Test
    fun `register returns CaptchaRequired captchaKeys should return CaptchaRequired`() =
        runTest {
            coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
            coEvery {
                accountsService.register(
                    body = RegisterRequestJson(
                        email = EMAIL,
                        masterPasswordHash = PASSWORD_HASH,
                        masterPasswordHint = null,
                        captchaResponse = null,
                        key = ENCRYPTED_USER_KEY,
                        keys = RegisterRequestJson.Keys(
                            publicKey = PUBLIC_KEY,
                            encryptedPrivateKey = PRIVATE_KEY,
                        ),
                        kdfType = KdfTypeJson.PBKDF2_SHA256,
                        kdfIterations = DEFAULT_KDF_ITERATIONS.toUInt(),
                    ),
                )
            } returns Result.success(
                RegisterResponseJson.CaptchaRequired(
                    validationErrors = RegisterResponseJson
                        .CaptchaRequired
                        .ValidationErrors(
                            captchaKeys = listOf(CAPTCHA_KEY),
                        ),
                ),
            )

            val result = repository.register(
                email = EMAIL,
                masterPassword = PASSWORD,
                masterPasswordHint = null,
                captchaToken = null,
                shouldCheckDataBreaches = false,
            )
            assertEquals(RegisterResult.CaptchaRequired(captchaId = CAPTCHA_KEY), result)
        }

    @Test
    fun `register Failure should return Error with no message`() = runTest {
        coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            accountsService.register(
                body = RegisterRequestJson(
                    email = EMAIL,
                    masterPasswordHash = PASSWORD_HASH,
                    masterPasswordHint = null,
                    captchaResponse = null,
                    key = ENCRYPTED_USER_KEY,
                    keys = RegisterRequestJson.Keys(
                        publicKey = PUBLIC_KEY,
                        encryptedPrivateKey = PRIVATE_KEY,
                    ),
                    kdfType = KdfTypeJson.PBKDF2_SHA256,
                    kdfIterations = DEFAULT_KDF_ITERATIONS.toUInt(),
                ),
            )
        } returns Result.failure(RuntimeException())

        val result = repository.register(
            email = EMAIL,
            masterPassword = PASSWORD,
            masterPasswordHint = null,
            captchaToken = null,
            shouldCheckDataBreaches = false,
        )
        assertEquals(RegisterResult.Error(errorMessage = null), result)
    }

    @Test
    fun `register returns Invalid should return Error with invalid message`() = runTest {
        coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            accountsService.register(
                body = RegisterRequestJson(
                    email = EMAIL,
                    masterPasswordHash = PASSWORD_HASH,
                    masterPasswordHint = null,
                    captchaResponse = null,
                    key = ENCRYPTED_USER_KEY,
                    keys = RegisterRequestJson.Keys(
                        publicKey = PUBLIC_KEY,
                        encryptedPrivateKey = PRIVATE_KEY,
                    ),
                    kdfType = KdfTypeJson.PBKDF2_SHA256,
                    kdfIterations = DEFAULT_KDF_ITERATIONS.toUInt(),
                ),
            )
        } returns Result.success(RegisterResponseJson.Invalid("message", mapOf()))

        val result = repository.register(
            email = EMAIL,
            masterPassword = PASSWORD,
            masterPasswordHint = null,
            captchaToken = null,
            shouldCheckDataBreaches = false,
        )
        assertEquals(RegisterResult.Error(errorMessage = "message"), result)
    }

    @Test
    fun `register returns Invalid should return Error with first message in map`() = runTest {
        coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            accountsService.register(
                body = RegisterRequestJson(
                    email = EMAIL,
                    masterPasswordHash = PASSWORD_HASH,
                    masterPasswordHint = null,
                    captchaResponse = null,
                    key = ENCRYPTED_USER_KEY,
                    keys = RegisterRequestJson.Keys(
                        publicKey = PUBLIC_KEY,
                        encryptedPrivateKey = PRIVATE_KEY,
                    ),
                    kdfType = KdfTypeJson.PBKDF2_SHA256,
                    kdfIterations = DEFAULT_KDF_ITERATIONS.toUInt(),
                ),
            )
        } returns Result.success(
            RegisterResponseJson.Invalid(
                message = "message",
                validationErrors = mapOf("" to listOf("expected")),
            ),
        )

        val result = repository.register(
            email = EMAIL,
            masterPassword = PASSWORD,
            masterPasswordHint = null,
            captchaToken = null,
            shouldCheckDataBreaches = false,
        )
        assertEquals(RegisterResult.Error(errorMessage = "expected"), result)
    }

    @Test
    fun `register returns Error body should return Error with message`() = runTest {
        coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            accountsService.register(
                body = RegisterRequestJson(
                    email = EMAIL,
                    masterPasswordHash = PASSWORD_HASH,
                    masterPasswordHint = null,
                    captchaResponse = null,
                    key = ENCRYPTED_USER_KEY,
                    keys = RegisterRequestJson.Keys(
                        publicKey = PUBLIC_KEY,
                        encryptedPrivateKey = PRIVATE_KEY,
                    ),
                    kdfType = KdfTypeJson.PBKDF2_SHA256,
                    kdfIterations = DEFAULT_KDF_ITERATIONS.toUInt(),
                ),
            )
        } returns Result.success(
            RegisterResponseJson.Error(
                message = "message",
            ),
        )

        val result = repository.register(
            email = EMAIL,
            masterPassword = PASSWORD,
            masterPasswordHint = null,
            captchaToken = null,
            shouldCheckDataBreaches = false,
        )
        assertEquals(RegisterResult.Error(errorMessage = "message"), result)
    }

    @Test
    fun `setCaptchaCallbackToken should change the value of captchaTokenFlow`() = runTest {
        repository.captchaTokenResultFlow.test {
            repository.setCaptchaCallbackTokenResult(CaptchaCallbackTokenResult.Success("mockk"))
            assertEquals(
                CaptchaCallbackTokenResult.Success("mockk"),
                awaitItem(),
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `logout for the active account should call logout on the UserLogoutManager and clear the user's in memory vault data`() {
        val userId = USER_ID_1
        fakeAuthDiskSource.userState = MULTI_USER_STATE

        repository.logout(userId = userId)

        verify { userLogoutManager.logout(userId = userId) }
        verify { vaultRepository.clearUnlockedData() }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `logout for an inactive account should call logout on the UserLogoutManager`() {
        val userId = USER_ID_2
        fakeAuthDiskSource.userState = MULTI_USER_STATE

        repository.logout(userId = userId)

        verify { userLogoutManager.logout(userId = userId) }
        verify(exactly = 0) { vaultRepository.clearUnlockedData() }
    }

    @Test
    fun `switchAccount when there is no saved UserState should do nothing`() {
        val updatedUserId = USER_ID_2

        fakeAuthDiskSource.userState = null
        assertNull(repository.userStateFlow.value)

        assertEquals(
            SwitchAccountResult.NoChange,
            repository.switchAccount(userId = updatedUserId),
        )

        assertNull(repository.userStateFlow.value)
        verify(exactly = 0) { vaultRepository.clearUnlockedData() }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `switchAccount when the given userId is the same as the current activeUserId should only clear any special circumstances`() {
        val originalUserId = USER_ID_1
        val originalUserState = SINGLE_USER_STATE_1.toUserState(
            vaultState = VAULT_STATE,
            userOrganizationsList = emptyList(),
            specialCircumstance = null,
            vaultUnlockTypeProvider = { VaultUnlockType.MASTER_PASSWORD },
        )
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        assertEquals(
            originalUserState,
            repository.userStateFlow.value,
        )
        repository.specialCircumstance = UserState.SpecialCircumstance.PendingAccountAddition

        assertEquals(
            SwitchAccountResult.NoChange,
            repository.switchAccount(userId = originalUserId),
        )

        assertEquals(
            originalUserState,
            repository.userStateFlow.value,
        )
        assertNull(repository.specialCircumstance)
        verify(exactly = 0) { vaultRepository.clearUnlockedData() }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `switchAccount when the given userId does not correspond to a saved account should do nothing`() {
        val invalidId = "invalidId"
        val originalUserState = SINGLE_USER_STATE_1.toUserState(
            vaultState = VAULT_STATE,
            userOrganizationsList = emptyList(),
            specialCircumstance = null,
            vaultUnlockTypeProvider = { VaultUnlockType.MASTER_PASSWORD },
        )
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        assertEquals(
            originalUserState,
            repository.userStateFlow.value,
        )

        assertEquals(
            SwitchAccountResult.NoChange,
            repository.switchAccount(userId = invalidId),
        )

        assertEquals(
            originalUserState,
            repository.userStateFlow.value,
        )
        verify(exactly = 0) { vaultRepository.clearUnlockedData() }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `switchAccount when the userId is valid should update the current UserState, clear the previously unlocked data, and reset the special circumstance`() {
        val updatedUserId = USER_ID_2
        val originalUserState = MULTI_USER_STATE.toUserState(
            vaultState = VAULT_STATE,
            userOrganizationsList = emptyList(),
            specialCircumstance = null,
            vaultUnlockTypeProvider = { VaultUnlockType.MASTER_PASSWORD },
        )
        fakeAuthDiskSource.userState = MULTI_USER_STATE
        assertEquals(
            originalUserState,
            repository.userStateFlow.value,
        )
        repository.specialCircumstance = UserState.SpecialCircumstance.PendingAccountAddition

        assertEquals(
            SwitchAccountResult.AccountSwitched,
            repository.switchAccount(userId = updatedUserId),
        )

        assertEquals(
            originalUserState.copy(activeUserId = updatedUserId),
            repository.userStateFlow.value,
        )
        assertNull(repository.specialCircumstance)
        verify { vaultRepository.clearUnlockedData() }
    }

    @Test
    fun `updateLastActiveTime should update the last active time for the current user`() {
        val userId = USER_ID_1
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1

        assertNull(fakeAuthDiskSource.getLastActiveTimeMillis(userId = userId))

        repository.updateLastActiveTime()

        assertEquals(
            elapsedRealtimeMillis,
            fakeAuthDiskSource.getLastActiveTimeMillis(userId = userId),
        )
    }

    @Test
    fun `getIsKnownDevice should return failure when service returns failure`() = runTest {
        coEvery {
            devicesService.getIsKnownDevice(EMAIL, UNIQUE_APP_ID)
        } returns Throwable("Fail").asFailure()

        val result = repository.getIsKnownDevice(EMAIL)

        coVerify(exactly = 1) {
            devicesService.getIsKnownDevice(EMAIL, UNIQUE_APP_ID)
        }
        assertEquals(KnownDeviceResult.Error, result)
    }

    @Test
    fun `getIsKnownDevice should return success when service returns success`() = runTest {
        val isKnownDevice = true
        coEvery {
            devicesService.getIsKnownDevice(EMAIL, UNIQUE_APP_ID)
        } returns isKnownDevice.asSuccess()

        val result = repository.getIsKnownDevice(EMAIL)

        coVerify(exactly = 1) {
            devicesService.getIsKnownDevice(EMAIL, UNIQUE_APP_ID)
        }
        assertEquals(KnownDeviceResult.Success(isKnownDevice), result)
    }

    @Test
    fun `getPasswordBreachCount should return failure when service returns failure`() = runTest {
        val password = "password"
        coEvery {
            haveIBeenPwnedService.getPasswordBreachCount(password)
        } returns Throwable("Fail").asFailure()

        val result = repository.getPasswordBreachCount(password)

        coVerify(exactly = 1) {
            haveIBeenPwnedService.getPasswordBreachCount(password)
        }
        assertEquals(BreachCountResult.Error, result)
    }

    @Test
    fun `getPasswordBreachCount should return success when service returns success`() = runTest {
        val password = "password"
        val breachCount = 5
        coEvery {
            haveIBeenPwnedService.getPasswordBreachCount(password)
        } returns breachCount.asSuccess()

        val result = repository.getPasswordBreachCount(password)

        coVerify(exactly = 1) {
            haveIBeenPwnedService.getPasswordBreachCount(password)
        }
        assertEquals(BreachCountResult.Success(breachCount), result)
    }

    @Test
    fun `getPasswordStrength returns expected results for various strength levels`() = runTest {
        coEvery {
            authSdkSource.passwordStrength(any(), eq("level_0"))
        } returns Result.success(LEVEL_0)

        coEvery {
            authSdkSource.passwordStrength(any(), eq("level_1"))
        } returns Result.success(LEVEL_1)

        coEvery {
            authSdkSource.passwordStrength(any(), eq("level_2"))
        } returns Result.success(LEVEL_2)

        coEvery {
            authSdkSource.passwordStrength(any(), eq("level_3"))
        } returns Result.success(LEVEL_3)

        coEvery {
            authSdkSource.passwordStrength(any(), eq("level_4"))
        } returns Result.success(LEVEL_4)

        assertEquals(
            PasswordStrengthResult.Success(LEVEL_0),
            repository.getPasswordStrength(EMAIL, "level_0"),
        )

        assertEquals(
            PasswordStrengthResult.Success(LEVEL_1),
            repository.getPasswordStrength(EMAIL, "level_1"),
        )

        assertEquals(
            PasswordStrengthResult.Success(LEVEL_2),
            repository.getPasswordStrength(EMAIL, "level_2"),
        )

        assertEquals(
            PasswordStrengthResult.Success(LEVEL_3),
            repository.getPasswordStrength(EMAIL, "level_3"),
        )

        assertEquals(
            PasswordStrengthResult.Success(LEVEL_4),
            repository.getPasswordStrength(EMAIL, "level_4"),
        )
    }

    companion object {
        private const val GET_TOKEN_RESPONSE_EXTENSIONS_PATH =
            "com.x8bit.bitwarden.data.auth.repository.util.GetTokenResponseExtensionsKt"
        private const val REFRESH_TOKEN_RESPONSE_EXTENSIONS_PATH =
            "com.x8bit.bitwarden.data.auth.repository.util.RefreshTokenResponseExtensionsKt"
        private const val UNIQUE_APP_ID = "testUniqueAppId"
        private const val EMAIL = "test@bitwarden.com"
        private const val EMAIL_2 = "test2@bitwarden.com"
        private const val PASSWORD = "password"
        private const val PASSWORD_HASH = "passwordHash"
        private const val ACCESS_TOKEN = "accessToken"
        private const val ACCESS_TOKEN_2 = "accessToken2"
        private const val REFRESH_TOKEN = "refreshToken"
        private const val REFRESH_TOKEN_2 = "refreshToken2"
        private const val CAPTCHA_KEY = "captcha"
        private const val DEFAULT_KDF_ITERATIONS = 600000
        private const val ENCRYPTED_USER_KEY = "encryptedUserKey"
        private const val PUBLIC_KEY = "PublicKey"
        private const val PRIVATE_KEY = "privateKey"
        private const val USER_ID_1 = "2a135b23-e1fb-42c9-bec3-573857bc8181"
        private const val USER_ID_2 = "b9d32ec0-6497-4582-9798-b350f53bfa02"
        private val ORGANIZATIONS = listOf(createMockOrganization(number = 0))
        private val PRE_LOGIN_SUCCESS = PreLoginResponseJson(
            kdfParams = PreLoginResponseJson.KdfParams.Pbkdf2(iterations = 1u),
        )
        private val REFRESH_TOKEN_RESPONSE_JSON = RefreshTokenResponseJson(
            accessToken = ACCESS_TOKEN_2,
            expiresIn = 3600,
            refreshToken = REFRESH_TOKEN_2,
            tokenType = "Bearer",
        )
        private val GET_TOKEN_RESPONSE_SUCCESS = GetTokenResponseJson.Success(
            accessToken = ACCESS_TOKEN,
            refreshToken = "refreshToken",
            tokenType = "Bearer",
            expiresInSeconds = 3600,
            key = "key",
            kdfType = KdfTypeJson.ARGON2_ID,
            kdfIterations = 600000,
            kdfMemory = 16,
            kdfParallelism = 4,
            privateKey = "privateKey",
            shouldForcePasswordReset = true,
            shouldResetMasterPassword = true,
            masterPasswordPolicyOptions = null,
            userDecryptionOptions = null,
        )
        private val ACCOUNT_1 = AccountJson(
            profile = AccountJson.Profile(
                userId = USER_ID_1,
                email = "test@bitwarden.com",
                isEmailVerified = true,
                name = "Bitwarden Tester",
                hasPremium = false,
                stamp = null,
                organizationId = null,
                avatarColorHex = null,
                forcePasswordResetReason = null,
                kdfType = KdfTypeJson.ARGON2_ID,
                kdfIterations = 600000,
                kdfMemory = 16,
                kdfParallelism = 4,
                userDecryptionOptions = null,
            ),
            tokens = AccountJson.Tokens(
                accessToken = ACCESS_TOKEN,
                refreshToken = REFRESH_TOKEN,
            ),
            settings = AccountJson.Settings(
                environmentUrlData = null,
            ),
        )
        private val ACCOUNT_2 = AccountJson(
            profile = AccountJson.Profile(
                userId = USER_ID_2,
                email = EMAIL_2,
                isEmailVerified = true,
                name = "Bitwarden Tester 2",
                hasPremium = false,
                stamp = null,
                organizationId = null,
                avatarColorHex = null,
                forcePasswordResetReason = null,
                kdfType = KdfTypeJson.PBKDF2_SHA256,
                kdfIterations = 400000,
                kdfMemory = null,
                kdfParallelism = null,
                userDecryptionOptions = null,
            ),
            tokens = AccountJson.Tokens(
                accessToken = ACCESS_TOKEN_2,
                refreshToken = "refreshToken",
            ),
            settings = AccountJson.Settings(
                environmentUrlData = null,
            ),
        )
        private val SINGLE_USER_STATE_1 = UserStateJson(
            activeUserId = USER_ID_1,
            accounts = mapOf(
                USER_ID_1 to ACCOUNT_1,
            ),
        )
        private val SINGLE_USER_STATE_2 = UserStateJson(
            activeUserId = USER_ID_2,
            accounts = mapOf(
                USER_ID_2 to ACCOUNT_2,
            ),
        )
        private val MULTI_USER_STATE = UserStateJson(
            activeUserId = USER_ID_1,
            accounts = mapOf(
                USER_ID_1 to ACCOUNT_1,
                USER_ID_2 to ACCOUNT_2,
            ),
        )
        private val USER_ORGANIZATIONS = listOf(
            UserOrganizations(
                userId = USER_ID_1,
                organizations = ORGANIZATIONS.toOrganizations(),
            ),
        )
        private val VAULT_STATE = VaultState(
            unlockedVaultUserIds = setOf(USER_ID_1),
            unlockingVaultUserIds = emptySet(),
        )
    }
}
