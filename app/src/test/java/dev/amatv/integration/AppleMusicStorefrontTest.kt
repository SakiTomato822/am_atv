package dev.amatv.integration

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicStorefrontTest {
    @Test
    fun readsAuthenticatedChinaStorefront() {
        val response = JSONObject(
            """{
              "data": [{
                "id": "CN",
                "type": "storefronts",
                "attributes": {"defaultLanguageTag": "zh-Hans-CN"}
              }]
            }""",
        )

        assertEquals("cn", parseUserStorefrontId(response))
    }

    @Test
    fun rejectsMissingOrMalformedStorefronts() {
        assertNull(parseUserStorefrontId(JSONObject("""{"data": []}""")))
        assertNull(parseUserStorefrontId(JSONObject("""{"data": [{"id": "china"}]}""")))
    }

    @Test
    fun effectiveStorefrontFallsBackToDefaultWhenUserTokenNull() = kotlinx.coroutines.runBlocking {
        val api = AppleMusicApi(
            storefront = "tw",
            developerTokens = DeveloperTokenClient(""),
            userToken = { null },
        )
        assertEquals("tw", api.effectiveStorefront())
    }
}
