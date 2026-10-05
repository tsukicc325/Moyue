package com.harness.inkreader.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.harness.inkreader.ui.bookshelf.BookshelfScreen
import com.harness.inkreader.ui.reader.ReaderScreen
import com.harness.inkreader.ui.settings.SettingsScreen
import com.harness.inkreader.ui.stats.StatsScreen

object Routes {
    const val SHELF = "shelf"
    const val READER = "reader/{bookId}"
    const val SETTINGS = "settings"
    const val STATS = "stats"

    fun reader(bookId: Long) = "reader/$bookId"
}

@Composable
fun InkNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.SHELF) {
        composable(Routes.SHELF) {
            BookshelfScreen(
                onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenStats = { navController.navigate(Routes.STATS) },
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) { entry ->
            ReaderScreen(
                bookId = entry.arguments?.getLong("bookId") ?: 0L,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenStats = { navController.navigate(Routes.STATS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.STATS) {
            StatsScreen(onBack = { navController.popBackStack() })
        }
    }
}
