package dev.tandem.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseTagTest {
    @Test fun aPlainTagGivesTheReleaseWorkflowsCode() {
        assertEquals(1_002_003, ReleaseTag.versionCode("v1.2.3"))
        assertEquals(3_000, ReleaseTag.versionCode("v0.3.0"))
        assertEquals(999_999, ReleaseTag.versionCode("v0.999.999"))
        assertEquals(10_000_000, ReleaseTag.versionCode("v10.0.0"))
    }

    @Test fun aSuffixIsIgnoredForTheCodeAsTheWorkflowDoes() {
        assertEquals(1_002_003, ReleaseTag.versionCode("v1.2.3-rc1"))
        assertEquals(1_002_003, ReleaseTag.versionCode("v1.2.3+build.7"))
        assertEquals("1.2.3-rc1", ReleaseTag.versionName("v1.2.3-rc1"))
        assertEquals("0.3.0", ReleaseTag.versionName("v0.3.0"))
    }

    @Test fun garbageTagsHaveNoCode() {
        for (t in listOf(
            "", "v", "1.2.3", "v1.2", "v1", "v1.2.3.4", "va.b.c", "v1.x.3", "v-1.2.3", "v1..3", " v1.2.3", "v1.2.3 ",
            "v1.1000.0", "v1.0.1000", "v99999999999.0.0", "latest", "v1.2.3-", "v١.٢.٣",
        )) {
            assertNull(t, ReleaseTag.versionCode(t))
            assertNull(t, ReleaseTag.versionName(t))
        }
    }

    @Test fun aCodeMustFitAnInt() {
        assertEquals(2_146_999_999, ReleaseTag.versionCode("v2146.999.999"))
        assertEquals(2_147_483_647, ReleaseTag.versionCode("v2147.483.647"))
        assertNull(ReleaseTag.versionCode("v2147.483.648"))
        assertNull(ReleaseTag.versionCode("v2148.0.0"))
    }
}
