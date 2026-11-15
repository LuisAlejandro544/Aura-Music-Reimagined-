package com.auramusic.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pruebas unitarias de la cadena de migraciones y esquemas versionados de Room para Aura Music.
 */
class AuraMigrationsTest {

    @Test
    fun currentDatabaseVersionHasValidMigrationChain() {
        assertTrue(AuraMigrations.validateMigrationChain())
    }

    @Test
    fun validatesContiguousMigrationChainAndRejectsGaps() {
        val m1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }
        val m2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        assertTrue(AuraMigrations.validateMigrationChain(targetVersion = 3, migrations = arrayOf(m1To2, m2To3)))
        assertFalse(AuraMigrations.validateMigrationChain(targetVersion = 3, migrations = arrayOf(m1To2)))
    }

    @Test
    fun currentDatabaseVersionSchemaFileIsVersionedAndValid() {
        val relativeSchema = "schemas/com.auramusic.core.database.AuraDatabase/${AuraMigrations.CURRENT_DATABASE_VERSION}.json"
        val candidates = listOf(
            File(relativeSchema),
            File("android/core/database/$relativeSchema"),
        )
        val schemaFile = candidates.firstOrNull { it.exists() }
        assertTrue("Debe existir el esquema Room versionado en $relativeSchema", schemaFile != null)

        val root = JSONObject(schemaFile!!.readText())
        val database = root.getJSONObject("database")
        assertEquals(AuraMigrations.CURRENT_DATABASE_VERSION, database.getInt("version"))

        val entities = database.getJSONArray("entities")
        val tableNames = (0 until entities.length()).map { idx ->
            entities.getJSONObject(idx).getString("tableName")
        }.toSet()
        assertTrue(tableNames.containsAll(setOf("tracks", "playlists", "playlist_tracks")))
    }
}
