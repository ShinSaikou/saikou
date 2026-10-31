package com.shinsaikou.signin

import com.shinsaikou.sync.drive.DriveAvailability
import com.shinsaikou.sync.google.GoogleClientCredentials
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Whether a build offers Google Drive (phase 3 unit H, item 6), through the endpoints each build
 * type's binding gives, on `GoogleBindingsTest`'s pattern: `:app` has unit tests for the debug
 * variant only, so the release build is `GoogleBindings.endpointsFor(debug = false, ...)`, the call
 * its provider makes.
 */
class DriveAvailabilityBuildTest {

    private val noClient = GoogleClientCredentials("", "")
    private val ownersClient = GoogleClientCredentials("owner-client.apps.example.invalid", "owner-secret")

    @Test
    fun `a release build offers Drive only with a client id, whatever a test base says`() {
        val release = GoogleBindings.endpointsFor(debug = false) { "http://10.0.2.2:18797" }

        DriveAvailability.offered(release, noClient) shouldBe false
        DriveAvailability.offered(release, GoogleClientCredentials("  ", "owner-secret")) shouldBe false
        DriveAvailability.offered(release, ownersClient) shouldBe true
        // The client id alone decides it; a missing secret is Google's refusal to report, at sign-in.
        DriveAvailability.offered(release, GoogleClientCredentials("owner-client.apps.example.invalid", "")) shouldBe true
    }

    @Test
    fun `a debug build also offers Drive while a test base is set, and without one reads the client id`() {
        DriveAvailability.offered(GoogleBindings.endpointsFor(debug = true) { "http://10.0.2.2:18797" }, noClient) shouldBe true
        DriveAvailability.offered(GoogleBindings.endpointsFor(debug = true) { null }, noClient) shouldBe false
        DriveAvailability.offered(GoogleBindings.endpointsFor(debug = true) { " " }, noClient) shouldBe false
        DriveAvailability.offered(GoogleBindings.endpointsFor(debug = true) { null }, ownersClient) shouldBe true
    }

    @Test
    fun `the graph's rule reads the endpoints again on each call`() {
        val testBase = GoogleTestBase()
        val availability = DriveAvailability({ GoogleBindings.provideGoogleEndpoints(lazyOf(testBase)) }, noClient)

        availability.offered shouldBe false
        testBase.value = "http://10.0.2.2:18797"
        availability.offered shouldBe true
        testBase.value = null
        availability.offered shouldBe false
    }
}
