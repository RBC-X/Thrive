package com.thrive.app.ui.savings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrive.app.data.model.Coupon
import com.thrive.app.ui.components.NewPill
import com.thrive.app.ui.components.PriceTag
import com.thrive.app.ui.components.ProductImage
import com.thrive.app.ui.components.SavingsPill
import com.thrive.app.ui.components.SoftChip
import com.thrive.app.ui.components.StrikePrice
import com.thrive.app.ui.components.StoreAvatar
import com.thrive.app.ui.theme.DealCoral
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import com.thrive.app.BuildConfig
import com.thrive.app.data.remote.UpdateInfo
import com.thrive.app.data.remote.isNewerVersion
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.util.Dates
import com.thrive.app.util.Money

@Composable
fun SavingsScreen(
    vm: SavingsViewModel,
    onOpenCoupon: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var expandedStores by rememberSaveable { mutableStateOf(setOf<String>()) }

    // The derived feed views sort/filter the full catalog (4,000+ offers), so
    // they're computed once per input change instead of on every access — the
    // LazyColumn and its children would otherwise re-run the sort repeatedly
    // on the main thread during each recomposition.
    val filtered = remember(state.coupons, state.category, state.query, state.mode) { state.filtered }
    val storeSections = remember(state.coupons, state.category, state.query, state.mode) { state.storeSections }
    val categories = remember(state.coupons) { state.categories }
    val newThisWeek = remember(state.coupons) { state.newThisWeek }
    val dailyPick = remember(state.coupons) { state.dailyPick }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            SavingsHeader(
                state = state,
                availableCount = filtered.size,
                hiddenCount = state.hiddenUnverified,
                showingEstimates = state.showingEstimates,
                onOpenSettings = onOpenSettings,
                onRefresh = vm::refreshNow,
            )
        }
        state.sync.update?.let { update ->
            if (state.sync.isLive && isNewerVersion(update.versionName, BuildConfig.VERSION_NAME)) {
                item { UpdateBanner(update) }
            }
        }
        if (state.mode == "Deals") {
            dailyPick?.let { pick ->
                item { DailyPickHero(pick, onClick = { onOpenCoupon(pick.id) }) }
            }
            item { SavingsSummaryStrip(state, filtered) }
        } else {
            item { StoresIntro(state) }
        }
        if (state.mode == "Deals" && state.query.isBlank() && newThisWeek.isNotEmpty()) {
            item { SectionHeader("New this week", "Fresh offers, while they last") }
            item {
                NewThisWeekShelf(
                    deals = newThisWeek,
                    onOpen = { onOpenCoupon(it.id) },
                )
            }
        }
        item { SearchField(query = state.query, onQuery = vm::setQuery) }
        item { ModeTabs(mode = state.mode, onSelect = vm::setMode) }
        item { CategoryChips(state.category, categories, vm::selectCategory) }
        if (state.mode == "Deals") {
            item {
                Text(
                    text = if (filtered.isEmpty()) "No deals match" else "${filtered.size} deals",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (filtered.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("Try a different search or category.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(filtered, key = { it.id }) { coupon ->
                DealCard(
                    coupon = coupon,
                    isFavorite = coupon.id in state.favorites,
                    onClick = { onOpenCoupon(coupon.id) },
                    onFavorite = { vm.toggleFavorite(coupon.id) },
                )
            }
        } else {
            StoresList(
                sections = storeSections,
                favorites = state.favorites,
                expandedStores = expandedStores,
                onToggleStore = { store ->
                    expandedStores = if (store in expandedStores) expandedStores - store else expandedStores + store
                },
                onOpenCoupon = onOpenCoupon,
                onFavorite = { vm.toggleFavorite(it) },
            )
        }
    }
}

@Composable
private fun StoresIntro(state: SavingsUiState) {
    val accents = LocalThriveColors.current
    val located = state.sync.nearbyStores.isNotEmpty()
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (located) Icons.Rounded.MyLocation else Icons.Rounded.Storefront,
            contentDescription = null,
            tint = accents.leaf,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (located)
                "Stores nearest you first — every deal at each store."
            else
                "Stores listed A–Z. Share your approximate location in Settings to see the nearest ones first.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun ModeTabs(mode: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("Deals", "Stores").forEach { m ->
            val selected = mode == m
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(m) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (m == "Deals") Icons.Rounded.LocalOffer else Icons.Rounded.Storefront,
                        contentDescription = null,
                        tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = m,
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

@Composable
private fun NewThisWeekShelf(deals: List<Coupon>, onOpen: (Coupon) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(deals, key = { it.id }) { deal ->
            MiniDealCard(deal, onClick = { onOpen(deal) })
        }
    }
}

@Composable
private fun MiniDealCard(coupon: Coupon, onClick: () -> Unit) {
    val accents = LocalThriveColors.current
    Column(
        modifier = Modifier
            .width(158.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box {
            ProductImage(
                seed = coupon.imageSeed,
                category = coupon.category,
                imageUrl = coupon.imageUrl,
                logoUrl = coupon.storeLogoUrl,
                store = coupon.store,
                corner = 12.dp,
                modifier = Modifier.size(110.dp),
                iconSize = 24.dp,
            )
            if (coupon.isNew) {
                NewPill()
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = coupon.store,
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Text(
            text = coupon.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Money.fmt(coupon.priceAfter),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
            )
            if (coupon.discountPercent > 0) {
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "Save ${coupon.discountPercent}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = accents.deal,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            } else if (!coupon.estimated) {
                // Honest live price: no promo is running, so no invented cut.
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "Live price",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

private fun LazyListScope.StoresList(
    sections: List<StoreSection>,
    favorites: Set<String>,
    expandedStores: Set<String>,
    onToggleStore: (String) -> Unit,
    onOpenCoupon: (String) -> Unit,
    onFavorite: (String) -> Unit,
) {
    item {
        Text(
            text = if (sections.isEmpty()) "No stores match"
            else "${sections.size} store${if (sections.size == 1) "" else "s"} · ${sections.sumOf { it.coupons.size }} deals",
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
    if (sections.isEmpty()) {
        item {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text("Try a different search or category.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    sections.forEach { section ->
        val isOpen = section.store in expandedStores
        item(key = "store-${section.store}") {
            StoreHeader(
                section = section,
                isOpen = isOpen,
                onClick = { onToggleStore(section.store) },
            )
        }
        if (isOpen) {
            items(section.coupons, key = { it.id }) { coupon ->
                DealCard(
                    coupon = coupon,
                    isFavorite = coupon.id in favorites,
                    onClick = { onOpenCoupon(coupon.id) },
                    onFavorite = { onFavorite(coupon.id) },
                )
            }
        }
    }
}

@Composable
private fun StoreHeader(section: StoreSection, isOpen: Boolean, onClick: () -> Unit) {
    val accents = LocalThriveColors.current
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(
            seed = section.store.lowercase(),
            category = "Grocery",
            imageUrl = null,
            logoUrl = section.coupons.firstOrNull()?.storeLogoUrl,
            store = section.store,
            corner = 12.dp,
            modifier = Modifier.size(44.dp),
            iconSize = 20.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = section.store,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (section.distMi != null) {
                    Icon(
                        Icons.Rounded.LocationOn,
                        contentDescription = null,
                        tint = accents.leaf,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "~${formatMi(section.distMi)}${section.city?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "${section.coupons.size} deal${if (section.coupons.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
        Icon(
            imageVector = if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (isOpen) "Collapse ${section.store}" else "Expand ${section.store}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatMi(mi: Double): String = com.thrive.app.util.Distances.mi(mi)

@Composable
private fun UpdateBanner(update: UpdateInfo) {
    val context = LocalContext.current
    val accents = LocalThriveColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accents.berrySoft)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.SystemUpdate,
            contentDescription = null,
            tint = Color(0xFF2D4BB0),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Update available · v${update.versionName}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            )
            if (update.notes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                update.notes.forEach { note ->
                    Text(
                        text = "• $note",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            } else {
                Text(
                    text = "Grab the latest APK from your sync server",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        TextButton(onClick = {
            if (update.apkUrl.isNotBlank()) {
                val intent = Intent(Intent.ACTION_VIEW, update.apkUrl.toUri())
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        }) {
            Text("Get it", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SavingsHeader(
    state: SavingsUiState,
    availableCount: Int,
    hiddenCount: Int,
    showingEstimates: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    val accents = LocalThriveColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Thrive",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = ThriveFont,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                SyncChip(state.sync)
                Spacer(Modifier.width(6.dp))
                // Visible build badge — makes updates obvious and helps verify
                // which release is installed.
                Text(
                    text = "v${com.thrive.app.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
            Text(
                text = "Good morning! Here's what's on sale today.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            if (!state.sync.hasLiveFeed) {
                Spacer(Modifier.height(6.dp))
                BundledFeedNotice(state.sync)
            }
            // Honest availability: every deal shown carries a VERIFIED direct
            // product link. Offers that only have a store search page (no exact
            // product page) are hidden, and the user is told exactly that — the
            // app never shows a deal it can't take you straight to.
            if (hiddenCount > 0) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(accents.dealSoft.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = accents.deal,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (showingEstimates)
                            "Showing $availableCount bundled planning estimates. Prices are not live; " +
                                "each item opens the retailer's search so you can confirm it."
                        else
                            "Showing $availableCount retailer-verified deals. $hiddenCount older or " +
                                "unverified feed rows are hidden.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "Refresh deals",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onOpenSettings() }) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncChip(sync: com.thrive.app.data.remote.SyncState) {
    val accents = LocalThriveColors.current
    val status = sync.status
    val label: String
    val bg: Color
    val fg: Color
    when {
        // Honest: "Live" only when the coupons on screen actually came from
        // the server. A reachable server that sent no coupons, or a dead one
        // after a previous live sync, is NOT live — it's the bundled feed.
        sync.hasLiveFeed -> {
            val mins = sync.lastSyncedAt?.let { ((System.currentTimeMillis() - it) / 60_000L).toInt() } ?: 0
            label = "Live · ${if (mins < 1) "just now" else "${mins}m ago"}"
            bg = accents.leafSoft
            fg = Color(0xFF1F6B2E)
        }
        status == com.thrive.app.data.remote.SyncStatus.SYNCING -> {
            label = "Syncing…"; bg = MaterialTheme.colorScheme.surfaceVariant; fg = MaterialTheme.colorScheme.onSurfaceVariant
        }
        status == com.thrive.app.data.remote.SyncStatus.ERROR -> {
            label = "Offline feed"; bg = accents.dealSoft; fg = Color(0xFFB33A1F)
        }
        else -> {
            label = "Bundled feed"; bg = MaterialTheme.colorScheme.surfaceVariant; fg = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = fg,
            ),
        )
    }
}

@Composable
private fun BundledFeedNotice(sync: com.thrive.app.data.remote.SyncState) {
    val text = when (sync.status) {
        com.thrive.app.data.remote.SyncStatus.ERROR ->
            "Can't reach the live server — showing bundled deals with estimated prices. " +
                "Pull to refresh or check Settings."
        com.thrive.app.data.remote.SyncStatus.OK ->
            "Server reached, but it sent no fresh deals — showing bundled estimates. " +
                "Pull to refresh."
        else ->
            "Offline — showing bundled deals with estimated prices. Deals refresh when a server is configured."
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun DailyPickHero(coupon: Coupon, onClick: () -> Unit) {
    val accents = LocalThriveColors.current
    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF0B6E4F), Color(0xFF128F6A)),
                )
            )
            .clickable(onClick = onClick),
    ) {
        // decorative sparkles
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 16.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "TODAY'S PICK",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White.copy(alpha = 0.85f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = coupon.store,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White.copy(alpha = 0.9f),
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = coupon.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column {
                    Text(
                        text = Money.fmt(coupon.priceAfter),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                    )
                    Text(
                        text = "was ${Money.fmt(coupon.priceBefore)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.White.copy(alpha = 0.75f),
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accents.deal)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Save ${coupon.discountPercent}%",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavingsSummaryStrip(state: SavingsUiState, filtered: List<Coupon>) {
    val accents = LocalThriveColors.current
    if (filtered.isEmpty()) return
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(accents.dealSoft.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Savings,
            contentDescription = null,
            tint = LocalThriveColors.current.deal,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        // Honest headline: only the user's saved (favorited) deals contribute to
        // a savings claim. With none saved, we say the feed is fresh and let
        // each offer speak for itself — never the catalog-wide sum.
        val favSavings = state.favoritesSavings
        val headline = when {
            favSavings != null ->
                "Your saved deals save you up to ${Money.fmt(favSavings.first)} across ${favSavings.second} items"
            state.showingEstimates ->
                "${filtered.size} planning estimates — open the retailer search to confirm each price"
            else ->
                "${filtered.size} fresh deals today — every one opens the exact product page"
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        placeholder = {
            Text("Search products, stores, or brands", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        ),
    )
}

@Composable
private fun CategoryChips(selected: String, categories: List<String>, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        items(categories, key = { it }) { category ->
            val isSelected = category == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(category) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun HotPill() {
    val accents = LocalThriveColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accents.gold)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = "POPULAR",
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
            ),
        )
    }
}

@Composable
fun DealCard(
    coupon: Coupon,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ProductImage(
                seed = coupon.imageSeed,
                category = coupon.category,
                imageUrl = coupon.imageUrl,
                logoUrl = coupon.storeLogoUrl,
                store = coupon.store,
                corner = 16.dp,
                modifier = Modifier
                    .size(104.dp),
                iconSize = 28.dp,
            )
            // Default IconButton size (48dp touch target); the heart itself
            // stays small inside it.
            IconButton(
                onClick = onFavorite,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) DealCoral else Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoreAvatar(coupon.store, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = coupon.store,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                if (coupon.isNew) {
                    Spacer(Modifier.width(6.dp))
                    NewPill()
                }
                if (coupon.isHot) {
                    Spacer(Modifier.width(6.dp))
                    HotPill()
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = coupon.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StrikePrice(coupon.priceBefore)
                Spacer(Modifier.width(6.dp))
                PriceTag(coupon.priceAfter, size = 18)
                Spacer(Modifier.width(8.dp))
                SavingsPill(coupon.discountPercent)
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoftChip(
                    text = Dates.expiryLabel(coupon.endsInDays),
                    bg = MaterialTheme.colorScheme.surfaceVariant,
                    fg = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                SoftChip(
                    text = coupon.dealType.replace("_", " "),
                    bg = LocalThriveColors.current.leafSoft,
                    fg = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (coupon.estimated) {
                    Spacer(Modifier.width(6.dp))
                    SoftChip(
                        text = "est.",
                        bg = MaterialTheme.colorScheme.surfaceVariant,
                        fg = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
