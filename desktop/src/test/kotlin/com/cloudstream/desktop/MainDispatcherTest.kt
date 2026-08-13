package com.cloudstream.desktop

import kotlin.test.Test
import kotlin.test.assertNull

class MainDispatcherTest {
    @Test
    fun `JavaFX main dispatcher is absent from the Compose runtime`() {
        val dispatcherFactory = javaClass.classLoader
            .getResource("kotlinx/coroutines/javafx/JavaFxDispatcherFactory.class")
        assertNull(
            dispatcherFactory,
            "kotlinx-coroutines-javafx binds Dispatchers.Main to the FX thread and breaks Compose's AWT lifecycle",
        )
    }
}
