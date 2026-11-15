package com.auramusic.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Registro centralizado de migraciones no destructivas de Room para Aura Music.
 *
 * Regla del proyecto: antes de ampliar tablas o columnas (por ejemplo Waveform, ReplayGain,
 * letras .lrc o cola persistente), se debe:
 * 1. Incrementar [CURRENT_DATABASE_VERSION].
 * 2. Definir la migración explícita (`MIGRATION_1_2`, `MIGRATION_2_3`, etc.) usando
 *    [safeAddColumnIfMissing] o sentencias SQL idempotentes.
 * 3. Registrarla dentro de [ALL_MIGRATIONS] sin usar nunca `fallbackToDestructiveMigration`,
 *    protegiendo así las canciones, "Me gusta" y playlists del usuario al actualizar el APK.
 */
object AuraMigrations {
    const val CURRENT_DATABASE_VERSION: Int = 1

    /**
     * Lista ordenada de migraciones registradas en [AuraDatabase].
     * En la versión inicial 1 permanece vacía; cada nueva versión añade aquí su `Migration(n, n+1)`.
     */
    val ALL_MIGRATIONS: Array<Migration> = emptyArray()

    /**
     * Añade una columna de forma segura e idempotente verificando primero `PRAGMA table_info`.
     * Evita errores si una migración parcial ya hubiera creado la columna previamente.
     */
    fun safeAddColumnIfMissing(
        db: SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
        columnDefinition: String,
    ) {
        val existingColumns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) {
                    existingColumns.add(cursor.getString(nameIndex))
                }
            }
        }
        if (columnName !in existingColumns) {
            db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition")
        }
    }

    /**
     * Verifica que exista una cadena continua de migraciones desde la versión 1 hasta [targetVersion].
     * Útil para detectar olvidos en pruebas unitarias antes de publicar un APK nuevo.
     */
    fun validateMigrationChain(
        targetVersion: Int = CURRENT_DATABASE_VERSION,
        migrations: Array<Migration> = ALL_MIGRATIONS,
    ): Boolean {
        if (targetVersion < 1) return false
        if (targetVersion == 1) return migrations.isEmpty()

        val sorted = migrations.sortedBy { it.startVersion }
        var current = 1
        while (current < targetVersion) {
            val next = sorted.firstOrNull { it.startVersion == current && it.endVersion > current }
                ?: return false
            current = next.endVersion
        }
        return current == targetVersion
    }
}
