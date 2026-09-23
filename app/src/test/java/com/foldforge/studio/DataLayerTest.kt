package com.foldforge.studio

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foldforge.studio.core.web.PreviewHost
import com.foldforge.studio.data.database.BuildHistoryEntity
import com.foldforge.studio.data.database.FoldForgeDatabase
import com.foldforge.studio.data.database.ProjectEntity
import com.foldforge.studio.data.database.SessionEntity
import com.foldforge.studio.data.settings.AppSettings
import com.foldforge.studio.data.settings.BatteryMode
import com.foldforge.studio.feature.palette.fuzzy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DataLayerTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<FoldForgeApp>()

    @Test fun room_projects_sessions_builds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, FoldForgeDatabase::class.java).allowMainThreadQueries().build()
        db.projects().upsert(ProjectEntity("a", "A", "WEB", "/p/a", 1, 5))
        db.projects().upsert(ProjectEntity("b", "B", "WEB", "/p/b", 1, 9))
        assertEquals(listOf("b", "a"), db.projects().observeAll().first().map { it.id })
        db.sessions().save(SessionEntity(1, "a", "index.html\nsrc/main.js", "src/main.js", "{}", "A", 1, cleanExit = false))
        db.sessions().markClean(true)
        assertTrue(db.sessions().get()!!.cleanExit)
        db.builds().insert(BuildHistoryEntity(projectId = "a", kind = "apk", status = "success", outputPath = "/x.apk", sizeBytes = 10, sha256 = "ab", createdAt = 1, message = ""))
        assertEquals(1, db.builds().observe("a").first().size)
        db.close()
    }

    @Test fun settings_persist_and_derive() = runBlocking {
        val repo = ctx.container.settings
        repo.update { it.copy(editorFontSize = 99, batteryMode = BatteryMode.SAVER, previewFpsLimit = 0) }
        val s = repo.current()
        assertEquals(24, s.editorFontSize) // clamped
        assertEquals(30, s.effectiveFpsLimit)
        assertEquals(1000L, s.effectiveQaPauseMs)
        assertEquals("claude-opus-5", AppSettings().aiModel)
    }

    @Test fun secure_store_refuses_plaintext_without_keystore() {
        // Robolectric has no AndroidKeyStore: secrets must not be stored in plain text.
        assertFalse(ctx.container.keystoreAvailable)
        assertNull(ctx.container.secureStore.get("ai_api_key"))
        val failed = runCatching { ctx.container.secureStore.put("ai_api_key", "sk-test") }.isFailure
        assertTrue(failed)
    }

    @Test fun preview_helpers() {
        assertEquals("text/javascript", PreviewHost.mimeFor("src/main.js"))
        assertEquals("model/gltf-binary", PreviewHost.mimeFor("a.glb"))
        assertEquals("run1", PreviewHost.decodeJsResult("\"run1\""))
        assertNull(PreviewHost.decodeJsResult("null"))
        assertEquals("src/game.js", PreviewHost.previewPath("https://appassets.androidplatform.net/project/src/game.js?x=1"))
        assertTrue(fuzzy("src/main.js", "mjs"))
        assertFalse(fuzzy("index.html", "zz"))
    }
}
