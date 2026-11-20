// SuggestionRegionSheet.kt

package com.mochyy.mochymusic.ui.screens.search.suggestions

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.SuggestionRegionSlugToName
import com.mochyy.mochymusic.ui.component.RemoteFocus
import kotlinx.coroutines.launch

@Composable
fun SuggestionRegionSheet(
    currentRegionSlug: String,
    onRegionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val menuState = com.mochyy.mochymusic.ui.component.LocalMenuState.current
    val currentOnSelected by rememberUpdatedState(onRegionSelected)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(menuState) {
        var closed = false
        var sheet: com.mochyy.mochymusic.ui.component.PopupSheetHandle? = null
        sheet = menuState.showWithHandle(
            onClosed = {
                closed = true
                currentOnDismiss()
            },
        ) {
            var searchQuery by remember { mutableStateOf("") }
            val filteredRegions = remember(searchQuery) {
                SuggestionRegionSlugToName
                    .toList()
                    .filter { it.first != "system" }
                    .filter { it.second.contains(searchQuery.trim(), ignoreCase = true) }
            }
            val listState = rememberLazyListState()
            LaunchedEffect(searchQuery) {
                if (filteredRegions.isNotEmpty()) listState.scrollToItem(0)
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                com.mochyy.mochymusic.ui.component.SheetLabel(
                    text = stringResource(R.string.suggestion_choose_region),
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                com.mochyy.mochymusic.ui.component.SheetSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.suggestion_search_regions),
                )

                Spacer(Modifier.height(14.dp))

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item(key = "system-label") {
                        com.mochyy.mochymusic.ui.component.SheetLabel(
                            text = stringResource(R.string.suggestion_system),
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    item(key = "system-row") {
                        val isSelected = currentRegionSlug == "system"
                        com.mochyy.mochymusic.ui.component.SheetOptionRow(
                            title = stringResource(R.string.suggestion_system_default),
                            leading = { com.mochyy.mochymusic.ui.component.SheetIcon(R.drawable.language) },
                            trailing = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else null,
                            onClick = {
                                currentOnSelected("system")
                                sheet?.dismiss()
                            },
                        )
                    }

                    item(key = "countries-label") {
                        com.mochyy.mochymusic.ui.component.SheetLabel(
                            text = stringResource(R.string.suggestion_countries_regions),
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }

                    itemsIndexed(filteredRegions, key = { _, pair -> pair.first }) { _, (slug, name) ->
                        val isSelected = slug == currentRegionSlug
                        com.mochyy.mochymusic.ui.component.SheetOptionRow(
                            title = name,
                            leading = { com.mochyy.mochymusic.ui.component.SheetIcon(R.drawable.language) },
                            trailing = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else null,
                            onClick = {
                                currentOnSelected(slug)
                                sheet?.dismiss()
                            },
                        )
                    }
                }
            }
        }
        onDispose { if (!closed) sheet?.dismiss() }
    }
}

@Composable
fun RegionListItem(
    headlineContent: @Composable (() -> Unit),
    selected: Boolean,
    items: Int,
    index: Int,
    supportingContent: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val top by animateDpAsState(
        if (isPressed) {
            36.dp
        } else {
            if (items == 1 || index == 0) {
                20.dp
            } else {
                4.dp
            }
        },
        label = "top",
    )
    val bottom by animateDpAsState(
        if (isPressed) {
            36.dp
        } else {
            if (items == 1 || index == items - 1) {
                20.dp
            } else {
                4.dp
            }
        },
        label = "bottom",
    )

    ListItem(
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        leadingContent =
            if (selected) {
                {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.suggestion_selected_desc),
                    )
                }
            } else {
                null
            },
        colors =
            if (selected) {
                ListItemDefaults.colors(
                    containerColor =
                        MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = 0.3f,
                        ),
                    leadingIconColor = MaterialTheme.colorScheme.primary,
                )
            } else {
                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            },
        modifier =
            Modifier
                .clip(
                    if (selected) {
                        CircleShape
                    } else {
                        RoundedCornerShape(
                            topStart = top,
                            topEnd = top,
                            bottomStart = bottom,
                            bottomEnd = bottom,
                        )
                    },
                ).clickable(
                    onClick = onClick,
                    interactionSource = interactionSource,
                    indication = RemoteFocus,
                ),
    )
}
