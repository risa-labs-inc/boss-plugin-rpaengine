package ai.rever.boss.plugin.dynamic.rpaengine

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RpaengineContent() {
    BossTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(imageVector = Icons.Outlined.Memory, contentDescription = "RPA Engine", modifier = Modifier.size(48.dp), tint = MaterialTheme.colors.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "RPA Engine", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.onBackground)
                Spacer(modifier = Modifier.height(8.dp))
                Card(modifier = Modifier.padding(16.dp), shape = RoundedCornerShape(8.dp), backgroundColor = MaterialTheme.colors.surface, elevation = 4.dp) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "Dynamic Plugin Stub", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colors.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Full functionality pending PluginContext expansion.", fontSize = 12.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}
