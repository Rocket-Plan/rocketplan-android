package com.example.rocketplan_android.ui.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.Company
import com.example.rocketplan_android.data.model.CurrentUserResponse
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class JoinCompanyViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val userId = 1L
    private lateinit var mockApplication: android.app.Application
    private lateinit var mockSecureStorage: SecureStorage
    private lateinit var encryptedPrefs: SharedPreferences
    private lateinit var encryptedEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        mockApplication = ApplicationProvider.getApplicationContext()
        mockSecureStorage = mockk(relaxed = true)

        encryptedPrefs = mockk(relaxed = true)
        encryptedEditor = mockk(relaxed = true)
        every { encryptedPrefs.edit() } returns encryptedEditor
        every { encryptedEditor.putString(any(), any()) } returns encryptedEditor
        every { encryptedEditor.remove(any()) } returns encryptedEditor
        every { encryptedEditor.clear() } returns encryptedEditor
        every { encryptedEditor.commit() } returns true

        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                any<Context>(), any(), any(), any(), any()
            )
        } returns encryptedPrefs

        mockkStatic("androidx.security.crypto.MasterKey\$Builder")

        mockkObject(SecureStorage)
        every { SecureStorage.getInstance(any()) } returns mockSecureStorage
    }

    @After
    fun tearDown() {
        unmockkStatic(EncryptedSharedPreferences::class)
        unmockkStatic("androidx.security.crypto.MasterKey\$Builder")
        unmockkObject(SecureStorage)
    }

    @Test
    fun `setCompanyCode updates the value`() {
        val vm = JoinCompanyViewModel(mockApplication, userId)

        vm.setCompanyCode("test-uuid")

        assertThat(vm.companyCode.value).isEqualTo("test-uuid")
    }

    @Test
    fun `setCompanyCode clears previous error`() = runTest {
        val vm = JoinCompanyViewModel(mockApplication, userId)
        vm.setCompanyCode("")

        vm.join()

        assertThat(vm.errorMessage.value).isNotNull()
        vm.setCompanyCode("new-code")

        assertThat(vm.errorMessage.value).isNull()
    }

    @Test
    fun `join with blank code shows error`() = runTest {
        val vm = JoinCompanyViewModel(mockApplication, userId)
        vm.setCompanyCode("   ")

        vm.join()

        assertThat(vm.errorMessage.value).isEqualTo("Company code is required")
    }

    @Test
    fun `join with non-uuid string falls back to using it as uuid`() = runTest {
        val mockRepo = mockk<AuthRepository>()
        coEvery { mockRepo.resolveCompanyByUuid("some-string") } returns Result.success(
            Company(id = 10, name = "Test Co")
        )
        coEvery { mockRepo.addCompanyUser(10, userId) } returns Result.success(Unit)
        coEvery { mockRepo.setActiveCompany(10) } returns Result.success(Unit)
        coEvery { mockRepo.refreshUserContext() } returns Result.success(
            CurrentUserResponse(
                id = userId,
                email = "test@test.com",
                firstName = null,
                lastName = null,
                companyId = 10,
                company = null,
                companies = null
            )
        )

        val vm = object : JoinCompanyViewModel(mockApplication, userId) {
            override val authRepository = mockRepo
        }

        vm.setCompanyCode("some-string")
        vm.join()

        assertThat(vm.joinComplete.value).isTrue()
    }

    @Test
    fun `join with valid invite link parses company uuid`() = runTest {
        val mockRepo = mockk<AuthRepository>()
        coEvery { mockRepo.resolveCompanyByUuid("company-uuid-123") } returns Result.success(
            Company(id = 10, name = "Test Co")
        )
        coEvery { mockRepo.addCompanyUser(10, userId) } returns Result.success(Unit)
        coEvery { mockRepo.setActiveCompany(10) } returns Result.success(Unit)
        coEvery { mockRepo.refreshUserContext() } returns Result.success(
            CurrentUserResponse(
                id = userId,
                email = "test@test.com",
                firstName = null,
                lastName = null,
                companyId = 10,
                company = null,
                companies = null
            )
        )

        val vm = object : JoinCompanyViewModel(mockApplication, userId) {
            override val authRepository = mockRepo
        }

        vm.setCompanyCode("https://app.rocketplan.com/invite-redirect/company-uuid-123")
        vm.join()

        assertThat(vm.joinComplete.value).isTrue()
    }

    @Test
    fun `join failure on resolveCompanyByUuid shows error`() = runTest {
        val mockRepo = mockk<AuthRepository>()
        coEvery { mockRepo.resolveCompanyByUuid("invalid-uuid") } returns Result.failure(Exception("Not found"))

        val vm = object : JoinCompanyViewModel(mockApplication, userId) {
            override val authRepository = mockRepo
        }

        vm.setCompanyCode("invalid-uuid")
        vm.join()

        assertThat(vm.errorMessage.value).isNotNull()
        assertThat(vm.isLoading.value).isFalse()
    }

    @Test
    fun `join failure on addCompanyUser shows error`() = runTest {
        val mockRepo = mockk<AuthRepository>()
        coEvery { mockRepo.resolveCompanyByUuid("valid-uuid") } returns Result.success(
            Company(id = 10, name = "Test Co")
        )
        coEvery { mockRepo.addCompanyUser(10, userId) } returns Result.failure(Exception("Add failed"))

        val vm = object : JoinCompanyViewModel(mockApplication, userId) {
            override val authRepository = mockRepo
        }

        vm.setCompanyCode("valid-uuid")
        vm.join()

        assertThat(vm.errorMessage.value).isNotNull()
        assertThat(vm.isLoading.value).isFalse()
    }

    @Test
    fun `onJoinCompleteHandled resets joinComplete flag`() = runTest {
        val mockRepo = mockk<AuthRepository>()
        coEvery { mockRepo.resolveCompanyByUuid("uuid") } returns Result.success(
            Company(id = 10, name = "Test Co")
        )
        coEvery { mockRepo.addCompanyUser(10, userId) } returns Result.success(Unit)
        coEvery { mockRepo.setActiveCompany(10) } returns Result.success(Unit)
        coEvery { mockRepo.refreshUserContext() } returns Result.success(
            CurrentUserResponse(
                id = userId,
                email = "test@test.com",
                firstName = null,
                lastName = null,
                companyId = 10,
                company = null,
                companies = null
            )
        )

        val vm = object : JoinCompanyViewModel(mockApplication, userId) {
            override val authRepository = mockRepo
        }

        vm.setCompanyCode("uuid")
        vm.join()
        assertThat(vm.joinComplete.value).isTrue()

        vm.onJoinCompleteHandled()
        assertThat(vm.joinComplete.value).isFalse()
    }
}