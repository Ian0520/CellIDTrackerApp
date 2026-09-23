package com.example.cellidtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class GoogleGeolocationClientTest {
    @Test
    fun radioTypeIsRequestLevelAndIpFallbackIsDisabled() {
        val payload = buildGeolocationPayload(
            listOf(CellTowerParams(mcc = 1, mnc = 1, lac = 1, cid = 12345678))
        )

        assertEquals("lte", payload.getString("radioType"))
        assertFalse(payload.getBoolean("considerIp"))
        val tower = payload.getJSONArray("cellTowers").getJSONObject(0)
        assertEquals(12345678, tower.getInt("cellId"))
        assertFalse(tower.has("radioType"))
    }

    @Test
    fun mixedRadioTypesCannotBeRepresentedInOneRequest() {
        assertThrows(IllegalArgumentException::class.java) {
            buildGeolocationPayload(
                listOf(
                    CellTowerParams(1, 1, 1, 12345678, "lte"),
                    CellTowerParams(1, 1, 1, 12345, "wcdma")
                )
            )
        }
    }
}
