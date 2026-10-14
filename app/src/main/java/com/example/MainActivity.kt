package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.core.model.JobStatus
import com.example.ui.screens.AudioScreen
import com.example.ui.screens.CompressScreen
import com.example.ui.screens.ConvertScreen
import com.example.ui.screens.EditScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.QueueScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplitScreen
import com.example.ui.screens.ThumbnailScreen
import com.example.ui.theme.UniversalConverterTheme
import com.example.ui.viewmodel.MediaViewModel

enum class AppScreen {
    HOME,
    COMPRESS,
    CONVERT,
    EDIT,
    AUDIO,
    SPLIT,
    THUMBNAIL,
    QUEUE,
    LIBRARY,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private val viewModel: MediaViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val isDarkThemePref by viewModel.isDarkTheme.collectAsState()
            val isPersian by viewModel.isPersianLanguage.collectAsState()
            val jobs by viewModel.jobs.collectAsState()
            val activeJobCount = jobs.count { it.status == JobStatus.PROCESSING || it.status == JobStatus.QUEUED }

            val useDarkTheme = isDarkThemePref ?: isSystemInDarkTheme()
            val layoutDirection = if (isPersian) LayoutDirection.Rtl else LayoutDirection.Ltr

            var currentScreen by remember { mutableStateOf(AppScreen.HOME) }

            // Handle system back navigation to return to Home
            BackHandler(enabled = currentScreen != AppScreen.HOME) {
                currentScreen = AppScreen.HOME
            }

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                UniversalConverterTheme(darkTheme = useDarkTheme) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF14171F)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Image(
                                                painter = painterResource(id = R.drawable.app_icon_fg_1791963700677),
                                                contentDescription = "Logo",
                                                modifier = Modifier.size(26.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = when (currentScreen) {
                                                AppScreen.HOME -> stringResource(id = R.string.app_name)
                                                AppScreen.COMPRESS -> stringResource(id = R.string.nav_compress)
                                                AppScreen.CONVERT -> stringResource(id = R.string.nav_convert)
                                                AppScreen.EDIT -> stringResource(id = R.string.nav_edit)
                                                AppScreen.AUDIO -> stringResource(id = R.string.nav_audio)
                                                AppScreen.SPLIT -> stringResource(id = R.string.nav_split)
                                                AppScreen.THUMBNAIL -> stringResource(id = R.string.nav_thumbnail)
                                                AppScreen.QUEUE -> stringResource(id = R.string.nav_queue)
                                                AppScreen.LIBRARY -> stringResource(id = R.string.nav_library)
                                                AppScreen.SETTINGS -> stringResource(id = R.string.nav_settings)
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                },
                                actions = {
                                    // Queue Action with Live Badge
                                    IconButton(
                                        onClick = { currentScreen = AppScreen.QUEUE },
                                        modifier = Modifier.testTag("top_bar_queue_button")
                                    ) {
                                        BadgedBox(
                                            badge = {
                                                if (activeJobCount > 0) {
                                                    Badge { Text("$activeJobCount") }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PendingActions,
                                                contentDescription = "Queue"
                                            )
                                        }
                                    }

                                    // Settings Action
                                    IconButton(
                                        onClick = { currentScreen = AppScreen.SETTINGS },
                                        modifier = Modifier.testTag("top_bar_settings_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings"
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        },
                        bottomBar = {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.testTag("bottom_navigation_bar")
                            ) {
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.HOME,
                                    onClick = { currentScreen = AppScreen.HOME },
                                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                    label = { Text(stringResource(id = R.string.nav_home)) },
                                    modifier = Modifier.testTag("nav_item_home")
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.COMPRESS,
                                    onClick = { currentScreen = AppScreen.COMPRESS },
                                    icon = { Icon(Icons.Default.Compress, contentDescription = "Compress") },
                                    label = { Text(stringResource(id = R.string.nav_compress)) },
                                    modifier = Modifier.testTag("nav_item_compress")
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.EDIT,
                                    onClick = { currentScreen = AppScreen.EDIT },
                                    icon = { Icon(Icons.Default.Edit, contentDescription = "Edit") },
                                    label = { Text(stringResource(id = R.string.nav_edit)) },
                                    modifier = Modifier.testTag("nav_item_edit")
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.SPLIT,
                                    onClick = { currentScreen = AppScreen.SPLIT },
                                    icon = { Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = "Split") },
                                    label = { Text(stringResource(id = R.string.nav_split)) },
                                    modifier = Modifier.testTag("nav_item_split")
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.THUMBNAIL,
                                    onClick = { currentScreen = AppScreen.THUMBNAIL },
                                    icon = { Icon(Icons.Default.Image, contentDescription = "Thumbnail") },
                                    label = { Text(stringResource(id = R.string.nav_thumbnail)) },
                                    modifier = Modifier.testTag("nav_item_thumbnail")
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.LIBRARY,
                                    onClick = { currentScreen = AppScreen.LIBRARY },
                                    icon = { Icon(Icons.Default.Folder, contentDescription = "Library") },
                                    label = { Text(stringResource(id = R.string.nav_library)) },
                                    modifier = Modifier.testTag("nav_item_library")
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (currentScreen) {
                                AppScreen.HOME -> HomeScreen(
                                    viewModel = viewModel,
                                    onNavigateToCompress = { currentScreen = AppScreen.COMPRESS },
                                    onNavigateToConvert = { currentScreen = AppScreen.CONVERT },
                                    onNavigateToEdit = { currentScreen = AppScreen.EDIT },
                                    onNavigateToAudio = { currentScreen = AppScreen.AUDIO },
                                    onNavigateToSplit = { currentScreen = AppScreen.SPLIT },
                                    onNavigateToThumbnail = { currentScreen = AppScreen.THUMBNAIL },
                                    onNavigateToQueue = { currentScreen = AppScreen.QUEUE },
                                    onNavigateToLibrary = { currentScreen = AppScreen.LIBRARY },
                                    onNavigateToSettings = { currentScreen = AppScreen.SETTINGS }
                                )
                                AppScreen.COMPRESS -> CompressScreen(
                                    viewModel = viewModel,
                                    onNavigateToQueue = { currentScreen = AppScreen.QUEUE }
                                )
                                AppScreen.CONVERT -> ConvertScreen(
                                    viewModel = viewModel,
                                    onNavigateToQueue = { currentScreen = AppScreen.QUEUE }
                                )
                                AppScreen.EDIT -> EditScreen(
                                    viewModel = viewModel,
                                    onNavigateToQueue = { currentScreen = AppScreen.QUEUE }
                                )
                                AppScreen.AUDIO -> AudioScreen(
                                    viewModel = viewModel,
                                    onNavigateToQueue = { currentScreen = AppScreen.QUEUE }
                                )
                                AppScreen.SPLIT -> SplitScreen(
                                    viewModel = viewModel,
                                    onNavigateToQueue = { currentScreen = AppScreen.QUEUE }
                                )
                                AppScreen.THUMBNAIL -> ThumbnailScreen(
                                    viewModel = viewModel,
                                    onNavigateToQueue = { currentScreen = AppScreen.QUEUE }
                                )
                                AppScreen.QUEUE -> QueueScreen(
                                    viewModel = viewModel
                                )
                                AppScreen.LIBRARY -> LibraryScreen(
                                    viewModel = viewModel
                                )
                                AppScreen.SETTINGS -> SettingsScreen(
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
