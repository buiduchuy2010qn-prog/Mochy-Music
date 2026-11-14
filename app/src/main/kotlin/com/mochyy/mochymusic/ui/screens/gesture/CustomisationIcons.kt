// customisation's launcher icons: the one in use, and behind a chevron every family's ready-made ones and a custom colour each

package com.mochyy.mochymusic.ui.screens.gesture

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.ui.component.pressBounce
import com.mochyy.mochymusic.ui.player.findActivity
import com.mochyy.mochymusic.ui.screens.audiolab.LabColors
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.AppIcon
import com.mochyy.mochymusic.utils.AppIconColor
import com.mochyy.mochymusic.utils.AppIconFamily
import com.mochyy.mochymusic.utils.AppIcons
import com.mochyy.mochymusic.utils.CustomAppBranding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TileSize = 66.dp
// close to a launcher's squircle, so the tile reads as the icon it will be
private val TileShape = RoundedCornerShape(TileSize * 0.27f)
private val TileRing = Color.White.copy(alpha = 0.12f)
private val TileRingPicked = Color.White.copy(alpha = 0.9f)

// the custom colours sit in two rows of six, which fit across any phone
private val SwatchSize = 30.dp
private const val SwatchesPerRow = 6

@Composable
internal fun IconsSection() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(AppIcons.current(context)) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // the family whose colours are open under its customize button
    var customizing by remember { mutableStateOf<AppIconFamily?>(null) }
    // each family's custom tile keeps the colour last picked for it, starting from the one in use
    val customColors = remember { mutableStateMapOf<AppIconFamily, AppIconColor>().apply { picked.color?.let { put(picked.family, it) } } }
    val art = rememberIconArt()

    fun pick(icon: AppIcon) {
        icon.color?.let { customColors[icon.family] = it }
        if (icon == picked) return
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        picked = icon
        // the next launch's splash goes with it: the icon itself for an anime one, the bare mark for the rest
        context.findActivity()?.let { AppIcons.syncSplash(it, icon) }
        // the package manager is a call to the system, kept off the main thread
        scope.launch(Dispatchers.IO) { AppIcons.apply(context.applicationContext, icon) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LabColors.Card)
            .padding(top = CardTextInset, bottom = 20.dp),
    ) {
        CustomBrandingSection(pickedPreset = picked, art = art)
        HorizontalDivider(
            color = Color.White.copy(alpha = 0.08f),
            modifier = Modifier.padding(horizontal = CardTextInset, vertical = 18.dp),
        )
        PickedIcon(icon = picked, art = art, expanded = expanded, onToggle = { expanded = !expanded })
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column {
                AppIconFamily.entries.forEach { family ->
                    FamilyIcons(
                        family = family,
                        picked = picked,
                        customColor = customColors[family] ?: AppIconColor.entries.first(),
                        art = art,
                        customizing = customizing == family,
                        onPick = ::pick,
                        onCustomize = { customizing = if (customizing == family) null else family },
                    )
                }
                Text(
                    text = stringResource(R.string.customise_icons_caption),
                    style = CaptionStyle,
                    color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.padding(start = CardTextInset, end = CardTextInset, top = 16.dp),
                )
            }
        }
    }
}

// each piece of art loaded once and shared by every tile; anime mochy is a bitmap, and every tile used to decode its own
@Composable
private fun rememberIconArt(): Map<Int, Painter> {
    val ids = remember { AppIcon.entries.map { it.foreground }.distinct() }
    return ids.associateWith { painterResource(it) }
}

// the icon in use with its name and family, and the chevron that opens every other choice
@Composable
private fun PickedIcon(icon: AppIcon, art: Map<Int, Painter>, expanded: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(260), label = "iconsChevron")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onToggle)
            .padding(horizontal = CardTextInset),
    ) {
        Text(
            text = stringResource(R.string.customise_icons),
            style = CardLabelStyle,
            color = Color.White.copy(alpha = 0.9f),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconArt(icon = icon, art = art, picked = true)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(icon.label),
                    style = CardLabelStyle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(icon.family.title),
                    style = CaptionStyle,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .pressBounce(interaction, pressedScale = 0.9f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.expand_more),
                    contentDescription = stringResource(if (expanded) R.string.customise_icons_show_less else R.string.customise_icons_show_all),
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = turn },
                )
            }
        }
    }
}

// one family: its ready-made icons swiped sideways with its custom one last, and its colours under customize
@Composable
private fun FamilyIcons(
    family: AppIconFamily,
    picked: AppIcon,
    customColor: AppIconColor,
    art: Map<Int, Painter>,
    customizing: Boolean,
    onPick: (AppIcon) -> Unit,
    onCustomize: () -> Unit,
) {
    Spacer(Modifier.height(18.dp))
    Text(
        text = stringResource(family.title),
        style = CaptionStyle,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = CardTextInset),
    )
    Spacer(Modifier.height(10.dp))
    // swiped sideways: a family's tiles and its custom one don't fit across a phone with their names
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = CardTextInset)),
    ) {
        AppIcon.presets.filter { it.family == family }.forEach { icon ->
            IconTile(icon = icon, art = art, picked = icon == picked, onClick = { onPick(icon) }) {
                TileLabel(text = stringResource(icon.label), picked = icon == picked)
            }
        }
        if (family.supportsCustomColor) {
            val custom = AppIcon.custom(family, customColor)
            IconTile(icon = custom, art = art, picked = custom == picked, onClick = { onPick(custom) }) {
                CustomizeButton(open = customizing, onClick = onCustomize)
            }
        }
    }
    if (family.supportsCustomColor) {
        AnimatedVisibility(
            visible = customizing,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            ColorSwatches(selected = customColor, onPick = { color -> onPick(AppIcon.custom(family, color)) })
        }
    }
}

// a tile you tap to wear that icon, with its name or its customize button under it
@Composable
private fun IconTile(icon: AppIcon, art: Map<Int, Painter>, picked: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(TileSize + 10.dp),
    ) {
        IconArt(
            icon = icon,
            art = art,
            picked = picked,
            modifier = Modifier
                .pressBounce(interaction, pressedScale = 0.94f)
                .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onClick),
        )
        Spacer(Modifier.height(8.dp))
        label()
    }
}

/** the icon as the launcher will draw it: its background, and its art at the adaptive icon's 108 to 72 crop */
@Composable
private fun IconArt(icon: AppIcon, art: Map<Int, Painter>, picked: Boolean, modifier: Modifier = Modifier) {
    val ring by animateColorAsState(if (picked) TileRingPicked else TileRing, tween(220), label = "iconRing")
    // a custom tile fades between colours as they're picked; a see-through one has nothing behind its art
    val fill by animateColorAsState(icon.background?.let { colorResource(it) } ?: Color.Transparent, tween(220), label = "iconFill")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(TileSize)
            .clip(TileShape)
            .drawBehind { drawRect(fill) }
            .border(if (picked) 2.dp else 1.dp, ring, TileShape),
    ) {
        Image(
            painter = art.getValue(icon.foreground),
            contentDescription = null,
            contentScale = if (icon.family == AppIconFamily.NewLogo) ContentScale.Crop else ContentScale.Fit,
            modifier = if (icon.family == AppIconFamily.NewLogo) Modifier.size(TileSize) else Modifier.requiredSize(TileSize * 1.5f),
        )
    }
}

@Composable
private fun TileLabel(text: String, picked: Boolean) {
    Text(
        text = text,
        style = CaptionStyle,
        color = Color.White.copy(alpha = if (picked) 0.95f else 0.6f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// the pill under a custom tile that opens and closes its family's colours, filled while they're open
@Composable
private fun CustomizeButton(open: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (open) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.1f), tween(220), label = "customizeFill")
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = stringResource(R.string.app_icon_customize),
        style = CaptionStyle,
        color = if (open) Color.Black else Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        modifier = Modifier
            .pressBounce(interaction, pressedScale = 0.92f)
            .clip(CircleShape)
            .drawBehind { drawRect(fill) }
            .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

// the twelve custom colours; tapping one puts the family's art on it straight away
@Composable
private fun ColorSwatches(selected: AppIconColor, onPick: (AppIconColor) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardTextInset, end = CardTextInset, top = 16.dp),
    ) {
        AppIconColor.entries.chunked(SwatchesPerRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                row.forEach { color -> Swatch(color = color, selected = color == selected, onClick = { onPick(color) }) }
            }
        }
    }
}

@Composable
private fun Swatch(color: AppIconColor, selected: Boolean, onClick: () -> Unit) {
    val ring by animateColorAsState(if (selected) TileRingPicked else TileRing, tween(220), label = "swatchRing")
    val name = stringResource(color.label)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(SwatchSize)
            .pressBounce(interaction, pressedScale = 0.9f)
            .clip(CircleShape)
            .background(colorResource(color.background))
            .border(if (selected) 2.dp else 1.dp, ring, CircleShape)
            .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onClick)
            .semantics {
                contentDescription = name
                this.selected = selected
            },
    )
}

@Composable
private fun CustomBrandingSection(pickedPreset: AppIcon, art: Map<Int, Painter>) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val branding = CustomAppBranding.rememberBranding()
    val customLogo = CustomAppBranding.rememberCustomLogoBitmap()

    var isPinned by remember(branding.logoVersion, branding.customName) {
        mutableStateOf(CustomAppBranding.isShortcutPinned(context))
    }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var cropBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val imageErrorMsg = stringResource(R.string.brand_toast_image_error)
    val savedNameMsg = stringResource(R.string.brand_toast_saved_name)
    val savedLogoMsg = stringResource(R.string.brand_toast_saved_logo)
    val resetMsg = stringResource(R.string.brand_toast_reset)
    val pinnedMsg = stringResource(R.string.brand_toast_pinned)
    val updatedHomeMsg = stringResource(R.string.brand_toast_updated_home)
    val pinUnsupportedMsg = stringResource(R.string.brand_toast_pin_unsupported)

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isPinned = CustomAppBranding.isShortcutPinned(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = CustomAppBranding.decodeUriBitmap(context, uri)
            if (decoded != null) {
                cropBitmap = decoded
            } else {
                TopToaster.error(imageErrorMsg)
            }
        }
    }

    fun launchPicker() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun handlePin(forceNewPin: Boolean) {
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                CustomAppBranding.pinOrUpdateHomeIcon(context.applicationContext, forceNewPin = forceNewPin)
            }
            isPinned = CustomAppBranding.isShortcutPinned(context)
            when (outcome) {
                CustomAppBranding.PinOutcome.UPDATED_EXISTING ->
                    TopToaster.show(updatedHomeMsg, iconRes = R.drawable.ic_push_pin)
                CustomAppBranding.PinOutcome.REQUESTED_PIN ->
                    TopToaster.show(pinnedMsg, iconRes = R.drawable.ic_push_pin)
                CustomAppBranding.PinOutcome.UNSUPPORTED ->
                    TopToaster.error(pinUnsupportedMsg)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardTextInset),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.brand_section_title),
                style = CardLabelStyle,
                color = Color.White.copy(alpha = 0.95f),
                modifier = Modifier.weight(1f),
            )
            if (branding.isCustomized) {
                val resetInteraction = remember { MutableInteractionSource() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .pressBounce(resetInteraction, pressedScale = 0.94f)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable(interactionSource = resetInteraction, indication = RemoteFocus) {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            CustomAppBranding.resetAll(context)
                            context.findActivity()?.let { CustomAppBranding.syncTaskDescription(it) }
                            TopToaster.show(resetMsg, iconRes = R.drawable.restore)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.restore),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = stringResource(R.string.brand_btn_reset_all),
                        style = CaptionStyle,
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val previewInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(TileSize)
                    .pressBounce(previewInteraction, pressedScale = 0.94f)
                    .clickable(interactionSource = previewInteraction, indication = RemoteFocus, onClick = ::launchPicker),
            ) {
                if (customLogo != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(TileShape)
                            .background(Color(0xFF0E0E10))
                            .border(1.5.dp, TileRingPicked, TileShape),
                    ) {
                        Image(
                            bitmap = customLogo,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    IconArt(icon = pickedPreset, art = art, picked = true)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF18181B))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.insert_photo),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = branding.displayName(stringResource(R.string.app_name)),
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = (-0.03).em,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (branding.isCustomized) R.string.brand_section_subtitle_active else R.string.brand_section_subtitle_default,
                    ),
                    style = CaptionStyle,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BrandActionPill(
                iconRes = R.drawable.edit,
                label = stringResource(R.string.brand_btn_rename),
                onClick = { showRenameDialog = true },
                modifier = Modifier.weight(1f),
            )
            BrandActionPill(
                iconRes = R.drawable.insert_photo,
                label = stringResource(R.string.brand_btn_pick_logo),
                onClick = ::launchPicker,
                modifier = Modifier.weight(1f),
            )
        }

        if (branding.hasCustomLogo) {
            Spacer(Modifier.height(8.dp))
            val removeInteraction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = removeInteraction, indication = RemoteFocus) {
                        CustomAppBranding.clearCustomLogo(context)
                        context.findActivity()?.let { CustomAppBranding.syncTaskDescription(it) }
                        TopToaster.show(resetMsg, iconRes = R.drawable.delete)
                    }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.brand_btn_remove_logo),
                    style = CaptionStyle,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val pinInteraction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .pressBounce(pinInteraction, pressedScale = 0.96f)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .clickable(interactionSource = pinInteraction, indication = RemoteFocus) { handlePin(forceNewPin = false) }
                    .padding(horizontal = 14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_push_pin),
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(if (isPinned) R.string.brand_btn_update_home else R.string.brand_btn_pin_home),
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = (-0.02).em,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isPinned) {
                val repinInteraction = remember { MutableInteractionSource() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(44.dp)
                        .pressBounce(repinInteraction, pressedScale = 0.96f)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable(interactionSource = repinInteraction, indication = RemoteFocus) { handlePin(forceNewPin = true) }
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.brand_btn_pin_again),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.brand_pin_note),
            style = CaptionStyle,
            color = Color.White.copy(alpha = 0.45f),
        )
    }

    if (showRenameDialog) {
        RenameAppDialog(
            initialName = branding.customName.orEmpty(),
            onDismiss = { showRenameDialog = false },
            onSave = { nextName ->
                CustomAppBranding.setCustomName(context, nextName)
                context.findActivity()?.let { CustomAppBranding.syncTaskDescription(it) }
                isPinned = CustomAppBranding.isShortcutPinned(context)
                showRenameDialog = false
                TopToaster.show(savedNameMsg, iconRes = R.drawable.edit)
            },
        )
    }

    cropBitmap?.let { bmp ->
        LogoCropDialog(
            bitmap = bmp,
            onDismiss = { cropBitmap = null },
            onApply = { zoom, panXNorm, panYNorm ->
                scope.launch {
                    val saved = CustomAppBranding.saveCroppedLogo(
                        context = context,
                        src = bmp,
                        zoom = zoom,
                        panXNorm = panXNorm,
                        panYNorm = panYNorm,
                    )
                    cropBitmap = null
                    if (saved) {
                        context.findActivity()?.let { CustomAppBranding.syncTaskDescription(it) }
                        isPinned = CustomAppBranding.isShortcutPinned(context)
                        TopToaster.show(savedLogoMsg, iconRes = R.drawable.insert_photo)
                    } else {
                        TopToaster.error(imageErrorMsg)
                    }
                }
            },
        )
    }
}

@Composable
private fun BrandActionPill(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(40.dp)
            .pressBounce(interaction, pressedScale = 0.96f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.09f))
            .clickable(interactionSource = interaction, indication = RemoteFocus, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.5.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RenameAppDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var draft by remember(initialName) { mutableStateOf(initialName) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF161618))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Text(
                text = stringResource(R.string.brand_rename_dialog_title),
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { next ->
                    if (next.length <= CustomAppBranding.MAX_NAME_LENGTH && !next.contains('\n')) {
                        draft = next
                    }
                },
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.brand_rename_dialog_hint),
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.4f),
                    )
                },
                textStyle = TextStyle(
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Color.White,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White.copy(alpha = 0.8f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                    focusedContainerColor = Color(0xFF0E0E10),
                    unfocusedContainerColor = Color(0xFF0E0E10),
                    cursorColor = Color.White,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.brand_rename_reset),
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSave(null) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
                Text(
                    text = "${draft.length}/${CustomAppBranding.MAX_NAME_LENGTH}",
                    fontFamily = InterFontFamily,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable(onClick = onDismiss),
                ) {
                    Text(
                        text = stringResource(R.string.brand_action_cancel),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color.White,
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable { onSave(draft) },
                ) {
                    Text(
                        text = stringResource(R.string.brand_action_save),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogoCropDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onApply: (zoom: Float, panXNorm: Float, panYNorm: Float) -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var panXNorm by remember(bitmap) { mutableFloatStateOf(0f) }
    var panYNorm by remember(bitmap) { mutableFloatStateOf(0f) }

    val cropSizeDp = 220.dp
    val cropShape = RoundedCornerShape(cropSizeDp * 0.27f)
    val density = LocalDensity.current
    val cropPx = with(density) { cropSizeDp.toPx() }
    val srcW = bitmap.width.coerceAtLeast(1).toFloat()
    val srcH = bitmap.height.coerceAtLeast(1).toFloat()
    val baseScale = maxOf(cropPx / srcW, cropPx / srcH)
    val effectiveScale = baseScale * zoom.coerceIn(1f, 4f)
    val drawW = srcW * effectiveScale
    val drawH = srcH * effectiveScale
    val maxPanX = maxOf(0f, (drawW - cropPx) / 2f)
    val maxPanY = maxOf(0f, (drawH - cropPx) / 2f)
    val drawWDp = with(density) { drawW.toDp() }
    val drawHDp = with(density) { drawH.toDp() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF161618))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(26.dp))
                .padding(22.dp),
        ) {
            Text(
                text = stringResource(R.string.brand_crop_dialog_title),
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.brand_crop_dialog_hint),
                fontFamily = InterFontFamily,
                fontSize = 12.5.sp,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(cropSizeDp)
                    .clip(cropShape)
                    .background(Color(0xFF0E0E10))
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val nextZoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                            val nextScale = baseScale * nextZoom
                            val nextMaxPanX = maxOf(0f, (srcW * nextScale - cropPx) / 2f)
                            val nextMaxPanY = maxOf(0f, (srcH * nextScale - cropPx) / 2f)
                            val curPanX = panXNorm * maxOf(0f, (srcW * baseScale * zoom - cropPx) / 2f)
                            val curPanY = panYNorm * maxOf(0f, (srcH * baseScale * zoom - cropPx) / 2f)
                            panXNorm = if (nextMaxPanX > 0.5f) ((curPanX + pan.x) / nextMaxPanX).coerceIn(-1f, 1f) else 0f
                            panYNorm = if (nextMaxPanY > 0.5f) ((curPanY + pan.y) / nextMaxPanY).coerceIn(-1f, 1f) else 0f
                            zoom = nextZoom
                        }
                    },
            ) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .requiredSize(width = drawWDp, height = drawHDp)
                        .graphicsLayer {
                            translationX = panXNorm * maxPanX
                            translationY = panYNorm * maxPanY
                        },
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(2.dp, Color.White.copy(alpha = 0.9f), cropShape),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.9f)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.brand_crop_zoom),
                    fontFamily = InterFontFamily,
                    fontSize = 12.5.sp,
                    color = Color.White.copy(alpha = 0.65f),
                )
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = zoom,
                    onValueChange = { zoom = it },
                    valueRange = 1f..4f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                    ),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable(onClick = onDismiss),
                ) {
                    Text(
                        text = stringResource(R.string.brand_action_cancel),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color.White,
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .clickable { onApply(zoom, panXNorm, panYNorm) },
                ) {
                    Text(
                        text = stringResource(R.string.brand_action_apply),
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.Black,
                    )
                }
            }
        }
    }
}
