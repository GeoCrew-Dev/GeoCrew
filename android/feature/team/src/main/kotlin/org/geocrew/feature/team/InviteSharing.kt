package org.geocrew.feature.team

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.geocrew.core.network.Invite

/** Text sent through the share sheet; [Invite.find] can read it back when pasted. */
internal fun inviteText(workspace: String, invite: Invite) = buildString {
    appendLine("邀请你加入同勘工作组「$workspace」")
    appendLine("服务器：${invite.server}")
    appendLine("邀请码：${invite.code}")
    append("已安装同勘可直接打开：${invite.toUri()}")
}

internal fun shareInvite(context: Context, workspace: String, invite: Invite) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, inviteText(workspace, invite))
    }
    context.startActivity(Intent.createChooser(send, "分享邀请"))
}

/**
 * Shows the invite as a QR code. A member scans it with the system camera,
 * which opens the `geocrew://join` link in the app with the form filled in.
 */
@Composable
internal fun InviteQrDialog(workspace: String, invite: Invite, onDismiss: () -> Unit) {
    val matrix = remember(invite) { encodeQr(invite.toUri()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码加入「$workspace」") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QrCode(matrix, Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(12.dp))
                Text(invite.code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                Text(
                    "组员用手机相机扫码即可打开同勘并填好加入信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun encodeQr(content: String): BitMatrix =
    QRCodeWriter().encode(
        content, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
    )

@Composable
private fun QrCode(matrix: BitMatrix, modifier: Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
