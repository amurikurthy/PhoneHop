package app.phonehop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private fun newer(r: String, l: String) = Updater.versionIsNewer(r, l)

    @Test fun higherPatchMinorMajorAreNewer() {
        assertTrue(newer("0.2.1", "0.2.0"))
        assertTrue(newer("0.3.0", "0.2.9"))
        assertTrue(newer("1.0.0", "0.99.99"))
    }

    @Test fun equalOrOlderIsNotNewer() {
        assertFalse(newer("0.2.0", "0.2.0"))
        assertFalse(newer("0.1.9", "0.2.0"))
    }

    @Test fun comparesNumericallyNotLexically() {
        assertTrue(newer("0.10.0", "0.9.0"))
        assertFalse(newer("0.9.0", "0.10.0"))
    }

    @Test fun toleratesTagPrefixAndSuffix() {
        assertTrue(newer("v0.2.1", "0.2.0"))
        assertFalse(newer("v0.2.0-rc1", "0.2.0"))
        assertTrue(newer("1.2", "1.1.9"))
    }
}
