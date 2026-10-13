package app.universal3d.inspector

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Pruebas de contrato sobre el script que se inyecta en las páginas (sin necesidad de Android). */
class PageScriptContractTest {
    private val js: String = listOf("src/main/assets/u3d_page.js", "app/src/main/assets/u3d_page.js").map(::File).first { it.exists() }.readText()

    @Test fun placeholdersAreReplacedByTheHostOnly() {
        assertTrue(js.contains("__U3D_CAPTURE__")); assertTrue(js.contains("__U3D_MAXBYTES__"))
        assertEquals(1, Regex("__U3D_CAPTURE__").findAll(js).count())
    }

    @Test fun scriptDoesNotTouchSensitiveBrowserState() {
        for (bad in listOf("document.cookie", "localStorage", "sessionStorage", "indexedDB", "type=\"password\"", "input[type", "eval(", "new Function(", "XMLHttpRequest", "fetch(", "navigator.sendBeacon", "WebSocket")) {
            assertFalse("el script no debe usar $bad", js.contains(bad))
        }
    }

    @Test fun scriptOnlyTalksToTheNativeBridge() {
        assertTrue(js.contains("U3DBridge.postMessage"))
        // sólo acepta el mensaje "capture:<id>" desde el nativo
        assertTrue(js.contains("indexOf('capture:') === 0"))
    }

    @Test fun everyHookCallsTheOriginalFunction() {
        assertTrue("wrap() debe invocar la función original", js.contains("orig.apply(this, arguments)"))
        assertTrue(js.contains("origGet.apply(this, arguments)"))
    }

    @Test fun postsAreRateLimited() {
        assertTrue(js.contains("MAXPOSTS = 30")); assertTrue(js.contains("U.posts >= MAXPOSTS"))
    }
}
