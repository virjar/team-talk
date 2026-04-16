package com.virjar.tk.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.virjar.tk.util.encodeQrMatrix

@Composable
fun QrCodeDialog(
    uid: String,
    name: String,
    onDismiss: () -> Unit,
) {
    val matrix = remember(uid) { encodeQrMatrix(uid) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = { Text("My QR Code") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Canvas(modifier = Modifier.size(200.dp)) {
                    val canvasSize = size.minDimension
                    val modules = matrix.size
                    val moduleSize = canvasSize / (modules + 2)
                    val offset = moduleSize

                    // White background
                    drawRect(Color.White, size = Size(canvasSize, canvasSize))

                    // QR foreground modules
                    for (y in 0 until modules) {
                        for (x in 0 until modules) {
                            if (matrix[y][x]) {
                                drawRect(
                                    Color.Black,
                                    topLeft = Offset(offset + x * moduleSize, offset + y * moduleSize),
                                    size = Size(moduleSize, moduleSize),
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "UID: ${uid.take(12)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Scan to add friend",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * Generic QR code dialog that displays arbitrary text content.
 */
@Composable
fun QrCodeContentDialog(
    content: String,
    title: String = "QR Code",
    subtitle: String? = null,
    onDismiss: () -> Unit,
) {
    val matrix = remember(content) { encodeQrMatrix(content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = { Text(title) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Canvas(modifier = Modifier.size(200.dp)) {
                    val canvasSize = size.minDimension
                    val modules = matrix.size
                    val moduleSize = canvasSize / (modules + 2)
                    val offset = moduleSize

                    drawRect(Color.White, size = Size(canvasSize, canvasSize))
                    for (y in 0 until modules) {
                        for (x in 0 until modules) {
                            if (matrix[y][x]) {
                                drawRect(
                                    Color.Black,
                                    topLeft = Offset(offset + x * moduleSize, offset + y * moduleSize),
                                    size = Size(moduleSize, moduleSize),
                                )
                            }
                        }
                    }
                }
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
