package com.goals.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goals.app.ui.goals.goalTextFieldColors
import com.goals.app.ui.theme.*

@Composable
fun ServerUrlDialog(
    currentUrl: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceColor,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                "SERVER URL",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.sp,
                color = TextColor,
                fontFamily = SyneFont
            )
        },
        text = {
            Column {
                Text(
                    "Enter the address of your Goals backend server.\nExample: http://192.168.1.10:2200/",
                    fontSize = 13.sp,
                    color = Text2Color,
                    fontFamily = SyneFont,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("http://192.168.1.x:2200/", color = Text3Color, fontFamily = DmMonoFont, fontSize = 12.sp) },
                    colors = goalTextFieldColors(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                var finalUrl = url.trim()
                if (!finalUrl.endsWith("/")) finalUrl += "/"
                onConfirm(finalUrl)
            }) {
                Text("CONNECT", color = AccentColor, fontWeight = FontWeight.Bold, fontFamily = SyneFont)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Text2Color, fontFamily = SyneFont)
            }
        }
    )
}
