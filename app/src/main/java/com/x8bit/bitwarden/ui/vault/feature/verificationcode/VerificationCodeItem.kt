package com.x8bit.bitwarden.ui.vault.feature.verificationcode

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.x8bit.bitwarden.R
import com.x8bit.bitwarden.ui.platform.components.BitwardenIcon
import com.x8bit.bitwarden.ui.platform.components.model.IconData
import com.x8bit.bitwarden.ui.platform.theme.BitwardenTheme

/**
 * The verification code item displayed to the user.
 *
 * @param authCode The code for the item.
 * @param label The label for the item.
 * @param periodSeconds The times span where the code is valid.
 * @param timeLeftSeconds The seconds remaining until a new code is needed.
 * @param startIcon The leading icon for the item.
 * @param onCopyClick The lambda function to be invoked when the copy button is clicked.
 * @param onItemClick The lambda function to be invoked when the item is clicked.
 * @param modifier The modifier for the item.
 * @param supportingLabel The supporting label for the item.
 */
@Suppress("LongMethod", "MagicNumber")
@Composable
fun VaultVerificationCodeItem(
    authCode: String,
    label: String,
    periodSeconds: Int,
    timeLeftSeconds: Int,
    startIcon: IconData,
    onCopyClick: () -> Unit,
    onItemClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingLabel: String? = null,
) {
    Row(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(color = MaterialTheme.colorScheme.primary),
                onClick = onItemClick,
            )
            .defaultMinSize(minHeight = 72.dp)
            .padding(vertical = 8.dp)
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BitwardenIcon(
            iconData = startIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )

        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            supportingLabel?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        CircularIndicator(
            timeLeftSeconds = timeLeftSeconds,
            periodSeconds = periodSeconds,
        )

        Text(
            text = authCode.chunked(3).joinToString(" "),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        IconButton(
            onClick = onCopyClick,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_copy),
                contentDescription = stringResource(id = R.string.copy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun CircularIndicator(
    timeLeftSeconds: Int,
    periodSeconds: Int,
) {
    val progressAnimate by animateFloatAsState(
        targetValue = timeLeftSeconds.toFloat() / periodSeconds,
        animationSpec = tween(
            durationMillis = periodSeconds,
            delayMillis = 0,
            easing = LinearOutSlowInEasing,
        ),
    )

    Box(contentAlignment = Alignment.Center) {

        CircularProgressIndicator(
            progress = { progressAnimate },
            modifier = Modifier.size(size = 50.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
            strokeCap = StrokeCap.Round,
        )

        Text(
            text = timeLeftSeconds.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("MagicNumber")
@Preview(showBackground = true)
@Composable
private fun VerificationCodeItem_preview() {
    BitwardenTheme {
        VaultVerificationCodeItem(
            startIcon = IconData.Local(R.drawable.ic_login_item),
            label = "Sample Label",
            supportingLabel = "Supporting Label",
            authCode = "1234567890".chunked(3).joinToString(" "),
            timeLeftSeconds = 15,
            periodSeconds = 30,
            onCopyClick = {},
            onItemClick = {},
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
