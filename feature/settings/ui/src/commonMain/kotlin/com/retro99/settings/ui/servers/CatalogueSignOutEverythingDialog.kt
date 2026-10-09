package com.retro99.settings.ui.servers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.catalogue.ui.add.catalogueDeviceName
import com.retro99.catalogue.ui.settings.CatalogueActionButton
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.*

@Composable
fun CatalogueSignOutEverythingDialog(
    hasCatalogue: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    deviceName: String = catalogueDeviceName(),
) {
    EmberDialog(onDismissRequest = onDismiss, title = stringResource(StringRes.catalogue_sign_out_everything_title),
        body = AnnotatedString(stringResource(StringRes.catalogue_sign_out_everything_body, deviceName)), actions = emptyList()) {
        if (hasCatalogue) Text(stringResource(StringRes.catalogue_sign_out_all_detail), style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
        CatalogueActionButton(stringResource(StringRes.server_detail_sign_out), onConfirm, filled = true, modifier = Modifier.fillMaxWidth())
        CatalogueActionButton(stringResource(StringRes.general_cancel), onDismiss, modifier = Modifier.fillMaxWidth(), border = false)
    }
}
