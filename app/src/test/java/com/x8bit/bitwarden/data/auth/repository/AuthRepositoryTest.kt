package com.x8bit.bitwarden.data.auth.repository

import app.cash.turbine.test
import com.bitwarden.core.AuthRequestResponse
import com.bitwarden.core.RegisterKeyResponse
import com.bitwarden.crypto.HashPurpose
import com.bitwarden.crypto.Kdf
import com.bitwarden.crypto.RsaKeyPair
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.EnvironmentUrlDataJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.network.model.AuthRequestsResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.GetTokenResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.IdentityTokenAuthModel
import com.x8bit.bitwarden.data.auth.datasource.network.model.KdfTypeJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.OrganizationDomainSsoDetailsResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.PasswordHintResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.PreLoginResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.PrevalidateSsoResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.RefreshTokenResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.RegisterRequestJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.RegisterResponseJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.ResendEmailJsonRequest
import com.x8bit.bitwarden.data.auth.datasource.network.model.TwoFactorAuthMethod
import com.x8bit.bitwarden.data.auth.datasource.network.model.TwoFactorDataModel
import com.x8bit.bitwarden.data.auth.datasource.network.service.AccountsService
import com.x8bit.bitwarden.data.auth.datasource.network.service.AuthRequestsService
import com.x8bit.bitwarden.data.auth.datasource.network.service.DevicesService
import com.x8bit.bitwarden.data.auth.datasource.network.service.HaveIBeenPwnedService
import com.x8bit.bitwarden.data.auth.datasource.network.service.IdentityService
import com.x8bit.bitwarden.data.auth.datasource.network.service.NewAuthRequestService
import com.x8bit.bitwarden.data.auth.datasource.network.service.OrganizationService
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength.LEVEL_0
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength.LEVEL_1
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength.LEVEL_2
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength.LEVEL_3
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength.LEVEL_4
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManager
import com.x8bit.bitwarden.data.auth.repository.model.AuthRequest
import com.x8bit.bitwarden.data.auth.repository.model.AuthRequestResult
import com.x8bit.bitwarden.data.auth.repository.model.AuthRequestsResult
import com.x8bit.bitwarden.data.auth.repository.model.AuthState
import com.x8bit.bitwarden.data.auth.repository.model.BreachCountResult
import com.x8bit.bitwarden.data.auth.repository.model.DeleteAccountResult
import com.x8bit.bitwarden.data.auth.repository.model.KnownDeviceResult
import com.x8bit.bitwarden.data.auth.repository.model.LoginResult
import com.x8bit.bitwarden.data.auth.repository.model.OrganizationDomainSsoDetailsResult
import com.x8bit.bitwarden.data.auth.repository.model.PasswordHintResult
import com.x8bit.bitwarden.data.auth.repository.model.PasswordStrengthResult
import com.x8bit.bitwarden.data.auth.repository.model.PrevalidateSsoResult
import com.x8bit.bitwarden.data.auth.repository.model.RegisterResult
import com.x8bit.bitwarden.data.auth.repository.model.ResendEmailResult
import com.x8bit.bitwarden.data.auth.repository.model.SwitchAccountResult
import com.x8bit.bitwarden.data.auth.repository.model.UserOrganizations
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePasswordResult
import com.x8bit.bitwarden.data.auth.repository.model.VaultUnlockType
import com.x8bit.bitwarden.data.auth.repository.util.CaptchaCallbackTokenResult
import com.x8bit.bitwarden.data.auth.repository.util.SsoCallbackResult
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
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockData
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

@Suppress("LargeClass")
class AuthRepositoryTest {

    private val dispatcherManager: DispatcherManager = FakeDispatcherManager()
    private val accountsService: AccountsService = mockk()
    private val authRequestsService: AuthRequestsService = mockk()
    private val devicesService: DevicesService = mockk()
    private val identityService: IdentityService = mockk()
    private val haveIBeenPwnedService: HaveIBeenPwnedService = mockk()
    private val newAuthRequestService: NewAuthRequestService = mockk()
    private val organizationService: OrganizationService = mockk()
    private val mutableVaultStateFlow = MutableStateFlow(VAULT_STATE)
    private val vaultRepository: VaultRepository = mockk {
        every { vaultUnlockDataStateFlow } returns mutableVaultStateFlow
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
            getNewAuthRequest(
                email = EMAIL,
            )
        } returns Result.success(AUTH_REQUEST_RESPONSE)
        coEvery {
            hashPassword(
                email = EMAIL,
                password = PASSWORD,
                kdf = PRE_LOGIN_SUCCESS.kdfParams.toSdkParams(),
                purpose = HashPurpose.SERVER_AUTHORIZATION,
            )
        } returns Result.success(PASSWORD_HASH)
        coEvery {
            hashPassword(
                email = EMAIL,
                password = PASSWORD,
                kdf = ACCOUNT_1.profile.toSdkParams(),
                purpose = HashPurpose.LOCAL_AUTHORIZATION,
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
    private val vaultSdkSource = mockk<VaultSdkSource> {
        coEvery {
            getAuthRequestKey(
                publicKey = PUBLIC_KEY,
                userId = USER_ID_1,
            )
        } returns "AsymmetricEncString".asSuccess()
    }
    private val userLogoutManager: UserLogoutManager = mockk {
        every { logout(any()) } just runs
    }

    private var elapsedRealtimeMillis = 123456789L

    private val repository = AuthRepositoryImpl(
        accountsService = accountsService,
        authRequestsService = authRequestsService,
        devicesService = devicesService,
        identityService = identityService,
        haveIBeenPwnedService = haveIBeenPwnedService,
        newAuthRequestService = newAuthRequestService,
        organizationService = organizationService,
        authSdkSource = authSdkSource,
        vaultSdkSource = vaultSdkSource,
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
        mockkStatic(
            GetTokenResponseJson.Success::toUserState,
            RefreshTokenResponseJson::toUserStateJson,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(
            GetTokenResponseJson.Success::toUserState,
            RefreshTokenResponseJson::toUserStateJson,
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
                hasPendingAccountAddition = false,
                isBiometricsEnabledProvider = { false },
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
                hasPendingAccountAddition = false,
                isBiometricsEnabledProvider = { false },
                vaultUnlockTypeProvider = { VaultUnlockType.PIN },
            ),
            repository.userStateFlow.value,
        )

        val emptyVaultState = emptyList<VaultUnlockData>()
        mutableVaultStateFlow.value = emptyVaultState
        assertEquals(
            MULTI_USER_STATE.toUserState(
                vaultState = emptyVaultState,
                userOrganizationsList = emptyList(),
                hasPendingAccountAddition = false,
                isBiometricsEnabledProvider = { false },
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
                hasPendingAccountAddition = false,
                isBiometricsEnabledProvider = { false },
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
    fun `rememberedOrgIdentifier should pull from and update AuthDiskSource`() {
        // AuthDiskSource and the repository start with the same value.
        assertNull(repository.rememberedOrgIdentifier)
        assertNull(fakeAuthDiskSource.rememberedOrgIdentifier)

        // Updating the repository updates AuthDiskSource
        repository.rememberedOrgIdentifier = "Bitwarden"
        assertEquals("Bitwarden", fakeAuthDiskSource.rememberedOrgIdentifier)

        // Updating AuthDiskSource updates the repository
        fakeAuthDiskSource.rememberedOrgIdentifier = null
        assertNull(repository.rememberedOrgIdentifier)
    }

    @Test
    fun `clear Pending Account Deletion should unblock userState updates`() = runTest {
        val masterPassword = "hello world"
        val hashedMasterPassword = "dlrow olleh"
        val originalUserState = SINGLE_USER_STATE_1.toUserState(
            vaultState = VAULT_STATE,
            userOrganizationsList = emptyList(),
            hasPendingAccountAddition = false,
            isBiometricsEnabledProvider = { false },
            vaultUnlockTypeProvider = { VaultUnlockType.MASTER_PASSWORD },
        )
        val finalUserState = SINGLE_USER_STATE_2.toUserState(
            vaultState = VAULT_STATE,
            userOrganizationsList = emptyList(),
            hasPendingAccountAddition = false,
            isBiometricsEnabledProvider = { false },
            vaultUnlockTypeProvider = { VaultUnlockType.MASTER_PASSWORD },
        )
        val kdf = SINGLE_USER_STATE_1.activeAccount.profile.toSdkParams()
        coEvery {
            authSdkSource.hashPassword(EMAIL, masterPassword, kdf, HashPurpose.SERVER_AUTHORIZATION)
        } returns hashedMasterPassword.asSuccess()
        coEvery {
            accountsService.deleteAccount(hashedMasterPassword)
        } returns Unit.asSuccess()
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1

        repository.userStateFlow.test {
            assertEquals(originalUserState, awaitItem())

            // Deleting the account sets the pending deletion flag
            repository.deleteAccount(password = masterPassword)

            // Update the account. No changes are emitted because
            // the pending deletion blocks the update.
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_2
            expectNoEvents()

            // Clearing the pending deletion allows the change to go through
            repository.clearPendingAccountDeletion()
            assertEquals(finalUserState, awaitItem())
        }
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
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
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
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
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
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
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
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
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
                    authModel = IdentityTokenAuthModel.MasterPassword(
                        username = EMAIL,
                        password = PASSWORD_HASH,
                    ),
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
            coEvery { vaultRepository.syncIfNecessary() } just runs
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
            fakeAuthDiskSource.assertMasterPasswordHash(
                userId = USER_ID_1,
                passwordHash = PASSWORD_HASH,
            )
            coVerify {
                identityService.getToken(
                    email = EMAIL,
                    authModel = IdentityTokenAuthModel.MasterPassword(
                        username = EMAIL,
                        password = PASSWORD_HASH,
                    ),
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
                vaultRepository.syncIfNecessary()
            }
            assertEquals(
                SINGLE_USER_STATE_1,
                fakeAuthDiskSource.userState,
            )
            verify { settingsRepository.setDefaultsIfNecessary(userId = USER_ID_1) }
            verify { vaultRepository.clearUnlockedData() }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `login get token succeeds when there is an existing user should switch to the new logged in user and lock the old user's vault`() =
        runTest {
            // Ensure the initial state for User 2 with a account addition
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_2
            repository.hasPendingAccountAddition = true

            // Set up login for User 1
            val successResponse = GET_TOKEN_RESPONSE_SUCCESS
            coEvery {
                accountsService.preLogin(email = EMAIL)
            } returns Result.success(PRE_LOGIN_SUCCESS)
            coEvery {
                identityService.getToken(
                    email = EMAIL,
                    authModel = IdentityTokenAuthModel.MasterPassword(
                        username = EMAIL,
                        password = PASSWORD_HASH,
                    ),
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
            coEvery { vaultRepository.syncIfNecessary() } just runs
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
                    authModel = IdentityTokenAuthModel.MasterPassword(
                        username = EMAIL,
                        password = PASSWORD_HASH,
                    ),
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
                vaultRepository.syncIfNecessary()
            }
            assertEquals(
                MULTI_USER_STATE,
                fakeAuthDiskSource.userState,
            )
            assertFalse(repository.hasPendingAccountAddition)
            verify { settingsRepository.setDefaultsIfNecessary(userId = USER_ID_1) }
            verify { vaultRepository.clearUnlockedData() }
        }

    @Test
    fun `login get token returns captcha request should return CaptchaRequired`() = runTest {
        coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
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
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
    }

    @Test
    fun `login get token returns two factor request should return TwoFactorRequired`() = runTest {
        coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
            .returns(
                Result.success(
                    GetTokenResponseJson.TwoFactorRequired(
                        TWO_FACTOR_AUTH_METHODS_DATA, null, null,
                    ),
                ),
            )
        val result = repository.login(email = EMAIL, password = PASSWORD, captchaToken = null)
        assertEquals(LoginResult.TwoFactorRequired, result)
        assertEquals(
            repository.twoFactorResponse,
            GetTokenResponseJson.TwoFactorRequired(TWO_FACTOR_AUTH_METHODS_DATA, null, null),
        )
        assertEquals(AuthState.Unauthenticated, repository.authStateFlow.value)
        coVerify { accountsService.preLogin(email = EMAIL) }
        coVerify {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
    }

    @Test
    fun `login two factor with remember saves two factor auth token`() = runTest {
        // Attempt a normal login with a two factor error first, so that the auth
        // data will be cached.
        coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        } returns Result.success(
            GetTokenResponseJson.TwoFactorRequired(
                TWO_FACTOR_AUTH_METHODS_DATA, null, null,
            ),
        )
        val firstResult = repository.login(email = EMAIL, password = PASSWORD, captchaToken = null)
        assertEquals(LoginResult.TwoFactorRequired, firstResult)
        coVerify { accountsService.preLogin(email = EMAIL) }
        coVerify {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }

        // Login with two factor data.
        val successResponse = GET_TOKEN_RESPONSE_SUCCESS.copy(
            twoFactorToken = "twoFactorTokenToStore",
        )
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
                twoFactorData = TWO_FACTOR_DATA,
            )
        } returns Result.success(successResponse)
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
        coEvery { vaultRepository.syncIfNecessary() } just runs
        every {
            successResponse.toUserState(
                previousUserState = null,
                environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US,
            )
        } returns SINGLE_USER_STATE_1
        val finalResult = repository.login(
            email = EMAIL,
            password = PASSWORD,
            twoFactorData = TWO_FACTOR_DATA,
            captchaToken = null,
        )
        assertEquals(LoginResult.Success, finalResult)
        assertNull(repository.twoFactorResponse)
        fakeAuthDiskSource.assertTwoFactorToken(
            email = EMAIL,
            twoFactorToken = "twoFactorTokenToStore",
        )
    }

    @Test
    fun `login uses remembered two factor tokens`() = runTest {
        fakeAuthDiskSource.storeTwoFactorToken(EMAIL, "storedTwoFactorToken")
        val rememberedTwoFactorData = TwoFactorDataModel(
            code = "storedTwoFactorToken",
            method = TwoFactorAuthMethod.REMEMBER.value.toString(),
            remember = false,
        )
        val successResponse = GET_TOKEN_RESPONSE_SUCCESS
        coEvery {
            accountsService.preLogin(email = EMAIL)
        } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
                twoFactorData = rememberedTwoFactorData,
            )
        } returns Result.success(successResponse)
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
        coEvery { vaultRepository.syncIfNecessary() } just runs
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
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
                twoFactorData = rememberedTwoFactorData,
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
            vaultRepository.syncIfNecessary()
        }
        assertEquals(
            SINGLE_USER_STATE_1,
            fakeAuthDiskSource.userState,
        )
        verify { settingsRepository.setDefaultsIfNecessary(userId = USER_ID_1) }
        verify { vaultRepository.clearUnlockedData() }
    }

    @Test
    fun `login two factor returns error if no cached auth data`() = runTest {
        val result = repository.login(
            email = EMAIL,
            password = PASSWORD,
            twoFactorData = TWO_FACTOR_DATA,
            captchaToken = null,
        )
        assertEquals(LoginResult.Error(errorMessage = null), result)
    }

    @Test
    fun `SSO login get token fails should return Error with no message`() = runTest {
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
            .returns(Result.failure(RuntimeException()))
        val result = repository.login(
            email = EMAIL,
            ssoCode = SSO_CODE,
            ssoCodeVerifier = SSO_CODE_VERIFIER,
            ssoRedirectUri = SSO_REDIRECT_URI,
            captchaToken = null,
        )
        assertEquals(LoginResult.Error(errorMessage = null), result)
        assertEquals(AuthState.Unauthenticated, repository.authStateFlow.value)
        coVerify {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
    }

    @Test
    fun `SSO login get token returns Invalid should return Error with correct message`() = runTest {
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
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

        val result = repository.login(
            email = EMAIL,
            ssoCode = SSO_CODE,
            ssoCodeVerifier = SSO_CODE_VERIFIER,
            ssoRedirectUri = SSO_REDIRECT_URI,
            captchaToken = null,
        )
        assertEquals(LoginResult.Error(errorMessage = "mock_error_message"), result)
        assertEquals(AuthState.Unauthenticated, repository.authStateFlow.value)
        coVerify {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
    }

    @Test
    @Suppress("MaxLineLength")
    fun `SSO login get token succeeds should return Success, update AuthState, update stored keys, and sync`() =
        runTest {
            val successResponse = GET_TOKEN_RESPONSE_SUCCESS
            coEvery {
                identityService.getToken(
                    email = EMAIL,
                    authModel = IdentityTokenAuthModel.SingleSignOn(
                        ssoCode = SSO_CODE,
                        ssoCodeVerifier = SSO_CODE_VERIFIER,
                        ssoRedirectUri = SSO_REDIRECT_URI,
                    ),
                    captchaToken = null,
                    uniqueAppId = UNIQUE_APP_ID,
                )
            }
                .returns(Result.success(successResponse))
            coEvery { vaultRepository.syncIfNecessary() } just runs
            every {
                GET_TOKEN_RESPONSE_SUCCESS.toUserState(
                    previousUserState = null,
                    environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US,
                )
            } returns SINGLE_USER_STATE_1
            val result = repository.login(
                email = EMAIL,
                ssoCode = SSO_CODE,
                ssoCodeVerifier = SSO_CODE_VERIFIER,
                ssoRedirectUri = SSO_REDIRECT_URI,
                captchaToken = null,
            )
            assertEquals(LoginResult.Success, result)
            assertEquals(AuthState.Authenticated(ACCESS_TOKEN), repository.authStateFlow.value)
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
                    authModel = IdentityTokenAuthModel.SingleSignOn(
                        ssoCode = SSO_CODE,
                        ssoCodeVerifier = SSO_CODE_VERIFIER,
                        ssoRedirectUri = SSO_REDIRECT_URI,
                    ),
                    captchaToken = null,
                    uniqueAppId = UNIQUE_APP_ID,
                )
                vaultRepository.syncIfNecessary()
            }
            assertEquals(
                SINGLE_USER_STATE_1,
                fakeAuthDiskSource.userState,
            )
            verify { settingsRepository.setDefaultsIfNecessary(userId = USER_ID_1) }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `SSO login get token succeeds when there is an existing user should switch to the new logged in user`() =
        runTest {
            // Ensure the initial state for User 2 with a account addition
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_2
            repository.hasPendingAccountAddition = true

            // Set up login for User 1
            val successResponse = GET_TOKEN_RESPONSE_SUCCESS
            coEvery {
                identityService.getToken(
                    email = EMAIL,
                    authModel = IdentityTokenAuthModel.SingleSignOn(
                        ssoCode = SSO_CODE,
                        ssoCodeVerifier = SSO_CODE_VERIFIER,
                        ssoRedirectUri = SSO_REDIRECT_URI,
                    ),
                    captchaToken = null,
                    uniqueAppId = UNIQUE_APP_ID,
                )
            }
                .returns(Result.success(successResponse))
            coEvery { vaultRepository.syncIfNecessary() } just runs
            every {
                GET_TOKEN_RESPONSE_SUCCESS.toUserState(
                    previousUserState = SINGLE_USER_STATE_2,
                    environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US,
                )
            } returns MULTI_USER_STATE

            val result = repository.login(
                email = EMAIL,
                ssoCode = SSO_CODE,
                ssoCodeVerifier = SSO_CODE_VERIFIER,
                ssoRedirectUri = SSO_REDIRECT_URI,
                captchaToken = null,
            )

            assertEquals(LoginResult.Success, result)
            assertEquals(AuthState.Authenticated(ACCESS_TOKEN), repository.authStateFlow.value)
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
                    authModel = IdentityTokenAuthModel.SingleSignOn(
                        ssoCode = SSO_CODE,
                        ssoCodeVerifier = SSO_CODE_VERIFIER,
                        ssoRedirectUri = SSO_REDIRECT_URI,
                    ),
                    captchaToken = null,
                    uniqueAppId = UNIQUE_APP_ID,
                )
                vaultRepository.syncIfNecessary()
            }
            assertEquals(
                MULTI_USER_STATE,
                fakeAuthDiskSource.userState,
            )
            assertFalse(repository.hasPendingAccountAddition)
            verify { settingsRepository.setDefaultsIfNecessary(userId = USER_ID_1) }
        }

    @Test
    fun `SSO login get token returns captcha request should return CaptchaRequired`() = runTest {
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
            .returns(Result.success(GetTokenResponseJson.CaptchaRequired(CAPTCHA_KEY)))
        val result = repository.login(
            email = EMAIL,
            ssoCode = SSO_CODE,
            ssoCodeVerifier = SSO_CODE_VERIFIER,
            ssoRedirectUri = SSO_REDIRECT_URI,
            captchaToken = null,
        )
        assertEquals(LoginResult.CaptchaRequired(CAPTCHA_KEY), result)
        assertEquals(AuthState.Unauthenticated, repository.authStateFlow.value)
        coVerify {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }
    }

    @Test
    fun `SSO login get token returns two factor request should return TwoFactorRequired`() =
        runTest {
            coEvery {
                identityService.getToken(
                    email = EMAIL,
                    authModel = IdentityTokenAuthModel.SingleSignOn(
                        ssoCode = SSO_CODE,
                        ssoCodeVerifier = SSO_CODE_VERIFIER,
                        ssoRedirectUri = SSO_REDIRECT_URI,
                    ),
                    captchaToken = null,
                    uniqueAppId = UNIQUE_APP_ID,
                )
            }
                .returns(
                    Result.success(
                        GetTokenResponseJson.TwoFactorRequired(
                            TWO_FACTOR_AUTH_METHODS_DATA, null, null,
                        ),
                    ),
                )
            val result = repository.login(
                email = EMAIL,
                ssoCode = SSO_CODE,
                ssoCodeVerifier = SSO_CODE_VERIFIER,
                ssoRedirectUri = SSO_REDIRECT_URI,
                captchaToken = null,
            )
            assertEquals(LoginResult.TwoFactorRequired, result)
            assertEquals(
                repository.twoFactorResponse,
                GetTokenResponseJson.TwoFactorRequired(TWO_FACTOR_AUTH_METHODS_DATA, null, null),
            )
            assertEquals(AuthState.Unauthenticated, repository.authStateFlow.value)
            coVerify {
                identityService.getToken(
                    email = EMAIL,
                    authModel = IdentityTokenAuthModel.SingleSignOn(
                        ssoCode = SSO_CODE,
                        ssoCodeVerifier = SSO_CODE_VERIFIER,
                        ssoRedirectUri = SSO_REDIRECT_URI,
                    ),
                    captchaToken = null,
                    uniqueAppId = UNIQUE_APP_ID,
                )
            }
        }

    @Test
    fun `SSO login two factor with remember saves two factor auth token`() = runTest {
        // Attempt a normal login with a two factor error first, so that the auth
        // data will be cached.
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        } returns Result.success(
            GetTokenResponseJson.TwoFactorRequired(
                TWO_FACTOR_AUTH_METHODS_DATA, null, null,
            ),
        )
        val firstResult = repository.login(
            email = EMAIL,
            ssoCode = SSO_CODE,
            ssoCodeVerifier = SSO_CODE_VERIFIER,
            ssoRedirectUri = SSO_REDIRECT_URI,
            captchaToken = null,
        )
        assertEquals(LoginResult.TwoFactorRequired, firstResult)
        coVerify {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }

        // Login with two factor data.
        val successResponse = GET_TOKEN_RESPONSE_SUCCESS.copy(
            twoFactorToken = "twoFactorTokenToStore",
        )
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
                twoFactorData = TWO_FACTOR_DATA,
            )
        } returns Result.success(successResponse)
        coEvery { vaultRepository.syncIfNecessary() } just runs
        every {
            successResponse.toUserState(
                previousUserState = null,
                environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US,
            )
        } returns SINGLE_USER_STATE_1
        val finalResult = repository.login(
            email = EMAIL,
            password = null,
            twoFactorData = TWO_FACTOR_DATA,
            captchaToken = null,
        )
        assertEquals(LoginResult.Success, finalResult)
        assertNull(repository.twoFactorResponse)
        fakeAuthDiskSource.assertTwoFactorToken(
            email = EMAIL,
            twoFactorToken = "twoFactorTokenToStore",
        )
    }

    @Test
    fun `SSO login uses remembered two factor tokens`() = runTest {
        fakeAuthDiskSource.storeTwoFactorToken(EMAIL, "storedTwoFactorToken")
        val rememberedTwoFactorData = TwoFactorDataModel(
            code = "storedTwoFactorToken",
            method = TwoFactorAuthMethod.REMEMBER.value.toString(),
            remember = false,
        )
        val successResponse = GET_TOKEN_RESPONSE_SUCCESS
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
                twoFactorData = rememberedTwoFactorData,
            )
        } returns Result.success(successResponse)
        coEvery { vaultRepository.syncIfNecessary() } just runs
        every {
            GET_TOKEN_RESPONSE_SUCCESS.toUserState(
                previousUserState = null,
                environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US,
            )
        } returns SINGLE_USER_STATE_1
        val result = repository.login(
            email = EMAIL,
            ssoCode = SSO_CODE,
            ssoCodeVerifier = SSO_CODE_VERIFIER,
            ssoRedirectUri = SSO_REDIRECT_URI,
            captchaToken = null,
        )
        assertEquals(LoginResult.Success, result)
        assertEquals(AuthState.Authenticated(ACCESS_TOKEN), repository.authStateFlow.value)
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
                authModel = IdentityTokenAuthModel.SingleSignOn(
                    ssoCode = SSO_CODE,
                    ssoCodeVerifier = SSO_CODE_VERIFIER,
                    ssoRedirectUri = SSO_REDIRECT_URI,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
                twoFactorData = rememberedTwoFactorData,
            )
            vaultRepository.syncIfNecessary()
        }
        assertEquals(
            SINGLE_USER_STATE_1,
            fakeAuthDiskSource.userState,
        )
        verify { settingsRepository.setDefaultsIfNecessary(userId = USER_ID_1) }
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
    fun `passwordHintRequest with valid email should return Success`() = runTest {
        val email = "valid@example.com"
        coEvery {
            accountsService.requestPasswordHint(email)
        } returns Result.success(PasswordHintResponseJson.Success)

        val result = repository.passwordHintRequest(email)

        assertEquals(PasswordHintResult.Success, result)
    }

    @Test
    fun `passwordHintRequest with error response should return Error`() = runTest {
        val email = "error@example.com"
        val errorMessage = "Error message"
        coEvery {
            accountsService.requestPasswordHint(email)
        } returns Result.success(PasswordHintResponseJson.Error(errorMessage))

        val result = repository.passwordHintRequest(email)

        assertEquals(PasswordHintResult.Error(errorMessage), result)
    }

    @Test
    fun `passwordHintRequest with failure should return Error with null message`() = runTest {
        val email = "failure@example.com"
        coEvery {
            accountsService.requestPasswordHint(email)
        } returns Result.failure(RuntimeException("Network error"))

        val result = repository.passwordHintRequest(email)

        assertEquals(PasswordHintResult.Error(null), result)
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

    @Test
    fun `setSsoCallbackResult should change the value of ssoCallbackResultFlow`() = runTest {
        repository.ssoCallbackResultFlow.test {
            repository.setSsoCallbackResult(
                SsoCallbackResult.Success(state = "mockk_state", code = "mockk_code"),
            )
            assertEquals(
                SsoCallbackResult.Success(state = "mockk_state", code = "mockk_code"),
                awaitItem(),
            )
        }
    }

    @Test
    fun `getOrganizationDomainSsoDetails Failure should return Failure `() = runTest {
        val email = "test@gmail.com"
        val throwable = Throwable()
        coEvery {
            organizationService.getOrganizationDomainSsoDetails(email)
        } returns Result.failure(throwable)
        val result = repository.getOrganizationDomainSsoDetails(email)
        assertEquals(OrganizationDomainSsoDetailsResult.Failure, result)
    }

    @Test
    fun `getOrganizationDomainSsoDetails Success should return Success`() = runTest {
        val email = "test@gmail.com"
        coEvery {
            organizationService.getOrganizationDomainSsoDetails(email)
        } returns Result.success(
            OrganizationDomainSsoDetailsResponseJson(
                isSsoAvailable = true,
                organizationIdentifier = "Test Org",
                domainName = "bitwarden.com",
                isSsoRequired = false,
                verifiedDate = ZonedDateTime.parse("2024-09-13T00:00Z"),
            ),
        )
        val result = repository.getOrganizationDomainSsoDetails(email)
        assertEquals(
            OrganizationDomainSsoDetailsResult.Success(
                isSsoAvailable = true,
                organizationIdentifier = "Test Org",
            ),
            result,
        )
    }

    @Test
    fun `prevalidateSso Failure should return Failure `() = runTest {
        val organizationId = "organizationid"
        val throwable = Throwable()
        coEvery {
            identityService.prevalidateSso(organizationId)
        } returns Result.failure(throwable)
        val result = repository.prevalidateSso(organizationId)
        assertEquals(PrevalidateSsoResult.Failure, result)
    }

    @Test
    fun `prevalidateSso Success with a blank token should return Failure`() = runTest {
        val organizationId = "organizationid"
        coEvery {
            identityService.prevalidateSso(organizationId)
        } returns Result.success(PrevalidateSsoResponseJson(token = ""))
        val result = repository.prevalidateSso(organizationId)
        assertEquals(PrevalidateSsoResult.Failure, result)
    }

    @Test
    fun `prevalidateSso Success with a valid token should return Success`() = runTest {
        val organizationId = "organizationid"
        coEvery {
            identityService.prevalidateSso(organizationId)
        } returns Result.success(PrevalidateSsoResponseJson(token = "token"))
        val result = repository.prevalidateSso(organizationId)
        assertEquals(PrevalidateSsoResult.Success(token = "token"), result)
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
    fun `resendVerificationCodeEmail uses cached request data to make api call`() = runTest {
        // Attempt a normal login with a two factor error first, so that the necessary
        // data will be cached.
        coEvery { accountsService.preLogin(EMAIL) } returns Result.success(PRE_LOGIN_SUCCESS)
        coEvery {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        } returns Result.success(
            GetTokenResponseJson.TwoFactorRequired(
                TWO_FACTOR_AUTH_METHODS_DATA, null, null,
            ),
        )
        val firstResult = repository.login(email = EMAIL, password = PASSWORD, captchaToken = null)
        assertEquals(LoginResult.TwoFactorRequired, firstResult)
        coVerify { accountsService.preLogin(email = EMAIL) }
        coVerify {
            identityService.getToken(
                email = EMAIL,
                authModel = IdentityTokenAuthModel.MasterPassword(
                    username = EMAIL,
                    password = PASSWORD_HASH,
                ),
                captchaToken = null,
                uniqueAppId = UNIQUE_APP_ID,
            )
        }

        // Resend the verification code email.
        coEvery {
            accountsService.resendVerificationCodeEmail(
                body = ResendEmailJsonRequest(
                    deviceIdentifier = UNIQUE_APP_ID,
                    email = EMAIL,
                    passwordHash = PASSWORD_HASH,
                    ssoToken = null,
                ),
            )
        } returns Result.success(Unit)
        val resendEmailResult = repository.resendVerificationCodeEmail()
        assertEquals(ResendEmailResult.Success, resendEmailResult)
        coVerify {
            accountsService.resendVerificationCodeEmail(
                body = ResendEmailJsonRequest(
                    deviceIdentifier = UNIQUE_APP_ID,
                    email = EMAIL,
                    passwordHash = PASSWORD_HASH,
                    ssoToken = null,
                ),
            )
        }
    }

    @Test
    fun `resendVerificationCodeEmail returns error if no request data cached`() = runTest {
        val result = repository.resendVerificationCodeEmail()
        assertEquals(ResendEmailResult.Error(message = null), result)
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
    fun `switchAccount when the given userId is the same as the current activeUserId should reset any pending account additions`() {
        val originalUserId = USER_ID_1
        val originalUserState = SINGLE_USER_STATE_1.toUserState(
            vaultState = VAULT_STATE,
            userOrganizationsList = emptyList(),
            hasPendingAccountAddition = false,
            isBiometricsEnabledProvider = { false },
            vaultUnlockTypeProvider = { VaultUnlockType.MASTER_PASSWORD },
        )
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        assertEquals(
            originalUserState,
            repository.userStateFlow.value,
        )
        repository.hasPendingAccountAddition = true

        assertEquals(
            SwitchAccountResult.NoChange,
            repository.switchAccount(userId = originalUserId),
        )

        assertEquals(
            originalUserState,
            repository.userStateFlow.value,
        )
        assertFalse(repository.hasPendingAccountAddition)
        verify(exactly = 0) { vaultRepository.clearUnlockedData() }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `switchAccount when the given userId does not correspond to a saved account should do nothing`() {
        val invalidId = "invalidId"
        val originalUserState = SINGLE_USER_STATE_1.toUserState(
            vaultState = VAULT_STATE,
            userOrganizationsList = emptyList(),
            hasPendingAccountAddition = false,
            isBiometricsEnabledProvider = { false },
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
    fun `switchAccount when the userId is valid should update the current UserState, clear the previously unlocked data, and reset any pending account additions`() {
        val updatedUserId = USER_ID_2
        val originalUserState = MULTI_USER_STATE.toUserState(
            vaultState = VAULT_STATE,
            userOrganizationsList = emptyList(),
            hasPendingAccountAddition = false,
            isBiometricsEnabledProvider = { false },
            vaultUnlockTypeProvider = { VaultUnlockType.MASTER_PASSWORD },
        )
        fakeAuthDiskSource.userState = MULTI_USER_STATE
        assertEquals(
            originalUserState,
            repository.userStateFlow.value,
        )
        repository.hasPendingAccountAddition = true

        assertEquals(
            SwitchAccountResult.AccountSwitched,
            repository.switchAccount(userId = updatedUserId),
        )

        assertEquals(
            originalUserState.copy(activeUserId = updatedUserId),
            repository.userStateFlow.value,
        )
        assertFalse(repository.hasPendingAccountAddition)
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
    fun `createAuthRequest should return failure when service returns failure`() = runTest {
        val accessCode = "accessCode"
        val fingerprint = "fingerprint"
        coEvery {
            newAuthRequestService.createAuthRequest(
                email = EMAIL,
                publicKey = PUBLIC_KEY,
                deviceId = UNIQUE_APP_ID,
                accessCode = accessCode,
                fingerprint = fingerprint,
            )
        } returns Throwable("Fail").asFailure()

        val result = repository.createAuthRequest(
            email = EMAIL,
        )

        coVerify(exactly = 1) {
            newAuthRequestService.createAuthRequest(
                email = EMAIL,
                publicKey = PUBLIC_KEY,
                deviceId = UNIQUE_APP_ID,
                accessCode = accessCode,
                fingerprint = fingerprint,
            )
        }
        assertEquals(AuthRequestResult.Error, result)
    }

    @Test
    fun `createAuthRequest should return success when service returns success`() = runTest {
        val accessCode = "accessCode"
        val fingerprint = "fingerprint"

        val responseJson = AuthRequestsResponseJson.AuthRequest(
            id = "1",
            publicKey = PUBLIC_KEY,
            platform = "Android",
            ipAddress = "192.168.0.1",
            key = "public",
            masterPasswordHash = "verySecureHash",
            creationDate = ZonedDateTime.parse("2024-09-13T00:00Z"),
            responseDate = null,
            requestApproved = true,
            originUrl = "www.bitwarden.com",
        )
        val expected = AuthRequestResult.Success(
            authRequest = AuthRequest(
                id = "1",
                publicKey = PUBLIC_KEY,
                platform = "Android",
                ipAddress = "192.168.0.1",
                key = "public",
                masterPasswordHash = "verySecureHash",
                creationDate = ZonedDateTime.parse("2024-09-13T00:00Z"),
                responseDate = null,
                requestApproved = true,
                originUrl = "www.bitwarden.com",
                fingerprint = fingerprint,
            ),
        )
        coEvery {
            newAuthRequestService.createAuthRequest(
                email = EMAIL,
                publicKey = PUBLIC_KEY,
                deviceId = UNIQUE_APP_ID,
                accessCode = accessCode,
                fingerprint = fingerprint,
            )
        } returns responseJson.asSuccess()

        val result = repository.createAuthRequest(
            email = EMAIL,
        )

        coVerify(exactly = 1) {
            newAuthRequestService.createAuthRequest(
                email = EMAIL,
                publicKey = PUBLIC_KEY,
                deviceId = UNIQUE_APP_ID,
                accessCode = accessCode,
                fingerprint = fingerprint,
            )
        }
        assertEquals(expected, result)
    }

    @Test
    fun `getAuthRequest should return failure when getAuthRequests returns failure`() = runTest {
        val fingerprint = "fingerprint"
        coEvery {
            authRequestsService.getAuthRequests()
        } returns Throwable("Fail").asFailure()

        val result = repository.getAuthRequest(fingerprint)

        coVerify(exactly = 1) {
            authRequestsService.getAuthRequests()
        }
        assertEquals(AuthRequestResult.Error, result)
    }

    @Test
    fun `getAuthRequest should return success when service returns success`() = runTest {
        val fingerprint = "fingerprint"
        val responseJson = AuthRequestsResponseJson(
            authRequests = listOf(
                AuthRequestsResponseJson.AuthRequest(
                    id = "1",
                    publicKey = PUBLIC_KEY,
                    platform = "Android",
                    ipAddress = "192.168.0.1",
                    key = "public",
                    masterPasswordHash = "verySecureHash",
                    creationDate = ZonedDateTime.parse("2024-09-13T00:00Z"),
                    responseDate = null,
                    requestApproved = true,
                    originUrl = "www.bitwarden.com",
                ),
            ),
        )
        val expected = AuthRequestResult.Success(
            authRequest = AuthRequest(
                id = "1",
                publicKey = PUBLIC_KEY,
                platform = "Android",
                ipAddress = "192.168.0.1",
                key = "public",
                masterPasswordHash = "verySecureHash",
                creationDate = ZonedDateTime.parse("2024-09-13T00:00Z"),
                responseDate = null,
                requestApproved = true,
                originUrl = "www.bitwarden.com",
                fingerprint = fingerprint,
            ),
        )
        coEvery {
            authSdkSource.getUserFingerprint(
                email = EMAIL,
                publicKey = PUBLIC_KEY,
            )
        } returns Result.success(fingerprint)
        coEvery {
            authRequestsService.getAuthRequests()
        } returns responseJson.asSuccess()
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1

        val result = repository.getAuthRequest(fingerprint)

        coVerify(exactly = 1) {
            authRequestsService.getAuthRequests()
            authSdkSource.getUserFingerprint(EMAIL, PUBLIC_KEY)
        }
        assertEquals(expected, result)
    }

    @Test
    fun `getAuthRequest should return error when no matching fingerprint exists`() = runTest {
        val fingerprint = "fingerprint"
        val responseJson = AuthRequestsResponseJson(
            authRequests = listOf(),
        )
        val expected = AuthRequestResult.Error
        coEvery {
            authRequestsService.getAuthRequests()
        } returns responseJson.asSuccess()

        val result = repository.getAuthRequest(fingerprint)

        coVerify(exactly = 1) {
            authRequestsService.getAuthRequests()
        }
        assertEquals(expected, result)
    }

    @Test
    fun `getAuthRequests should return failure when service returns failure`() = runTest {
        coEvery {
            authRequestsService.getAuthRequests()
        } returns Throwable("Fail").asFailure()

        val result = repository.getAuthRequests()

        coVerify(exactly = 1) {
            authRequestsService.getAuthRequests()
        }
        assertEquals(AuthRequestsResult.Error, result)
    }

    @Test
    fun `getAuthRequests should return success when service returns success`() = runTest {
        val fingerprint = "fingerprint"
        val responseJson = AuthRequestsResponseJson(
            authRequests = listOf(
                AuthRequestsResponseJson.AuthRequest(
                    id = "1",
                    publicKey = PUBLIC_KEY,
                    platform = "Android",
                    ipAddress = "192.168.0.1",
                    key = "public",
                    masterPasswordHash = "verySecureHash",
                    creationDate = ZonedDateTime.parse("2024-09-13T00:00Z"),
                    responseDate = null,
                    requestApproved = true,
                    originUrl = "www.bitwarden.com",
                ),
            ),
        )
        val expected = AuthRequestsResult.Success(
            authRequests = listOf(
                AuthRequest(
                    id = "1",
                    publicKey = PUBLIC_KEY,
                    platform = "Android",
                    ipAddress = "192.168.0.1",
                    key = "public",
                    masterPasswordHash = "verySecureHash",
                    creationDate = ZonedDateTime.parse("2024-09-13T00:00Z"),
                    responseDate = null,
                    requestApproved = true,
                    originUrl = "www.bitwarden.com",
                    fingerprint = fingerprint,
                ),
            ),
        )
        coEvery {
            authSdkSource.getUserFingerprint(
                email = EMAIL,
                publicKey = PUBLIC_KEY,
            )
        } returns Result.success(fingerprint)
        coEvery {
            authRequestsService.getAuthRequests()
        } returns responseJson.asSuccess()
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1

        val result = repository.getAuthRequests()

        coVerify(exactly = 1) {
            authRequestsService.getAuthRequests()
            authSdkSource.getUserFingerprint(EMAIL, PUBLIC_KEY)
        }
        assertEquals(expected, result)
    }

    @Test
    fun `getAuthRequests should return empty list when user profile is null`() = runTest {
        val responseJson = AuthRequestsResponseJson(
            authRequests = listOf(
                AuthRequestsResponseJson.AuthRequest(
                    id = "1",
                    publicKey = PUBLIC_KEY,
                    platform = "Android",
                    ipAddress = "192.168.0.1",
                    key = "public",
                    masterPasswordHash = "verySecureHash",
                    creationDate = ZonedDateTime.parse("2024-09-13T00:00Z"),
                    responseDate = null,
                    requestApproved = true,
                    originUrl = "www.bitwarden.com",
                ),
            ),
        )
        val expected = AuthRequestsResult.Success(emptyList())
        coEvery {
            authRequestsService.getAuthRequests()
        } returns responseJson.asSuccess()

        val result = repository.getAuthRequests()

        coVerify(exactly = 1) {
            authRequestsService.getAuthRequests()
        }
        assertEquals(expected, result)
    }

    @Test
    fun `updateAuthRequest should return failure when sdk returns failure`() = runTest {
        coEvery {
            vaultSdkSource.getAuthRequestKey(
                publicKey = PUBLIC_KEY,
                userId = USER_ID_1,
            )
        } returns Throwable("Fail").asFailure()
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1

        val result = repository.updateAuthRequest(
            requestId = "requestId",
            masterPasswordHash = "masterPasswordHash",
            publicKey = PUBLIC_KEY,
            isApproved = false,
        )

        coVerify(exactly = 1) {
            vaultSdkSource.getAuthRequestKey(
                publicKey = PUBLIC_KEY,
                userId = USER_ID_1,
            )
        }
        assertEquals(AuthRequestResult.Error, result)
    }

    @Test
    fun `updateAuthRequest should return failure when service returns failure`() = runTest {
        val requestId = "requestId"
        val passwordHash = "masterPasswordHash"
        val encodedKey = "encodedKey"
        coEvery {
            vaultSdkSource.getAuthRequestKey(
                publicKey = PUBLIC_KEY,
                userId = USER_ID_1,
            )
        } returns encodedKey.asSuccess()
        coEvery {
            authRequestsService.updateAuthRequest(
                requestId = requestId,
                masterPasswordHash = passwordHash,
                key = encodedKey,
                deviceId = UNIQUE_APP_ID,
                isApproved = false,
            )
        } returns Throwable("Mission failed").asFailure()
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1

        val result = repository.updateAuthRequest(
            requestId = "requestId",
            masterPasswordHash = "masterPasswordHash",
            publicKey = PUBLIC_KEY,
            isApproved = false,
        )

        coVerify(exactly = 1) {
            vaultSdkSource.getAuthRequestKey(
                publicKey = PUBLIC_KEY,
                userId = USER_ID_1,
            )
        }
        assertEquals(AuthRequestResult.Error, result)
    }

    @Suppress("LongMethod")
    @Test
    fun `updateAuthRequest should return success when service & sdk return success`() = runTest {
        val requestId = "requestId"
        val passwordHash = "masterPasswordHash"
        val encodedKey = "encodedKey"
        val responseJson = AuthRequestsResponseJson.AuthRequest(
            id = requestId,
            publicKey = PUBLIC_KEY,
            platform = "Android",
            ipAddress = "192.168.0.1",
            key = "key",
            masterPasswordHash = passwordHash,
            creationDate = ZonedDateTime.parse("2024-09-13T00:00Z"),
            responseDate = null,
            requestApproved = true,
            originUrl = "www.bitwarden.com",
        )
        val expected = AuthRequestResult.Success(
            authRequest = AuthRequest(
                id = requestId,
                publicKey = PUBLIC_KEY,
                platform = "Android",
                ipAddress = "192.168.0.1",
                key = "key",
                masterPasswordHash = passwordHash,
                creationDate = ZonedDateTime.parse("2024-09-13T00:00Z"),
                responseDate = null,
                requestApproved = true,
                originUrl = "www.bitwarden.com",
                fingerprint = "",
            ),
        )
        coEvery {
            vaultSdkSource.getAuthRequestKey(
                publicKey = PUBLIC_KEY,
                userId = USER_ID_1,
            )
        } returns encodedKey.asSuccess()
        coEvery {
            authRequestsService.updateAuthRequest(
                requestId = requestId,
                masterPasswordHash = passwordHash,
                key = encodedKey,
                deviceId = UNIQUE_APP_ID,
                isApproved = false,
            )
        } returns responseJson.asSuccess()
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1

        val result = repository.updateAuthRequest(
            requestId = requestId,
            masterPasswordHash = passwordHash,
            publicKey = PUBLIC_KEY,
            isApproved = false,
        )

        coVerify(exactly = 1) {
            vaultSdkSource.getAuthRequestKey(
                publicKey = PUBLIC_KEY,
                userId = USER_ID_1,
            )
            authRequestsService.updateAuthRequest(
                requestId = requestId,
                masterPasswordHash = passwordHash,
                key = encodedKey,
                deviceId = UNIQUE_APP_ID,
                isApproved = false,
            )
        }
        assertEquals(expected, result)
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

    @Test
    fun `validatePassword with no current user returns ValidatePasswordResult Error`() = runTest {
        val userId = "userId"
        val password = "password"
        val passwordHash = "passwordHash"
        fakeAuthDiskSource.userState = null
        coEvery {
            vaultSdkSource.validatePassword(
                userId = userId,
                password = password,
                passwordHash = passwordHash,
            )
        } returns true.asSuccess()

        val result = repository
            .validatePassword(
                password = password,
            )

        assertEquals(
            ValidatePasswordResult.Error,
            result,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `validatePassword with no stored password hash returns ValidatePasswordResult Error`() =
        runTest {
            val userId = USER_ID_1
            val password = "password"
            val passwordHash = "passwordHash"
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            coEvery {
                vaultSdkSource.validatePassword(
                    userId = userId,
                    password = password,
                    passwordHash = passwordHash,
                )
            } returns true.asSuccess()

            val result = repository
                .validatePassword(
                    password = password,
                )

            assertEquals(
                ValidatePasswordResult.Error,
                result,
            )
        }

    @Test
    fun `validatePassword with sdk failure returns a ValidatePasswordResult Error`() = runTest {
        val userId = USER_ID_1
        val password = "password"
        val passwordHash = "passwordHash"
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        fakeAuthDiskSource.storeMasterPasswordHash(userId = userId, passwordHash = passwordHash)
        coEvery {
            vaultSdkSource.validatePassword(
                userId = userId,
                password = password,
                passwordHash = passwordHash,
            )
        } returns Throwable().asFailure()

        val result = repository
            .validatePassword(
                password = password,
            )

        assertEquals(
            ValidatePasswordResult.Error,
            result,
        )
    }

    @Test
    fun `validatePassword with sdk success returns a ValidatePasswordResult Success`() = runTest {
        val userId = USER_ID_1
        val password = "password"
        val passwordHash = "passwordHash"
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        fakeAuthDiskSource.storeMasterPasswordHash(userId = userId, passwordHash = passwordHash)
        coEvery {
            vaultSdkSource.validatePassword(
                userId = userId,
                password = password,
                passwordHash = passwordHash,
            )
        } returns true.asSuccess()

        val result = repository
            .validatePassword(
                password = password,
            )

        assertEquals(
            ValidatePasswordResult.Success(isValid = true),
            result,
        )
    }

    companion object {
        private const val UNIQUE_APP_ID = "testUniqueAppId"
        private const val EMAIL = "test@bitwarden.com"
        private const val EMAIL_2 = "test2@bitwarden.com"
        private const val PASSWORD = "password"
        private const val PASSWORD_HASH = "passwordHash"
        private const val PASSWORD_HASH_LOCAL = "passwordHashLocal"
        private const val ACCESS_TOKEN = "accessToken"
        private const val ACCESS_TOKEN_2 = "accessToken2"
        private const val REFRESH_TOKEN = "refreshToken"
        private const val REFRESH_TOKEN_2 = "refreshToken2"
        private const val CAPTCHA_KEY = "captcha"
        private const val TWO_FACTOR_CODE = "123456"
        private val TWO_FACTOR_METHOD = TwoFactorAuthMethod.EMAIL
        private const val TWO_FACTOR_REMEMBER = true
        private val TWO_FACTOR_DATA = TwoFactorDataModel(
            code = TWO_FACTOR_CODE,
            method = TWO_FACTOR_METHOD.value.toString(),
            remember = TWO_FACTOR_REMEMBER,
        )
        private const val SSO_CODE = "ssoCode"
        private const val SSO_CODE_VERIFIER = "ssoCodeVerifier"
        private const val SSO_REDIRECT_URI = "bitwarden://sso-test"

        private const val DEFAULT_KDF_ITERATIONS = 600000
        private const val ENCRYPTED_USER_KEY = "encryptedUserKey"
        private const val PUBLIC_KEY = "PublicKey"
        private const val PRIVATE_KEY = "privateKey"
        private const val USER_ID_1 = "2a135b23-e1fb-42c9-bec3-573857bc8181"
        private const val USER_ID_2 = "b9d32ec0-6497-4582-9798-b350f53bfa02"
        private val ORGANIZATIONS = listOf(createMockOrganization(number = 0))
        private val TWO_FACTOR_AUTH_METHODS_DATA = mapOf(
            TwoFactorAuthMethod.EMAIL to mapOf("Email" to "ex***@email.com"),
            TwoFactorAuthMethod.AUTHENTICATOR_APP to mapOf("Email" to null),
        )
        private val PRE_LOGIN_SUCCESS = PreLoginResponseJson(
            kdfParams = PreLoginResponseJson.KdfParams.Pbkdf2(iterations = 1u),
        )
        private val AUTH_REQUEST_RESPONSE = AuthRequestResponse(
            privateKey = PRIVATE_KEY,
            publicKey = PUBLIC_KEY,
            accessCode = "accessCode",
            fingerprint = "fingerprint",
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
            twoFactorToken = null,
            masterPasswordPolicyOptions = null,
            userDecryptionOptions = null,
        )
        private val ACCOUNT_1 = AccountJson(
            profile = AccountJson.Profile(
                userId = USER_ID_1,
                email = EMAIL,
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
        private val VAULT_STATE = listOf(
            VaultUnlockData(
                userId = USER_ID_1,
                status = VaultUnlockData.Status.UNLOCKED,
            ),
        )
    }
}
