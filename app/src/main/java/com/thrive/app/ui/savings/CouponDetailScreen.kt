package com.thrive.app.ui.savings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrive.app.data.model.Coupon
import com.thrive.app.ui.components.NewPill
import com.thrive.app.ui.components.PriceTag
import com.thrive.app.ui.components.ProductImage
import com.thrive.app.ui.components.SavingsPill
import com.thrive.app.ui.components.SectionHeader
import com.thrive.app.ui.components.SoftChip
import com.thrive.app.ui.components.StrikePrice
import com.thrive.app.ui.components.StoreAvatar
import com.thrive.app.ui.theme.DealCoral
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.util.Dates
import com.thrive.app.util.Money

@Composable
fun CouponDetailScreen(
    vm: SavingsViewModel,
    couponId: String,
    onBack: () -> Unit,
    onOpenCoupon: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val coupon = state.coupons.firstOrNull { it.id == couponId } ?: return
    val isFavorite = coupon.id in state.favorites
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Box {
                ProductImage(
                    seed = coupon.imageSeed,
                    category = coupon.category,
                    imageUrl = coupon.imageUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp),
                    iconSize = 56.dp,
                )
                // scrim for controls
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent)
                            )
                        ),
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.92f)),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = { vm.toggleFavorite(coupon.id) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.92f)),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) DealCoral else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StoreAvatar(coupon.store, size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(coupon.store, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = coupon.category,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                    if (coupon.isNew) NewPill()
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = coupon.title,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Money.fmt(coupon.priceBefore),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    PriceTag(coupon.priceAfter, size = 28)
                    Spacer(Modifier.width(10.dp))
                    SavingsPill(coupon.discountPercent)
                }
                if (coupon.estimated) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Estimated price — check the store for the exact offer.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        ),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = coupon.description,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftChip(
                        text = Dates.countdownLabel(coupon.endsInDays),
                        bg = if (coupon.endsInDays <= 1) LocalThriveColors.current.dealSoft
                        else LocalThriveColors.current.goldSoft,
                        fg = MaterialTheme.colorScheme.onSurface,
                    )
                    SoftChip(
                        text = coupon.dealType.replace("_", " "),
                        bg = MaterialTheme.colorScheme.surfaceVariant,
                        fg = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(18.dp))

                if (coupon.dealType == "CODE" && coupon.code != null) {
                    DealCodeCard(code = coupon.code, context = context)
                    Spacer(Modifier.height(12.dp))
                }

                PrimaryDealButton(coupon = coupon, context = context)
            }
        }

        if (coupon.terms.isNotBlank()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("Fine print", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = coupon.terms,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }

        val similar = state.coupons
            .filter { it.id != coupon.id && (it.category == coupon.category || it.store == coupon.store) }
            .take(6)
        if (similar.isNotEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                SectionHeader("More deals like this", subtitle = null, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(similar, key = { it.id }) { deal ->
                        SimilarDealCard(
                            deal = deal,
                            onClick = { onOpenCoupon(deal.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DealCodeCard(code: String, context: Context) {
    val accents = LocalThriveColors.current
    var copied by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    val onCopy: () -> Unit = {
        val ok = com.thrive.app.util.Clipboard.copy(context, "deal code", code)
        if (ok) {
            copied = true
            Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            // Fallback: device refused clipboard access — let them see/copy it.
            showManual = true
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(accents.dealSoft.copy(alpha = 0.5f))
            .clickable(onClick = onCopy)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = if (copied) Color(0xFF1F6B2E) else accents.deal,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (copied) "Copied to clipboard" else "Use code at checkout",
                style = MaterialTheme.typography.labelMedium,
                color = if (copied) Color(0xFF1F6B2E) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = code,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
        Text(
            text = if (copied) "COPIED" else "TAP TO COPY",
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (copied) Color(0xFF1F6B2E) else accents.deal,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            ),
        )
    }
    if (showManual) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("Clipboard unavailable") },
            text = {
                Column {
                    Text("This device blocked automatic copy. The code is below — copy it manually:")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = code,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copied = true
                    showManual = false
                }) { Text("Got it") }
            },
            dismissButton = {
                TextButton(onClick = { showManual = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun PrimaryDealButton(coupon: Coupon, context: Context) {
    val accents = LocalThriveColors.current
    val url = coupon.url
    if (url == null) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text("In-store deal — show this screen at checkout")
        }
        return
    }
    // Honest link labeling: only claim the button opens the exact product/offer
    // when urlVerified is true. Store-level links open the retailer but never
    // pretend to be the item.
    if (!coupon.urlVerified) {
        Column {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }.onFailure {
                        Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Open ${coupon.store} site",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Item link not verified — check the ad in store or on their site.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        return
    }
    Button(
        onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
    ) {
        Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (coupon.dealType == "PICKUP") "Get this deal · pickup" else "Get this deal",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SimilarDealCard(deal: Coupon, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        ProductImage(
            seed = deal.imageSeed,
            category = deal.category,
            imageUrl = deal.imageUrl,
            corner = 12.dp,
            modifier = Modifier.fillMaxWidth().height(84.dp),
            iconSize = 24.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = deal.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StrikePrice(deal.priceBefore)
            Spacer(Modifier.width(5.dp))
            PriceTag(deal.priceAfter, size = 15)
        }
    }
}
