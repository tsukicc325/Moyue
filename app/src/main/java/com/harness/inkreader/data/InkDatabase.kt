package com.harness.inkreader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ProgressEntity::class,
        BookmarkEntity::class,
        ReadingSessionEntity::class,
        AnnotationEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class InkDatabase : RoomDatabase() {

    abstract fun books(): BookDao
    abstract fun chapters(): ChapterDao
    abstract fun progress(): ProgressDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun annotations(): AnnotationDao
    abstract fun sessions(): ReadingSessionDao

    companion object {
        const val NAME = "inkreader.db"

        /** v1 → v2 的唯一一句 SQL。抽成常量，测试可以直接跑它验证旧数据不受影响。 */
        const val SQL_ADD_COVER = "ALTER TABLE books ADD COLUMN coverPath TEXT"

        /**
         * v1 → v2：给书籍加自定义封面路径。
         *
         * 迁移只有一句 ALTER TABLE，但**必须真的写迁移**：用户库里存着几千章的索引和阅读
         * 进度，一旦用破坏性迁移（fallbackToDestructiveMigration）就会全部清空。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(SQL_ADD_COVER)
            }
        }

        /** v2 → v3：记录来源格式与原始文件位置（EPUB 支持）。 */
        const val SQL_ADD_FORMAT = "ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'TXT'"
        const val SQL_ADD_SOURCE_PATH = "ALTER TABLE books ADD COLUMN sourcePath TEXT"

        /**
         * v2 → v3：给书籍加 format / sourcePath。
         *
         * 和 v1→v2 一样必须**真迁移**：老库里的书全部是 TXT，
         * 所以 format 的默认值给 'TXT'，一本书都不会因为升级而丢失。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(SQL_ADD_FORMAT)
                db.execSQL(SQL_ADD_SOURCE_PATH)
            }
        }

        @Volatile
        private var instance: InkDatabase? = null

        fun get(context: Context): InkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    InkDatabase::class.java,
                    NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }

        /** 测试用：内存库。 */
        fun inMemory(context: Context): InkDatabase =
            Room.inMemoryDatabaseBuilder(context, InkDatabase::class.java)
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
