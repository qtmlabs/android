package com.x8bit.bitwarden.data.tools.generator.repository

import app.cash.turbine.test
import com.bitwarden.core.PasswordHistory
import com.bitwarden.core.PasswordHistoryView
import com.bitwarden.generators.AppendType
import com.bitwarden.generators.ForwarderServiceType
import com.bitwarden.generators.PassphraseGeneratorRequest
import com.bitwarden.generators.PasswordGeneratorRequest
import com.bitwarden.generators.UsernameGeneratorRequest
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.EnvironmentUrlDataJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.ForcePasswordResetReason
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.KdfTypeJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.KeyConnectorUserDecryptionOptionsJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.TrustedDeviceUserDecryptionOptionsJson
import com.x8bit.bitwarden.data.auth.datasource.network.model.UserDecryptionOptionsJson
import com.x8bit.bitwarden.data.auth.repository.model.PolicyInformation
import com.x8bit.bitwarden.data.platform.base.FakeDispatcherManager
import com.x8bit.bitwarden.data.platform.manager.PolicyManager
import com.x8bit.bitwarden.data.platform.repository.model.LocalDataState
import com.x8bit.bitwarden.data.platform.util.asFailure
import com.x8bit.bitwarden.data.platform.util.asSuccess
import com.x8bit.bitwarden.data.tools.generator.datasource.disk.GeneratorDiskSource
import com.x8bit.bitwarden.data.tools.generator.datasource.disk.PasswordHistoryDiskSource
import com.x8bit.bitwarden.data.tools.generator.datasource.disk.entity.PasswordHistoryEntity
import com.x8bit.bitwarden.data.tools.generator.datasource.disk.entity.toPasswordHistoryEntity
import com.x8bit.bitwarden.data.tools.generator.datasource.sdk.GeneratorSdkSource
import com.x8bit.bitwarden.data.tools.generator.repository.model.GeneratedCatchAllUsernameResult
import com.x8bit.bitwarden.data.tools.generator.repository.model.GeneratedForwardedServiceUsernameResult
import com.x8bit.bitwarden.data.tools.generator.repository.model.GeneratedPassphraseResult
import com.x8bit.bitwarden.data.tools.generator.repository.model.GeneratedPasswordResult
import com.x8bit.bitwarden.data.tools.generator.repository.model.GeneratedPlusAddressedUsernameResult
import com.x8bit.bitwarden.data.tools.generator.repository.model.GeneratedRandomWordUsernameResult
import com.x8bit.bitwarden.data.tools.generator.repository.model.PasscodeGenerationOptions
import com.x8bit.bitwarden.data.tools.generator.repository.model.UsernameGenerationOptions
import com.x8bit.bitwarden.data.vault.datasource.network.model.PolicyTypeJson
import com.x8bit.bitwarden.data.vault.datasource.network.model.SyncResponseJson
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

@Suppress("LargeClass")
class GeneratorRepositoryTest {

    private val mutableUserStateFlow = MutableStateFlow<UserStateJson?>(null)

    private val generatorSdkSource: GeneratorSdkSource = mockk()
    private val generatorDiskSource: GeneratorDiskSource = mockk()
    private val authDiskSource: AuthDiskSource = mockk {
        every { userStateFlow } returns mutableUserStateFlow
        every { userState } returns null
    }
    private val passwordHistoryDiskSource: PasswordHistoryDiskSource = mockk()
    private val vaultSdkSource: VaultSdkSource = mockk()
    private val dispatcherManager = FakeDispatcherManager()
    private val policyManager: PolicyManager = mockk()

    private val repository = GeneratorRepositoryImpl(
        generatorSdkSource = generatorSdkSource,
        generatorDiskSource = generatorDiskSource,
        authDiskSource = authDiskSource,
        passwordHistoryDiskSource = passwordHistoryDiskSource,
        vaultSdkSource = vaultSdkSource,
        dispatcherManager = dispatcherManager,
        policyManager = policyManager,
    )

    @AfterEach
    fun tearDown() {
        unmockkStatic(Instant::class)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `generatePassword should emit Success result and store the generated password when shouldSave is true`() =
        runTest {
            val fixedInstant = Instant.parse("2021-01-01T00:00:00Z")

            mockkStatic(Instant::class)
            every { Instant.now() } returns fixedInstant

            val userId = "testUserId"
            val request = PasswordGeneratorRequest(
                lowercase = true,
                uppercase = true,
                numbers = true,
                special = true,
                length = 12.toUByte(),
                avoidAmbiguous = false,
                minLowercase = null,
                minUppercase = null,
                minNumber = null,
                minSpecial = null,
            )
            val generatedPassword = "GeneratedPassword123!"
            val encryptedPasswordHistory = PasswordHistory(
                password = generatedPassword,
                lastUsedDate = Instant.now(),
            )

            coEvery { authDiskSource.userState?.activeUserId } returns userId

            coEvery {
                generatorSdkSource.generatePassword(request)
            } returns generatedPassword.asSuccess()

            coEvery {
                vaultSdkSource.encryptPasswordHistory(any(), any())
            } returns encryptedPasswordHistory.asSuccess()

            coEvery { passwordHistoryDiskSource.insertPasswordHistory(any()) } just runs

            val result = repository.generatePassword(request, true)

            assertEquals(GeneratedPasswordResult.Success(generatedPassword), result)
            coVerify { generatorSdkSource.generatePassword(request) }

            coVerify {
                passwordHistoryDiskSource.insertPasswordHistory(
                    encryptedPasswordHistory.toPasswordHistoryEntity(userId),
                )
            }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `generatePassword should emit Success result but not store the generated password when shouldSave is false`() =
        runTest {
            val fixedInstant = Instant.parse("2021-01-01T00:00:00Z")

            mockkStatic(Instant::class)
            every { Instant.now() } returns fixedInstant

            val userId = "testUserId"
            val request = PasswordGeneratorRequest(
                lowercase = true,
                uppercase = true,
                numbers = true,
                special = true,
                length = 12.toUByte(),
                avoidAmbiguous = false,
                minLowercase = null,
                minUppercase = null,
                minNumber = null,
                minSpecial = null,
            )
            val generatedPassword = "GeneratedPassword123!"
            val encryptedPasswordHistory = PasswordHistory(
                password = generatedPassword,
                lastUsedDate = Instant.now(),
            )

            coEvery { authDiskSource.userState?.activeUserId } returns userId

            coEvery {
                generatorSdkSource.generatePassword(request)
            } returns generatedPassword.asSuccess()

            coEvery {
                vaultSdkSource.encryptPasswordHistory(any(), any())
            } returns encryptedPasswordHistory.asSuccess()

            coEvery { passwordHistoryDiskSource.insertPasswordHistory(any()) } just runs

            val result = repository.generatePassword(request, false)

            assertEquals(GeneratedPasswordResult.Success(generatedPassword), result)
            coVerify { generatorSdkSource.generatePassword(request) }

            coVerify(exactly = 0) {
                passwordHistoryDiskSource.insertPasswordHistory(any())
            }
        }

    @Test
    fun `generatePassword should emit InvalidRequest result when SDK throws exception`() = runTest {
        val request = PasswordGeneratorRequest(
            lowercase = true,
            uppercase = true,
            numbers = true,
            special = true,
            length = 12.toUByte(),
            avoidAmbiguous = false,
            minLowercase = null,
            minUppercase = null,
            minNumber = null,
            minSpecial = null,
        )
        val exception = RuntimeException("An error occurred")
        coEvery { generatorSdkSource.generatePassword(request) } returns exception.asFailure()

        val result = repository.generatePassword(request, true)

        assertEquals(GeneratedPasswordResult.InvalidRequest, result)
        coVerify { generatorSdkSource.generatePassword(request) }
    }

    @Test
    fun `generatePassphrase should emit Success result and store the generated passphrase`() =
        runTest {
            val fixedInstant = Instant.parse("2021-01-01T00:00:00Z")
            mockkStatic(Instant::class)
            every { Instant.now() } returns fixedInstant

            val userId = "testUserId"
            val request = PassphraseGeneratorRequest(
                numWords = 5.toUByte(),
                capitalize = true,
                includeNumber = true,
                wordSeparator = "-",
            )
            val generatedPassphrase = "Generated-Passphrase-123"
            val encryptedPasswordHistory = PasswordHistory(
                password = generatedPassphrase,
                lastUsedDate = Instant.now(),
            )

            coEvery { authDiskSource.userState?.activeUserId } returns userId

            coEvery {
                generatorSdkSource.generatePassphrase(request)
            } returns generatedPassphrase.asSuccess()

            coEvery {
                vaultSdkSource.encryptPasswordHistory(any(), any())
            } returns encryptedPasswordHistory.asSuccess()

            coEvery { passwordHistoryDiskSource.insertPasswordHistory(any()) } just runs

            val result = repository.generatePassphrase(request)

            assertEquals(GeneratedPassphraseResult.Success(generatedPassphrase), result)
            coVerify { generatorSdkSource.generatePassphrase(request) }
            coVerify { vaultSdkSource.encryptPasswordHistory(any(), any()) }
            coVerify {
                passwordHistoryDiskSource.insertPasswordHistory(
                    encryptedPasswordHistory.toPasswordHistoryEntity(userId),
                )
            }
        }

    @Test
    fun `generatePassphrase should emit InvalidRequest result when SDK throws exception`() =
        runTest {
            val request = PassphraseGeneratorRequest(
                numWords = 5.toUByte(),
                capitalize = true,
                includeNumber = true,
                wordSeparator = '-'.toString(),
            )
            val exception = RuntimeException("An error occurred")
            coEvery { generatorSdkSource.generatePassphrase(request) } returns exception.asFailure()

            val result = repository.generatePassphrase(request)

            assertEquals(GeneratedPassphraseResult.InvalidRequest, result)
            coVerify { generatorSdkSource.generatePassphrase(request) }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `generatePlusAddressedEmail should return Success with generated email when SDK call is successful`() =
        runTest {
            val userId = "testUserId"
            val request = UsernameGeneratorRequest.Subaddress(
                type = AppendType.Random,
                email = "user@example.com",
            )
            val generatedEmail = "user+generated@example.com"

            coEvery { authDiskSource.userState?.activeUserId } returns userId
            coEvery {
                generatorSdkSource.generatePlusAddressedEmail(request)
            } returns generatedEmail.asSuccess()

            val result = repository.generatePlusAddressedEmail(request)

            assertEquals(GeneratedPlusAddressedUsernameResult.Success(generatedEmail), result)
            coVerify { generatorSdkSource.generatePlusAddressedEmail(request) }
        }

    @Test
    fun `generatePlusAddressedEmail should return InvalidRequest on SDK failure`() = runTest {
        val request = UsernameGeneratorRequest.Subaddress(
            type = AppendType.Random,
            email = "user@example.com",
        )
        val exception = RuntimeException("An error occurred")
        coEvery {
            generatorSdkSource.generatePlusAddressedEmail(request)
        } returns exception.asFailure()

        val result = repository.generatePlusAddressedEmail(request)

        assertEquals(GeneratedPlusAddressedUsernameResult.InvalidRequest, result)
        coVerify { generatorSdkSource.generatePlusAddressedEmail(request) }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `generateCatchAllEmail should return Success with generated email when SDK call is successful`() =
        runTest {
            val request = UsernameGeneratorRequest.Catchall(
                type = AppendType.Random,
                domain = "domain",
            )
            val generatedEmail = "user@domain"

            coEvery {
                generatorSdkSource.generateCatchAllEmail(request)
            } returns generatedEmail.asSuccess()

            val result = repository.generateCatchAllEmail(request)

            assertEquals(GeneratedCatchAllUsernameResult.Success(generatedEmail), result)
            coVerify { generatorSdkSource.generateCatchAllEmail(request) }
        }

    @Test
    fun `generateCatchAllEmail should return InvalidRequest on SDK failure`() = runTest {
        val request = UsernameGeneratorRequest.Catchall(
            type = AppendType.Random,
            domain = "user@domain",
        )
        val exception = RuntimeException("An error occurred")
        coEvery { generatorSdkSource.generateCatchAllEmail(request) } returns exception.asFailure()

        val result = repository.generateCatchAllEmail(request)

        assertEquals(GeneratedCatchAllUsernameResult.InvalidRequest, result)
        coVerify { generatorSdkSource.generateCatchAllEmail(request) }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `generateRandomWord should return Success with generated email when SDK call is successful`() =
        runTest {
            val request = UsernameGeneratorRequest.Word(
                capitalize = false,
                includeNumber = false,
            )
            val generatedEmail = "user"

            coEvery {
                generatorSdkSource.generateRandomWord(request)
            } returns generatedEmail.asSuccess()

            val result = repository.generateRandomWordUsername(request)

            assertEquals(GeneratedRandomWordUsernameResult.Success(generatedEmail), result)
            coVerify { generatorSdkSource.generateRandomWord(request) }
        }

    @Test
    fun `generateRandomWord should return InvalidRequest on SDK failure`() = runTest {
        val request = UsernameGeneratorRequest.Word(
            capitalize = false,
            includeNumber = false,
        )
        val exception = RuntimeException("An error occurred")
        coEvery { generatorSdkSource.generateRandomWord(request) } returns exception.asFailure()

        val result = repository.generateRandomWordUsername(request)

        assertEquals(GeneratedRandomWordUsernameResult.InvalidRequest, result)
        coVerify { generatorSdkSource.generateRandomWord(request) }
    }

    @Test
    fun `generateForwardedService should emit Success result and store the generated email`() =
        runTest {
            val userId = "testUserId"
            val request = UsernameGeneratorRequest.Forwarded(
                service = ForwarderServiceType.DuckDuckGo(
                    token = "testToken",
                ),
                website = null,
            )

            val generatedEmail = "generated@email.com"

            coEvery { authDiskSource.userState?.activeUserId } returns userId
            coEvery {
                generatorSdkSource.generateForwardedServiceEmail(request)
            } returns generatedEmail.asSuccess()

            val result = repository.generateForwardedServiceUsername(request)

            assertEquals(GeneratedForwardedServiceUsernameResult.Success(generatedEmail), result)
            coVerify { generatorSdkSource.generateForwardedServiceEmail(request) }
        }

    @Test
    fun `generateForwardedService should emit InvalidRequest result when SDK throws exception`() =
        runTest {
            val request = UsernameGeneratorRequest.Forwarded(
                service = ForwarderServiceType.DuckDuckGo(token = "testToken"),
                website = null,
            )
            val exception = RuntimeException("An error occurred")
            coEvery {
                generatorSdkSource.generateForwardedServiceEmail(request)
            } returns exception.asFailure()

            val result = repository.generateForwardedServiceUsername(request)

            assertEquals(GeneratedForwardedServiceUsernameResult.InvalidRequest, result)
            coVerify { generatorSdkSource.generateForwardedServiceEmail(request) }
        }

    @Test
    fun `getPasscodeGenerationOptions should return options when available`() = runTest {
        val userId = "activeUserId"
        val expectedOptions = PasscodeGenerationOptions(
            length = 14,
            allowAmbiguousChar = false,
            hasNumbers = true,
            minNumber = 0,
            hasUppercase = true,
            minUppercase = null,
            hasLowercase = false,
            minLowercase = null,
            allowSpecial = false,
            minSpecial = 1,
            allowCapitalize = false,
            allowIncludeNumber = false,
            wordSeparator = "-",
            numWords = 3,
        )

        coEvery { authDiskSource.userState } returns USER_STATE

        coEvery {
            generatorDiskSource.getPasscodeGenerationOptions(userId)
        } returns expectedOptions

        val result = repository.getPasscodeGenerationOptions()

        assertEquals(expectedOptions, result)
        coVerify { generatorDiskSource.getPasscodeGenerationOptions(userId) }
    }

    @Test
    fun `getPasscodeGenerationOptions should return null when there is no active user`() = runTest {
        coEvery { authDiskSource.userState } returns null

        val result = repository.getPasscodeGenerationOptions()

        assertNull(result)
        coVerify(exactly = 0) { generatorDiskSource.getPasscodeGenerationOptions(any()) }
    }

    @Test
    fun `getPasscodeGenerationOptions should return null when no data is stored for active user`() =
        runTest {
            val userId = "activeUserId"
            coEvery { authDiskSource.userState } returns USER_STATE
            coEvery { generatorDiskSource.getPasscodeGenerationOptions(userId) } returns null

            val result = repository.getPasscodeGenerationOptions()

            assertNull(result)
            coVerify { generatorDiskSource.getPasscodeGenerationOptions(userId) }
        }

    @Test
    fun `savePasscodeGenerationOptions should store options correctly`() = runTest {
        val userId = "activeUserId"
        val optionsToSave = PasscodeGenerationOptions(
            length = 14,
            allowAmbiguousChar = false,
            hasNumbers = true,
            minNumber = 0,
            hasUppercase = true,
            minUppercase = null,
            hasLowercase = false,
            minLowercase = null,
            allowSpecial = false,
            minSpecial = 1,
            allowCapitalize = false,
            allowIncludeNumber = false,
            wordSeparator = "-",
            numWords = 3,
        )

        coEvery { authDiskSource.userState } returns USER_STATE

        coEvery {
            generatorDiskSource.storePasscodeGenerationOptions(userId, optionsToSave)
        } just runs

        repository.savePasscodeGenerationOptions(optionsToSave)

        coVerify { generatorDiskSource.storePasscodeGenerationOptions(userId, optionsToSave) }
    }

    @Test
    fun `storePasswordHistory should call encrypt and insert functions`() = runTest {
        val testUserId = "testUserId"
        val passwordHistoryView = PasswordHistoryView(
            password = "decryptedPassword",
            lastUsedDate = Instant.parse("2021-01-01T00:00:00Z"),
        )
        val encryptedPasswordHistory = PasswordHistory(
            password = "encryptedPassword",
            lastUsedDate = Instant.parse("2021-01-01T00:00:00Z"),
        )
        val expectedPasswordHistoryEntity = encryptedPasswordHistory
            .toPasswordHistoryEntity(testUserId)

        coEvery { authDiskSource.userState?.activeUserId } returns testUserId

        coEvery {
            vaultSdkSource.encryptPasswordHistory(
                userId = testUserId,
                passwordHistory = passwordHistoryView,
            )
        } returns encryptedPasswordHistory.asSuccess()

        coEvery {
            passwordHistoryDiskSource.insertPasswordHistory(expectedPasswordHistoryEntity)
        } just runs

        repository.storePasswordHistory(passwordHistoryView)

        coVerify {
            vaultSdkSource.encryptPasswordHistory(
                userId = testUserId,
                passwordHistory = passwordHistoryView,
            )
            passwordHistoryDiskSource.insertPasswordHistory(expectedPasswordHistoryEntity)
        }
    }

    @Test
    fun `passwordHistoryStateFlow should emit correct states based on password history updates`() =
        runTest {
            val encryptedPasswordHistoryEntities = listOf(
                PasswordHistoryEntity(
                    userId = USER_STATE.activeUserId,
                    encryptedPassword = "encryptedPassword2",
                    generatedDateTimeMs = Instant.parse("2021-01-02T00:00:00Z").toEpochMilli(),
                ),
                PasswordHistoryEntity(
                    userId = USER_STATE.activeUserId,
                    encryptedPassword = "encryptedPassword1",
                    generatedDateTimeMs = Instant.parse("2021-01-01T00:00:00Z").toEpochMilli(),
                ),
            )

            val decryptedPasswordHistoryList = listOf(
                PasswordHistoryView(
                    password = "password2",
                    lastUsedDate = Instant.parse("2021-01-02T00:00:00Z"),
                ),
                PasswordHistoryView(
                    password = "password1",
                    lastUsedDate = Instant.parse("2021-01-01T00:00:00Z"),
                ),
            )

            coEvery {
                passwordHistoryDiskSource.getPasswordHistoriesForUser(USER_STATE.activeUserId)
            } returns flowOf(encryptedPasswordHistoryEntities)

            coEvery {
                vaultSdkSource.decryptPasswordHistoryList(any(), any())
            } returns decryptedPasswordHistoryList.asSuccess()

            val historyFlow = repository.passwordHistoryStateFlow

            historyFlow.test {
                assertEquals(LocalDataState.Loading, awaitItem())
                mutableUserStateFlow.value = USER_STATE
                assertEquals(LocalDataState.Loaded(decryptedPasswordHistoryList), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                passwordHistoryDiskSource.getPasswordHistoriesForUser(USER_STATE.activeUserId)
            }

            coVerify { vaultSdkSource.decryptPasswordHistoryList(any(), any()) }
        }

    @Test
    fun `clearPasswordHistory should call clearAllPasswords function`() = runTest {
        val testUserId = "testUserId"
        coEvery { authDiskSource.userState?.activeUserId } returns testUserId
        coEvery { passwordHistoryDiskSource.clearPasswordHistories(testUserId) } just runs

        repository.clearPasswordHistory()

        coVerify { passwordHistoryDiskSource.clearPasswordHistories(testUserId) }
    }

    @Test
    fun `savePasscodeGenerationOptions should not store options when there is no active user`() =
        runTest {
            val optionsToSave = PasscodeGenerationOptions(
                length = 14,
                allowAmbiguousChar = false,
                hasNumbers = true,
                minNumber = 0,
                hasUppercase = true,
                minUppercase = null,
                hasLowercase = false,
                minLowercase = null,
                allowSpecial = false,
                minSpecial = 1,
                allowCapitalize = false,
                allowIncludeNumber = false,
                wordSeparator = "-",
                numWords = 3,
            )

            coEvery { authDiskSource.userState } returns null

            repository.savePasscodeGenerationOptions(optionsToSave)

            coVerify(exactly = 0) {
                generatorDiskSource.storePasscodeGenerationOptions(any(), any())
            }
        }

    @Test
    fun `getUsernameGenerationOptions should return options when available`() = runTest {
        val userId = "activeUserId"
        val expectedOptions = UsernameGenerationOptions(
            type = UsernameGenerationOptions.UsernameType.RANDOM_WORD,
            serviceType = UsernameGenerationOptions.ForwardedEmailServiceType.NONE,
            capitalizeRandomWordUsername = true,
            includeNumberRandomWordUsername = false,
            plusAddressedEmail = "example+plus@gmail.com",
            catchAllEmailDomain = "example.com",
            firefoxRelayApiAccessToken = "access_token_firefox_relay",
            simpleLoginApiKey = "api_key_simple_login",
            duckDuckGoApiKey = "api_key_duck_duck_go",
            fastMailApiKey = "api_key_fast_mail",
            anonAddyApiAccessToken = "access_token_anon_addy",
            anonAddyDomainName = "anonaddy.com",
            forwardEmailApiAccessToken = "access_token_forward_email",
            forwardEmailDomainName = "forwardemail.net",
            emailWebsite = "email.example.com",
        )

        coEvery { authDiskSource.userState } returns USER_STATE
        coEvery { generatorDiskSource.getUsernameGenerationOptions(userId) } returns expectedOptions

        val result = repository.getUsernameGenerationOptions()

        assertEquals(expectedOptions, result)
        coVerify { generatorDiskSource.getUsernameGenerationOptions(userId) }
    }

    @Test
    fun `getUsernameGenerationOptions should return null when there is no active user`() = runTest {
        coEvery { authDiskSource.userState } returns null

        val result = repository.getUsernameGenerationOptions()

        assertNull(result)
        coVerify(exactly = 0) { generatorDiskSource.getUsernameGenerationOptions(any()) }
    }

    @Test
    fun `getUsernameGenerationOptions should return null when no data is stored for active user`() =
        runTest {
            val userId = "activeUserId"
            coEvery { authDiskSource.userState } returns USER_STATE
            coEvery { generatorDiskSource.getUsernameGenerationOptions(userId) } returns null

            val result = repository.getUsernameGenerationOptions()

            assertNull(result)
            coVerify { generatorDiskSource.getUsernameGenerationOptions(userId) }
        }

    @Test
    fun `saveUsernameGenerationOptions should store options correctly`() = runTest {
        val userId = "activeUserId"
        val optionsToSave = UsernameGenerationOptions(
            type = UsernameGenerationOptions.UsernameType.RANDOM_WORD,
            serviceType = UsernameGenerationOptions.ForwardedEmailServiceType.NONE,
            capitalizeRandomWordUsername = true,
            includeNumberRandomWordUsername = false,
            plusAddressedEmail = "example+plus@gmail.com",
            catchAllEmailDomain = "example.com",
            firefoxRelayApiAccessToken = "access_token_firefox_relay",
            simpleLoginApiKey = "api_key_simple_login",
            duckDuckGoApiKey = "api_key_duck_duck_go",
            fastMailApiKey = "api_key_fast_mail",
            anonAddyApiAccessToken = "access_token_anon_addy",
            anonAddyDomainName = "anonaddy.com",
            forwardEmailApiAccessToken = "access_token_forward_email",
            forwardEmailDomainName = "forwardemail.net",
            emailWebsite = "email.example.com",
        )

        coEvery { authDiskSource.userState } returns USER_STATE
        coEvery {
            generatorDiskSource.storeUsernameGenerationOptions(userId, optionsToSave)
        } just runs

        repository.saveUsernameGenerationOptions(optionsToSave)

        coVerify { generatorDiskSource.storeUsernameGenerationOptions(userId, optionsToSave) }
    }

    @Test
    fun `saveUsernameGenerationOptions should not store options when there is no active user`() =
        runTest {
            val optionsToSave = UsernameGenerationOptions(
                type = UsernameGenerationOptions.UsernameType.RANDOM_WORD,
                serviceType = UsernameGenerationOptions.ForwardedEmailServiceType.NONE,
                capitalizeRandomWordUsername = true,
                includeNumberRandomWordUsername = false,
                plusAddressedEmail = "example+plus@gmail.com",
                catchAllEmailDomain = "example.com",
                firefoxRelayApiAccessToken = "access_token_firefox_relay",
                simpleLoginApiKey = "api_key_simple_login",
                duckDuckGoApiKey = "api_key_duck_duck_go",
                fastMailApiKey = "api_key_fast_mail",
                anonAddyApiAccessToken = "access_token_anon_addy",
                anonAddyDomainName = "anonaddy.com",
                forwardEmailApiAccessToken = "access_token_forward_email",
                forwardEmailDomainName = "forwardemail.net",
                emailWebsite = "email.example.com",
            )
            coEvery { authDiskSource.userState } returns null

            repository.saveUsernameGenerationOptions(optionsToSave)

            coVerify(exactly = 0) {
                generatorDiskSource.storeUsernameGenerationOptions(any(), any())
            }
        }

    @Test
    fun `getPasswordGeneratorPolicy returns default settings when no policies are present`() =
        runTest {
            every {
                policyManager.getActivePolicies(type = PolicyTypeJson.PASSWORD_GENERATOR)
            } returns emptyList()

            val policy = repository.getPasswordGeneratorPolicy()

            val expectedPolicy = PolicyInformation.PasswordGenerator(
                defaultType = "password",
                minLength = null,
                useUpper = false,
                useLower = false,
                useNumbers = false,
                useSpecial = false,
                minNumbers = null,
                minSpecial = null,
                minNumberWords = null,
                capitalize = false,
                includeNumber = false,
            )

            assertNotNull(policy)
            assertEquals(expectedPolicy, policy)
        }

    @Test
    fun `getPasswordGeneratorPolicy applies strictest settings from multiple policies`() = runTest {
        val policy1 = PolicyInformation.PasswordGenerator(
            defaultType = "password",
            minLength = 8,
            useUpper = true,
            useLower = true,
            useNumbers = true,
            useSpecial = false,
            minNumbers = 1,
            minSpecial = 1,
            minNumberWords = 3,
            capitalize = true,
            includeNumber = true,
        )
        val policy2 = PolicyInformation.PasswordGenerator(
            defaultType = "passphrase", // Different type, more specific in this context
            minLength = 12, // More strict
            useUpper = true,
            useLower = true,
            useNumbers = true,
            useSpecial = true, // More strict
            minNumbers = 2, // More strict
            minSpecial = 2, // More strict
            minNumberWords = 4, // More strict
            capitalize = false, // Different, less strict, should not override
            includeNumber = false, // Different, less strict, should not override
        )
        val policies = listOf(
            SyncResponseJson.Policy(
                id = "1",
                type = PolicyTypeJson.PASSWORD_GENERATOR,
                isEnabled = true,
                data = Json.encodeToJsonElement(policy1).jsonObject,
                organizationId = "id1",
            ),
            SyncResponseJson.Policy(
                id = "2",
                type = PolicyTypeJson.PASSWORD_GENERATOR,
                isEnabled = true,
                data = Json.encodeToJsonElement(policy2).jsonObject,
                organizationId = "id2",
            ),
        )
        every {
            policyManager.getActivePolicies(type = PolicyTypeJson.PASSWORD_GENERATOR)
        } returns policies

        val resultPolicy = repository.getPasswordGeneratorPolicy()

        // The expected combined policy
        val expectedPolicy = PolicyInformation.PasswordGenerator(
            defaultType = "passphrase",
            minLength = 12,
            useUpper = true,
            useLower = true,
            useNumbers = true,
            useSpecial = true,
            minNumbers = 2,
            minSpecial = 2,
            minNumberWords = 4,
            capitalize = true,
            includeNumber = true,
        )

        assertEquals(expectedPolicy, resultPolicy)
    }
}

private val USER_STATE = UserStateJson(
    activeUserId = "activeUserId",
    accounts = mapOf(
        "activeUserId" to AccountJson(
            profile = AccountJson.Profile(
                userId = "activeUserId",
                email = "email",
                isEmailVerified = true,
                name = "name",
                stamp = "stamp",
                organizationId = "organizationId",
                avatarColorHex = "avatarColorHex",
                hasPremium = true,
                forcePasswordResetReason = ForcePasswordResetReason.ADMIN_FORCE_PASSWORD_RESET,
                kdfType = KdfTypeJson.ARGON2_ID,
                kdfIterations = 600000,
                kdfMemory = 16,
                kdfParallelism = 4,
                userDecryptionOptions = UserDecryptionOptionsJson(
                    hasMasterPassword = true,
                    trustedDeviceUserDecryptionOptions = TrustedDeviceUserDecryptionOptionsJson(
                        encryptedPrivateKey = "encryptedPrivateKey",
                        encryptedUserKey = "encryptedUserKey",
                        hasAdminApproval = true,
                        hasLoginApprovingDevice = true,
                        hasManageResetPasswordPermission = true,
                    ),
                    keyConnectorUserDecryptionOptions = KeyConnectorUserDecryptionOptionsJson(
                        keyConnectorUrl = "keyConnectorUrl",
                    ),
                ),
            ),
            tokens = AccountJson.Tokens(
                accessToken = "accessToken",
                refreshToken = "refreshToken",
            ),
            settings = AccountJson.Settings(
                environmentUrlData = EnvironmentUrlDataJson(
                    base = "base",
                    api = "api",
                    identity = "identity",
                    icon = "icon",
                    notifications = "notifications",
                    webVault = "webVault",
                    events = "events",
                ),
            ),
        ),
    ),
)
