package org.geocrew.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** One entry of the tools hub. Features add tools by adding an entry here. */
data class ToolEntry(val title: String, val description: String, val icon: ImageVector, val route: String)

val builtInTools = listOf(
    ToolEntry("罗盘 · 产状", "测量方位和岩层产状，保存为地质点", Icons.Default.Explore, "compass"),
    ToolEntry("地层厚度", "按产状和导线（方位、坡角、斜距）算真厚度和铅直厚度", Icons.Default.Straighten, Routes.THICKNESS),
    ToolEntry("地质路线", "按顺序串起当天的地质点，写点间描述和路线小结，导出路线记录表", Icons.Default.Hiking, Routes.ROUTE_ANY),
    ToolEntry("实测剖面", "沿导线逐层记录岩性和产状，算各层真厚度，剖面线保存到地图", Icons.Default.Layers, Routes.SECTION_ANY),
    ToolEntry("钻孔编录", "钻孔、浅井按层底深度记岩性和水位，导出钻孔柱状图", Icons.Default.VerticalAlignBottom, Routes.BOREHOLE_ANY),
    ToolEntry("方位交会", "用罗盘方位定点：前方交会求够不着的目标，后方交会在 GPS 差时求自己的位置", Icons.Default.ChangeHistory, Routes.BEARING_FIX),
    ToolEntry("寻点", "带方向箭头走到记录点或指派给你的任务点", Icons.Default.NearMe, Routes.SEEK_ANY),
    ToolEntry("日出日落", "当地日出、日落和天黑时间，还剩多久天黑", Icons.Default.WbTwilight, Routes.SUN),
    ToolEntry("坐标换算", "经纬度与高斯-克吕格 3°/6° 带平面坐标互换，导出点的平面坐标表", Icons.Default.GridOn, Routes.COORDINATES),
    ToolEntry("赤平投影", "产状的施密特网极点、大圆与平均产状", Icons.Default.DonutLarge, Routes.STEREONET),
    ToolEntry("空间分析", "插值、剖面视倾角、统计、范围、缓冲区", Icons.Default.Analytics, Routes.ANALYSIS),
    ToolEntry("导入 / 导出", "GeoJSON、KML、GPX、Shapefile", Icons.Default.ImportExport, Routes.TRANSFER),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(tools: List<ToolEntry>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(tools, key = { it.route }) { t ->
                ListItem(
                    headlineContent = { Text(t.title) },
                    supportingContent = { Text(t.description) },
                    leadingContent = { Icon(t.icon, null) },
                    modifier = Modifier.clickable { onOpen(t.route) },
                )
            }
        }
    }
}
